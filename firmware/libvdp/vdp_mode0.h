/**
 * vdp_mode0.h — Generic Mode0 helper layer.
 *
 * Exposes the landed Mode0 register surface through named constants and
 * small helper functions. This layer is intentionally adapter-agnostic:
 * it covers global Mode0 features only, not ZX/C64/NES/etc shadows.
 */
#ifndef VDP_MODE0_H
#define VDP_MODE0_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Global / status register block */
#define VDP_MODE0_REG_LAYER_ENABLE      0x0300u
#define VDP_MODE0_REG_VDP_CTRL          0x0310u
#define VDP_MODE0_REG_VDP_TILE_MODE     0x0311u
#define VDP_MODE0_REG_VDP_ATTR_MODE     0x0312u
#define VDP_MODE0_REG_MODE_SELECT       0x0313u
#define VDP_MODE0_REG_STATUS_STICKY     0x0320u
#define VDP_MODE0_REG_STATUS_ENABLE     0x0321u
#define VDP_MODE0_REG_SPRITE_COLL_MASK  0x0322u

/* Window / color math / border block */
#define VDP_MODE0_REG_WIN1_X0           0x0330u
#define VDP_MODE0_REG_WIN1_X1           0x0331u
#define VDP_MODE0_REG_WIN1_Y0           0x0332u
#define VDP_MODE0_REG_WIN1_Y1           0x0333u
#define VDP_MODE0_REG_COLOR_MATH_CTRL   0x0334u
#define VDP_MODE0_REG_WIN2_X0           0x0335u
#define VDP_MODE0_REG_WIN2_X1           0x0336u
#define VDP_MODE0_REG_WIN2_Y0           0x0337u
#define VDP_MODE0_REG_WIN2_Y1           0x0338u
#define VDP_MODE0_REG_WIN2_CTRL         0x0339u
#define VDP_MODE0_REG_WIN_COMBINE       0x033Au
#define VDP_MODE0_REG_LAYER_MASK        0x033Bu
#define VDP_MODE0_REG_BORDER_X0         0x033Cu
#define VDP_MODE0_REG_BORDER_X1         0x033Du
#define VDP_MODE0_REG_BORDER_Y0         0x033Eu
#define VDP_MODE0_REG_BORDER_Y1         0x033Fu
#define VDP_MODE0_REG_AFFINE_A          0x0340u
#define VDP_MODE0_REG_AFFINE_B          0x0341u
#define VDP_MODE0_REG_AFFINE_C          0x0342u
#define VDP_MODE0_REG_AFFINE_D          0x0343u
#define VDP_MODE0_REG_AFFINE_X          0x0344u
#define VDP_MODE0_REG_AFFINE_Y          0x0345u
#define VDP_MODE0_REG_AFFINE_CTRL       0x0346u
#define VDP_MODE0_REG_BORDER_CTRL       0x0347u
#define VDP_MODE0_REG_SCALE_CTRL        0x0349u
#define VDP_MODE0_REG_LOGIC_WIDTH       0x034Au
#define VDP_MODE0_REG_LOGIC_HEIGHT      0x034Bu
#define VDP_MODE0_REG_INNER_BORDER_L    0x034Cu
#define VDP_MODE0_REG_INNER_BORDER_R    0x034Du
#define VDP_MODE0_REG_INNER_BORDER_T    0x034Eu
#define VDP_MODE0_REG_INNER_BORDER_B    0x034Fu

/* Bitmap fetch block */
#define VDP_MODE0_REG_BITMAP_CTRL       0x0350u
#define VDP_MODE0_REG_BITMAP_HEIGHT     0x0357u
#define VDP_MODE0_REG_BITMAP_BASE_LO    0x0351u
#define VDP_MODE0_REG_BITMAP_BASE_HI    0x0352u
#define VDP_MODE0_REG_ATTR_BASE_LO      0x0353u
#define VDP_MODE0_REG_ATTR_BASE_HI      0x0354u
#define VDP_MODE0_REG_BITMAP_STRIDE     0x0355u
#define VDP_MODE0_REG_ATTR_STRIDE       0x0356u

