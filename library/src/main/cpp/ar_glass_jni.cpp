#include "ar_glass.h"

#include <jni.h>

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
