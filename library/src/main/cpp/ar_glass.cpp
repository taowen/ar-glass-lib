#include "ar_glass.h"

#include <cmath>
#include <cstring>

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

std::vector<std::uint8_t> make_mcu_command(std::uint16_t command, std::span<const std::uint8_t> payload) {
    const auto body_length = static_cast<std::uint16_t>(17 + payload.size());
    std::vector<std::uint8_t> packet(22 + payload.size());
    packet[0] = 0xfd;
    put_le(packet, 5, body_length, 2);
    put_le(packet, 7, 0, 8);
    put_le(packet, 15, command, 2);
    std::copy(payload.begin(), payload.end(), packet.begin() + 22);
    put_le(packet, 1, crc32(std::span(packet).subspan(5, body_length)), 4);
    return packet;
}

bool decode_xreal_imu(std::span<const std::uint8_t> b, ImuSample& out) {
    if (b.size() != 64 || b[0] != 1 || (b[1] != 1 && b[1] != 2)) return false;
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
    const auto mag_offset = read_le<std::int16_t>(b, v1 ? 36 : 42);
    const auto mag_divisor = read_le<std::int32_t>(b, v1 ? 38 : 44);
    if (mag_divisor != 0) for (std::size_t i = 0; i < 3; ++i)
        out.magnetic_field[i] = static_cast<float>(read_le<std::int16_t>(b, (v1 ? 42 : 48) + i * 2) - mag_offset) / mag_divisor;
    out.temperature_celsius = read_le<std::int16_t>(b, 2) * (v1 ? 0.4831F : 0.007548309F) + 25.F;
    return true;
}
}  // namespace ar_glass
