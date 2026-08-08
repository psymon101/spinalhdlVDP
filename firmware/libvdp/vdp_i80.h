/**
 * vdp_i80.h - ESP32-S3 i80 host transport facade.
 *
 * The implementation is shared with the historical transport unit so existing
 * Mode0 helper code keeps linking, but new firmware should include this header
 * and use the host-neutral register/upload calls below.
 */
#ifndef VDP_I80_H
#define VDP_I80_H

#error "The i80 host transport is retired. Include firmware/libvdp/vdp_host.h and use vdp_host_init() with the QSPI backend instead. " \
       "If you are intentionally maintaining legacy ESP32-S3 i80 code, define VDP_I80_ALLOW_RETIRED before including this header."

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    VDP_HOST_ERR_NONE = 0,
    VDP_HOST_ERR_INVALID_ARG = 2,
    VDP_HOST_ERR_BUS_INIT = 3,
    VDP_HOST_ERR_DEVICE = 4,
    VDP_HOST_ERR_TX = 5,
    VDP_HOST_ERR_RX = 6,
    VDP_HOST_ERR_NOT_INITIALIZED = 7,
    VDP_HOST_ERR_INVALID_SELECTOR = 8,
};

#define VDP_UPLOAD_STATUS_CLEAR_REG    0x0323u
#define VDP_UPLOAD_STATUS_BUSY         0x0001u
#define VDP_UPLOAD_STATUS_DONE         0x0002u
#define VDP_UPLOAD_STATUS_ERROR        0x0004u
#define VDP_UPLOAD_STATUS_OVERFLOW     0x0008u
#define VDP_UPLOAD_STATUS_CLEAR_MASK \
    (VDP_UPLOAD_STATUS_ERROR | VDP_UPLOAD_STATUS_OVERFLOW)

void vdp_host_init(void);
void vdp_reg_write(uint32_t addr, uint16_t data);
void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t num_words);
uint16_t vdp_reg_read(uint32_t addr);
void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words);
void vdp_clear_upload_status(uint16_t mask);
uint32_t vdp_read_status(uint8_t sel);
int vdp_last_error(void);

#ifdef __cplusplus
}
#endif

#endif /* VDP_I80_H */
