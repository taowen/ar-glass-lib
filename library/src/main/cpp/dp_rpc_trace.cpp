#include "dp_rpc_trace.h"

#include <chrono>
#include <fstream>
#include <mutex>
#include <string>

namespace ar_glass {
namespace {
std::mutex trace_mutex;
std::string trace_path;

void u16(std::ostream& out, std::uint16_t value) {
    out.put(static_cast<char>((value >> 8) & 0xff));
    out.put(static_cast<char>(value & 0xff));
}

void u32(std::ostream& out, std::uint32_t value) {
    u16(out, static_cast<std::uint16_t>((value >> 16) & 0xffff));
    u16(out, static_cast<std::uint16_t>(value & 0xffff));
}

void u64(std::ostream& out, std::uint64_t value) {
    u32(out, static_cast<std::uint32_t>((value >> 32) & 0xffffffff));
    u32(out, static_cast<std::uint32_t>(value & 0xffffffff));
}

void write_record(const char* host, int port, int operation, int command, int sequence,
                  int result, const std::uint8_t* payload, std::size_t payload_size) {
    std::lock_guard lock(trace_mutex);
    if (trace_path.empty()) return;
    std::ofstream out(trace_path, std::ios::binary | std::ios::app);
    if (!out) return;

    const std::string host_text = host ? host : "";
    const auto host_size = static_cast<std::uint16_t>(
        host_text.size() > 0xffff ? 0xffff : host_text.size());
    const auto timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    u32(out, 0x41524450);  // ARDP
    u16(out, 1);
    u64(out, static_cast<std::uint64_t>(timestamp));
    u16(out, host_size);
    if (host_size) out.write(host_text.data(), host_size);
    u32(out, static_cast<std::uint32_t>(port));
    out.put(static_cast<char>(operation & 0xff));
    u32(out, static_cast<std::uint32_t>(command));
    u32(out, static_cast<std::uint32_t>(sequence));
    u32(out, static_cast<std::uint32_t>(result));
    u32(out, static_cast<std::uint32_t>(payload_size));
    if (payload_size) out.write(reinterpret_cast<const char*>(payload), payload_size);
}
}  // namespace

void configure_xreal_one_dp_trace(std::string path) {
    std::lock_guard lock(trace_mutex);
    trace_path = std::move(path);
}

void record_xreal_one_dp_rpc(const char* host, int port, int operation, int command, int sequence,
                             int result, const std::uint8_t* payload, std::size_t payload_size) {
    write_record(host, port, operation, command, sequence, result, payload, payload_size);
}

void record_xreal_one_dp_rpc_error(const char* host, int port, int operation, int command, int sequence,
                                   int result, const char* message) {
    const std::string text = message ? message : "";
    write_record(host, port, operation, command, sequence, result,
                 reinterpret_cast<const std::uint8_t*>(text.data()), text.size());
}
}  // namespace ar_glass