/* Raster trigger block: TR1..TR3 are bus-controlled */
#define VDP_MODE0_REG_TRIGGER1_LINE     0x0360u
#define VDP_MODE0_REG_TRIGGER1_PIXEL    0x0361u
#define VDP_MODE0_REG_TRIGGER1_CTRL     0x0362u
#define VDP_MODE0_REG_TRIGGER2_LINE     0x0364u
#define VDP_MODE0_REG_TRIGGER2_PIXEL    0x0365u
#define VDP_MODE0_REG_TRIGGER2_CTRL     0x0366u
#define VDP_MODE0_REG_TRIGGER3_LINE     0x0368u
#define VDP_MODE0_REG_TRIGGER3_PIXEL    0x0369u
#define VDP_MODE0_REG_TRIGGER3_CTRL     0x036Au

/* Sprite block: 32 slots x 8 words (attr) + 32 slots x 1 word (hard) */
#define VDP_MODE0_REG_SPRITE_ATTR_BASE  0x0800u
#define VDP_MODE0_REG_SPRITE_HARD_BASE  0x0D20u

/* HDMA / Copper / palette / tables */
#define VDP_MODE0_REG_HDMA_BASE         0x0380u
#define VDP_MODE0_REG_COPPER_RAM_BASE   0x0400u
#define VDP_MODE0_REG_PALETTE_DATA      0x0600u
#define VDP_MODE0_REG_PALETTE_PTR       0x0601u
#define VDP_MODE0_REG_VSCROLL_BASE      0x0A00u

/* Sprite pattern RAM (Task 53 / Phase 2) */
#define VDP_MODE0_REG_PATTERN_RAM_DATA  0x0D10u
#define VDP_MODE0_REG_PATTERN_RAM_PTR   0x0D11u

/* HDMA sub-register offsets (base = 0x0380) */
#define VDP_MODE0_HDMA_OFFSET_CTRL      0x00u
#define VDP_MODE0_HDMA_OFFSET_DONE_ACK  0x01u
#define VDP_MODE0_HDMA_OFFSET_CH0_ADDR  0x02u
#define VDP_MODE0_HDMA_OFFSET_CH1_ADDR  0x04u
#define VDP_MODE0_HDMA_OFFSET_CH2_ADDR  0x06u
#define VDP_MODE0_HDMA_OFFSET_CH3_ADDR  0x08u
#define VDP_MODE0_HDMA_OFFSET_DATA_PTR  0x50u
#define VDP_MODE0_HDMA_OFFSET_DATA_WR   0x51u

/* DMA / blitter */
#define VDP_MODE0_REG_DMA_DST           0x0B00u
#define VDP_MODE0_REG_DMA_LEN           0x0B01u
#define VDP_MODE0_REG_DMA_FILL          0x0B02u
#define VDP_MODE0_REG_DMA_CTRL          0x0B03u
#define VDP_MODE0_REG_DMA_STAGING_BASE  0x0B10u
#define VDP_MODE0_REG_BLIT_CTRL         0x0C00u
#define VDP_MODE0_REG_BLIT_WIDTH        0x0C01u
#define VDP_MODE0_REG_BLIT_HEIGHT       0x0C02u
#define VDP_MODE0_REG_BLIT_DST_ADDR     0x0C03u
#define VDP_MODE0_REG_BLIT_DST_STRIDE   0x0C04u
#define VDP_MODE0_REG_BLIT_SRC_ADDR     0x0C05u
#define VDP_MODE0_REG_BLIT_SRC_STRIDE   0x0C06u
#define VDP_MODE0_REG_BLIT_FILL_VAL     0x0C07u
#define VDP_MODE0_REG_BLIT_SRC_RAM_BASE 0x0C10u

/* Linestate prepare store */
#define VDP_MODE0_REG_LINESTATE_BASE    0x0000u
#define VDP_MODE0_LINESTATE_COUNT       480u

enum {
    VDP_MODE0_TILE_MODE_PACKED   = 0,
    VDP_MODE0_TILE_MODE_PLANAR   = 1,
    VDP_MODE0_TILE_MODE_SHUFFLED = 2
};

enum {
    VDP_MODE0_ATTR_MODE_LINEAR    = 0,
    VDP_MODE0_ATTR_MODE_PACKED_2X2 = 1
};

