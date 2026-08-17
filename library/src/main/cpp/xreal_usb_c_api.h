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
int ar_glass_xreal_imu(void* session, uint8_t command,
        const uint8_t* payload, int payload_size,
        uint8_t* out, int out_cap);
void ar_glass_xreal_usb_set_imu_sink(void* session,
        ar_glass_xreal_imu_sink sink, void* user);
int ar_glass_xreal_start_imu_stream(void* session);

#ifdef __cplusplus
}
#endif
