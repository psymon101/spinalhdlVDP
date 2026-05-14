#include "vdp_mode0.h"

#include "vdp_qspi.h"

static void vdp_mode0_write_u32_pair(uint16_t lo_addr, uint16_t hi_addr, uint32_t value)
{
    vdp_reg_write(lo_addr, (uint16_t)(value & 0xFFFFu));
    vdp_reg_write(hi_addr, (uint16_t)((value >> 16) & 0xFFFFu));
}

uint16_t vdp_mode0_bitmap_ctrl(bool enable, uint8_t bpp, uint8_t cell_width_log2)
{
    return (uint16_t)((enable ? 1u : 0u) |
                      (((uint16_t)bpp & 0x3u) << 1) |
                      (((uint16_t)cell_width_log2 & 0xFu) << 3));
}

uint16_t vdp_mode0_border_ctrl(bool enable, uint8_t palette_index)
{
    return (uint16_t)((enable ? 1u : 0u) | (((uint16_t)palette_index & 0x1Fu) << 8));
}

uint16_t vdp_mode0_trigger_ctrl(bool enable, bool pixel_cmp_enable, bool clear_pulse)
{
    return (uint16_t)((enable ? 1u : 0u) |
                      (pixel_cmp_enable ? 0x0002u : 0u) |
                      (clear_pulse ? 0x0004u : 0u));
}

uint16_t vdp_mode0_dma_ctrl(bool go, uint8_t mode, bool done_ack)
{
    return (uint16_t)((go ? 1u : 0u) |
                      (((uint16_t)mode & 0x1u) << 1) |
                      (done_ack ? 0x0004u : 0u));
}

uint16_t vdp_mode0_blit_ctrl(bool go, uint8_t mode, bool done_ack)
{
    return (uint16_t)((go ? 1u : 0u) |
                      (((uint16_t)mode & 0x3u) << 1) |
                      (done_ack ? 0x0008u : 0u));
}

void vdp_mode0_set_layer_enable(uint16_t mask)
{
    vdp_reg_write(VDP_MODE0_REG_LAYER_ENABLE, mask);
}

void vdp_mode0_set_vdp_ctrl(bool copper_enable)
{
    vdp_reg_write(VDP_MODE0_REG_VDP_CTRL, copper_enable ? 1u : 0u);
}

void vdp_mode0_set_tile_mode(uint8_t mode)
{
    vdp_reg_write(VDP_MODE0_REG_VDP_TILE_MODE, (uint16_t)(mode & 0x3u));
}

void vdp_mode0_set_attr_mode(uint8_t mode)
{
    vdp_reg_write(VDP_MODE0_REG_VDP_ATTR_MODE, (uint16_t)(mode & 0x1u));
}

void vdp_mode0_set_mode_select(uint16_t mode_select)
{
    vdp_reg_write(VDP_MODE0_REG_MODE_SELECT, mode_select);
}

uint8_t vdp_mode0_read_live_mode(void)
{
    return (uint8_t)(vdp_read_status(7) & 0x0Fu);
}

void vdp_mode0_set_status_enable(uint16_t mask)
{
    vdp_reg_write(VDP_MODE0_REG_STATUS_ENABLE, mask);
}

void vdp_mode0_clear_status(uint16_t mask)
{
    vdp_reg_write(VDP_MODE0_REG_STATUS_STICKY, mask);
}

void vdp_mode0_clear_sprite_coll_mask(uint8_t mask)
{
    vdp_reg_write(VDP_MODE0_REG_SPRITE_COLL_MASK, mask);
}

bool vdp_mode0_write_linestate(uint16_t line_index, uint16_t word)
{
    if (line_index >= VDP_MODE0_LINESTATE_COUNT) return false;
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_LINESTATE_BASE + line_index), word);
    return true;
}

