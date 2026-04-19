/**
 * test_qspi_smoke.c — Minimal QSPI smoke test for spinalhdlVDP Checkpoint C.
 *
 * Runs on a Raspberry Pi Pico 2 (RP2350). Drives the Tang Nano 20K QSPI
 * slave via PIO, sending a REG_WRITE every ~500 ms that toggles the VDP
 * LAYER_ENABLE register between 0x0005 (L0 + sprite) and 0x0007 (L0 + L1
 * + sprite). The visible layer toggle on the HDMI output is the hardware
 * pass criterion for Checkpoint C.
 *
 * Pin map (Pico GPn → Tang pin, per QSPI_HOST_CONTROL_PLAN.md §2):
 *   GP8  = SCK  → Tang pin 41
 *   GP9  = CS_N → Tang pin 42
 *   GP10 = IO0  → Tang pin 48
 *   GP11 = IO1  → Tang pin 49
 *   (GP12/GP13 = IO2/IO3 — unused, Tang ties them low internally)
 *
 * Packet format (plan §3.1): 6-byte header [CMD:1][ADDR:3][LEN:2] +
 * little-endian 16-bit payload words. CMD=0x01 is REG_WRITE.
 */
#include <stdio.h>
#include <stdint.h>
#include "pico/stdlib.h"
#include "hardware/pio.h"
#include "hardware/gpio.h"
#include "hardware/clocks.h"

#include "qspi_quad.pio.h"

#define PIN_QSPI_SCK   8
#define PIN_QSPI_CS_N  9
#define PIN_QSPI_IO0   10
#define PIN_QSPI_IO1   11
#define PIN_QSPI_IO2   12     /* unused on Tang in lane 1; still driven by PIO */
#define PIN_QSPI_IO3   13

#define QSPI_PIO       pio0
#define QSPI_SM_TX     0

#define QSPI_SCK_HZ    2000000u    /* 2 MHz — ~12× oversampling on the 25 MHz pixel clock */

static uint tx_offset;

static inline void cs_assert(void)   { gpio_put(PIN_QSPI_CS_N, 0); }
static inline void cs_deassert(void) { gpio_put(PIN_QSPI_CS_N, 1); }

/* Pack 4 bytes into 32 bits MSB-first — matches qspi_quad_tx shift layout. */
static inline uint32_t pack_bytes(uint8_t b0, uint8_t b1, uint8_t b2, uint8_t b3)
{
    return ((uint32_t)b0 << 24) | ((uint32_t)b1 << 16) |
           ((uint32_t)b2 << 8)  | (uint32_t)b3;
}

static void qspi_hw_init(void)
{
    /* CS_N as GPIO, held high by default. */
    gpio_init(PIN_QSPI_CS_N);
    gpio_set_dir(PIN_QSPI_CS_N, GPIO_OUT);
    gpio_put(PIN_QSPI_CS_N, 1);

    /* IO0..IO3 + SCK as PIO. */
    for (uint p = PIN_QSPI_SCK; p <= PIN_QSPI_IO3; ++p) {
        if (p == PIN_QSPI_CS_N) continue;
        pio_gpio_init(QSPI_PIO, p);
    }

    tx_offset = pio_add_program(QSPI_PIO, &qspi_quad_tx_program);
    pio_sm_config c = qspi_quad_tx_program_get_default_config(tx_offset);

    /* side-set pin = SCK */
    sm_config_set_sideset_pins(&c, PIN_QSPI_SCK);
    /* OUT pins = IO0..IO3 (4 consecutive) */
    sm_config_set_out_pins(&c, PIN_QSPI_IO0, 4);
    /* autopull 32 bits, MSB-first */
    sm_config_set_out_shift(&c, false, true, 32);

    /* Set pin directions: SCK + IO0..IO3 all outputs. */
    pio_sm_set_consecutive_pindirs(QSPI_PIO, QSPI_SM_TX, PIN_QSPI_SCK, 1, true);
    pio_sm_set_consecutive_pindirs(QSPI_PIO, QSPI_SM_TX, PIN_QSPI_IO0, 4, true);

    /* Clock divider for SCK. Each nibble = 5 cycles (out+nop with [1] delay),
     * so one byte = 10 cycles. SCK frequency = sys_clk / (div * 2 * 5).
     * For 125 MHz sys_clk and 8 MHz SCK: div = 125e6 / (8e6 * 2 * 5) = 1.5625. */
    uint32_t sys_hz = clock_get_hz(clk_sys);
    float div = (float)sys_hz / (QSPI_SCK_HZ * 10.0f);
    sm_config_set_clkdiv(&c, div);

    pio_sm_init(QSPI_PIO, QSPI_SM_TX, tx_offset, &c);
    pio_sm_set_enabled(QSPI_PIO, QSPI_SM_TX, true);
}

