#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

namespace ar_glass {
void configure_usb_trace(std::string path);
void record_usb_transfer(int vid, int pid, int operation, int address_or_type,
                         int request, int value, int index, int result,
                         const std::uint8_t* payload, std::size_t payload_size);
}
