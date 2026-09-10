#include "ar_glass.h"
#include "dp_rpc_trace.h"
#include "usb_trace.h"
#include "xreal_usb_c_api.h"

#include <jni.h>
#include <android/log.h>
#include <libusb.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/resource.h>

#include <atomic>
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <span>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <time.h>

namespace {
std::vector<std::uint8_t> to_vector(JNIEnv* env, jbyteArray input) {
    const auto size = env->GetArrayLength(input);
    std::vector<std::uint8_t> bytes(size);
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    return bytes;
}
jbyteArray to_array(JNIEnv* env, const std::vector<std::uint8_t>& bytes) {
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

}  // namespace

namespace {
class XrealUsbSession {
public:
    XrealUsbSession(int fd, int vid, int pid, int mcu_interface, int mcu_in, int mcu_out,
                    int imu_interface, int imu_in, int imu_out)
        : fd_(fd), vid_(vid), pid_(pid), mcu_interface_(mcu_interface), mcu_in_(mcu_in), mcu_out_(mcu_out),
          imu_interface_(imu_interface), imu_in_(imu_in), imu_out_(imu_out) {
        libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
        if (libusb_init(&context_) != 0 || libusb_wrap_sys_device(context_, fd, &handle_) != 0)
            throw std::runtime_error("Cannot wrap XREAL USB file descriptor");
        libusb_set_auto_detach_kernel_driver(handle_, 1);
        if (mcu_interface_ >= 0 && libusb_claim_interface(handle_, mcu_interface_) != 0)
            throw std::runtime_error("Cannot claim XREAL MCU interface");
        if (imu_interface_ >= 0 && libusb_claim_interface(handle_, imu_interface_) != 0)
            throw std::runtime_error("Cannot claim XREAL IMU interface");
    }

