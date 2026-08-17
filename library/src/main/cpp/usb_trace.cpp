#include "usb_trace.h"
#include <atomic>
#include <chrono>
#include <fstream>
#include <mutex>

namespace ar_glass {
namespace {
std::mutex trace_mutex;
std::string trace_path;
std::atomic<bool> trace_enabled{false};
void u16(std::ostream& out, std::uint16_t value) { out.put(value >> 8); out.put(value); }
void u32(std::ostream& out, std::uint32_t value) { u16(out, value >> 16); u16(out, value); }
void u64(std::ostream& out, std::uint64_t value) { u32(out, value >> 32); u32(out, value); }
}
void configure_usb_trace(std::string path) {
    std::lock_guard lock(trace_mutex);
    trace_path = std::move(path);
    trace_enabled.store(!trace_path.empty(), std::memory_order_release);
}
void record_usb_transfer(int vid, int pid, int operation, int address_or_type,
                         int request, int value, int index, int result,
                         const std::uint8_t* payload, std::size_t payload_size) {
    if (!trace_enabled.load(std::memory_order_acquire)) return;
    std::lock_guard lock(trace_mutex);
    if (trace_path.empty()) return;
    std::ofstream out(trace_path, std::ios::binary | std::ios::app);
    if (!out) return;
    u32(out, 0x41525553); u16(out, 1);
    u64(out, std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());
    u32(out, vid); u32(out, pid); out.put(operation); u32(out, address_or_type);
    u32(out, request); u32(out, value); u32(out, index); u32(out, result);
    u32(out, payload_size);
    if (payload_size) out.write(reinterpret_cast<const char*>(payload), payload_size);
}
}
