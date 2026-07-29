/**
 * esp32_sc45_host_init.ino — #9026 sc45-host narrowed proof for ESP32.
 *
 * Sc45: drive the copper sequence so the FPGA renders sc45 with the
 * bitstream's `useHostInit=true` boot bypass active.
 *
 * This version reuses libvdp for transport.
 *
 * Board:  ESP32 dev1
 * FQBN:   esp32:esp32:esp32
 */

#include <Arduino.h>
#include <vdp_qspi.h>

static void sc45_host_init(void)
{
    Serial.println("sc45-host: uploading copper program (220 words)...");

    vdp_reg_write(0x0400u, 0x0000u);         // WAIT y=0
    vdp_reg_write(0x0401u, 0x4350u);         // WRITE BITMAP_CTRL opcode
    vdp_reg_write(0x0402u, 0x0081u);         // BITMAP_CTRL = en|1bpp|useSdram

    for (int g = 0; g < 24; ++g) {
        uint16_t opcodeWord = 0xB800u | (uint16_t)(g * 8);  // WSEQ | (7<<11) | baseLine
        uint32_t base = 0x0400u + 3u + (uint32_t)(g * 9);
        vdp_reg_write(base + 0, opcodeWord);
        for (int k = 1; k <= 8; ++k) {
            vdp_reg_write(base + k, 0x0800u);  // l0en=1, l1en=0, scrollX=0
        }
    }

    vdp_reg_write(0x0400u + 219u, 0xC000u);  // JUMP 0

    Serial.println("sc45-host: copper program uploaded; writing control regs...");

    vdp_reg_write(0x0311u, 0x0000u);         // TILE_MODE = packed
    vdp_reg_write(0x0312u, 0x0000u);         // ATTR_MODE = linear
    vdp_reg_write(0x0310u, 0x0001u);         // VDP_CTRL  = copper enabled
    vdp_reg_write(0x0300u, 0x0001u);         // LAYER_ENABLE = L0 only
    vdp_reg_write(0x0330u, 0x0000u);         // WIN_X0
    vdp_reg_write(0x0331u, 0x0000u);         // WIN_X1
    vdp_reg_write(0x0332u, 0x0000u);         // WIN_Y0
    vdp_reg_write(0x0333u, 0x0000u);         // WIN_Y1
    vdp_reg_write(0x0334u, 0x0000u);         // COLOR_MATH = passthrough

    Serial.println("sc45-host: control regs written; init complete");
}

void setup(void)
{
    Serial.begin(115200);
    delay(50);
    Serial.println("ESP32 sc45-host host (libvdp version) — booting");

    vdp_qspi_init();
    delay(200);
    sc45_host_init();
    Serial.println("Host-init done; idling (bitmap driven by copper running on FPGA)");
}

void loop(void)
{
    delay(1000);
}