    std::vector<std::uint8_t> mcu(JNIEnv*, std::uint16_t command, std::span<const std::uint8_t> payload,
                                int response_timeout_ms = 0) {
        std::lock_guard lock(command_mutex_);
        const auto request_id = next_mcu_request_id_++;
        return transact(mcu_out_, mcu_in_, ar_glass::make_mcu_command(command, request_id, payload),
                        0xfd, command, -1, response_timeout_ms);
    }
    std::vector<std::uint8_t> imu(JNIEnv*, std::uint8_t command, std::span<const std::uint8_t> payload) {
        std::lock_guard lock(command_mutex_);
        return transact(imu_out_, imu_in_, ar_glass::make_imu_command(command, payload), 0xaa, command, -1);
    }
    std::vector<std::uint8_t> read_imu(JNIEnv*, int timeout) {
        return read(imu_in_, 64, timeout);
    }
    void set_imu_sink(ar_glass_xreal_imu_sink sink, void* user) {
        std::lock_guard lock(sink_mutex_);
        imu_sink_ = sink;
        imu_sink_user_ = user;
    }
    void set_mcu_sink(ar_glass_xreal_mcu_sink sink, void* user) {
        std::lock_guard lock(mcu_sink_mutex_);
        mcu_sink_ = sink;
        mcu_sink_user_ = user;
    }
    void set_mcu_send_sink(ar_glass_xreal_mcu_send_sink sink, void* user) {
        std::lock_guard lock(mcu_send_sink_mutex_);
        mcu_send_sink_ = sink;
        mcu_send_sink_user_ = user;
    }
    bool publish_mcu_response(std::span<const std::uint8_t> response) {
        std::lock_guard lock(mcu_reply_mutex_);
        if (!mcu_pending_ || !ar_glass::matches_mcu_response(response,
                mcu_pending_command_, mcu_pending_id_)) return false;
        mcu_reply_.assign(response.begin(), response.end());
        mcu_pending_ = false;
        mcu_reply_ready_.notify_all();
        return true;
    }
    bool start_mcu_stream() {
        // Serialize the transition with synchronous MCU reads: after this
        // point only the receiver may read the MCU IN endpoint.
        std::lock_guard lock(command_mutex_);
        if (!mcu_in_ || !running_ || mcu_streaming_) return false;
        if (mcu_reader_.joinable()) mcu_reader_.join();
        mcu_streaming_ = true;
        try {
            mcu_reader_ = std::thread([this] { read_mcu_stream(); });
            mcu_async_enabled_ = true;
        } catch (...) {
            mcu_streaming_ = false;
            throw;
        }
        return true;
    }
    bool start_imu_stream() {
        if (!imu_in_ || !running_ || imu_streaming_.exchange(true)) return false;
        imu_reader_ = std::thread([this] { read_imu_stream(); });
        return true;
    }
    std::vector<std::uint8_t> read_imu_record(int timeout) {
        std::unique_lock lock(imu_queue_mutex_);
        imu_queue_ready_.wait_for(lock, std::chrono::milliseconds(std::max(timeout, 0)), [this] {
            return !imu_queue_.empty() || !imu_streaming_ || !running_;
        });
        if (imu_queue_.empty()) return {};
        auto result = std::move(imu_queue_.front());
        imu_queue_.pop_front();
        return result;
    }
    void close(JNIEnv*) {
        set_imu_sink(nullptr, nullptr);
        set_mcu_sink(nullptr, nullptr);
        set_mcu_send_sink(nullptr, nullptr);
        {
            std::lock_guard lock(mcu_reply_mutex_);
            if (!running_.exchange(false)) return;
        }
        mcu_reply_ready_.notify_all();
        if (mcu_reader_.joinable()) mcu_reader_.join();
        imu_streaming_.store(false);
        imu_queue_ready_.notify_all();
        for (auto& slot : imu_urbs_)
            ioctl(fd_, USBDEVFS_DISCARDURB, &slot.urb);
        if (imu_reader_.joinable()) imu_reader_.join();
        std::lock_guard command_lock(command_mutex_);
        if (imu_interface_ >= 0) libusb_release_interface(handle_, imu_interface_);
        if (mcu_interface_ >= 0) libusb_release_interface(handle_, mcu_interface_);
        if (handle_) libusb_close(handle_);
        if (context_) libusb_exit(context_);
        handle_ = nullptr; context_ = nullptr;
    }
    ~XrealUsbSession() = default;
    void destroy(JNIEnv* env) {
        close(env);
    }

private:
    struct ImuUrb {
        std::array<std::uint8_t, 64> bytes{};
        // usbdevfs_urb ends in a zero-length ISO descriptor array, so Clang
        // requires it to remain the final member even for a bulk URB.
        usbdevfs_urb urb{};
    };
    static std::int64_t monotonic_time_nanos() {
        timespec time{};
        if (clock_gettime(CLOCK_MONOTONIC, &time) != 0) return 0;
        return static_cast<std::int64_t>(time.tv_sec) * 1'000'000'000LL + time.tv_nsec;
    }
    void read_imu_stream() {
        prctl(PR_SET_NAME, "imu_cb", 0, 0, 0);
        setpriority(PRIO_PROCESS, 0, -20);
        const auto submit = [this](ImuUrb& slot) {
            slot.urb = {};
            slot.urb.type = USBDEVFS_URB_TYPE_BULK;
            slot.urb.endpoint = static_cast<unsigned char>(imu_in_);
            slot.urb.buffer = slot.bytes.data();
            slot.urb.buffer_length = slot.bytes.size();
            slot.urb.usercontext = &slot;
            return ioctl(fd_, USBDEVFS_SUBMITURB, &slot.urb) == 0;
        };

        int active = 0;
        for (auto& slot : imu_urbs_) active += submit(slot) ? 1 : 0;
        if (active == 0) {
            __android_log_print(ANDROID_LOG_ERROR, "ArGlassNative",
                "Cannot submit XREAL asynchronous IMU URBs errno=%d (%s)",
                errno, std::strerror(errno));
        }
        while (active > 0) {
            pollfd descriptor{.fd = fd_, .events = POLLOUT, .revents = 0};
            const int poll_result = poll(&descriptor, 1, 100);
            if (poll_result < 0 && errno != EINTR) break;
            if (poll_result == 0) {
                if (!running_ || !imu_streaming_) {
                    for (auto& slot : imu_urbs_)
                        ioctl(fd_, USBDEVFS_DISCARDURB, &slot.urb);
                }
                continue;
            }
            while (true) {
                usbdevfs_urb* completed = nullptr;
                if (ioctl(fd_, USBDEVFS_REAPURBNDELAY, &completed) != 0) {
                    if (errno != EAGAIN && errno != EINTR) active = 0;
                    break;
                }
                --active;
                auto* slot = static_cast<ImuUrb*>(completed->usercontext);
                // Official 0x145dce4 memcpy the 64-byte report, then pack
                // and produce. Keep the URB in flight before produce so a
                // 1 kHz completion does not stall the next 1 ms packet.
                std::array<std::uint8_t, 72> record{};
                bool have_record = false;
                if (completed->status == 0
                        && completed->actual_length == static_cast<int>(slot->bytes.size())) {
                    const auto arrival = monotonic_time_nanos();
                    ar_glass::record_usb_transfer(
                            vid_, pid_, 1, imu_in_, 0, 0, 0,
                            completed->actual_length, slot->bytes.data(), slot->bytes.size());
                    for (int byte = 0; byte < 8; ++byte)
                        record[byte] = static_cast<std::uint8_t>(arrival >> (byte * 8));
                    std::copy(slot->bytes.begin(), slot->bytes.end(), record.begin() + 8);
                    have_record = true;
                }
                if (running_ && imu_streaming_ && submit(*slot)) ++active;
                if (!have_record) continue;
                // Official USB completion is 0x145dce4 -> pack ->
                // produce 0x1d4a39c on this thread. A registered sink
                // is that produce. Do not also enqueue for Java; the
                // 256-deep FIFO is up to 256 ms of stale IMU.
                if (!invoke_imu_sink(record.data(),
                        static_cast<int>(record.size()))) {
                    std::lock_guard lock(imu_queue_mutex_);
                    if (imu_queue_.size() == 256) imu_queue_.pop_front();
                    imu_queue_.emplace_back(record.begin(), record.end());
                    imu_queue_ready_.notify_one();
                }
            }
        }
        imu_streaming_.store(false);
        imu_queue_ready_.notify_all();
    }
    void read_mcu_stream() {
        prctl(PR_SET_NAME, "mcu_cb", 0, 0, 0);
        bool received_first_packet = false;
        while (running_) {
            std::array<std::uint8_t, 64> packet{};
            std::int64_t received_ns = 0;
            const int size = transfer(mcu_in_, packet.data(), packet.size(), 100,
                    &received_ns);
            if (!running_) break;
            if (size == -ETIMEDOUT || size == -EINTR || size == 0) continue;
            {
                std::lock_guard lock(mcu_sink_mutex_);
                if (mcu_sink_) mcu_sink_(packet.data(), size, received_ns, mcu_sink_user_);
            }
            if (size < 0) break;
            if (!received_first_packet) {
                received_first_packet = true;
                __android_log_print(ANDROID_LOG_INFO, "ArGlassNative",
                        "MCU receiver first packet bytes=%d receive_ns=%lld", size,
                        static_cast<long long>(received_ns));
            }
            publish_mcu_response(std::span<const std::uint8_t>(packet.data(), size));
        }
        {
            std::lock_guard lock(mcu_reply_mutex_);
            mcu_streaming_ = false;
        }
        mcu_reply_ready_.notify_all();
    }
    int transfer(int endpoint, std::uint8_t* bytes, int size, int timeout,
                 std::int64_t* completion_ns = nullptr) {
        usbdevfs_bulktransfer request{};
        request.ep = static_cast<unsigned int>(endpoint);
        request.len = static_cast<unsigned int>(size);
        request.timeout = static_cast<unsigned int>(std::max(timeout, 0));
        request.data = bytes;
        const int result = ioctl(fd_, USBDEVFS_BULK, &request);
        const int returned = result >= 0 ? result : -errno;
        if (completion_ns) *completion_ns = monotonic_time_nanos();
        const bool input = (endpoint & LIBUSB_ENDPOINT_DIR_MASK) != 0;
        ar_glass::record_usb_transfer(vid_, pid_, input ? 1 : 2, endpoint, 0, 0, 0, returned,
            bytes, input ? static_cast<std::size_t>(std::max(result, 0)) : static_cast<std::size_t>(size));
        if (result < 0 && errno != ETIMEDOUT) {
            __android_log_print(ANDROID_LOG_INFO, "ArGlassNative",
                "XREAL usbdevfs bulk transfer failed endpoint=0x%x errno=%d (%s)",
                endpoint, errno, std::strerror(errno));
        }
        return returned;
    }
    std::vector<std::uint8_t> read(int endpoint, int size, int timeout) {
        if (!endpoint || !running_) return {};
        std::vector<std::uint8_t> result(size);
        const int length = transfer(endpoint, result.data(), size, timeout);
        if (length > 0) result.resize(length); else result.clear();
        return result;
    }
    std::vector<std::uint8_t> transact(int out, int in,
            const std::vector<std::uint8_t>& request, int magic, int command, int request_id,
            int response_timeout_ms = 0) {
        if (!out || !in || !running_) return {};
        if (magic == 0xfd && mcu_async_enabled_) {
            if (!mcu_streaming_) return {};
            // command_mutex_ is held by mcu(). Install the expected reply
            // before writing, so a fast response cannot be lost.
            std::unique_lock lock(mcu_reply_mutex_);
            mcu_reply_.clear();
            mcu_pending_command_ = static_cast<std::uint16_t>(command);
            std::memcpy(&mcu_pending_id_, request.data() + 7, sizeof(mcu_pending_id_));
            mcu_pending_ = true;
            lock.unlock();
            notify_mcu_send(request);
            const int written = transfer(out, const_cast<std::uint8_t*>(request.data()),
                    request.size(), 750);
            lock.lock();
            if (written == static_cast<int>(request.size())) {
                mcu_reply_ready_.wait_for(lock,
                        std::chrono::milliseconds(response_timeout_ms > 0 ? response_timeout_ms : 2000),
                        [this] { return !mcu_pending_ || !running_ || !mcu_streaming_; });
            } else {
                mcu_reply_.clear();
            }
            mcu_pending_ = false;
            return std::move(mcu_reply_);
        }
        if (magic == 0xfd) notify_mcu_send(request);
        const int written = transfer(out, const_cast<std::uint8_t*>(request.data()), request.size(), 750);
        if (written != static_cast<int>(request.size())) return {};
        const auto deadline = std::chrono::steady_clock::now() +
            std::chrono::milliseconds(response_timeout_ms > 0 ? response_timeout_ms : 2000);
        while (running_ && std::chrono::steady_clock::now() < deadline) {
            int read_timeout_ms = 500;
            if (response_timeout_ms > 0) {
                const auto remaining = deadline - std::chrono::steady_clock::now();
                if (remaining <= decltype(remaining)::zero()) break;
                // usbdevfs accepts whole milliseconds; zero would mean an
                // unbounded wait. Round up only the final fractional millisecond.
                read_timeout_ms = static_cast<int>(std::min<std::int64_t>(500,
                    std::chrono::ceil<std::chrono::milliseconds>(remaining).count()));
            }
            auto response = read(in, 64, read_timeout_ms);
            if (response_timeout_ms > 0 && magic == 0xfd) {
                std::uint32_t expected_id;
                std::memcpy(&expected_id, request.data() + 7, sizeof(expected_id));
                if (ar_glass::matches_mcu_response(response,
                        static_cast<std::uint16_t>(command), expected_id)) return response;
                continue;
            }
            if (response.size() < 8 || response[0] != magic) continue;
            const int response_command = magic == 0xfd && response.size() >= 17
                ? response[15] | response[16] << 8 : response[7];
            const int response_id = magic == 0xfd && response.size() >= 11
                ? response[7] | response[8] << 8 | response[9] << 16 | response[10] << 24 : -1;
            if (response_command == command && (request_id < 0 || response_id == request_id)) return response;
        }
        __android_log_print(ANDROID_LOG_INFO, "ArGlassNative",
            "XREAL command timed out magic=0x%x command=0x%x requestId=%d", magic, command, request_id);
        return {};
    }