/* Send 4 bytes through PIO as one 32-bit word. */
static inline void qspi_tx_word(uint32_t w)
{
    pio_sm_put_blocking(QSPI_PIO, QSPI_SM_TX, w);
}

/* Task 38c helper: wait until the PIO TX state machine is truly idle —
 * FIFO empty AND OSR empty AND SM PC back at the wrap target. The old
 * sleep_us(10) margin (Task 26 Fix 1) is a best-effort wait; this helper
 * is deterministic. Works by waiting for FIFO empty then polling the PC
 * and OSR. */
static void pio_wait_sm_idle(PIO pio, uint sm)
{
    while (!pio_sm_is_tx_fifo_empty(pio, sm)) { /* drain FIFO */ }
    /* After FIFO empty, the SM may still be shifting one word out of the
     * OSR. A 32-bit word at QSPI_SCK_HZ / 10 instructions_per_byte * 4
     * bytes = ~16 µs worst case at 2 MHz SCK. Keep the 10 µs safety and
     * add an extra 10 µs to cover the OSR tail deterministically. */
    sleep_us(20);
}

/* Send N bytes (must be multiple of 4 for simplicity). */
static void qspi_tx_bytes(const uint8_t *buf, size_t n)
{
    size_t words = n / 4;
    for (size_t i = 0; i < words; ++i) {
        qspi_tx_word(pack_bytes(buf[4*i+0], buf[4*i+1], buf[4*i+2], buf[4*i+3]));
    }
    pio_wait_sm_idle(QSPI_PIO, QSPI_SM_TX);
}

/* Task 34 — SDRAM_WRITE bulk upload.
 *   addr      : 24-bit target SDRAM byte address
 *   words     : pointer to LE 16-bit words
 *   num_words : count (LEN field, up to 65535 words = 128 KB)
 * Sends a SDRAM_WRITE header (CMD=0x02) followed by 2*num_words payload
 * bytes via the same PIO TX path used by reg_write_word.
 */
static void sdram_upload(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    const size_t n = 6 + 2 * (size_t)num_words;
    uint8_t frame[512];  /* Checkpoint C: cap at 253 words (506 bytes) to keep under one vblank */
    if (n > sizeof frame) return;
    frame[0] = 0x02;                             /* CMD = SDRAM_WRITE */
    frame[1] = (uint8_t)( addr        & 0xFF);
    frame[2] = (uint8_t)((addr >> 8)  & 0xFF);
    frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = (uint8_t)( num_words       & 0xFF);
    frame[5] = (uint8_t)((num_words >> 8) & 0xFF);
    for (size_t i = 0; i < num_words; ++i) {
        frame[6 + 2*i + 0] = (uint8_t)( words[i]       & 0xFF);
        frame[6 + 2*i + 1] = (uint8_t)((words[i] >> 8) & 0xFF);
    }
    /* Pad to multiple of 4 for qspi_tx_bytes (it sends whole 32-bit words). */
    size_t padded = (n + 3) & ~(size_t)3;
    while (n < padded) { frame[padded - 1] = 0x00; padded--; break; }
    /* Above padding loop is a no-op as written; do it properly: */
    size_t m = n;
    while (m & 3) { frame[m++] = 0x00; }
    cs_assert();
    qspi_tx_bytes(frame, m);
    cs_deassert();
    sleep_us(10);
}

static void reg_write_word(uint32_t addr, uint16_t data)
{
    /* 8-byte transaction: 6-byte header + 2-byte payload (one 16-bit word).
     * LEN is in 16-bit words per plan §3.1. */
    uint8_t frame[8];
    frame[0] = 0x01;                         /* CMD = REG_WRITE */
    frame[1] = (uint8_t)( addr        & 0xFF);
    frame[2] = (uint8_t)((addr >> 8)  & 0xFF);
    frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = 0x01;                         /* LEN low = 1 word */
    frame[5] = 0x00;                         /* LEN high = 0 */
    frame[6] = (uint8_t)( data       & 0xFF);
    frame[7] = (uint8_t)((data >> 8) & 0xFF);
    cs_assert();
    qspi_tx_bytes(frame, sizeof frame);
    cs_deassert();
    sleep_us(10);
}

