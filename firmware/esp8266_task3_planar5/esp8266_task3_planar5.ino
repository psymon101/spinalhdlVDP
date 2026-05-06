/**
 * esp8266_task3_planar5.ino — Task 3 Checkpoint F (HW proof) host sketch.
 *
 * Standalone, single-purpose firmware per `feedback_firmware_per_test`.
 * Drives the 5-plane / 320-pixel planar fetch added in Task 3 (commit
 * 44efa3f). On boot:
 *
 *   1. Init QSPI GPIO (ESP8266 NodeMCU pin map per
 *      reference_esp8266_qspi_wiring.md).
 *   2. SDRAM_WRITE (CMD=0x02): upload SMPTE-bar planar bit data to
 *      SDRAM at planeBase[p] = 0x100000 + p*0x1000 for p in 0..4.
 *      Plane 0 = LSB of the bar index, planes 3..4 zero (per
 *      PlanarProofAssets). 40 bytes / 20 16-bit-words per plane.
 *   3. REG_WRITE (CMD=0x01): load 32-entry palette via 0x0601/0x0600
 *      with classic 8-bar SMPTE colors at slots 0..7, black elsewhere.
 *   4. REG_WRITE plane base addresses to PLANE_BASE_LO/HI registers
 *      at 0x0D40..0x0D49 (lo/hi pairs, 23-bit composed).
 *   5. REG_WRITE PLANAR_CTRL @ 0x0D4A = 0x0001 (planarFetchEnable=1).
 *   6. Idle loop — once descriptors land, the FPGA renders a static
 *      planar scene continuously.
 *
 * Wire protocol: identical 8-byte REG_WRITE framing as sc62/task2b
 * (BronzeGate #9123). SDRAM_WRITE extends this with LEN×2 trailing
 * data bytes (LEN counts 16-bit words; bridge converts × 2 internally
 * per QspiDecoder line 107).
 *
 * Pin map: D5/SCK D6/CS_N D7/IO0 D1/IO1 D2/IO2 D0/IO3 (per
 * reference_esp8266_qspi_wiring.md).
 *
 * Expected on-screen result:
 *   - Vertical SMPTE-style color bars across the planar 320-pixel
 *     window (left half of the 640×480 display per
 *     reference_hdmi_signal_spec).
 *   - 8 distinct colors (white/yellow/cyan/green/magenta/red/blue/black)
 *     each ≈40 wide.
 *   - Static (no motion); the 5-plane fetch is exercised on every
 *     scanline regardless of vertical position.
 */

#include <Arduino.h>

// ---- Pin map (per reference_esp8266_qspi_wiring.md) ------------------------

static constexpr uint8_t PIN_SCK   = 14;   // D5
static constexpr uint8_t PIN_CS_N  = 12;   // D6
static constexpr uint8_t PIN_IO0   = 13;   // D7
static constexpr uint8_t PIN_IO1   =  5;   // D1
static constexpr uint8_t PIN_IO2   =  4;   // D2
static constexpr uint8_t PIN_IO3   = 16;   // D0 — RTC pad

static constexpr uint32_t MASK_SCK    = 1u << PIN_SCK;
static constexpr uint32_t MASK_CS_N   = 1u << PIN_CS_N;
static constexpr uint32_t MASK_IO0    = 1u << PIN_IO0;
static constexpr uint32_t MASK_IO1    = 1u << PIN_IO1;
static constexpr uint32_t MASK_IO2    = 1u << PIN_IO2;
static constexpr uint32_t MASK_IO_LOW = MASK_IO0 | MASK_IO1 | MASK_IO2;

static constexpr uint32_t HALF_PERIOD_US = 1;   // ~500 kHz SCK

// ---- QSPI primitives -------------------------------------------------------

static inline void drive_nibble(uint8_t n)
{
    uint32_t set = 0;
    if (n & 0x1) set |= MASK_IO0;
    if (n & 0x2) set |= MASK_IO1;
    if (n & 0x4) set |= MASK_IO2;
    GPOC = MASK_IO_LOW;
    if (set) GPOS = set;
    digitalWrite(PIN_IO3, (n & 0x8) ? HIGH : LOW);
}

static inline void send_nibble(uint8_t n)
{
    drive_nibble(n);
    GPOC = MASK_SCK;
    delayMicroseconds(HALF_PERIOD_US);
    GPOS = MASK_SCK;
    delayMicroseconds(HALF_PERIOD_US);
}

static inline void send_byte(uint8_t b)
{
    send_nibble((b >> 4) & 0x0F);
    send_nibble( b       & 0x0F);
}

