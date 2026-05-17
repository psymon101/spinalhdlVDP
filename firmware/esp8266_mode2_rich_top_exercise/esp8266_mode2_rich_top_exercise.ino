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

static void log_burst(const char *block_name, uint32_t base_addr,
                      const char *const *names, const uint16_t *words, uint16_t count)
{
    Serial.printf("BURST %-22s  base=0x%04X  count=%u\n", block_name, (unsigned)base_addr, count);
    for (uint16_t i = 0; i < count; ++i) {
        Serial.printf("  %-22s  addr=0x%04X  data=0x%04X\n",
                      names[i], (unsigned)(base_addr + i), words[i]);
    }
    vdp_reg_write_burst(base_addr, words, count);
    delayMicroseconds(100);
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
    {
        const char *names[] = { "VDP_CTRL", "TILE_MODE", "ATTR_MODE", "MODE_SELECT" };
        const uint16_t words[] = { 0x0001u, 0x0001u, 0x0001u, 0x0000u };
        log_burst("VDP_CTRL block", VDP_MODE0_REG_VDP_CTRL, names, words, 4);
    }

    /* --- Window / color-math block --- */
    {
        const char *names[] = {
            "WIN1_X0", "WIN1_X1", "WIN1_Y0", "WIN1_Y1", "COLOR_MATH_CTRL"
        };
        const uint16_t words[] = { 0x0050u, 0x01E0u, 0x0030u, 0x0168u, 0x0001u };
        log_burst("WIN1 block", VDP_MODE0_REG_WIN1_X0, names, words, 5);
    }
    log_write("WIN2_X0",         VDP_MODE0_REG_WIN2_X0,           0x00A0u);
    log_write("WIN2_CTRL",       VDP_MODE0_REG_WIN2_CTRL,         0x0001u);
    log_write("WIN_COMBINE",     VDP_MODE0_REG_WIN_COMBINE,       0x0000u);
    log_write("LAYER_MASK",      VDP_MODE0_REG_LAYER_MASK,        0x00FFu);
    {
        const char *names[] = {
            "BORDER_X0", "BORDER_X1", "BORDER_Y0", "BORDER_Y1"
        };
        const uint16_t words[] = { 0x0010u, 0x0270u, 0x0010u, 0x01D0u };
        log_burst("BORDER block", VDP_MODE0_REG_BORDER_X0, names, words, 4);
    }
    log_write("BORDER_CTRL",     VDP_MODE0_REG_BORDER_CTRL,       0x0001u);

    /* --- Affine background block --- */
    {
        const char *names[] = {
            "AFFINE_A", "AFFINE_B", "AFFINE_C", "AFFINE_D",
            "AFFINE_X", "AFFINE_Y", "AFFINE_CTRL"
        };
        const uint16_t words[] = {
            0x0100u, 0x0000u, 0x0000u, 0x0100u, 0x0080u, 0x0060u, 0x0001u
        };
        log_burst("AFFINE block", VDP_MODE0_REG_AFFINE_A, names, words, 7);
    }

    /* --- Bitmap fetch block --- */
    {
        const char *names[] = {
            "BITMAP_CTRL", "BITMAP_BASE_LO", "BITMAP_BASE_HI",
            "ATTR_BASE_LO", "ATTR_BASE_HI", "BITMAP_STRIDE", "ATTR_STRIDE"
        };
        const uint16_t words[] = {
            0x0001u, 0x0000u, 0x0000u, 0x0000u, 0x0000u, 0x0140u, 0x000Au
        };
        log_burst("BITMAP block", VDP_MODE0_REG_BITMAP_CTRL, names, words, 7);
    }

    /* --- Raster trigger block --- */
    {
        const char *names1[] = { "TRIGGER1_LINE", "TRIGGER1_PIXEL", "TRIGGER1_CTRL" };
        const uint16_t words1[] = { 0x0064u, 0x00C8u, 0x0001u };
        log_burst("TRIGGER1", VDP_MODE0_REG_TRIGGER1_LINE, names1, words1, 3);
    }
    {
        const char *names2[] = { "TRIGGER2_LINE", "TRIGGER2_PIXEL", "TRIGGER2_CTRL" };
        const uint16_t words2[] = { 0x00C8u, 0x0190u, 0x0001u };
        log_burst("TRIGGER2", VDP_MODE0_REG_TRIGGER2_LINE, names2, words2, 3);
    }
    {
        const char *names3[] = { "TRIGGER3_LINE", "TRIGGER3_PIXEL", "TRIGGER3_CTRL" };
        const uint16_t words3[] = { 0x012Cu, 0x0258u, 0x0001u };
        log_burst("TRIGGER3", VDP_MODE0_REG_TRIGGER3_LINE, names3, words3, 3);
    }

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

    /* --- Quick sanity: read last_addr should be VSCROLL_BASE --- */
    uint32_t last_addr = vdp_read_status(2);
    uint32_t last_data = vdp_read_status(3);
    Serial.printf("\nSanity: last_addr=0x%04X last_data=0x%04X\n",
                  (unsigned)(last_addr & 0xFFFFu),
                  (unsigned)(last_data & 0xFFFFu));

    if ((last_addr & 0xFFFFu) == 0x0A00u) {
        Serial.println("PASS: last_addr matches expected VSCROLL_BASE");
    } else {
        Serial.printf("WARN: last_addr expected 0x0A00, got 0x%04X\n",
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
