#include "ar_glass.h"

#include <algorithm>
#include <cmath>
#include <chrono>
#include <cstring>
#include <limits>

namespace ar_glass {
namespace {
std::uint32_t crc32(std::span<const std::uint8_t> bytes) {
    std::uint32_t crc = 0xffffffffU;
    for (const auto byte : bytes) {
        crc ^= byte;
        for (int bit = 0; bit < 8; ++bit) crc = (crc >> 1U) ^ (0xedb88320U & (0U - (crc & 1U)));
    }
    return crc ^ 0xffffffffU;
}
template <typename T> T read_le(std::span<const std::uint8_t> bytes, std::size_t offset) {
    T value{};
    std::memcpy(&value, bytes.data() + offset, sizeof(T));
    return value;
}
void put_le(std::vector<std::uint8_t>& bytes, std::size_t offset, std::uint64_t value, std::size_t size) {
    for (std::size_t i = 0; i < size; ++i) bytes[offset + i] = static_cast<std::uint8_t>(value >> (8U * i));
}
std::int32_t read_i24(std::span<const std::uint8_t> bytes, std::size_t offset) {
    std::int32_t value = bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16);
    return (value & 0x800000) != 0 ? value - 0x1000000 : value;
}
std::int16_t read_be_i16(std::span<const std::uint8_t> bytes, std::size_t offset) {
    const auto value = static_cast<std::uint16_t>(bytes[offset]) << 8 |
                       static_cast<std::uint16_t>(bytes[offset + 1]);
    return static_cast<std::int16_t>(value);
}
std::int32_t read_be_i32(std::span<const std::uint8_t> bytes, std::size_t offset) {
    const auto value = static_cast<std::uint32_t>(bytes[offset]) << 24 |
                       static_cast<std::uint32_t>(bytes[offset + 1]) << 16 |
                       static_cast<std::uint32_t>(bytes[offset + 2]) << 8 |
                       static_cast<std::uint32_t>(bytes[offset + 3]);
    return static_cast<std::int32_t>(value);
}
std::int16_t read_xreal_v2_magnetic_i16(
        std::span<const std::uint8_t> bytes, std::size_t offset) {
    const auto value = static_cast<std::uint16_t>(bytes[offset]) |
                       static_cast<std::uint16_t>(bytes[offset + 1] ^ 0x80U) << 8;
    return static_cast<std::int16_t>(value);
}
}  // namespace

std::vector<std::uint8_t> make_imu_command(std::uint8_t command, std::span<const std::uint8_t> payload) {
    const auto body_length = static_cast<std::uint16_t>(3 + payload.size());
    std::vector<std::uint8_t> packet(8 + payload.size());
    packet[0] = 0xaa;
    put_le(packet, 5, body_length, 2);
    packet[7] = command;
    std::copy(payload.begin(), payload.end(), packet.begin() + 8);
    put_le(packet, 1, crc32(std::span(packet).subspan(5, body_length)), 4);
    return packet;
}

std::vector<std::uint8_t> make_mcu_command(std::uint16_t command, std::uint32_t request_id,
                                           std::span<const std::uint8_t> payload) {
    const auto body_length = static_cast<std::uint16_t>(17 + payload.size());
    std::vector<std::uint8_t> packet(22 + payload.size());
    packet[0] = 0xfd;
    put_le(packet, 5, body_length, 2);
    put_le(packet, 7, request_id, 4);
    const auto stamp = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    put_le(packet, 11, static_cast<std::uint32_t>(stamp), 4);
    put_le(packet, 15, command, 2);
    std::copy(payload.begin(), payload.end(), packet.begin() + 22);
    put_le(packet, 1, crc32(std::span(packet).subspan(5, body_length)), 4);
    return packet;
}

