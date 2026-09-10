#include "ar_glass.h"
#include <algorithm>
#include <array>
#include <cassert>

int main() {
    // Independently encoded response: command 0x9f, request 37, status 0,
    // mode 11. CRC computed with Python zlib.crc32 over bytes 5..23.
    constexpr std::array<std::uint8_t, 24> reply{
        0xfd, 0xc1, 0x2d, 0x42, 0xea, 0x13, 0x00, 0x25,
        0x00, 0x00, 0x00, 0x40, 0xe2, 0x01, 0x00, 0x9f,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0b};
    assert(ar_glass::matches_mcu_response(reply, 0x9f, 37));
    assert(!ar_glass::matches_mcu_response(reply, 0x9f, 38));
    assert(!ar_glass::matches_mcu_response(reply, 0x07, 37));
    for (std::size_t size = 0; size < reply.size(); ++size) {
        assert(!ar_glass::matches_mcu_response(std::span(reply).first(size), 0x9f, 37));
    }
    for (std::size_t offset = 0; offset < reply.size(); ++offset) {
        auto corrupt = reply;
        corrupt[offset] ^= 1;
        assert(!ar_glass::matches_mcu_response(corrupt, 0x9f, 37));
    }
    std::array<std::uint8_t, 64> padded{};
    std::copy(reply.begin(), reply.end(), padded.begin());
    assert(ar_glass::matches_mcu_response(padded, 0x9f, 37));
}
