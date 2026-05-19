#include "vdp_mode0.h"

#include "vdp_qspi.h"

static void vdp_mode0_write_block(uint16_t base_addr, const uint16_t *words, uint16_t count)
{
    vdp_reg_write_burst(base_addr, words, count);
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

void vdp_mode0_set_vdp_ctrl_word(uint16_t ctrl)
{
    vdp_reg_write(VDP_MODE0_REG_VDP_CTRL, ctrl);
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
    const uint16_t words[5] = {
        rect->x0, rect->x1, rect->y0, rect->y1, color_math_ctrl
    };
    vdp_mode0_write_block(VDP_MODE0_REG_WIN1_X0, words, 5);
}

void vdp_mode0_set_window2(const vdp_mode0_rect_t *rect, uint16_t win2_ctrl)
{
    if (!rect) return;
    const uint16_t words[5] = {
        rect->x0, rect->x1, rect->y0, rect->y1, win2_ctrl
    };
    vdp_mode0_write_block(VDP_MODE0_REG_WIN2_X0, words, 5);
}

void vdp_mode0_set_window_combine(uint16_t combine_ctrl, uint16_t layer_mask)
{
    const uint16_t words[2] = { combine_ctrl, layer_mask };
    vdp_mode0_write_block(VDP_MODE0_REG_WIN_COMBINE, words, 2);
}

void vdp_mode0_set_border_window(const vdp_mode0_rect_t *rect, uint16_t border_ctrl)
{
    if (!rect) return;
    const uint16_t words[4] = {
        rect->x0, rect->x1, rect->y0, rect->y1
    };
    vdp_mode0_write_block(VDP_MODE0_REG_BORDER_X0, words, 4);
    vdp_reg_write(VDP_MODE0_REG_BORDER_CTRL, border_ctrl);
}

void vdp_mode0_set_border_ctrl(uint16_t border_ctrl)
{
    vdp_reg_write(VDP_MODE0_REG_BORDER_CTRL, border_ctrl);
}

void vdp_mode0_set_affine(const vdp_mode0_affine_t *cfg)
{
    if (!cfg) return;
    const uint16_t words[7] = {
        cfg->a, cfg->b, cfg->c, cfg->d, cfg->x, cfg->y, cfg->ctrl
    };
    vdp_mode0_write_block(VDP_MODE0_REG_AFFINE_A, words, 7);
}

void vdp_mode0_set_bitmap_cfg(const vdp_mode0_bitmap_cfg_t *cfg)
{
    if (!cfg) return;
    const uint16_t words[7] = {
        cfg->ctrl,
        (uint16_t)(cfg->bitmap_base & 0xFFFFu),
        (uint16_t)((cfg->bitmap_base >> 16) & 0xFFFFu),
        (uint16_t)(cfg->attr_base & 0xFFFFu),
        (uint16_t)((cfg->attr_base >> 16) & 0xFFFFu),
        cfg->bitmap_stride,
        cfg->attr_stride
    };
    vdp_mode0_write_block(VDP_MODE0_REG_BITMAP_CTRL, words, 7);
}

void vdp_mode0_set_bitmap_ctrl(uint16_t ctrl)
{
    vdp_reg_write(VDP_MODE0_REG_BITMAP_CTRL, ctrl);
}

bool vdp_mode0_set_raster_trigger(uint8_t trigger_index, const vdp_mode0_trigger_t *cfg)
{
    uint16_t base;
    if (!cfg) return false;
    if (trigger_index < 1u || trigger_index > 3u) return false;
    base = (uint16_t)(VDP_MODE0_REG_TRIGGER1_LINE + ((trigger_index - 1u) * 4u));
    {
        const uint16_t words[3] = { cfg->line, cfg->pixel, cfg->ctrl };
        vdp_mode0_write_block(base, words, 3);
    }
    return true;
}

void vdp_mode0_set_color_math(uint16_t ctrl)
{
    vdp_reg_write(VDP_MODE0_REG_COLOR_MATH_CTRL, ctrl);
}

void vdp_mode0_set_sprite(uint8_t slot, const vdp_mode0_sprite_cfg_t *cfg)
{
    if (!cfg || slot >= 32u) return;

    /* Word 0: {enabled[15], patIdx[3:0]@[14:11], affineEnable[10], y[9:0]} */
    uint16_t w0 = (uint16_t)(cfg->y & 0x03FFu) |
                  (uint16_t)(cfg->affine_en ? 0x0400u : 0u) |
                  (uint16_t)(((uint16_t)cfg->pat_idx & 0x0Fu) << 11) |
                  (uint16_t)(cfg->enabled ? 0x8000u : 0u);

    /* Word 1: {_[15:10], x[9:0]} */
    uint16_t w1 = (uint16_t)(cfg->x & 0x03FFu);

    /* Words 0..7: Attr block */
    {
        uint16_t words[8] = {
            w0, w1, cfg->matrix[0], cfg->matrix[1], cfg->matrix[2], cfg->matrix[3],
            cfg->trans_x, cfg->trans_y
        };
        vdp_mode0_write_block((uint16_t)(VDP_MODE0_REG_SPRITE_ATTR_BASE + (slot * 8u)), words, 8);
    }

    /* Word 8: Hardening extension block
     * {sizeSel[15:14], paletteBank[13:11], priority[10:9], flipH[8], flipV[7],
     *  bppSel[6:5], mask[4], _[3:2], patIdx[5:4]@[1:0]}
     */
    uint16_t w8 = (uint16_t)(((uint16_t)cfg->pat_idx >> 4) & 0x3u) |
                  (uint16_t)(cfg->mask ? 0x0010u : 0u) |
                  (uint16_t)(((uint16_t)cfg->bpp_sel & 0x3u) << 5) |
                  (uint16_t)(cfg->flip_v ? 0x0080u : 0u) |
                  (uint16_t)(cfg->flip_h ? 0x0100u : 0u) |
                  (uint16_t)(((uint16_t)cfg->prio & 0x3u) << 9) |
                  (uint16_t)(((uint16_t)cfg->pal_bank & 0x7u) << 11) |
                  (uint16_t)(((uint16_t)cfg->size_sel & 0x3u) << 14);

    vdp_reg_write((uint16_t)(VDP_MODE0_REG_SPRITE_HARD_BASE + slot), w8);
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

void vdp_mode0_set_hdma_base(uint16_t hdma_base)
{
    vdp_reg_write(VDP_MODE0_REG_HDMA_BASE, hdma_base);
}

uint16_t vdp_mode0_hdma_ctrl_encode(bool enable, uint8_t ch_mask, bool indirect)
{
    return (uint16_t)((enable ? 1u : 0u)
                    | (((uint16_t)ch_mask & 0x0Fu) << 1)
                    | (indirect ? 0x0020u : 0u));
}

void vdp_mode0_set_hdma_ctrl(bool enable, uint8_t ch_mask, bool indirect)
{
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_HDMA_BASE + VDP_MODE0_HDMA_OFFSET_CTRL),
                  vdp_mode0_hdma_ctrl_encode(enable, ch_mask, indirect));
}