bool decode_xreal_imu(std::span<const std::uint8_t> b, ImuSample& out) {
    if (b.size() != 64 || b[0] != 1 || (b[1] != 1 && b[1] != 2)) return false;
    out = {};
    out.magnetic_field.fill(std::numeric_limits<float>::quiet_NaN());
    out.report_version = b[1];
    out.timestamp_nanos = read_le<std::int64_t>(b, 4);
    const auto scale3 = [&](std::size_t offset, std::size_t stride, std::uint16_t numerator,
                            std::int32_t divisor, std::array<float, 3>& values) {
        if (divisor == 0) return false;
        for (std::size_t i = 0; i < 3; ++i) {
            const auto raw = stride == 2 ? read_le<std::int16_t>(b, offset + i * stride) : read_i24(b, offset + i * stride);
            values[i] = static_cast<float>(raw) * numerator / divisor;
        }
        return true;
    };
    std::array<float, 3> gyro{}, accel{};
    const bool v1 = b[1] == 1;
    if (!scale3(18, v1 ? 2 : 3, read_le<std::uint16_t>(b, 12), read_le<std::int32_t>(b, 14), gyro) ||
        !scale3(v1 ? 30 : 33, v1 ? 2 : 3, read_le<std::uint16_t>(b, v1 ? 24 : 27),
                read_le<std::int32_t>(b, v1 ? 26 : 29), accel)) return false;
    constexpr float radians = 0.01745329251994329577F;
    out.angular_velocity_radps = {-gyro[0] * radians, gyro[2] * radians, gyro[1] * radians};
    out.acceleration_mps2 = {-accel[0] * 9.81F, accel[2] * 9.81F, accel[1] * 9.81F};
    if (v1) {
        const auto mag_offset = read_le<std::int16_t>(b, 36);
        const auto mag_divisor = read_le<std::int32_t>(b, 38);
        if (mag_divisor != 0) {
            for (std::size_t i = 0; i < 3; ++i) {
                out.magnetic_field[i] =
                        static_cast<float>(read_le<std::int16_t>(b, 42 + i * 2) - mag_offset) /
                        mag_divisor;
            }
        }
    } else if (b[62] != 0) {
        // Version 2 uses a different magnetic encoding from gyro/accel: the
        // multiplier and divisor are big-endian, and each sample stores its
        // sign bit XOR 0x80 in the high byte. Byte 62 is the freshness flag;
        // the three magnetic words remain populated between 100 Hz samples,
        // but forwarding those stale words as new measurements would make the
        // fusion filter update them at the roughly 1 kHz report rate.
        const auto mag_multiplier = read_be_i16(b, 42);
        const auto mag_divisor = read_be_i32(b, 44);
        if (mag_divisor != 0) {
            for (std::size_t i = 0; i < 3; ++i) {
                out.magnetic_field[i] =
                        static_cast<float>(read_xreal_v2_magnetic_i16(b, 48 + i * 2)) *
                        mag_multiplier / mag_divisor;
            }
        }
    }
    out.temperature_celsius = read_le<std::int16_t>(b, 2) * (v1 ? 0.4831F : 0.007548309F) + 25.F;
    return true;
}

std::array<std::uint8_t, 24> make_goovis_command(std::uint8_t group, std::uint8_t value) {
    std::array<std::uint8_t, 24> report{};
    report[0] = 0xaa;
    report[1] = 0x55;
    report[2] = 0x55;
    report[3] = 0xaa;
    report[4] = group;
    report[5] = value;
    unsigned int checksum = 0;
    for (std::size_t i = 0; i < 6; ++i) checksum += report[i];
    report[6] = static_cast<std::uint8_t>(checksum);
    return report;
}

