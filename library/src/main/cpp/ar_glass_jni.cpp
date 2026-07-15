#include "ar_glass.h"

#include <jni.h>
#include <android/log.h>

#include <atomic>
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
    XrealUsbSession(JNIEnv* env, jobject connection, jobject device, jobject mcu_interface,
                    jobject mcu_in, jobject mcu_out, jobject imu_interface, jobject imu_in, jobject imu_out)
        : connection_(env->NewGlobalRef(connection)), device_(env->NewGlobalRef(device)),
          mcu_interface_(global(env, mcu_interface)), mcu_in_(global(env, mcu_in)), mcu_out_(global(env, mcu_out)),
          imu_interface_(global(env, imu_interface)), imu_in_(global(env, imu_in)), imu_out_(global(env, imu_out)) {
        const auto cls = env->GetObjectClass(connection);
        claim_ = env->GetMethodID(cls, "claimInterface", "(Landroid/hardware/usb/UsbInterface;Z)Z");
        release_ = env->GetMethodID(cls, "releaseInterface", "(Landroid/hardware/usb/UsbInterface;)Z");
        close_ = env->GetMethodID(cls, "close", "()V");
        env->DeleteLocalRef(cls);
        const auto bridge = env->FindClass("com/taowen/arglass/NativeBridge");
        transfer_ = env->GetStaticMethodID(bridge, "tracedTransfer",
            "(Landroid/hardware/usb/UsbDeviceConnection;Landroid/hardware/usb/UsbDevice;Landroid/hardware/usb/UsbEndpoint;[BI)I");
        bridge_ = reinterpret_cast<jclass>(env->NewGlobalRef(bridge));
        env->DeleteLocalRef(bridge);
        if (mcu_interface_ && !env->CallBooleanMethod(connection_, claim_, mcu_interface_, JNI_TRUE))
            throw std::runtime_error("Cannot claim XREAL MCU interface");
        if (imu_interface_ && !env->CallBooleanMethod(connection_, claim_, imu_interface_, JNI_TRUE))
            throw std::runtime_error("Cannot claim XREAL IMU interface");
    }

    std::vector<std::uint8_t> mcu(JNIEnv* env, std::uint16_t command, std::span<const std::uint8_t> payload) {
        std::lock_guard lock(command_mutex_);
        const auto id = request_id_++;
        return transact(env, mcu_out_, mcu_in_, ar_glass::make_mcu_command(command, id, payload), 0xfd, command, id);
    }
    std::vector<std::uint8_t> imu(JNIEnv* env, std::uint8_t command, std::span<const std::uint8_t> payload) {
        std::lock_guard lock(command_mutex_);
        return transact(env, imu_out_, imu_in_, ar_glass::make_imu_command(command, payload), 0xaa, command, -1);
    }
    std::vector<std::uint8_t> read_imu(JNIEnv* env, int timeout) {
        return read(env, imu_in_, 64, timeout);
    }
    void close(JNIEnv* env) {
        if (!running_.exchange(false)) return;
        if (imu_interface_) env->CallBooleanMethod(connection_, release_, imu_interface_);
        if (mcu_interface_) env->CallBooleanMethod(connection_, release_, mcu_interface_);
        env->CallVoidMethod(connection_, close_);
    }
    ~XrealUsbSession() = default;
    void destroy(JNIEnv* env) {
        close(env);
        for (auto ref : {connection_, device_, mcu_interface_, mcu_in_, mcu_out_, imu_interface_, imu_in_, imu_out_})
            if (ref) env->DeleteGlobalRef(ref);
        env->DeleteGlobalRef(bridge_);
    }

private:
    static jobject global(JNIEnv* env, jobject value) { return value ? env->NewGlobalRef(value) : nullptr; }
    int transfer(JNIEnv* env, jobject endpoint, jbyteArray bytes, int timeout) {
        return env->CallStaticIntMethod(bridge_, transfer_, connection_, device_, endpoint, bytes, timeout);
    }
    std::vector<std::uint8_t> read(JNIEnv* env, jobject endpoint, int size, int timeout) {
        if (!endpoint || !running_) return {};
        auto array = env->NewByteArray(size);
        const int length = transfer(env, endpoint, array, timeout);
        std::vector<std::uint8_t> result;
        if (length > 0) {
            result.resize(length);
            env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(result.data()));
        }
        env->DeleteLocalRef(array);
        return result;
    }
    std::vector<std::uint8_t> transact(JNIEnv* env, jobject out, jobject in,
            const std::vector<std::uint8_t>& request, int magic, int command, std::int64_t id) {
        if (!out || !in || !running_) return {};
        auto array = to_array(env, request);
        const int written = transfer(env, out, array, 750);
        env->DeleteLocalRef(array);
        if (written != static_cast<int>(request.size())) return {};
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
        while (running_ && std::chrono::steady_clock::now() < deadline) {
            auto response = read(env, in, 64, 500);
            if (response.size() < 8 || response[0] != magic) continue;
            const int response_command = magic == 0xfd && response.size() >= 17
                ? response[15] | response[16] << 8 : response[7];
            std::uint32_t response_id = 0;
            if (magic == 0xfd && response.size() >= 11)
                std::memcpy(&response_id, response.data() + 7, sizeof(response_id));
            if (response_command == command && (id < 0 || response_id == static_cast<std::uint32_t>(id))) return response;
        }
        return {};
    }

    jobject connection_, device_, mcu_interface_, mcu_in_, mcu_out_, imu_interface_, imu_in_, imu_out_;
    jclass bridge_;
    jmethodID claim_, release_, close_, transfer_;
    std::mutex command_mutex_;
    std::atomic_bool running_{true};
    std::uint32_t request_id_{1};
};

XrealUsbSession* session(jlong handle) { return reinterpret_cast<XrealUsbSession*>(handle); }
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_makeImuCommand(JNIEnv* env, jobject, jint command, jbyteArray payload) {
    const auto bytes = to_vector(env, payload);
    return to_array(env, ar_glass::make_imu_command(static_cast<std::uint8_t>(command), bytes));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_makeMcuCommand(JNIEnv* env, jobject, jint command, jint request_id, jbyteArray payload) {
    const auto bytes = to_vector(env, payload);
    return to_array(env, ar_glass::make_mcu_command(static_cast<std::uint16_t>(command), static_cast<std::uint32_t>(request_id), bytes));
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
Java_com_taowen_arglass_NativeBridge_createXrealUsbSession(JNIEnv* env, jobject, jobject connection, jobject device,
        jobject mcu_interface, jobject mcu_in, jobject mcu_out, jobject imu_interface, jobject imu_in, jobject imu_out) {
    try { return reinterpret_cast<jlong>(new XrealUsbSession(env, connection, device, mcu_interface, mcu_in, mcu_out,
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