bool vdp_mode0_write_vscroll_entry(uint8_t layer, uint8_t entry_index, uint16_t offset)
{
    if (layer > 1u) return false;
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_VSCROLL_BASE + (entry_index * 2u) + layer),
                  (uint16_t)(offset & 0x03FFu));
    return true;
}

void vdp_mode0_set_window1(const vdp_mode0_rect_t *rect, uint16_t color_math_ctrl)
{
    if (!rect) return;
    vdp_reg_write(VDP_MODE0_REG_WIN1_X0, rect->x0);
    vdp_reg_write(VDP_MODE0_REG_WIN1_X1, rect->x1);
    vdp_reg_write(VDP_MODE0_REG_WIN1_Y0, rect->y0);
    vdp_reg_write(VDP_MODE0_REG_WIN1_Y1, rect->y1);
    vdp_reg_write(VDP_MODE0_REG_COLOR_MATH_CTRL, color_math_ctrl);
}

void vdp_mode0_set_window2(const vdp_mode0_rect_t *rect, uint16_t win2_ctrl)
{
    if (!rect) return;
    vdp_reg_write(VDP_MODE0_REG_WIN2_X0, rect->x0);
    vdp_reg_write(VDP_MODE0_REG_WIN2_X1, rect->x1);
    vdp_reg_write(VDP_MODE0_REG_WIN2_Y0, rect->y0);
    vdp_reg_write(VDP_MODE0_REG_WIN2_Y1, rect->y1);
    vdp_reg_write(VDP_MODE0_REG_WIN2_CTRL, win2_ctrl);
}

void vdp_mode0_set_window_combine(uint16_t combine_ctrl, uint16_t layer_mask)
{
    vdp_reg_write(VDP_MODE0_REG_WIN_COMBINE, combine_ctrl);
    vdp_reg_write(VDP_MODE0_REG_LAYER_MASK, layer_mask);
}

void vdp_mode0_set_border_window(const vdp_mode0_rect_t *rect, uint16_t border_ctrl)
{
    if (!rect) return;
    vdp_reg_write(VDP_MODE0_REG_BORDER_X0, rect->x0);
    vdp_reg_write(VDP_MODE0_REG_BORDER_X1, rect->x1);
    vdp_reg_write(VDP_MODE0_REG_BORDER_Y0, rect->y0);
    vdp_reg_write(VDP_MODE0_REG_BORDER_Y1, rect->y1);
    vdp_reg_write(VDP_MODE0_REG_BORDER_CTRL, border_ctrl);
}

void vdp_mode0_set_affine(const vdp_mode0_affine_t *cfg)
{
    if (!cfg) return;
    vdp_reg_write(VDP_MODE0_REG_AFFINE_A, cfg->a);
    vdp_reg_write(VDP_MODE0_REG_AFFINE_B, cfg->b);
    vdp_reg_write(VDP_MODE0_REG_AFFINE_C, cfg->c);
    vdp_reg_write(VDP_MODE0_REG_AFFINE_D, cfg->d);
    vdp_reg_write(VDP_MODE0_REG_AFFINE_X, cfg->x);
    vdp_reg_write(VDP_MODE0_REG_AFFINE_Y, cfg->y);
    vdp_reg_write(VDP_MODE0_REG_AFFINE_CTRL, cfg->ctrl);
}

void vdp_mode0_set_bitmap_cfg(const vdp_mode0_bitmap_cfg_t *cfg)
{
    if (!cfg) return;
    vdp_reg_write(VDP_MODE0_REG_BITMAP_CTRL, cfg->ctrl);
    vdp_mode0_write_u32_pair(VDP_MODE0_REG_BITMAP_BASE_LO, VDP_MODE0_REG_BITMAP_BASE_HI,
                             cfg->bitmap_base);
    vdp_mode0_write_u32_pair(VDP_MODE0_REG_ATTR_BASE_LO, VDP_MODE0_REG_ATTR_BASE_HI,
                             cfg->attr_base);
    vdp_reg_write(VDP_MODE0_REG_BITMAP_STRIDE, cfg->bitmap_stride);
    vdp_reg_write(VDP_MODE0_REG_ATTR_STRIDE, cfg->attr_stride);
}

