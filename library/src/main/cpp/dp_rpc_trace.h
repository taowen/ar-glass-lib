#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

namespace ar_glass {
void configure_xreal_one_dp_trace(std::string path);
void record_xreal_one_dp_rpc(const char* host, int port, int operation, int command, int sequence,
                             int result, const std::uint8_t* payload, std::size_t payload_size);
void record_xreal_one_dp_rpc_error(const char* host, int port, int operation, int command, int sequence,
                                   int result, const char* message);
}