static void vdp_qspi_init(void)
{
    pinMode(PIN_SCK,  OUTPUT);
    pinMode(PIN_CS_N, OUTPUT);
    pinMode(PIN_IO0,  OUTPUT);
    pinMode(PIN_IO1,  OUTPUT);
    pinMode(PIN_IO2,  OUTPUT);
    pinMode(PIN_IO3,  OUTPUT);
    GPOS = MASK_CS_N;
    GPOC = MASK_SCK | MASK_IO_LOW;
    digitalWrite(PIN_IO3, LOW);
}

static void vdp_reg_write(uint32_t addr, uint16_t data)
{
    uint8_t frame[8];
    frame[0] = 0x01;                                  // REG_WRITE opcode
    frame[1] = (uint8_t)( addr        & 0xFF);
    frame[2] = (uint8_t)((addr >>  8) & 0xFF);
    frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = 0x01;                                  // LEN lo (1 word)
    frame[5] = 0x00;                                  // LEN hi
    frame[6] = (uint8_t)( data       & 0xFF);
    frame[7] = (uint8_t)((data >> 8) & 0xFF);

    GPOC = MASK_CS_N;
    delayMicroseconds(1);
    for (size_t i = 0; i < sizeof(frame); ++i) send_byte(frame[i]);
    GPOC = MASK_SCK;
    delayMicroseconds(1);
    GPOS = MASK_CS_N;
    delayMicroseconds(10);
}

// SDRAM_WRITE (CMD=0x02): 6-byte header (opcode + 24-bit addr + 16-bit
// LEN-in-words) followed by LEN × 2 bytes of data (LE 16-bit words).
// Per QspiDecoder.scala line 107, the bridge multiplies cmd_len by 2 to
// get bytes; per QspiSdramBridge.scala the FSM writes one byte per
// pixel-clock cycle gated on `allowUpload && !sdramBusy`.
static void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    GPOC = MASK_CS_N;
    delayMicroseconds(1);

    send_byte(0x02);                                  // SDRAM_WRITE opcode
    send_byte((uint8_t)( addr        & 0xFF));
    send_byte((uint8_t)((addr >>  8) & 0xFF));
    send_byte((uint8_t)((addr >> 16) & 0xFF));
    send_byte((uint8_t)( num_words       & 0xFF));    // LEN lo
    send_byte((uint8_t)((num_words >> 8) & 0xFF));    // LEN hi

    for (uint16_t i = 0; i < num_words; ++i) {
        send_byte((uint8_t)( words[i]       & 0xFF));
        send_byte((uint8_t)((words[i] >> 8) & 0xFF));
    }

    GPOC = MASK_SCK;
    delayMicroseconds(1);
    GPOS = MASK_CS_N;
    // Larger settle than REG_WRITE — the bridge needs time to drain its
    // FSM (one byte per pixel-clock cycle, but allowUpload gating may
    // queue up to ~1 ms of work for large transactions).
    delayMicroseconds(2000);
}

// ---- SMPTE plane data (PlanarProofAssets equivalent) -----------------------
//
// Computes plane p, slot s (0=leftmost) per:
//   for b in 0..32:
//     pixelIdx = s*32 + b
//     barIdx   = pixelIdx / 40    (40 px per bar, 8 bars across 320)
//     bit      = (barIdx >> p) & 1
//     dword[31 - b] = bit
// Planes 3..4 always 0 (only 8 bars use bits 0..2).
//
// Within SDRAM, each dword's 4 bytes are little-endian — byte at addr+0
// = dword[7:0], byte at addr+3 = dword[31:24]. Per BitplaneRowFetch the
// dout32 word's bit 31 is the leftmost pixel of the slot.

static constexpr int PLANE_COUNT     = 5;
static constexpr int PLANE_PIXELS    = 320;
static constexpr int DWORDS_PER_PLANE = PLANE_PIXELS / 32;   // 10
static constexpr int WORDS_PER_PLANE  = DWORDS_PER_PLANE * 2; // 20 (16-bit words)
static constexpr uint32_t PLANE_BASE_STRIDE = 0x1000;         // 4 KB per plane
static constexpr uint32_t PLANE_BASE_ROOT   = 0x100000;       // 1 MB into SDRAM

static uint32_t plane_dword(int plane, int slot)
{
    if (plane >= 3) return 0;
    uint32_t word = 0;
    for (int b = 0; b < 32; ++b) {
        int pixelIdx = slot * 32 + b;
        int barIdx   = pixelIdx / 40;
        int bit      = (barIdx >> plane) & 1;
        if (bit) word |= (1u << (31 - b));
    }
    return word;
}

