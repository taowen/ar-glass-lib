#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace ar_glass {

struct ImuSample {
    std::int64_t timestamp_nanos{};
    std::array<float, 3> acceleration_mps2{};
    std::array<float, 3> angular_velocity_radps{};
    std::array<float, 3> magnetic_field{};
    float temperature_celsius{};
    std::uint8_t report_version{};
};

std::vector<std::uint8_t> make_imu_command(std::uint8_t command, std::span<const std::uint8_t> payload = {});
std::vector<std::uint8_t> make_mcu_command(std::uint16_t command, std::span<const std::uint8_t> payload = {});
bool decode_xreal_imu(std::span<const std::uint8_t> report, ImuSample& result);

}  // namespace ar_glass