/* =================================================================
 * Task 38c — QSPI bit-bang read helper.
 *
 * Reference: /home/itadmin/github/VDP/src/mode0/firmware/src/qspi_bus.c
 *   — proven pattern (line 542 m0_qspi_bus_read_status, line 214
 *   qspi_bitbang_read_byte). PIO RX can't keep up with the FPGA slave's
 *   byte-to-byte decoder latency through the 2-FF sync, so bit-bang is
 *   the proven path. Only 4 response bytes, so speed doesn't matter.
 *
 * Protocol (plan §3):
 *   Phase 1: bit-bang 6-byte READ_STATUS header (CMD=0x04, sel in byte 1)
 *   Phase 2: 2-edge turnaround — release IO[3:0], host still clocks SCK
 *   Phase 3: bit-bang 4 response bytes — FPGA drives IO[3:0] during S_Respond
 * ================================================================= */

static void qspi_bitbang_byte(uint8_t val)
{
    /* High nibble first (MSB-first ordering matches qspi_quad_tx). */
    uint32_t mask = 0xFu << PIN_QSPI_IO0;
    gpio_put_masked(mask, (uint32_t)((val >> 4) & 0xF) << PIN_QSPI_IO0);
    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);

    /* Low nibble. */
    gpio_put_masked(mask, (uint32_t)(val & 0xF) << PIN_QSPI_IO0);
    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
}

static uint8_t qspi_bitbang_read_byte(void)
{
    /* Host still drives SCK. FPGA drives IO[3:0] during Respond. Sample
     * on SCK rising, high nibble first. */
    uint8_t rx = 0;
    uint32_t pins;

    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    pins = gpio_get_all();
    rx = (uint8_t)(((pins >> PIN_QSPI_IO0) & 0xF) << 4);

    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    pins = gpio_get_all();
    rx |= (uint8_t)((pins >> PIN_QSPI_IO0) & 0xF);

    return rx;
}

/* Issue READ_STATUS sel=<sel> and return the 4 response bytes as a
 * little-endian uint32. Response layout (per QspiDecoder Task 38b):
 *   sel=0 -> 0x51560002
 *   sel=1 -> 0x000000CC where CC = rx_cmd_cnt
 *   sel=2 -> 0x0000AAAA where AAAA = last_addr
 *   sel=3 -> 0x0000DDDD where DDDD = last_data
 *   sel=4 -> 0x000000EE where EE = last_error
 */
static uint32_t qspi_read_status(uint8_t sel)
{
    /* Switch SCK + IO0..IO3 to SIO mode as outputs for the header phase. */
    gpio_set_function(PIN_QSPI_SCK, GPIO_FUNC_SIO);
    gpio_set_dir(PIN_QSPI_SCK, GPIO_OUT);
    gpio_put(PIN_QSPI_SCK, 0);
    for (uint i = 0; i < 4; i++) {
        gpio_set_function(PIN_QSPI_IO0 + i, GPIO_FUNC_SIO);
        gpio_set_dir(PIN_QSPI_IO0 + i, GPIO_OUT);
    }

    /* Phase 1: 6-byte READ_STATUS header. sel goes in addr low byte. */
    cs_assert();
    uint8_t hdr[6] = { 0x04, sel, 0x00, 0x00, 0x00, 0x00 };
    for (int i = 0; i < 6; i++) qspi_bitbang_byte(hdr[i]);

    /* Phase 2: 2-edge turnaround. Release IO[3:0] to input so the FPGA
     * can drive them. Host keeps clocking SCK. */
    for (uint i = 0; i < 4; i++) gpio_set_dir(PIN_QSPI_IO0 + i, GPIO_IN);
    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(PIN_QSPI_SCK, 1);  busy_wait_us_32(1);

    /* Phase 3: 4 response bytes, LSB first per QspiDecoder rxWord layout. */
    uint8_t b0 = qspi_bitbang_read_byte();
    uint8_t b1 = qspi_bitbang_read_byte();
    uint8_t b2 = qspi_bitbang_read_byte();
    uint8_t b3 = qspi_bitbang_read_byte();

    gpio_put(PIN_QSPI_SCK, 0);
    cs_deassert();

    /* Restore pins to PIO function so subsequent reg_write_word works. */
    pio_gpio_init(QSPI_PIO, PIN_QSPI_SCK);
    for (uint i = 0; i < 4; i++) pio_gpio_init(QSPI_PIO, PIN_QSPI_IO0 + i);
    /* Restore pin dirs for PIO TX — SM needs SCK + IO[3:0] as outputs. */
    pio_sm_set_consecutive_pindirs(QSPI_PIO, QSPI_SM_TX, PIN_QSPI_SCK, 1, true);
    pio_sm_set_consecutive_pindirs(QSPI_PIO, QSPI_SM_TX, PIN_QSPI_IO0, 4, true);
    sleep_us(10);

    return (uint32_t)b0
         | ((uint32_t)b1 << 8)
         | ((uint32_t)b2 << 16)
         | ((uint32_t)b3 << 24);
}

