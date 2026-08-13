#include "ar_glass.h"

#include <jni.h>

#include <algorithm>
#include <cstring>
#include <vector>

namespace {
constexpr std::size_t kOutputSampleSize = 48;

void write_bytes(std::uint8_t* output, std::size_t offset, const void* value, std::size_t size) {
    std::memcpy(output + offset, value, size);
}
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_taowen_arglass_NativeBridge_decodeRokidMax2ImuBatch(
        JNIEnv* env, jobject, jbyteArray packet, jint requested_length) {
    const auto array_length = env->GetArrayLength(packet);
    const auto length = std::clamp(static_cast<jsize>(requested_length), static_cast<jsize>(0), array_length);
    std::vector<std::uint8_t> input(static_cast<std::size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(packet, 0, length, reinterpret_cast<jbyte*>(input.data()));
    }
    const auto samples = ar_glass::decode_rokid_max2_imu_batch(input);
    std::vector<std::uint8_t> output(samples.size() * kOutputSampleSize);
    for (std::size_t index = 0; index < samples.size(); ++index) {
        const auto& sample = samples[index];
        const std::size_t base = index * kOutputSampleSize;
        write_bytes(output.data(), base, &sample.timestamp_nanos, sizeof(sample.timestamp_nanos));
        write_bytes(output.data(), base + 8, sample.acceleration_mps2.data(), 3 * sizeof(float));
        write_bytes(output.data(), base + 20, sample.angular_velocity_radps.data(), 3 * sizeof(float));
        write_bytes(output.data(), base + 32, sample.magnetic_field.data(), 3 * sizeof(float));
        const std::int32_t version = sample.report_version;
        write_bytes(output.data(), base + 44, &version, sizeof(version));
    }
    auto result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (!output.empty()) {
        env->SetByteArrayRegion(
            result, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte*>(output.data()));
    }
    return result;
}
