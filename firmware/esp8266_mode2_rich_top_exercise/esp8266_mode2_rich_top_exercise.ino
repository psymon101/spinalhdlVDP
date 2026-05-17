/**
 * Mode2optimized rich-top register surface exercise sketch.
 *
 * Purpose: Exercise the full Mode0 rich-top register bus via libvdp
 * to prove the host write path and verify register commit on the
 * mode2optimized FPGA image.
 *
 * Hardware: Tang Nano 20K (mode2optimized branch / rich-top)
 * Host:     ESP8266 NodeMCU 1.0
 * Pins:     SCK=D5/GPIO14, CS=D6/GPIO12, IO0=D7/GPIO13,
 *           IO1=D1/GPIO5, IO2=D2/GPIO4, IO3=D0/GPIO16
 *
 * Protocol: Standard 6-byte QSPI framing via libvdp.
 *
 * Expected result:
 *   Serial console prints a register-exercise log showing each write
 *   address/data pair and the corresponding READ_STATUS verification.
 *   No visual output is required — this is a transport/registers test.
 */

#include <Arduino.h>
#include <vdp_qspi.h>
#include <vdp_mode0.h>
#include <vdp_status.h>

static void log_write(const char *name, uint32_t addr, uint16_t data)
{
    Serial.printf("REG %-24s  addr=0x%04X  data=0x%04X\n", name, (unsigned)addr, data);
    vdp_reg_write(addr, data);
    delayMicroseconds(50);
}

static void log_status(const char *name, uint8_t sel)
{
    uint32_t val = vdp_read_status(sel);
    Serial.printf("STS %-24s  sel=%u  val=0x%08X\n", name, sel, (unsigned)val);
    delayMicroseconds(50);
}