enum {
    VDP_MODE0_BITMAP_BPP_1 = 0,
    VDP_MODE0_BITMAP_BPP_2 = 1,
    VDP_MODE0_BITMAP_BPP_4 = 2,
    VDP_MODE0_BITMAP_BPP_8 = 3
};

enum {
    VDP_MODE0_DMA_MODE_FILL = 0,
    VDP_MODE0_DMA_MODE_COPY = 1
};

enum {
    VDP_MODE0_BLIT_MODE_RECT_FILL = 0,
    VDP_MODE0_BLIT_MODE_RECT_COPY = 1,
    VDP_MODE0_BLIT_MODE_LINE_FILL = 2
};

typedef struct {
    uint16_t x0;
    uint16_t x1;
    uint16_t y0;
    uint16_t y1;
} vdp_mode0_rect_t;

typedef struct {
    uint16_t a;
    uint16_t b;
    uint16_t c;
    uint16_t d;
    uint16_t x;
    uint16_t y;
    uint16_t ctrl;
} vdp_mode0_affine_t;

typedef struct {
    uint16_t ctrl;
    uint32_t bitmap_base;
    uint32_t attr_base;
    uint16_t bitmap_stride;
    uint16_t attr_stride;
    uint16_t height;
} vdp_mode0_bitmap_cfg_t;

typedef struct {
    uint16_t line;
    uint16_t pixel;
    uint16_t ctrl;
} vdp_mode0_trigger_t;

typedef struct {
    uint16_t dst;
    uint16_t len_m1;
    uint16_t fill;
    uint8_t mode;
} vdp_mode0_dma_cfg_t;

typedef struct {
    uint16_t ctrl;
    uint16_t width_m1;
    uint16_t height_m1;
    uint16_t dst_addr;
    uint16_t dst_stride;
    uint16_t src_addr;
    uint16_t src_stride;
    uint16_t fill_val;
} vdp_mode0_blit_cfg_t;

typedef struct {
    uint16_t x;
    uint16_t y;
    uint16_t matrix[4]; // a, b, c, d
    uint16_t trans_x;
    uint16_t trans_y;
    uint8_t  pat_idx;   // 6 bits (0..63)
    bool     enabled;
    bool     affine_en;
    uint8_t  size_sel;  // 2 bits (0..3)
    uint8_t  pal_bank;  // 3 bits (0..7)
    uint8_t  prio;      // 2 bits (0..3)
    bool     flip_h;
    bool     flip_v;
    uint8_t  bpp_sel;   // 2 bits (0..2)
    bool     mask;
} vdp_mode0_sprite_cfg_t;

uint16_t vdp_mode0_bitmap_ctrl(bool enable, uint8_t bpp, uint8_t cell_width_log2);
uint16_t vdp_mode0_border_ctrl(bool enable, uint8_t palette_index);
uint16_t vdp_mode0_border_ctrl_inner(bool enable, bool inner_enable, uint8_t palette_index);
uint16_t vdp_mode0_scale_ctrl(uint8_t scale_x, uint8_t scale_y, bool auto_center);
uint16_t vdp_mode0_trigger_ctrl(bool enable, bool pixel_cmp_enable, bool clear_pulse);
uint16_t vdp_mode0_dma_ctrl(bool go, uint8_t mode, bool done_ack);
uint16_t vdp_mode0_blit_ctrl(bool go, uint8_t mode, bool done_ack);

void vdp_mode0_set_layer_enable(uint16_t mask);
void vdp_mode0_set_vdp_ctrl(bool copper_enable);
void vdp_mode0_set_tile_mode(uint8_t mode);
void vdp_mode0_set_attr_mode(uint8_t mode);
void vdp_mode0_set_mode_select(uint16_t mode_select);
void vdp_mode0_set_vdp_ctrl_word(uint16_t ctrl);
uint8_t vdp_mode0_read_live_mode(void);

void vdp_mode0_set_status_enable(uint16_t mask);
void vdp_mode0_clear_status(uint16_t mask);
void vdp_mode0_clear_sprite_coll_mask(uint8_t mask);

bool vdp_mode0_write_linestate(uint16_t line_index, uint16_t word);
bool vdp_mode0_write_vscroll_entry(uint8_t layer, uint8_t entry_index, uint16_t offset);