bool decode_goovis_imu(std::span<const std::uint8_t> report, GoovisModelKind model,
                       std::int64_t timestamp_nanos, ImuSample& out) {
    if (report.size() < 13 || report[12] == 0) return false;
    constexpr float gravity_mps2 = 9.80665F;
    constexpr float accel_g_per_lsb = 4.F / 32768.F;
    constexpr float gyro_radps_per_lsb = 1000.F / 32768.F * 0.01745329251994329577F;
    const std::array<float, 3> acceleration = {
        read_be_i16(report, 0) * accel_g_per_lsb * gravity_mps2,
        read_be_i16(report, 2) * accel_g_per_lsb * gravity_mps2,
        read_be_i16(report, 4) * accel_g_per_lsb * gravity_mps2,
    };
    const std::array<float, 3> angular_velocity = {
        read_be_i16(report, 6) * gyro_radps_per_lsb,
        read_be_i16(report, 8) * gyro_radps_per_lsb,
        read_be_i16(report, 10) * gyro_radps_per_lsb,
    };
    const auto runtime_coordinates = [model](const std::array<float, 3>& vector) {
        switch (model) {
            case GoovisModelKind::g3: return vector;
            case GoovisModelKind::g3x:
            case GoovisModelKind::g3x_pro: return std::array{-vector[0], vector[2], vector[1]};
            case GoovisModelKind::a1: return std::array{vector[1], vector[2], vector[0]};
        }
        return vector;
    };
    out = {};
    out.timestamp_nanos = timestamp_nanos;
    out.acceleration_mps2 = runtime_coordinates(acceleration);
    out.angular_velocity_radps = runtime_coordinates(angular_velocity);
    out.magnetic_field.fill(std::numeric_limits<float>::quiet_NaN());
    out.temperature_celsius = std::numeric_limits<float>::quiet_NaN();
    out.report_version = 1;
    return true;
}

std::vector<ImuSample> decode_rokid_max2_imu_batch(std::span<const std::uint8_t> packet) {
    constexpr std::size_t sample_size = 64;
    constexpr float radians_per_degree = 0.01745329251994329577F;
    constexpr float meters_per_second_squared_per_g = 9.80665F;
    std::vector<ImuSample> decoded;
    decoded.reserve(packet.size() / sample_size);
    const auto norm = [](const std::array<float, 3>& value) {
        return std::sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2]);
    };
    for (std::size_t offset = 0; offset + sample_size <= packet.size(); offset += sample_size) {
        const auto sample = packet.subspan(offset, sample_size);
        if (sample[0] != 0x11) continue;
        ImuSample out{};
        std::uint64_t timestamp_micros = 0;
        for (std::size_t i = 0; i < 7; ++i) {
            timestamp_micros |= static_cast<std::uint64_t>(sample[1 + i]) << (8U * i);
        }
        out.timestamp_nanos = static_cast<std::int64_t>(timestamp_micros * 1000U);
        for (std::size_t i = 0; i < 3; ++i) {
            out.acceleration_mps2[i] = read_le<float>(sample, 12 + i * sizeof(float));
            out.angular_velocity_radps[i] =
                    read_le<float>(sample, 24 + i * sizeof(float)) * radians_per_degree;
            out.magnetic_field[i] = read_le<float>(sample, 36 + i * sizeof(float));
        }
        const float acceleration_norm = norm(out.acceleration_mps2);
        if (acceleration_norm >= 0.1F && acceleration_norm <= 4.F) {
            for (auto& value : out.acceleration_mps2) value *= meters_per_second_squared_per_g;
        }
        if (!std::all_of(out.acceleration_mps2.begin(), out.acceleration_mps2.end(),
                         [](float value) { return std::isfinite(value); }) ||
            !std::all_of(out.angular_velocity_radps.begin(), out.angular_velocity_radps.end(),
                         [](float value) { return std::isfinite(value); }) ||
            !std::all_of(out.magnetic_field.begin(), out.magnetic_field.end(),
                         [](float value) { return std::isfinite(value); }) ||
            norm(out.acceleration_mps2) < 1.F || norm(out.acceleration_mps2) > 40.F ||
            norm(out.magnetic_field) < 1.F || norm(out.magnetic_field) > 500.F) continue;
        out.temperature_celsius = std::numeric_limits<float>::quiet_NaN();
        out.report_version = 0x11;
        decoded.push_back(out);
    }
    return decoded;
}
}  // namespace ar_glass
