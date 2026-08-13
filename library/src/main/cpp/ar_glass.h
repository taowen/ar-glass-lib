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

enum class GoovisModelKind : std::uint8_t {
    g3 = 0,
    g3x = 1,
    g3x_pro = 2,
    a1 = 3,
};

std::vector<std::uint8_t> make_imu_command(std::uint8_t command, std::span<const std::uint8_t> payload = {});
std::vector<std::uint8_t> make_mcu_command(std::uint16_t command, std::uint32_t request_id,
                                           std::span<const std::uint8_t> payload = {});
bool decode_xreal_imu(std::span<const std::uint8_t> report, ImuSample& result);
std::array<std::uint8_t, 24> make_goovis_command(std::uint8_t group, std::uint8_t value);
bool decode_goovis_imu(std::span<const std::uint8_t> report, GoovisModelKind model,
                       std::int64_t timestamp_nanos, ImuSample& result);
std::vector<ImuSample> decode_rokid_max2_imu_batch(std::span<const std::uint8_t> packet);

}  // namespace ar_glass
