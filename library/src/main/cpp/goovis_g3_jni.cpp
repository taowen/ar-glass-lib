#include "ar_glass.h"
#include "usb_trace.h"

#include <jni.h>
#include <android/log.h>
#include <libusb.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <vector>

namespace {
class GoovisUsbSession {
public:
    GoovisUsbSession(int fd, int vid, int pid, int interface_id, int input_endpoint,
                     int output_endpoint, ar_glass::GoovisModelKind model)
        : vid_(vid), pid_(pid), interface_id_(interface_id), input_endpoint_(input_endpoint),
          output_endpoint_(output_endpoint), model_(model) {
        libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
        if (libusb_init(&context_) != 0) {
            throw std::runtime_error("Cannot initialize GOOVIS USB context");
        }
        if (libusb_wrap_sys_device(context_, fd, &handle_) != 0) {
            libusb_exit(context_);
            context_ = nullptr;
            throw std::runtime_error("Cannot wrap GOOVIS USB file descriptor");
        }
        libusb_set_auto_detach_kernel_driver(handle_, 1);
        if (libusb_claim_interface(handle_, interface_id_) != 0) {
            libusb_close(handle_);
            libusb_exit(context_);
            handle_ = nullptr;
            context_ = nullptr;
            throw std::runtime_error("Cannot claim GOOVIS HID interface");
        }
    }

    bool set_display_sbs(bool enabled) { return write_command(0, enabled ? 0 : 1); }
    bool set_imu_enabled(bool enabled) { return write_command(1, enabled ? 1 : 0); }

    bool read_imu(int timeout, ar_glass::ImuSample& sample) {
        if (!running_ || input_endpoint_ == 0) return false;
        std::vector<std::uint8_t> report(64);
        int actual = 0;
        const int rc = libusb_interrupt_transfer(handle_, static_cast<unsigned char>(input_endpoint_),
            report.data(), static_cast<int>(report.size()), &actual, std::max(timeout, 0));
        const int returned = rc == 0 ? actual : rc;
        ar_glass::record_usb_transfer(vid_, pid_, 1, input_endpoint_, 0, 0, 0, returned,
            report.data(), static_cast<std::size_t>(std::max(actual, 0)));
        if (rc != 0 || actual < 13) return false;
        report.resize(static_cast<std::size_t>(actual));
        const auto interval_nanos = static_cast<std::int64_t>(report[12]) * 1'000'000;
        if (interval_nanos == 0) return false;
        device_timestamp_nanos_ += interval_nanos;
        return ar_glass::decode_goovis_imu(report, model_, device_timestamp_nanos_, sample);
    }

    void close() {
        if (!running_.exchange(false)) return;
        if (handle_) libusb_release_interface(handle_, interface_id_);
        if (handle_) libusb_close(handle_);
        if (context_) libusb_exit(context_);
        handle_ = nullptr;
        context_ = nullptr;
    }

    ~GoovisUsbSession() = default;

private:
    bool write_command(std::uint8_t group, std::uint8_t value) {
        std::lock_guard lock(write_mutex_);
        if (!running_ || output_endpoint_ == 0) return false;
        auto report = ar_glass::make_goovis_command(group, value);
        int actual = 0;
        const int rc = libusb_interrupt_transfer(handle_, static_cast<unsigned char>(output_endpoint_),
            report.data(), static_cast<int>(report.size()), &actual, 1'000);
        const int returned = rc == 0 ? actual : rc;
        ar_glass::record_usb_transfer(vid_, pid_, 2, output_endpoint_, 0, 0, 0, returned,
            report.data(), report.size());
        if (rc != 0) {
            __android_log_print(ANDROID_LOG_INFO, "ArGlassNative",
                "GOOVIS interrupt OUT failed endpoint=0x%x libusb=%d", output_endpoint_, rc);
        }
        return rc == 0 && actual == static_cast<int>(report.size());
    }

    libusb_context* context_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    int vid_;
    int pid_;
    int interface_id_;
    int input_endpoint_;
    int output_endpoint_;
    ar_glass::GoovisModelKind model_;
    std::int64_t device_timestamp_nanos_ = 0;
    std::mutex write_mutex_;
    std::atomic_bool running_{true};
};

GoovisUsbSession* session(jlong handle) {
    return reinterpret_cast<GoovisUsbSession*>(handle);
}

void put_le(std::vector<std::uint8_t>& bytes, std::size_t offset, std::uint64_t value,
            std::size_t size) {
    for (std::size_t i = 0; i < size; ++i) {
        bytes[offset + i] = static_cast<std::uint8_t>(value >> (8U * i));
    }
}

void put_float_le(std::vector<std::uint8_t>& bytes, std::size_t offset, float value) {
    std::uint32_t bits = 0;
    static_assert(sizeof(bits) == sizeof(value));
    std::memcpy(&bits, &value, sizeof(bits));
    put_le(bytes, offset, bits, sizeof(bits));
}

jbyteArray encode_sample(JNIEnv* env, const ar_glass::ImuSample& sample) {
    std::vector<std::uint8_t> bytes(33);
    put_le(bytes, 0, static_cast<std::uint64_t>(sample.timestamp_nanos), 8);
    for (std::size_t i = 0; i < 3; ++i) put_float_le(bytes, 8 + i * 4, sample.acceleration_mps2[i]);
    for (std::size_t i = 0; i < 3; ++i) put_float_le(bytes, 20 + i * 4, sample.angular_velocity_radps[i]);
    bytes[32] = sample.report_version;
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_taowen_arglass_NativeBridge_createGoovisUsbSession(
        JNIEnv* env, jobject, jint fd, jint vid, jint pid, jint interface_id,
        jint input_endpoint, jint output_endpoint, jint model_kind) {
    try {
        if (model_kind < 0 || model_kind > 3) throw std::runtime_error("Invalid GOOVIS model kind");
        return reinterpret_cast<jlong>(new GoovisUsbSession(fd, vid, pid, interface_id,
            input_endpoint, output_endpoint, static_cast<ar_glass::GoovisModelKind>(model_kind)));
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, "ArGlassNative", "%s", error.what());
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_taowen_arglass_NativeBridge_goovisSetDisplaySbs(
        JNIEnv*, jobject, jlong handle, jboolean enabled) {
    return session(handle)->set_display_sbs(enabled);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_taowen_arglass_NativeBridge_goovisSetImuEnabled(
        JNIEnv*, jobject, jlong handle, jboolean enabled) {
    return session(handle)->set_imu_enabled(enabled);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_goovisReadImu(
        JNIEnv* env, jobject, jlong handle, jint timeout) {
    ar_glass::ImuSample sample;
    return session(handle)->read_imu(timeout, sample) ? encode_sample(env, sample) : nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_taowen_arglass_NativeBridge_closeGoovisUsbSession(
        JNIEnv*, jobject, jlong handle) {
    if (!handle) return;
    auto* value = session(handle);
    value->close();
    delete value;
}