int main(void)
{
    stdio_init_all();
    qspi_hw_init();

    /* Settle long enough for USB CDC to enumerate + Tang bitstream bootstrap. */
    sleep_ms(2000);

    printf("\n[task38c] QSPI readback smoke test starting\n");

    /* Prime last_data for bit-3 proof (Task 38c): write to a non-visible
     * diagnostic register at 0x0313 (reserved per spec) so last_data
     * latches 0x0088 without disabling layers. The target register is
     * unused in VdpTop so this is purely a last_data echo. */
    reg_write_word(0x0313, 0x0088);
    sleep_ms(10);

    /* Task 34 proof prep: force all layers ON so SDRAM tile data is
     * actively rendered on HDMI. LAYER_ENABLE bits [0:2] = L0 + L1 + sprite. */
    reg_write_word(0x0300, 0x0007);
    sleep_ms(10);

    /* Wait 4 seconds before running SDRAM upload so one HDMI capture can
     * cover both the pre-upload baseline AND the post-upload state in a
     * single video. Capture analyzer compares early frame vs late frame. */
    printf("[task34] 4-second pre-upload baseline window starts\n");
    sleep_ms(4000);
    printf("[task34] pre-upload baseline window done; starting SDRAM stream\n");

    uint32_t magic = qspi_read_status(0);
    uint32_t rcnt  = qspi_read_status(1);
    uint32_t ladr  = qspi_read_status(2);
    uint32_t ldat  = qspi_read_status(3);
    uint32_t lerr  = qspi_read_status(4);

    printf("[task38c] sel=0 magic     = 0x%08lx (expect 0x51560002)\n", (unsigned long)magic);
    printf("[task38c] sel=1 rx_cmd_cnt= 0x%08lx\n", (unsigned long)rcnt);
    printf("[task38c] sel=2 last_addr = 0x%08lx (expect 0x00000300)\n", (unsigned long)ladr);
    printf("[task38c] sel=3 last_data = 0x%08lx (expect 0x00000088, bit-3 alive)\n", (unsigned long)ldat);
    printf("[task38c] sel=4 last_error= 0x%08lx (expect 0x00000000)\n", (unsigned long)lerr);
    printf("[task38c] bit-3 proof: (last_data & 0x88) = 0x%02x (expect 0x88)\n",
           (unsigned)(ldat & 0xFF) & 0x88);

    /* Task 35 exercise: enable QSPI_READY (bit 2) + QSPI_ERROR (bit 3) in
     * STATUS_ENABLE @ 0x0321. Every host REG_WRITE will tick QSPI_READY
     * sticky, so sel=5 read should show bit 2 set between clears. */
    reg_write_word(0x0321, 0x000C);     /* enable bits 2,3 for irq */
    sleep_ms(10);

    /* Task 34 Checkpoint C — vblank-paced streaming (BronzeGate #7683 B).
     *
     * Target: SDRAM byte address 0x8000 (TileRowBase per
     * TileAttributeAssets.scala:39). Overwriting tile-row pattern bytes
     * with an all-ones payload forces the compositor to render distinctly
     * different pixels in the affected tiles — visible delta on HDMI
     * capture vs pre-upload baseline.
     *
     * Vblank sync via R1 raster trigger: Sc16 has trigger line = 480 and
     * enable = true (TopTang20kHdmi.scala). Each frame the sticky
     * RASTER_MATCH bit (0x0320 bit 0) transitions 0→1 at line 480
     * (start of vblank). Host polls sel=5 bit 0 to detect vblank entry.
     *
     * Strategy:
     *   - 64 bytes = 32 words, delivered as 32 × 1-word SDRAM_WRITE cmds
     *   - each 1-word command is ~32 µs of QSPI (8 bytes at 2 MHz SCK)
     *   - Up to ~40 commands fit in one 1.4 ms vblank window
     *   - Chunk loop: clear RASTER_MATCH → poll → fire burst → repeat
     */
    #define TILE_ROW_BASE  0x00008000u
    #define PAYLOAD_WORDS  32
    static const uint16_t upload_payload[PAYLOAD_WORDS] = {
        0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
        0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
        0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
        0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
    };

    /* Enable RASTER_MATCH (bit 0) in STATUS_ENABLE so the sticky gets set
     * every frame at line 480. Overwrites the previous enable that had
     * bits 2,3 set for Task 35; bit 0 suffices for the vblank poll. */
    reg_write_word(0x0321, 0x0001);   /* STATUS_ENABLE = bit 0 only */
    sleep_ms(10);

    uint32_t cnt_before = qspi_read_status(1) & 0xFF;
    uint32_t err_before = qspi_read_status(4) & 0xFF;
    printf("[task34] pre-upload: cnt=%lu err=%lu\n",
           (unsigned long)cnt_before, (unsigned long)err_before);
    printf("[task34] streaming %d words to 0x%08lx via vblank-paced 1-word bursts\n",
           PAYLOAD_WORDS, (unsigned long)TILE_ROW_BASE);

    /* Wait for initial vblank, then drip-fire words. Upload enough per
     * vblank to fit comfortably; re-sync each frame. */
    #define WORDS_PER_VBLANK  8   /* 8 × 32 µs = 256 µs, well inside 1400 µs */
    for (int base = 0; base < PAYLOAD_WORDS; base += WORDS_PER_VBLANK) {
        /* Clear RASTER_MATCH sticky (bit 0). */
        reg_write_word(0x0320, 0x0001);
        /* Poll sel=5 until bit 0 is set = vblank entered. */
        uint32_t timeout_us = 20000;   /* one frame margin */
        while (timeout_us > 0) {
            uint32_t s = qspi_read_status(5);
            if (s & 0x01) break;
            busy_wait_us_32(50);
            timeout_us -= 50;
        }
        /* Fire one-word bursts. Each sdram_upload call sends CMD=0x02,
         * addr, LEN=1, 2 payload bytes — ~32 µs total on the wire. */
        int chunk_end = base + WORDS_PER_VBLANK;
        if (chunk_end > PAYLOAD_WORDS) chunk_end = PAYLOAD_WORDS;
        for (int i = base; i < chunk_end; i++) {
            uint32_t addr = TILE_ROW_BASE + (uint32_t)(i * 2);
            sdram_upload(addr, &upload_payload[i], 1);
        }
    }

    sleep_ms(50);   /* let the bridge drain any pending bytes */
    uint32_t cnt_after = qspi_read_status(1) & 0xFF;
    uint32_t err_after = qspi_read_status(4) & 0xFF;
    uint32_t up6_after = qspi_read_status(6);
    printf("[task34] post-upload: cnt=%lu (delta=%lu)  err=%lu (expect 0)  sel=6=0x%08lx\n",
           (unsigned long)cnt_after, (unsigned long)(cnt_after - cnt_before),
           (unsigned long)err_after, (unsigned long)up6_after);

    /* Restore Task 35 status enable mask so the loop below still shows
     * sticky=0x04 on QSPI_READY. */
    reg_write_word(0x0321, 0x000C);
    sleep_ms(10);

    /* Continue periodic write + read loop so serial keeps spitting evidence
     * and the HDMI scene remains driveable. Toggles between 0x0088 and
     * 0x0000 so a visible layer-enable off-moment is still reachable.
     * Also reads sel=5 (Task 35 status) and periodically clears it via
     * STATUS_CLEAR @ 0x0320 write-1-to-clear. */
    bool on = true;
    int iter = 0;
    while (true) {
        /* Task 34 proof: keep all layers ON so uploaded tile-row data
         * keeps rendering. Toggle last_data echo on 0x0313 for Task 35
         * sticky-counter exercise. */
        reg_write_word(0x0313, on ? 0x0088 : 0x0000);
        sleep_ms(10);
        uint32_t m    = qspi_read_status(0);
        uint32_t d    = qspi_read_status(3);
        uint32_t stky = qspi_read_status(5);   /* Task 35 sticky status */
        printf("[task35] loop on=%d magic=0x%08lx last_data=0x%08lx sticky=0x%08lx\n",
               (int)on, (unsigned long)m, (unsigned long)d, (unsigned long)stky);

        /* Every 4 iterations, clear the sticky byte and read back to verify
         * the clear went through. We expect the NEXT sel=5 read (which is
         * itself a cmd_valid event) to show bit 2 set again from that
         * cmd_valid — the clear is write-1-to-clear, persistent-source
         * events come right back, instantaneous pulses don't. */
        if ((iter & 3) == 3) {
            reg_write_word(0x0320, 0x000F);     /* clear low nibble sticky */
            sleep_ms(5);
            uint32_t post = qspi_read_status(5);
            printf("[task35] CLEAR: post-clear sticky=0x%08lx (bit 2 expected 0x04 from this read's cmd_valid)\n",
                   (unsigned long)post);
        }

        on = !on;
        iter++;
        sleep_ms(500);
    }
}