bool vdp_mode0_set_raster_trigger(uint8_t trigger_index, const vdp_mode0_trigger_t *cfg)
{
    uint16_t base;
    if (!cfg) return false;
    if (trigger_index < 1u || trigger_index > 3u) return false;
    base = (uint16_t)(VDP_MODE0_REG_TRIGGER1_LINE + ((trigger_index - 1u) * 4u));
    vdp_reg_write(base + 0u, cfg->line);
    vdp_reg_write(base + 1u, cfg->pixel);
    vdp_reg_write(base + 2u, cfg->ctrl);
    return true;
}

void vdp_mode0_write_copper_word(uint16_t word_index, uint16_t data)
{
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_COPPER_RAM_BASE + word_index), data);
}

bool vdp_mode0_hdma_write(uint8_t offset, uint16_t data)
{
    if (offset > 0x49u && offset != 0x50u && offset != 0x51u) return false;
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_HDMA_BASE + offset), data);
    return true;
}

void vdp_mode0_palette_set_ptr(uint8_t ptr)
{
    vdp_reg_write(VDP_MODE0_REG_PALETTE_PTR, ptr);
}

void vdp_mode0_palette_write_data(uint16_t data)
{
    vdp_reg_write(VDP_MODE0_REG_PALETTE_DATA, data);
}

void vdp_mode0_palette_write_rgb888(uint8_t entry_index, uint8_t r, uint8_t g, uint8_t b)
{
    vdp_mode0_palette_set_ptr((uint8_t)(entry_index * 2u));
    vdp_mode0_palette_write_data((uint16_t)(((uint16_t)g << 8) | b));
    vdp_mode0_palette_write_data(r);
}

void vdp_mode0_dma_write_staging(uint8_t slot, uint16_t data)
{
    if (slot >= 64u) return;
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_DMA_STAGING_BASE + slot), data);
}

void vdp_mode0_dma_config(const vdp_mode0_dma_cfg_t *cfg)
{
    if (!cfg) return;
    vdp_reg_write(VDP_MODE0_REG_DMA_DST, cfg->dst);
    vdp_reg_write(VDP_MODE0_REG_DMA_LEN, cfg->len_m1);
    vdp_reg_write(VDP_MODE0_REG_DMA_FILL, cfg->fill);
    vdp_reg_write(VDP_MODE0_REG_DMA_CTRL,
                  vdp_mode0_dma_ctrl(true, cfg->mode, false));
}

void vdp_mode0_blit_write_src(uint16_t word_index, uint16_t data)
{
    if (word_index >= 512u) return;
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_BLIT_SRC_RAM_BASE + word_index), data);
}

void vdp_mode0_blit_config(const vdp_mode0_blit_cfg_t *cfg)
{
    if (!cfg) return;
    vdp_reg_write(VDP_MODE0_REG_BLIT_WIDTH, cfg->width_m1);
    vdp_reg_write(VDP_MODE0_REG_BLIT_HEIGHT, cfg->height_m1);
    vdp_reg_write(VDP_MODE0_REG_BLIT_DST_ADDR, cfg->dst_addr);
    vdp_reg_write(VDP_MODE0_REG_BLIT_DST_STRIDE, cfg->dst_stride);
    vdp_reg_write(VDP_MODE0_REG_BLIT_SRC_ADDR, cfg->src_addr);
    vdp_reg_write(VDP_MODE0_REG_BLIT_SRC_STRIDE, cfg->src_stride);
    vdp_reg_write(VDP_MODE0_REG_BLIT_FILL_VAL, cfg->fill_val);
    vdp_reg_write(VDP_MODE0_REG_BLIT_CTRL, cfg->ctrl);
}