void setup(void)
{
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n=== Mode2optimized Rich-Top Register Exercise ===\n");

    vdp_qspi_init();
    delay(200);

    /* --- Global control block --- */
    log_write("LAYER_ENABLE",    VDP_MODE0_REG_LAYER_ENABLE,      0x0007u);
    log_write("VDP_CTRL",        VDP_MODE0_REG_VDP_CTRL,          0x0001u);
    log_write("TILE_MODE",       VDP_MODE0_REG_VDP_TILE_MODE,     0x0001u);
    log_write("ATTR_MODE",       VDP_MODE0_REG_VDP_ATTR_MODE,     0x0001u);
    log_write("MODE_SELECT",     VDP_MODE0_REG_MODE_SELECT,       0x0000u);

    /* --- Window / color-math block --- */
    log_write("WIN1_X0",         VDP_MODE0_REG_WIN1_X0,           0x0050u);
    log_write("WIN1_X1",         VDP_MODE0_REG_WIN1_X1,           0x01E0u);
    log_write("WIN1_Y0",         VDP_MODE0_REG_WIN1_Y0,           0x0030u);
    log_write("WIN1_Y1",         VDP_MODE0_REG_WIN1_Y1,           0x0168u);
    log_write("COLOR_MATH_CTRL", VDP_MODE0_REG_COLOR_MATH_CTRL,   0x0001u);
    log_write("WIN2_X0",         VDP_MODE0_REG_WIN2_X0,           0x00A0u);
    log_write("WIN2_CTRL",       VDP_MODE0_REG_WIN2_CTRL,         0x0001u);
    log_write("WIN_COMBINE",     VDP_MODE0_REG_WIN_COMBINE,       0x0000u);
    log_write("LAYER_MASK",      VDP_MODE0_REG_LAYER_MASK,        0x00FFu);
    log_write("BORDER_X0",       VDP_MODE0_REG_BORDER_X0,         0x0010u);
    log_write("BORDER_X1",       VDP_MODE0_REG_BORDER_X1,         0x0270u);
    log_write("BORDER_Y0",       VDP_MODE0_REG_BORDER_Y0,         0x0010u);
    log_write("BORDER_Y1",       VDP_MODE0_REG_BORDER_Y1,         0x01D0u);
    log_write("BORDER_CTRL",     VDP_MODE0_REG_BORDER_CTRL,       0x0001u);

    /* --- Affine background block --- */
    log_write("AFFINE_A",        VDP_MODE0_REG_AFFINE_A,          0x0100u);
    log_write("AFFINE_B",        VDP_MODE0_REG_AFFINE_B,          0x0000u);
    log_write("AFFINE_C",        VDP_MODE0_REG_AFFINE_C,          0x0000u);
    log_write("AFFINE_D",        VDP_MODE0_REG_AFFINE_D,          0x0100u);
    log_write("AFFINE_X",        VDP_MODE0_REG_AFFINE_X,          0x0080u);
    log_write("AFFINE_Y",        VDP_MODE0_REG_AFFINE_Y,          0x0060u);
    log_write("AFFINE_CTRL",     VDP_MODE0_REG_AFFINE_CTRL,       0x0001u);

    /* --- Bitmap fetch block --- */
    log_write("BITMAP_CTRL",     VDP_MODE0_REG_BITMAP_CTRL,       0x0001u);
    log_write("BITMAP_BASE_LO",  VDP_MODE0_REG_BITMAP_BASE_LO,    0x0000u);
    log_write("BITMAP_BASE_HI",  VDP_MODE0_REG_BITMAP_BASE_HI,    0x0000u);
    log_write("ATTR_BASE_LO",    VDP_MODE0_REG_ATTR_BASE_LO,      0x0000u);
    log_write("ATTR_BASE_HI",    VDP_MODE0_REG_ATTR_BASE_HI,      0x0000u);
    log_write("BITMAP_STRIDE",   VDP_MODE0_REG_BITMAP_STRIDE,     0x0140u);
    log_write("ATTR_STRIDE",     VDP_MODE0_REG_ATTR_STRIDE,       0x000Au);

    /* --- Raster trigger block --- */
    log_write("TRIGGER1_LINE",   VDP_MODE0_REG_TRIGGER1_LINE,     0x0064u);
    log_write("TRIGGER1_PIXEL",  VDP_MODE0_REG_TRIGGER1_PIXEL,    0x00C8u);
    log_write("TRIGGER1_CTRL",   VDP_MODE0_REG_TRIGGER1_CTRL,     0x0001u);
    log_write("TRIGGER2_LINE",   VDP_MODE0_REG_TRIGGER2_LINE,     0x00C8u);
    log_write("TRIGGER2_PIXEL",  VDP_MODE0_REG_TRIGGER2_PIXEL,    0x0190u);
    log_write("TRIGGER2_CTRL",   VDP_MODE0_REG_TRIGGER2_CTRL,     0x0001u);
    log_write("TRIGGER3_LINE",   VDP_MODE0_REG_TRIGGER3_LINE,     0x012Cu);
    log_write("TRIGGER3_PIXEL",  VDP_MODE0_REG_TRIGGER3_PIXEL,    0x0258u);
    log_write("TRIGGER3_CTRL",   VDP_MODE0_REG_TRIGGER3_CTRL,     0x0001u);

    /* --- HDMA / table base --- */
    log_write("HDMA_BASE",       VDP_MODE0_REG_HDMA_BASE,         0x0380u);
    log_write("VSCROLL_BASE",    VDP_MODE0_REG_VSCROLL_BASE,      0x0A00u);

    /* --- Status / sticky verification --- */
    Serial.println("\n--- Status read-back ---");
    log_status("Magic ID",        0);
    log_status("Rx Cmd Cnt",      1);
    log_status("Last Addr",       2);
    log_status("Last Data",       3);
    log_status("Last Error",      4);
    log_status("Status Sticky",   5);
    log_status("Upload Status",   6);
    log_status("Live Mode",       7);

    /* --- Quick sanity: read last_addr should be HDMA_BASE --- */
    uint32_t last_addr = vdp_read_status(2);
    uint32_t last_data = vdp_read_status(3);
    Serial.printf("\nSanity: last_addr=0x%04X last_data=0x%04X\n",
                  (unsigned)(last_addr & 0xFFFFu),
                  (unsigned)(last_data & 0xFFFFu));

    if ((last_addr & 0xFFFFu) == 0x0380u) {
        Serial.println("PASS: last_addr matches expected HDMA_BASE");
    } else {
        Serial.printf("WARN: last_addr expected 0x0380, got 0x%04X\n",
                      (unsigned)(last_addr & 0xFFFFu));
    }

    /* --- QSPI_ERROR sticky check --- */
    uint32_t sticky = vdp_read_status(5);
    if (sticky & (1u << 3)) {
        Serial.println("WARN: QSPI_ERROR sticky bit is set");
    } else {
        Serial.println("PASS: QSPI_ERROR sticky bit is clear");
    }

    Serial.println("\n=== Register exercise complete ===");
}

void loop(void)
{
    delay(5000);
}
