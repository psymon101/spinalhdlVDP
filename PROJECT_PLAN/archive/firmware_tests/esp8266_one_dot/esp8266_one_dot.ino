/**
 * esp8266_one_dot.ino — smallest bitmap visibility discriminator.
 *
 * Writes one lit 16-bit word into SDRAM bitmap memory, points Mode0 bitmap
 * fetch at the known-good proof addresses, and enables only L0.
 *
 * Board: NodeMCU 1.0 (ESP-12E)
 * FQBN:  esp8266:esp8266:nodemcuv2
 */

#include <Arduino.h>
#include <vdp_qspi.h>

#define DOT_BITMAP_BASE 0x00003000u
#define DOT_ATTR_BASE   0x00004000u

static void program_one_dot(void)
{
    static const uint16_t bitmap_word = 0x8000u;
    static const uint16_t attr_word = 0x4747u;

    vdp_reg_write(0x0300u, 0x0000u); /* hide layers during setup */
    vdp_reg_write(0x0310u, 0x0000u); /* copper off */
    vdp_reg_write(0x0313u, 0x0000u); /* native mode */

    vdp_sdram_write(DOT_BITMAP_BASE, &bitmap_word, 1);
    vdp_sdram_write(DOT_ATTR_BASE, &attr_word, 1);

    vdp_reg_write(0x0351u, (uint16_t)(DOT_BITMAP_BASE & 0xFFFFu));
    vdp_reg_write(0x0352u, (uint16_t)((DOT_BITMAP_BASE >> 16) & 0x007Fu));
    vdp_reg_write(0x0353u, (uint16_t)(DOT_ATTR_BASE & 0xFFFFu));
    vdp_reg_write(0x0354u, (uint16_t)((DOT_ATTR_BASE >> 16) & 0x007Fu));
    vdp_reg_write(0x0355u, 80u);     /* 640/8 bytes per row */
    vdp_reg_write(0x0356u, 80u);
    vdp_reg_write(0x0350u, 0x0081u); /* enable | 1bpp | useSdram */
    vdp_reg_write(0x0300u, 0x0001u); /* L0 on */
}

void setup(void)
{
    Serial.begin(115200);
    delay(100);
    Serial.println();
    Serial.println(F("ESP8266 one-dot test — booting"));

    vdp_qspi_init();
    delay(200);

    Serial.print(F("Magic: 0x"));
    Serial.println(vdp_read_status(0), HEX);

    program_one_dot();

    Serial.print(F("Sticky: 0x"));
    Serial.println(vdp_read_status(5), HEX);
    Serial.print(F("Last error: 0x"));
    Serial.println(vdp_read_status(4), HEX);
    Serial.println(F("One-dot armed"));
}

void loop(void)
{
    delay(1000);
}
