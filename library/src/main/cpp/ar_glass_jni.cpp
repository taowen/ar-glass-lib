#include "ar_glass.h"
#include "usb_trace.h"

#include <jni.h>
#include <android/log.h>
#include <libusb.h>

#include <atomic>
#include <algorithm>
#include <chrono>
#include <mutex>

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
        : vid_(vid), pid_(pid), mcu_interface_(mcu_interface), mcu_in_(mcu_in), mcu_out_(mcu_out),
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

    std::vector<std::uint8_t> mcu(JNIEnv*, std::uint16_t command, std::span<const std::uint8_t> payload) {
        std::lock_guard lock(command_mutex_);
        return transact(mcu_out_, mcu_in_, ar_glass::make_mcu_command(command, payload), 0xfd, command);
    }
    std::vector<std::uint8_t> imu(JNIEnv*, std::uint8_t command, std::span<const std::uint8_t> payload) {
        std::lock_guard lock(command_mutex_);
        return transact(imu_out_, imu_in_, ar_glass::make_imu_command(command, payload), 0xaa, command);
    }
    std::vector<std::uint8_t> read_imu(JNIEnv*, int timeout) {
        return read(imu_in_, 64, timeout);
    }
    void close(JNIEnv*) {
        if (!running_.exchange(false)) return;
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
    int transfer(int endpoint, std::uint8_t* bytes, int size, int timeout) {
        int actual = 0;
        // XREAL MCU/IMU endpoints are HID interrupt endpoints. Android's
        // bulkTransfer accepts both bulk and interrupt endpoints; libusb keeps
        // them as separate APIs, so use the actual HID transfer type here.
        const int result = libusb_interrupt_transfer(handle_, static_cast<unsigned char>(endpoint), bytes, size, &actual, timeout);
        const int returned = result == 0 ? actual : result;
        const bool input = (endpoint & LIBUSB_ENDPOINT_DIR_MASK) != 0;
        ar_glass::record_usb_transfer(vid_, pid_, input ? 1 : 2, endpoint, 0, 0, 0, returned,
            bytes, input ? static_cast<std::size_t>(std::max(actual, 0)) : static_cast<std::size_t>(size));
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
            const std::vector<std::uint8_t>& request, int magic, int command) {
        if (!out || !in || !running_) return {};
        const int written = transfer(out, const_cast<std::uint8_t*>(request.data()), request.size(), 750);
        if (written != static_cast<int>(request.size())) return {};
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
        while (running_ && std::chrono::steady_clock::now() < deadline) {
            // XREAL One advertises a 1024-byte interrupt packet even though
            // the FD-framed MCU message at its start is usually only 23-27
            // bytes. A 64-byte libusb buffer reports OVERFLOW and discards the
            // otherwise valid response.
            auto response = read(in, 1024, 500);
            if (response.size() < 8 || response[0] != magic) continue;
            const int response_command = magic == 0xfd && response.size() >= 17
                ? response[15] | response[16] << 8 : response[7];
            if (response_command == command) return response;
        }
        return {};
    }

    libusb_context* context_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    [[maybe_unused]] int vid_, pid_;
    int mcu_interface_, mcu_in_, mcu_out_, imu_interface_, imu_in_, imu_out_;
    std::mutex command_mutex_;
    std::atomic_bool running_{true};
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
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_makeImuCommand(JNIEnv* env, jobject, jint command, jbyteArray payload) {
    const auto bytes = to_vector(env, payload);
    return to_array(env, ar_glass::make_imu_command(static_cast<std::uint8_t>(command), bytes));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_makeMcuCommand(JNIEnv* env, jobject, jint command, jint request_id, jbyteArray payload) {
    const auto bytes = to_vector(env, payload);
    (void) request_id;
    return to_array(env, ar_glass::make_mcu_command(static_cast<std::uint16_t>(command), bytes));
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
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_xrealImuCommand(JNIEnv* env, jobject, jlong handle, jint command, jbyteArray payload) {
    return to_array(env, session(handle)->imu(env, command, to_vector(env, payload)));
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_xrealReadImu(JNIEnv* env, jobject, jlong handle, jint timeout) {
    const auto bytes = session(handle)->read_imu(env, timeout);
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