void vdp_mode0_hdma_done_ack(void)
{
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_HDMA_BASE + VDP_MODE0_HDMA_OFFSET_DONE_ACK), 0x0001u);
}

bool vdp_mode0_set_hdma_ch_addr(uint8_t ch, uint16_t addr)
{
    uint8_t off;
    switch (ch) {
        case 0: off = VDP_MODE0_HDMA_OFFSET_CH0_ADDR; break;
        case 1: off = VDP_MODE0_HDMA_OFFSET_CH1_ADDR; break;
        case 2: off = VDP_MODE0_HDMA_OFFSET_CH2_ADDR; break;
        case 3: off = VDP_MODE0_HDMA_OFFSET_CH3_ADDR; break;
        default: return false;
    }
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_HDMA_BASE + off), addr & 0x7FFFu);
    return true;
}

void vdp_mode0_set_hdma_data_ptr(uint8_t ptr)
{
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_HDMA_BASE + VDP_MODE0_HDMA_OFFSET_DATA_PTR), ptr);
}

void vdp_mode0_hdma_write_data(uint16_t data)
{
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_HDMA_BASE + VDP_MODE0_HDMA_OFFSET_DATA_WR), data);
}

void vdp_mode0_set_vscroll_base(uint16_t base)
{
    vdp_reg_write(VDP_MODE0_REG_VSCROLL_BASE, base);
}

void vdp_mode0_set_pattern_ptr(uint16_t ptr)
{
    vdp_reg_write(VDP_MODE0_REG_PATTERN_RAM_PTR, ptr);
}

void vdp_mode0_write_pattern_data(uint16_t data)
{
    vdp_reg_write(VDP_MODE0_REG_PATTERN_RAM_DATA, data);
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
    {
        const uint16_t words[4] = {
            cfg->dst,
            cfg->len_m1,
            cfg->fill,
            vdp_mode0_dma_ctrl(true, cfg->mode, false)
        };
        vdp_mode0_write_block(VDP_MODE0_REG_DMA_DST, words, 4);
    }
}

void vdp_mode0_blit_write_src(uint16_t word_index, uint16_t data)
{
    if (word_index >= 512u) return;
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_BLIT_SRC_RAM_BASE + word_index), data);
}

void vdp_mode0_blit_config(const vdp_mode0_blit_cfg_t *cfg)
{
    if (!cfg) return;
    {
        /* Write parameters first (0x0C01..0x0C07) */
        const uint16_t words[7] = {
            cfg->width_m1,
            cfg->height_m1,
            cfg->dst_addr,
            cfg->dst_stride,
            cfg->src_addr,
            cfg->src_stride,
            cfg->fill_val
        };
        vdp_mode0_write_block(VDP_MODE0_REG_BLIT_WIDTH, words, 7);
        /* Trigger GO at 0x0C00 */
        vdp_reg_write(VDP_MODE0_REG_BLIT_CTRL, cfg->ctrl);
    }
}
