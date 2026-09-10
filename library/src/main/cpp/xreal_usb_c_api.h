#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Native owner for one XREAL USB device. Java may open the fd and pass it
// here once. IMU completion never returns to Java when a sink is set.
typedef void (*ar_glass_xreal_imu_sink)(const uint8_t* record, int size, void* user);

void* ar_glass_xreal_usb_open(int fd, int vid, int pid,
        int mcu_interface, int mcu_in, int mcu_out,
        int imu_interface, int imu_in, int imu_out);
void ar_glass_xreal_usb_close(void* session);
int ar_glass_xreal_mcu(void* session, uint16_t command,
        const uint8_t* payload, int payload_size,
        uint8_t* out, int out_cap);
// One exchange, without retries. The positive response timeout starts after
// the request write completes; the existing write timeout remains 750 ms.
// Only CRC-valid replies with the same command and request ID are returned.
// Returns reply length, zero on timeout/write failure, or -1 for invalid input.
int ar_glass_xreal_mcu_with_timeout(void* session, uint16_t command,
        const uint8_t* payload, int payload_size,
        uint8_t* out, int out_cap, int response_timeout_ms);
int ar_glass_xreal_imu(void* session, uint8_t command,
        const uint8_t* payload, int payload_size,
        uint8_t* out, int out_cap);
// Optional continuous MCU reception. The sink runs on the native USB reader,
// with CLOCK_MONOTONIC nanoseconds sampled at transfer completion. Positive
// sizes carry raw packets; negative sizes report transport errors. The sink
// must not issue a synchronous command on this session. Clearing the sink
// waits for an in-flight callback; close joins the reader before releasing USB.
typedef void (*ar_glass_xreal_mcu_sink)(const uint8_t* packet, int size,
        int64_t receive_time_ns, void* user);
void ar_glass_xreal_usb_set_mcu_sink(void* session,
        ar_glass_xreal_mcu_sink sink, void* user);
// Runs on the command caller after serialization and packet construction,
// immediately before submitting the MCU write. This is a separate clock sample
// from any timestamp carried in the request payload. It also observes failed
// write attempts. Do not issue commands or change sinks inside this callback.
// Clearing the sink waits for an in-flight callback.
typedef void (*ar_glass_xreal_mcu_send_sink)(const uint8_t* packet, int size,
        int64_t send_time_ns, void* user);
void ar_glass_xreal_usb_set_mcu_send_sink(void* session,
        ar_glass_xreal_mcu_send_sink sink, void* user);
// A receive sink may publish a parsed response before calling its downstream
// consumer, so the command waiter can resume independently of that consumer.
// The normal receiver does this automatically after the sink returns if the
// sink did not publish it. Only the pending CRC/command/request-ID match is
// accepted; duplicate or unsolicited packets return zero without waking it.
int ar_glass_xreal_publish_mcu_response(void* session,
        const uint8_t* packet, int size);
// Validate framing and CRC for either a response or an unsolicited MCU event.
int ar_glass_xreal_validate_mcu_packet(const uint8_t* packet, int size);
int ar_glass_xreal_start_mcu_stream(void* session);

void ar_glass_xreal_usb_set_imu_sink(void* session,
        ar_glass_xreal_imu_sink sink, void* user);
int ar_glass_xreal_start_imu_stream(void* session);

#ifdef __cplusplus
}
#endif