static void upload_plane_data(void)
{
    uint16_t buf[WORDS_PER_PLANE];
    for (int p = 0; p < PLANE_COUNT; ++p) {
        for (int s = 0; s < DWORDS_PER_PLANE; ++s) {
            uint32_t dw = plane_dword(p, s);
            // Pack as 2 LE 16-bit words: low word = dw[15:0], high word
            // = dw[31:16]. SDRAM byte order is LE so byte[addr+0]=dw[7:0]
            // and byte[addr+3]=dw[31:24], matching the dout32 wiring
            // verified against fpga/tang20k/third_party/sdram/sdram.v.
            buf[s * 2 + 0] = (uint16_t)( dw        & 0xFFFF);
            buf[s * 2 + 1] = (uint16_t)((dw >> 16) & 0xFFFF);
        }
        uint32_t base = PLANE_BASE_ROOT + (uint32_t)p * PLANE_BASE_STRIDE;
        vdp_sdram_write(base, buf, WORDS_PER_PLANE);
        delay(2);   // small gap between transactions for bridge to drain
    }
}

// ---- Palette load ----------------------------------------------------------
//
// Per VdpTop.scala lines 1478-1502:
//   0x0601 PALETTE_PTR  : sets paletteWritePtr[7:0] (entry × 2 + half)
//   0x0600 PALETTE_DATA : auto-incrementing two-write entry commit
//                          half=0 (even ptr): low 16 bits = G[7:0]:B[7:0]
//                          half=1 (odd  ptr): low 8 bits  = R[7:0]
//                                            commits {R,G,B} into entry
//
// 8 SMPTE bars at slots 0..7 (plane data drives palette index 0..7
// across the 8 bars). Slots 8..31 zeroed.

struct Rgb { uint8_t r, g, b; };

static const Rgb SMPTE[8] = {
    { 0xFF, 0xFF, 0xFF },   // 0 white
    { 0xFF, 0xFF, 0x00 },   // 1 yellow
    { 0x00, 0xFF, 0xFF },   // 2 cyan
    { 0x00, 0xFF, 0x00 },   // 3 green
    { 0xFF, 0x00, 0xFF },   // 4 magenta
    { 0xFF, 0x00, 0x00 },   // 5 red
    { 0x00, 0x00, 0xFF },   // 6 blue
    { 0x00, 0x00, 0x00 },   // 7 black
};

static void load_palette(void)
{
    vdp_reg_write(0x0601, 0x0000);                    // pointer = entry 0, half 0
    for (int i = 0; i < 32; ++i) {
        Rgb c = (i < 8) ? SMPTE[i] : Rgb{0, 0, 0};
        // half=0: low 16 bits = G:B
        uint16_t gb = ((uint16_t)c.g << 8) | (uint16_t)c.b;
        vdp_reg_write(0x0600, gb);
        // half=1: low 8 bits = R; commits entry, ptr auto-incs
        vdp_reg_write(0x0600, (uint16_t)c.r);
    }
}

// ---- Plane base + control register load ------------------------------------

static void load_plane_descriptors(void)
{
    for (int p = 0; p < PLANE_COUNT; ++p) {
        uint32_t base = PLANE_BASE_ROOT + (uint32_t)p * PLANE_BASE_STRIDE;
        vdp_reg_write(0x0D40 + p * 2 + 0, (uint16_t)( base        & 0xFFFF));   // lo
        vdp_reg_write(0x0D40 + p * 2 + 1, (uint16_t)((base >> 16) & 0x007F));   // hi (7 bits)
    }
    // PLANAR_CTRL bit 0 = planarFetchEnable
    vdp_reg_write(0x0D4A, 0x0001);
}

// ---- Setup / loop ----------------------------------------------------------

void setup()
{
    Serial.begin(115200);
    delay(200);
    Serial.println();
    Serial.println(F("[task3-planar5] init"));

    vdp_qspi_init();
    delay(50);

    Serial.println(F("[task3-planar5] uploading plane data"));
    upload_plane_data();

    Serial.println(F("[task3-planar5] loading palette (8 SMPTE bars + zeros)"));
    load_palette();

    Serial.println(F("[task3-planar5] loading plane descriptors + enabling fetch"));
    load_plane_descriptors();

    Serial.println(F("[task3-planar5] done — planar scene active"));
}

void loop()
{
    // Static scene; nothing to update per frame.
    delay(1000);
}