    bool invoke_imu_sink(const std::uint8_t* record, int size) {
        std::lock_guard lock(sink_mutex_);
        if (imu_sink_ == nullptr) return false;
        imu_sink_(record, size, imu_sink_user_);
        return true;
    }
    void notify_mcu_send(const std::vector<std::uint8_t>& request) {
        std::lock_guard lock(mcu_send_sink_mutex_);
        if (mcu_send_sink_) {
            const auto send_ns = monotonic_time_nanos();
            mcu_send_sink_(request.data(), static_cast<int>(request.size()),
                    send_ns, mcu_send_sink_user_);
        }
    }

    libusb_context* context_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    int fd_;
    [[maybe_unused]] int vid_, pid_;
    int mcu_interface_, mcu_in_, mcu_out_, imu_interface_, imu_in_, imu_out_;
    std::mutex command_mutex_;
    std::mutex mcu_sink_mutex_;
    ar_glass_xreal_mcu_sink mcu_sink_ = nullptr;
    void* mcu_sink_user_ = nullptr;
    std::mutex mcu_send_sink_mutex_;
    ar_glass_xreal_mcu_send_sink mcu_send_sink_ = nullptr;
    void* mcu_send_sink_user_ = nullptr;
    std::atomic_bool mcu_streaming_{false};
    bool mcu_async_enabled_ = false; // guarded by command_mutex_
    std::thread mcu_reader_;
    std::mutex mcu_reply_mutex_;
    std::condition_variable mcu_reply_ready_;
    bool mcu_pending_ = false;
    std::uint16_t mcu_pending_command_ = 0;
    std::uint32_t mcu_pending_id_ = 0;
    std::vector<std::uint8_t> mcu_reply_;
    std::mutex sink_mutex_;
    ar_glass_xreal_imu_sink imu_sink_ = nullptr;
    void* imu_sink_user_ = nullptr;
    std::atomic_bool running_{true};
    std::atomic_bool imu_streaming_{false};
    std::thread imu_reader_;
    std::mutex imu_queue_mutex_;
    std::condition_variable imu_queue_ready_;
    std::deque<std::vector<std::uint8_t>> imu_queue_;
    std::array<ImuUrb, 8> imu_urbs_{};
    std::atomic_uint32_t next_mcu_request_id_{1};
};

XrealUsbSession* session(jlong handle) { return reinterpret_cast<XrealUsbSession*>(handle); }

class UsbSession {
public:
    UsbSession(int fd, int vid, int pid) : vid_(vid), pid_(pid) {
        libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
        if (libusb_init(&context_) != 0 || libusb_wrap_sys_device(context_, fd, &handle_) != 0)
            throw std::runtime_error("Cannot wrap USB file descriptor");
        libusb_set_auto_detach_kernel_driver(handle_, 1);
    }
    ~UsbSession() {
        if (handle_) libusb_close(handle_);
        if (context_) libusb_exit(context_);
    }
    bool claim(int id) { return libusb_claim_interface(handle_, id) == 0; }
    void release(int id) { libusb_release_interface(handle_, id); }
    int endpoint(int address, bool interrupt, std::uint8_t* data, int size, int timeout) {
        int actual = 0;
        const int rc = interrupt
            ? libusb_interrupt_transfer(handle_, address, data, size, &actual, timeout)
            : libusb_bulk_transfer(handle_, address, data, size, &actual, timeout);
        const int returned = rc == 0 ? actual : rc;
        const bool input = (address & LIBUSB_ENDPOINT_DIR_MASK) != 0;
        ar_glass::record_usb_transfer(vid_, pid_, input ? 1 : 2, address, 0, 0, 0, returned,
            data, input ? static_cast<std::size_t>(std::max(actual, 0)) : static_cast<std::size_t>(size));
        return returned;
    }
    int control(int request_type, int request, int value, int index, std::uint8_t* data, int size, int timeout) {
        const int result = libusb_control_transfer(handle_, request_type, request, value, index, data, size, timeout);
        const bool input = (request_type & LIBUSB_ENDPOINT_DIR_MASK) != 0;
        ar_glass::record_usb_transfer(vid_, pid_, input ? 1 : 2, request_type, request, value, index, result,
            data, input ? static_cast<std::size_t>(std::max(result, 0)) : static_cast<std::size_t>(size));
        return result;
    }
private:
    libusb_context* context_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    int vid_, pid_;
};
UsbSession* usb_session(jlong handle) { return reinterpret_cast<UsbSession*>(handle); }

int parse_xreal_mcu_display_mode_value(const std::vector<std::uint8_t>& response, int bytes) {
    if (bytes == 1 && response.size() >= 24) return static_cast<int>(response[23]);
    if (bytes == 4 && response.size() >= 27) {
        const int first_payload_byte = static_cast<int>(response[22]);
        const int value = static_cast<int>(response[23]) |
                          (static_cast<int>(response[24]) << 8) |
                          (static_cast<int>(response[25]) << 16) |
                          (static_cast<int>(response[26]) << 24);
        if (value != 0 || first_payload_byte == 0) return value;
        // XBX A01 2D readback was observed as "01 00 00 00 00" after
        // set-mode success, while 3D readback uses "00 <uint32 mode>".
        // Treat the non-zero first byte as the mode only when the uint32
        // field is empty.
        return first_payload_byte;
    }
    return -1;
}

std::vector<std::uint8_t> xreal_mcu_display_mode_payload(int value, int bytes) {
    if (bytes == 1) return {static_cast<std::uint8_t>(value & 0xff)};
    if (bytes == 4) {
        return {
            static_cast<std::uint8_t>(value & 0xff),
            static_cast<std::uint8_t>((value >> 8) & 0xff),
            static_cast<std::uint8_t>((value >> 16) & 0xff),
            static_cast<std::uint8_t>((value >> 24) & 0xff),
        };
    }
    throw std::runtime_error("Unsupported XREAL display mode payload size");
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_makeImuCommand(JNIEnv* env, jobject, jint command, jbyteArray payload) {
    const auto bytes = to_vector(env, payload);
    return to_array(env, ar_glass::make_imu_command(static_cast<std::uint8_t>(command), bytes));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_makeMcuCommand(JNIEnv* env, jobject, jint command, jint request_id, jbyteArray payload) {
    const auto bytes = to_vector(env, payload);
    return to_array(env, ar_glass::make_mcu_command(
        static_cast<std::uint16_t>(command), static_cast<std::uint32_t>(request_id), bytes));
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_taowen_arglass_NativeBridge_decodeImuReport(JNIEnv* env, jobject, jbyteArray report) {
    const auto bytes = to_vector(env, report);
    ar_glass::ImuSample sample;
    if (!ar_glass::decode_xreal_imu(bytes, sample)) return nullptr;
    const float values[] = {
        static_cast<float>(sample.timestamp_nanos),
        sample.acceleration_mps2[0], sample.acceleration_mps2[1], sample.acceleration_mps2[2],
        sample.angular_velocity_radps[0], sample.angular_velocity_radps[1], sample.angular_velocity_radps[2],
        sample.magnetic_field[0], sample.magnetic_field[1], sample.magnetic_field[2],
        sample.temperature_celsius, static_cast<float>(sample.report_version),
    };
    auto result = env->NewFloatArray(12);
    env->SetFloatArrayRegion(result, 0, 12, values);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_taowen_arglass_NativeBridge_createXrealUsbSession(JNIEnv* env, jobject, jint fd, jint vid, jint pid,
        jint mcu_interface, jint mcu_in, jint mcu_out, jint imu_interface, jint imu_in, jint imu_out) {
    try { return reinterpret_cast<jlong>(new XrealUsbSession(fd, vid, pid, mcu_interface, mcu_in, mcu_out,
                                                             imu_interface, imu_in, imu_out)); }
    catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, "ArGlassNative", "%s", error.what());
        const auto exception = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exception, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_xrealMcuCommand(JNIEnv* env, jobject, jlong handle, jint command, jbyteArray payload) {
    return to_array(env, session(handle)->mcu(env, command, to_vector(env, payload)));
}
extern "C" JNIEXPORT jint JNICALL
Java_com_taowen_arglass_NativeBridge_xrealMcuGetDisplayModeValue(JNIEnv* env, jobject, jlong handle,
        jint payload_bytes) {
    return parse_xreal_mcu_display_mode_value(
        session(handle)->mcu(env, 0x07, {}), static_cast<int>(payload_bytes));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_taowen_arglass_NativeBridge_xrealMcuSetDisplayModeValue(JNIEnv* env, jobject, jlong handle,
        jint mode_value, jint payload_bytes) {
    const auto response = session(handle)->mcu(
        env, 0x08, xreal_mcu_display_mode_payload(static_cast<int>(mode_value), static_cast<int>(payload_bytes)));
    return response.size() >= 23 && (response[22] & 0xff) == 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_xrealImuCommand(JNIEnv* env, jobject, jlong handle, jint command, jbyteArray payload) {
    return to_array(env, session(handle)->imu(env, command, to_vector(env, payload)));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_xrealReadImu(JNIEnv* env, jobject, jlong handle, jint timeout) {
    const auto bytes = session(handle)->read_imu(env, timeout);
    return bytes.empty() ? nullptr : to_array(env, bytes);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_taowen_arglass_NativeBridge_xrealStartImuStream(JNIEnv*, jobject, jlong handle) {
    return session(handle)->start_imu_stream();
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_xrealReadImuRecord(JNIEnv* env, jobject, jlong handle, jint timeout) {
    const auto bytes = session(handle)->read_imu_record(timeout);
    return bytes.empty() ? nullptr : to_array(env, bytes);
}
extern "C" JNIEXPORT void JNICALL
Java_com_taowen_arglass_NativeBridge_closeXrealUsbSession(JNIEnv* env, jobject, jlong handle) {
    if (!handle) return;
    auto* value = session(handle);
    value->destroy(env);
    delete value;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_taowen_arglass_NativeBridge_createUsbSession(JNIEnv* env, jobject, jint fd, jint vid, jint pid) {
    try { return reinterpret_cast<jlong>(new UsbSession(fd, vid, pid)); }
    catch (const std::exception& error) {
        const auto exception = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exception, error.what());
        return 0;
    }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_taowen_arglass_NativeBridge_usbClaimInterface(JNIEnv*, jobject, jlong handle, jint id) {
    return usb_session(handle)->claim(id);
}
extern "C" JNIEXPORT void JNICALL
Java_com_taowen_arglass_NativeBridge_usbReleaseInterface(JNIEnv*, jobject, jlong handle, jint id) {
    usb_session(handle)->release(id);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_taowen_arglass_NativeBridge_usbEndpointTransfer(JNIEnv* env, jobject, jlong handle, jint endpoint,
        jboolean interrupt, jbyteArray buffer, jint timeout) {
    auto bytes = to_vector(env, buffer);
    const int result = usb_session(handle)->endpoint(endpoint, interrupt, bytes.data(), bytes.size(), timeout);
    if (result > 0 && (endpoint & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_IN)
        env->SetByteArrayRegion(buffer, 0, result, reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_taowen_arglass_NativeBridge_usbControlTransfer(JNIEnv* env, jobject, jlong handle, jint request_type,
        jint request, jint value, jint index, jbyteArray buffer, jint timeout) {
    auto bytes = to_vector(env, buffer);
    const int result = usb_session(handle)->control(request_type, request, value, index, bytes.data(), bytes.size(), timeout);
    if (result > 0 && (request_type & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_IN)
        env->SetByteArrayRegion(buffer, 0, result, reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_taowen_arglass_NativeBridge_closeUsbSession(JNIEnv*, jobject, jlong handle) {
    delete usb_session(handle);
}
extern "C" JNIEXPORT void JNICALL
Java_com_taowen_arglass_NativeBridge_configureUsbDiagnostics(JNIEnv* env, jobject, jstring path) {
    const char* value = env->GetStringUTFChars(path, nullptr);
    ar_glass::configure_usb_trace(value);
    env->ReleaseStringUTFChars(path, value);
}
extern "C" JNIEXPORT void JNICALL
Java_com_taowen_arglass_NativeBridge_configureXrealOneDpDiagnostics(JNIEnv* env, jobject, jstring path) {
    const char* value = env->GetStringUTFChars(path, nullptr);
    ar_glass::configure_xreal_one_dp_trace(value);
    env->ReleaseStringUTFChars(path, value);
}

// Native USB owner used by the translation APK. IMU completion stays in
// this process: 0x145dce4 analog -> pack -> produce, never Java.
namespace {
int copy_reply(const std::vector<std::uint8_t>& reply, uint8_t* out, int out_cap) {
    if (out != nullptr && out_cap > 0 && !reply.empty()) {
        const int n = std::min(out_cap, static_cast<int>(reply.size()));
        std::memcpy(out, reply.data(), static_cast<std::size_t>(n));
    }
    return static_cast<int>(reply.size());
}
}  // namespace

extern "C" JNIEXPORT void* ar_glass_xreal_usb_open(int fd, int vid, int pid,
        int mcu_interface, int mcu_in, int mcu_out,
        int imu_interface, int imu_in, int imu_out) {
    try {
        return new XrealUsbSession(fd, vid, pid, mcu_interface, mcu_in, mcu_out,
                imu_interface, imu_in, imu_out);
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, "ArGlassNative", "%s", error.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT void ar_glass_xreal_usb_close(void* session) {
    if (session == nullptr) return;
    auto* value = static_cast<XrealUsbSession*>(session);
    value->close(nullptr);
    delete value;
}
extern "C" JNIEXPORT int ar_glass_xreal_mcu(void* session, uint16_t command,
        const uint8_t* payload, int payload_size, uint8_t* out, int out_cap) {
    if (session == nullptr) return -1;
    std::vector<std::uint8_t> in;
    if (payload != nullptr && payload_size > 0) {
        in.assign(payload, payload + payload_size);
    }
    return copy_reply(static_cast<XrealUsbSession*>(session)->mcu(nullptr, command, in),
            out, out_cap);
}
extern "C" JNIEXPORT int ar_glass_xreal_mcu_with_timeout(void* session, uint16_t command,
        const uint8_t* payload, int payload_size, uint8_t* out, int out_cap,
        int response_timeout_ms) {
    if (session == nullptr || response_timeout_ms <= 0 || payload_size < 0 ||
            (payload_size > 0 && payload == nullptr) || out == nullptr || out_cap <= 0) return -1;
    const std::span<const std::uint8_t> input(payload, static_cast<std::size_t>(payload_size));
    return copy_reply(static_cast<XrealUsbSession*>(session)->mcu(
            nullptr, command, input, response_timeout_ms), out, out_cap);
}
extern "C" JNIEXPORT int ar_glass_xreal_imu(void* session, uint8_t command,
        const uint8_t* payload, int payload_size, uint8_t* out, int out_cap) {
    if (session == nullptr) return -1;
    std::vector<std::uint8_t> in;
    if (payload != nullptr && payload_size > 0) {
        in.assign(payload, payload + payload_size);
    }
    return copy_reply(static_cast<XrealUsbSession*>(session)->imu(nullptr, command, in),
            out, out_cap);
}
extern "C" JNIEXPORT void ar_glass_xreal_usb_set_imu_sink(void* session,
        ar_glass_xreal_imu_sink sink, void* user) {
    if (session != nullptr) {
        static_cast<XrealUsbSession*>(session)->set_imu_sink(sink, user);
    }
}
extern "C" JNIEXPORT int ar_glass_xreal_start_imu_stream(void* session) {
    return session != nullptr &&
            static_cast<XrealUsbSession*>(session)->start_imu_stream() ? 1 : 0;
}

extern "C" JNIEXPORT void ar_glass_xreal_usb_set_mcu_sink(void* session,
        ar_glass_xreal_mcu_sink sink, void* user) {
    if (session) static_cast<XrealUsbSession*>(session)->set_mcu_sink(sink, user);
}
extern "C" JNIEXPORT void ar_glass_xreal_usb_set_mcu_send_sink(void* session,
        ar_glass_xreal_mcu_send_sink sink, void* user) {
    if (session) static_cast<XrealUsbSession*>(session)->set_mcu_send_sink(sink, user);
}
extern "C" JNIEXPORT int ar_glass_xreal_publish_mcu_response(void* session,
        const std::uint8_t* packet, int size) {
    if (!session || !packet || size <= 0) return 0;
    return static_cast<XrealUsbSession*>(session)->publish_mcu_response(
            std::span<const std::uint8_t>(packet, size)) ? 1 : 0;
}
extern "C" JNIEXPORT int ar_glass_xreal_validate_mcu_packet(
        const std::uint8_t* packet, int size) {
    if (!packet || size < 22) return 0;
    const auto message_id = static_cast<std::uint16_t>(packet[15] | packet[16] << 8);
    std::uint32_t request_id = 0;
    for (unsigned i = 0; i < 4; ++i)
        request_id |= static_cast<std::uint32_t>(packet[7 + i]) << (i * 8);
    return ar_glass::matches_mcu_response(
            std::span<const std::uint8_t>(packet, size), message_id, request_id) ? 1 : 0;
}
extern "C" JNIEXPORT int ar_glass_xreal_start_mcu_stream(void* session) {
    if (!session) return 0;
    try {
        return static_cast<XrealUsbSession*>(session)->start_mcu_stream() ? 1 : 0;
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, "ArGlassNative", "%s", error.what());
        return 0;
    }
}