void vdp_mode0_set_window1(const vdp_mode0_rect_t *rect, uint16_t color_math_ctrl);
void vdp_mode0_set_window2(const vdp_mode0_rect_t *rect, uint16_t win2_ctrl);
void vdp_mode0_set_window_combine(uint16_t combine_ctrl, uint16_t layer_mask);
void vdp_mode0_set_border_window(const vdp_mode0_rect_t *rect, uint16_t border_ctrl);
void vdp_mode0_set_border_ctrl(uint16_t border_ctrl);
void vdp_mode0_set_inner_border(uint16_t left, uint16_t right, uint16_t top, uint16_t bottom);
void vdp_mode0_set_scale_ctrl(uint16_t ctrl);
void vdp_mode0_set_logic_size(uint16_t width, uint16_t height);
void vdp_mode0_set_scale_mode(uint8_t scale_x, uint8_t scale_y, bool auto_center,
                              uint16_t width, uint16_t height);

void vdp_mode0_set_affine(const vdp_mode0_affine_t *cfg);
void vdp_mode0_set_bitmap_cfg(const vdp_mode0_bitmap_cfg_t *cfg);
void vdp_mode0_set_bitmap_ctrl(uint16_t ctrl);

void vdp_mode0_set_bitmap_base(uint32_t base);
void vdp_mode0_set_attr_base(uint32_t base);
void vdp_mode0_set_bitmap_stride(uint16_t stride);
void vdp_mode0_set_attr_stride(uint16_t stride);

bool vdp_mode0_set_raster_trigger(uint8_t trigger_index, const vdp_mode0_trigger_t *cfg);
void vdp_mode0_set_color_math(uint16_t ctrl);

void vdp_mode0_set_sprite(uint8_t slot, const vdp_mode0_sprite_cfg_t *cfg);

/**
 * One-shot sprite upload: pattern RAM + optional palette + descriptor.
 *
 * @param slot           Sprite slot (0..31)
 * @param pattern        4bpp pixel data, one uint16_t per pixel
 * @param pattern_start  Pattern RAM pixel index to start writing
 * @param pattern_pixels Number of pixels to upload
 * @param palette        Array of 0x00RRGGBB palette entries, or NULL
 * @param palette_start  First palette entry index (0..255)
 * @param palette_count  Number of palette entries (0 = skip)
 * @param cfg            Sprite descriptor config; NULL = skip descriptor write
 * @return true on success, false if slot out of range
 */
bool vdp_sprite_upload(uint8_t slot,
                       const uint16_t *pattern, uint16_t pattern_start, uint16_t pattern_pixels,
                       const uint32_t *palette, uint8_t palette_start, uint8_t palette_count,
                       const vdp_mode0_sprite_cfg_t *cfg);

void vdp_mode0_write_copper_word(uint16_t word_index, uint16_t data);
bool vdp_mode0_hdma_write(uint8_t offset, uint16_t data);
void vdp_mode0_set_hdma_base(uint16_t hdma_base);

uint16_t vdp_mode0_hdma_ctrl_encode(bool enable, uint8_t ch_mask, bool indirect);
void vdp_mode0_set_hdma_ctrl(bool enable, uint8_t ch_mask, bool indirect);
void vdp_mode0_hdma_done_ack(void);
bool vdp_mode0_set_hdma_ch_addr(uint8_t ch, uint16_t addr);
void vdp_mode0_set_hdma_data_ptr(uint8_t ptr);
void vdp_mode0_hdma_write_data(uint16_t data);

void vdp_mode0_set_vscroll_base(uint16_t base);

void vdp_mode0_set_pattern_ptr(uint16_t ptr);
void vdp_mode0_write_pattern_data(uint16_t data);

void vdp_mode0_palette_set_ptr(uint8_t ptr);
void vdp_mode0_palette_write_data(uint16_t data);
void vdp_mode0_palette_write_rgb888(uint8_t entry_index, uint8_t r, uint8_t g, uint8_t b);

void vdp_mode0_dma_write_staging(uint8_t slot, uint16_t data);
void vdp_mode0_dma_config(const vdp_mode0_dma_cfg_t *cfg);

void vdp_mode0_blit_write_src(uint16_t word_index, uint16_t data);
void vdp_mode0_blit_config(const vdp_mode0_blit_cfg_t *cfg);

#ifdef __cplusplus
}
#endif

#endif /* VDP_MODE0_H */
