# spinalhdlVDP — libvdp Host Library Bundle

- Generated: 2026-07-25
- Source: `firmware/libvdp/`
- File count: 21
- Note: All libvdp source/header/config files concatenated for external review.

---

## FILE 1 / 21: `CMakeLists.txt`

```txt
# libvdp — host driver library for the spinalhdlVDP host-control
# loop (Task 39). CMake target `libvdp` can be linked by any Pico 2
# firmware executable via `target_link_libraries(<exe> PRIVATE libvdp)`.
#
# Must be added by the including CMakeLists with `add_subdirectory()`
# AFTER `pico_sdk_init()` so the Pico SDK targets are already present.

add_library(libvdp STATIC
    vdp_host.c
    vdp_status.c
    vdp_upload.c
)

pico_generate_pio_header(libvdp
    ${CMAKE_CURRENT_SOURCE_DIR}/qspi_quad.pio
)

target_include_directories(libvdp PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Make the Pico SDK platform branch explicit for the library sources.
target_compile_definitions(libvdp PUBLIC
    PICO
)

target_link_libraries(libvdp PUBLIC
    pico_stdlib
    hardware_pio
    hardware_gpio
    hardware_clocks
)

# Treat warnings from the library itself as warnings (will be promoted
# to errors by whatever enclosing project chooses -Werror).
target_compile_options(libvdp PRIVATE -Wall -Wextra)

```

---

## FILE 2 / 21: `library.properties`

```properties
name=libvdp
version=1.0.0
author=SignalWire
maintainer=SignalWire
sentence=Shared host driver library for VDP Mode0.
paragraph=Encapsulates host transport, register writes, and SDRAM uploads.
category=Display
url=https://github.com/spinalhdlVDP
architectures=*

```

---

## FILE 3 / 21: `mode0_regs.json`

```json
{
  "registers": [
    {
      "name": "LAYER_ENABLE",
      "addr": "0x0300",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "L0",
          "lsb": 0,
          "width": 1,
          "description": "Enables layer 0 output."
        },
        {
          "name": "L1",
          "lsb": 1,
          "width": 1,
          "description": "Enables layer 1 output."
        },
        {
          "name": "SPRITE",
          "lsb": 2,
          "width": 1,
          "description": "Enables sprite output."
        },
        {
          "name": "L2",
          "lsb": 3,
          "width": 1,
          "description": "Enables layer 2 output."
        },
        {
          "name": "L3",
          "lsb": 4,
          "width": 1,
          "description": "Enables layer 3 output."
        }
      ],
      "description": "Enables visible Mode0 display layers."
    },
    {
      "name": "VDP_CTRL",
      "addr": "0x0310",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "COPPER_ENABLE",
          "lsb": 0,
          "width": 1,
          "description": "Enables copper command execution."
        },
        {
          "name": "COPPER_SWAP_REQUEST",
          "lsb": 1,
          "width": 1,
          "description": "Requests a copper buffer swap."
        },
        {
          "name": "SOFT_RESET_REQUEST",
          "lsb": 2,
          "width": 1,
          "description": "Triggers a host-requested soft reset."
        }
      ],
      "description": "Controls global Mode0 runtime features."
    },
    {
      "name": "VDP_TILE_MODE",
      "addr": "0x0311",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "MODE",
          "lsb": 0,
          "width": 2,
          "description": "Tile pattern decode mode selector."
        }
      ],
      "description": "Selects the tile pattern decode mode."
    },
    {
      "name": "VDP_ATTR_MODE",
      "addr": "0x0312",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "MODE",
          "lsb": 0,
          "width": 1,
          "description": "Tile attribute decode mode selector."
        }
      ],
      "description": "Selects the tile attribute decode mode."
    },
    {
      "name": "MODE_SELECT",
      "addr": "0x0313",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "ADAPTER_MODE",
          "lsb": 0,
          "width": 4,
          "description": "Compatibility adapter mode selector."
        },
        {
          "name": "MODE_FLAGS",
          "lsb": 8,
          "width": 8,
          "description": "Adapter-specific mode option flags."
        }
      ],
      "description": "Selects the active compatibility adapter mode."
    },
    {
      "name": "L0_TRANS_KEY",
      "addr": "0x0314",
      "width": 4,
      "access": "RW",
      "reset": "0x0000",
      "category": "H-boundary",
      "description": "4-bit transparency palette index for layer 0."
    },
    {
      "name": "L1_TRANS_KEY",
      "addr": "0x0315",
      "width": 4,
      "access": "RW",
      "reset": "0x0000",
      "category": "H-boundary",
      "description": "4-bit transparency palette index for layer 1."
    },
    {
      "name": "L2_TRANS_KEY",
      "addr": "0x0316",
      "width": 4,
      "access": "RW",
      "reset": "0x0000",
      "category": "H-boundary",
      "description": "4-bit transparency palette index for layer 2."
    },
    {
      "name": "L3_TRANS_KEY",
      "addr": "0x0317",
      "width": 4,
      "access": "RW",
      "reset": "0x0000",
      "category": "H-boundary",
      "description": "4-bit transparency palette index for layer 3."
    },
    {
      "name": "STATUS_STICKY",
      "addr": "0x0320",
      "width": 16,
      "access": "W1C",
      "reset": "0x0000",
      "category": "diagnostic",
      "fields": [
        {
          "name": "RASTER_MATCH",
          "lsb": 0,
          "width": 1,
          "description": "Raster trigger match occurred."
        },
        {
          "name": "SPRITE_OVERFLOW",
          "lsb": 1,
          "width": 1,
          "description": "Sprite evaluation overflow occurred."
        },
        {
          "name": "HOST_READY",
          "lsb": 2,
          "width": 1,
          "description": "Host bridge reported ready; legacy alias QSPI_READY."
        },
        {
          "name": "HOST_ERROR",
          "lsb": 3,
          "width": 1,
          "description": "Host bridge reported an error; legacy alias QSPI_ERROR."
        },
        {
          "name": "SPRITE_0_HIT",
          "lsb": 4,
          "width": 1,
          "description": "Sprite 0 collision flag latched."
        },
        {
          "name": "SPRITE_BG_HIT",
          "lsb": 5,
          "width": 1,
          "description": "Sprite/background collision flag latched."
        },
        {
          "name": "DMA_DONE",
          "lsb": 8,
          "width": 1,
          "description": "DMA operation completed."
        },
        {
          "name": "BLIT_DONE",
          "lsb": 9,
          "width": 1,
          "description": "Blitter operation completed."
        },
        {
          "name": "BLIT_BUSY",
          "lsb": 10,
          "width": 1,
          "description": "Blitter busy state is latched."
        },
        {
          "name": "MODE_SELECT_CHANGED",
          "lsb": 11,
          "width": 1,
          "description": "Mode select value changed."
        }
      ],
      "description": "Sticky status and interrupt cause flags; write one to clear."
    },
    {
      "name": "STATUS_ENABLE",
      "addr": "0x0321",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "diagnostic",
      "description": "Enables reporting for selected sticky status sources."
    },
    {
      "name": "SPRITE_COLL_MASK",
      "addr": "0x0322",
      "width": 16,
      "access": "W1C",
      "reset": "0x0000",
      "category": "diagnostic",
      "description": "Clears selected sprite collision sticky bits."
    },
    {
      "name": "UPLOAD_STATUS_CLEAR",
      "addr": "0x0323",
      "width": 16,
      "access": "W1C",
      "reset": "0x0000",
      "category": "diagnostic",
      "fields": [
        {
          "name": "UPLOAD_ERROR",
          "lsb": 2,
          "width": 1,
          "description": "Clears upload error sticky flag."
        },
        {
          "name": "UPLOAD_OVERFLOW",
          "lsb": 3,
          "width": 1,
          "description": "Clears upload overflow sticky flag."
        },
        {
          "name": "TXN_DROPPED",
          "lsb": 4,
          "width": 1,
          "description": "Clears dropped transaction sticky flag."
        },
        {
          "name": "SHORT_FRAME",
          "lsb": 5,
          "width": 1,
          "description": "Clears short-frame sticky flag."
        }
      ],
      "description": "Clears sticky host upload bridge error flags. NOTE: the current main bitstream allocates this address and the firmware helper issues the write, but the RTL clear decode is not yet implemented; writes to 0x0323 have no effect until the RTL decode lands (FULL-DOC-AUDIT-151)."
    },
    {
      "name": "WIN1_X0",
      "addr": "0x0330",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 1 inclusive left X coordinate."
    },
    {
      "name": "WIN1_X1",
      "addr": "0x0331",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 1 exclusive right X coordinate."
    },
    {
      "name": "WIN1_Y0",
      "addr": "0x0332",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 1 inclusive top Y coordinate."
    },
    {
      "name": "WIN1_Y1",
      "addr": "0x0333",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 1 exclusive bottom Y coordinate."
    },
    {
      "name": "COLOR_MATH_CTRL",
      "addr": "0x0334",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Controls windowed color math and blend behavior."
    },
    {
      "name": "WIN2_X0",
      "addr": "0x0335",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 2 inclusive left X coordinate."
    },
    {
      "name": "WIN2_X1",
      "addr": "0x0336",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 2 exclusive right X coordinate."
    },
    {
      "name": "WIN2_Y0",
      "addr": "0x0337",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 2 inclusive top Y coordinate."
    },
    {
      "name": "WIN2_Y1",
      "addr": "0x0338",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Window 2 exclusive bottom Y coordinate."
    },
    {
      "name": "WIN2_CTRL",
      "addr": "0x0339",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Controls Window 2 enable and selection behavior."
    },
    {
      "name": "WIN_COMBINE",
      "addr": "0x033A",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Selects how Window 1 and Window 2 masks combine."
    },
    {
      "name": "LAYER_MASK",
      "addr": "0x033B",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Selects which layers participate in window/color operations."
    },
    {
      "name": "BORDER_X0",
      "addr": "0x033C",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Outer border inclusive left X coordinate."
    },
    {
      "name": "BORDER_X1",
      "addr": "0x033D",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Outer border exclusive right X coordinate."
    },
    {
      "name": "BORDER_Y0",
      "addr": "0x033E",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Outer border inclusive top Y coordinate."
    },
    {
      "name": "BORDER_Y1",
      "addr": "0x033F",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Outer border exclusive bottom Y coordinate."
    },
    {
      "name": "AFFINE_A",
      "addr": "0x0340",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine matrix A coefficient for transformed fetches."
    },
    {
      "name": "AFFINE_B",
      "addr": "0x0341",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine matrix B coefficient for transformed fetches."
    },
    {
      "name": "AFFINE_C",
      "addr": "0x0342",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine matrix C coefficient for transformed fetches."
    },
    {
      "name": "AFFINE_D",
      "addr": "0x0343",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine matrix D coefficient for transformed fetches."
    },
    {
      "name": "AFFINE_X",
      "addr": "0x0344",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine transform X origin or translation term."
    },
    {
      "name": "AFFINE_Y",
      "addr": "0x0345",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Affine transform Y origin or translation term."
    },
    {
      "name": "AFFINE_CTRL",
      "addr": "0x0346",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "description": "Controls affine transform enable and options."
    },
    {
      "name": "BORDER_CTRL",
      "addr": "0x0347",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "ENABLE",
          "lsb": 0,
          "width": 1,
          "description": "Enables outer border rendering."
        },
        {
          "name": "INNER_BORDER_ENABLE",
          "lsb": 1,
          "width": 1,
          "description": "Enables inner border inset handling."
        },
        {
          "name": "PALETTE_INDEX",
          "lsb": 8,
          "width": 5,
          "description": "Palette index used for border pixels."
        }
      ],
      "description": "Enables border rendering and selects its palette index."
    },
    {
      "name": "BACKDROP_INDEX",
      "addr": "0x0348",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "INDEX",
          "lsb": 0,
          "width": 7,
          "description": "Palette index used for backdrop pixels."
        }
      ],
      "description": "Selects the backdrop palette index."
    },
    {
      "name": "SCALE_CTRL",
      "addr": "0x0349",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "SCALE_X",
          "lsb": 0,
          "width": 3,
          "description": "Horizontal integer scale factor selector."
        },
        {
          "name": "SCALE_Y",
          "lsb": 4,
          "width": 3,
          "description": "Vertical integer scale factor selector."
        },
        {
          "name": "AUTO_CENTER",
          "lsb": 7,
          "width": 1,
          "description": "Centers the logical image in the output frame."
        }
      ],
      "description": "Controls logical-to-output pixel scaling."
    },
    {
      "name": "LOGIC_WIDTH",
      "addr": "0x034A",
      "width": 16,
      "access": "RW",
      "reset": "0x0280",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "WIDTH",
          "lsb": 0,
          "width": 11,
          "description": "Logical source width in pixels."
        }
      ],
      "description": "Logical source width used by the scaler."
    },
    {
      "name": "LOGIC_HEIGHT",
      "addr": "0x034B",
      "width": 16,
      "access": "RW",
      "reset": "0x01E0",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "HEIGHT",
          "lsb": 0,
          "width": 11,
          "description": "Logical source height in pixels."
        }
      ],
      "description": "Logical source height used by the scaler."
    },
    {
      "name": "INNER_BORDER_L",
      "addr": "0x034C",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "THICKNESS",
          "lsb": 0,
          "width": 10,
          "description": "Left inner border thickness in logical pixels."
        }
      ],
      "description": "Inner border thickness on the left edge."
    },
    {
      "name": "INNER_BORDER_R",
      "addr": "0x034D",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "THICKNESS",
          "lsb": 0,
          "width": 10,
          "description": "Right inner border thickness in logical pixels."
        }
      ],
      "description": "Inner border thickness on the right edge."
    },
    {
      "name": "INNER_BORDER_T",
      "addr": "0x034E",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "THICKNESS",
          "lsb": 0,
          "width": 10,
          "description": "Top inner border thickness in logical pixels."
        }
      ],
      "description": "Inner border thickness on the top edge."
    },
    {
      "name": "INNER_BORDER_B",
      "addr": "0x034F",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "THICKNESS",
          "lsb": 0,
          "width": 10,
          "description": "Bottom inner border thickness in logical pixels."
        }
      ],
      "description": "Inner border thickness on the bottom edge."
    },
    {
      "name": "BITMAP_CTRL",
      "addr": "0x0350",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "ENABLE",
          "lsb": 0,
          "width": 1,
          "description": "Enables SDRAM bitmap fetch."
        },
        {
          "name": "BPP",
          "lsb": 1,
          "width": 2,
          "description": "Bitmap bits-per-pixel mode selector; 0b10 selects RGB565 direct color."
        },
        {
          "name": "CELL_WIDTH_LOG2",
          "lsb": 3,
          "width": 4,
          "description": "Log2 cell width for indexed bitmap addressing."
        }
      ],
      "description": "Enables SDRAM bitmap fetch and selects bitmap format."
    },
    {
      "name": "BITMAP_BASE_LO",
      "addr": "0x0351",
      "width": 16,
      "access": "RW",
      "reset": "0x3000",
      "category": "vblank-sensitive",
      "description": "Low 16 bits of the SDRAM bitmap byte-plane base address. In RGB565 direct-color mode (BITMAP_CTRL mode 0b10) the effective base is forced 32-byte aligned by the hardware; writes to bits [4:0] are ignored in that mode."
    },
    {
      "name": "BITMAP_BASE_HI",
      "addr": "0x0352",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "ADDR_HI",
          "lsb": 0,
          "width": 7,
          "description": "Address bits 22:16 for bitmap base."
        }
      ],
      "description": "High 7 bits of the SDRAM bitmap byte-plane base address. Combined with BITMAP_BASE_LO to form a 23-bit byte address; the low 5 bits are masked to zero in RGB565 direct-color burst mode."
    },
    {
      "name": "ATTR_BASE_LO",
      "addr": "0x0353",
      "width": 16,
      "access": "RW",
      "reset": "0x4000",
      "category": "vblank-sensitive",
      "description": "Low 16 bits of the SDRAM attribute or high-byte plane base address. In RGB565 direct-color mode the effective base is forced 32-byte aligned by the hardware; writes to bits [4:0] are ignored in that mode."
    },
    {
      "name": "ATTR_BASE_HI",
      "addr": "0x0354",
      "width": 16,
      "access": "RW",
      "reset": "0x0000",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "ADDR_HI",
          "lsb": 0,
          "width": 7,
          "description": "Address bits 22:16 for attribute or high-byte plane base."
        }
      ],
      "description": "High 7 bits of the SDRAM attribute or high-byte plane base address. Combined with ATTR_BASE_LO to form a 23-bit byte address; the low 5 bits are masked to zero in RGB565 direct-color burst mode."
    },
    {
      "name": "BITMAP_STRIDE",
      "addr": "0x0355",
      "width": 16,
      "access": "RW",
      "reset": "0x0200",
      "category": "vblank-sensitive",
      "description": "Direct-color bitmap byte-plane row stride in bytes. In RGB565 direct-color mode the hardware masks bits [4:0] to zero, so the stride must be a multiple of 32 bytes. The default 0x0200 (512) is 32-byte aligned."
    },
    {
      "name": "ATTR_STRIDE",
      "addr": "0x0356",
      "width": 16,
      "access": "RW",
      "reset": "0x0200",
      "category": "vblank-sensitive",
      "description": "Direct-color attribute or high-byte plane row stride in bytes. In RGB565 direct-color mode the hardware masks bits [4:0] to zero, so the stride must be a multiple of 32 bytes. The default 0x0200 (512) is 32-byte aligned."
    },
    {
      "name": "BITMAP_HEIGHT",
      "addr": "0x0357",
      "width": 16,
      "access": "RW",
      "reset": "0x00F0",
      "category": "vblank-sensitive",
      "fields": [
        {
          "name": "HEIGHT",
          "lsb": 0,
          "width": 11,
          "description": "Source bitmap height in rows."
        }
      ],
      "description": "Source bitmap height in rows; currently consumed by init-fill path only."
    },
    {
      "name": "PLANAR_WIDTH",
      "addr": "0x0D4B",
      "width": 10,
      "access": "RW",
      "reset": "0x0140",
      "category": "vblank-sensitive",
      "description": "10-bit planar clip width in pixels (default 320)."
    }
  ]
}

```

---

## FILE 4 / 21: `qspi_quad.pio`

```pio
;
; qspi_quad.pio — PIO QSPI quad-mode transport for VDP Mode0
;
; Generates SCK via side-set and transfers 4 bits per SCK edge.
;
; Pin mapping (PIO contiguous):
;   GP8  = SCK (side-set pin)
;   GP9  = CS_N (software-controlled GPIO, not PIO)
;   GP10 = IO0 (OUT/IN base)
;   GP11 = IO1
;   GP12 = IO2
;   GP13 = IO3
;
; Protocol: always quad — 1 nibble per SCK edge, 2 edges per byte.
; High nibble first, then low nibble (matching FPGA slave).
;
; TX program:
;   Pulls 32-bit words from FIFO. Each word contains 4 bytes packed
;   MSB-first: bits[31:28]=byte0_hi, bits[27:24]=byte0_lo,
;   bits[23:20]=byte1_hi, etc.  Sends 8 nibbles (4 bytes) per word.
;
;   autopull threshold = 32, out shift = left (MSB first).
;
; RX program:
;   Clocks SCK and samples IO[3:0] on each rising edge.
;   Pushes 32-bit words to RX FIFO (8 nibbles = 4 bytes per push).
;   autopush threshold = 32, in shift = left (MSB first).

; ================================================================
; TX program: Pico drives IO[3:0], clocks SCK via side-set
; ================================================================
; 4 instructions × 2 (SCK low + SCK high) = 8 nibbles = 4 bytes/word
; Loop is unrolled for 8 nibbles to avoid an explicit counter.
; Each pair: OUT 4 bits with SCK low, then NOP with SCK high.
; Total: 5 cycles per nibble at clkdiv=1 → 10 cycles per byte.
; At 125 MHz sys_clk with clkdiv=5: SCK = 125/(5*2) = 12.5 MHz.

.program qspi_quad_tx
.side_set 1

.wrap_target
    out pins, 4   side 0 [1]  ; nibble 0 (byte 0 high), SCK low, data setup
    nop           side 1 [1]  ; SCK high → FPGA samples
    out pins, 4   side 0 [1]  ; nibble 1 (byte 0 low)
    nop           side 1 [1]  ; SCK high
    out pins, 4   side 0 [1]  ; nibble 2 (byte 1 high)
    nop           side 1 [1]  ; SCK high
    out pins, 4   side 0 [1]  ; nibble 3 (byte 1 low)
    nop           side 1 [1]  ; SCK high
    out pins, 4   side 0 [1]  ; nibble 4 (byte 2 high)
    nop           side 1 [1]  ; SCK high
    out pins, 4   side 0 [1]  ; nibble 5 (byte 2 low)
    nop           side 1 [1]  ; SCK high
    out pins, 4   side 0 [1]  ; nibble 6 (byte 3 high)
    nop           side 1 [1]  ; SCK high
    out pins, 4   side 0 [1]  ; nibble 7 (byte 3 low)
    nop           side 1 [1]  ; SCK high
.wrap

; ================================================================
; RX program: Pico samples IO[3:0], clocks SCK via side-set
; ================================================================
; Used for READ_STATUS response (FPGA drives IO[3:0]).
; 8 nibbles per 32-bit push = 4 bytes.

.program qspi_quad_rx
.side_set 1

.wrap_target
    nop           side 0 [1]  ; SCK low (FPGA drives data)
    in pins, 4    side 1 [1]  ; SCK high → sample IO[3:0]
    nop           side 0 [1]
    in pins, 4    side 1 [1]
    nop           side 0 [1]
    in pins, 4    side 1 [1]
    nop           side 0 [1]
    in pins, 4    side 1 [1]
    nop           side 0 [1]
    in pins, 4    side 1 [1]
    nop           side 0 [1]
    in pins, 4    side 1 [1]
    nop           side 0 [1]
    in pins, 4    side 1 [1]
    nop           side 0 [1]
    in pins, 4    side 1 [1]
.wrap

; Turnaround (2 dummy SCK edges for direction switch) is handled in C
; by bit-banging GPIO while both PIO SMs are stopped.

; No c-sdk helper section needed — qspi_bus.c configures SMs directly
; using the program structs and default_config functions generated by
; the pioasm tool (qspi_quad_tx_program, qspi_quad_rx_program, etc.).

```

---

## FILE 5 / 21: `vdp_copper.c`

```c
/**
 * vdp_copper.c — Copper program upload and control.
 */
#include "vdp_copper.h"
#include "vdp_host.h"

#if defined(PICO) || defined(ARDUINO_ARCH_RP2040)
#include "pico/stdlib.h"
#elif defined(ARDUINO)
#include <Arduino.h>
#endif

#define COPPER_RAM_BASE 0x0400u

static inline void vdp_copper_delay_us(uint32_t us)
{
#if defined(PICO) || defined(ARDUINO_ARCH_RP2040)
    sleep_us(us);
#else
    delayMicroseconds(us);
#endif
}

void vdp_copper_upload(const uint16_t *prog, uint16_t nwords)
{
    if (!prog || nwords == 0 || nwords > 1024u) return;

    /* Program RAM is only writable while copper is disabled.
     * Issue the disable first.
     */
    vdp_reg_write(0x0310u, 0x0000u);

    /* copperCtrlReg commits at hCounter==0 (once per scanline, ~tens of us).
     * Wait briefly to ensure the disable has latched.
     */
    vdp_copper_delay_us(2000);

    /* Direct upload: HostInterface is absent in the current top, so the QSPI
     * transport writes directly to the Copper Program RAM without buffering.
     * Chunk only because the host-side vdp_reg_write_burst caps at 253 words
     * (frame buffer is 512 bytes = 6 header + 506 payload). The FPGA's
     * QspiDecoder auto-increments writeAddr (see writeAddr := writeAddr + 1
     * in QspiDecoder.scala), so consecutive bursts to base + offset are
     * equivalent to one logical upload. No inter-chunk delay needed.
     */
    const uint16_t CHUNK = 253u;
    uint16_t offset = 0;
    while (offset < nwords) {
        const uint16_t remaining = (uint16_t)(nwords - offset);
        const uint16_t chunk = (remaining > CHUNK) ? CHUNK : remaining;
        vdp_reg_write_burst((uint32_t)(COPPER_RAM_BASE + offset),
                            prog + offset, chunk);
        offset = (uint16_t)(offset + chunk);
    }
}

void vdp_copper_enable(bool en)
{
    /* Read-modify-write VDP_CTRL @ 0x0310 bit[0] */
    /* For a demo sketch we know the initial state; just write directly. */
    vdp_reg_write(0x0310u, en ? 0x0001u : 0x0000u);
}

void vdp_copper_swap_request(void)
{
    /* VDP_CTRL @ 0x0310: bit[0]=COPPER_ENABLE, bit[1]=COPPER_SWAP_REQUEST.
     * Writing 0x0003 keeps copper enabled and requests the swap.
     * HW commits at next vSyncStart and auto-clears bit[1].
     *
     * Sequencing rule: always upload the next frame's program to the
     * inactive bank (burst to 0x0400 while copper is enabled) BEFORE
     * calling this function. Requesting a swap without first uploading
     * promotes uninitialized bank content — see CopperSim case 11.
     */
    vdp_reg_write(0x0310u, 0x0003u);
}

void vdp_copper_upload_and_swap(const uint16_t *prog, uint16_t nwords)
{
    if (!prog || nwords == 0 || nwords > 1024u) return;

    /* Precondition: copper must be enabled so burst writes to 0x0400
     * route to the inactive bank rather than corrupting the active one.
     * Chunk in 253-word pieces; host-side burst cap (see vdp_copper_upload). */
    const uint16_t CHUNK = 253u;
    uint16_t offset = 0;
    while (offset < nwords) {
        const uint16_t remaining = (uint16_t)(nwords - offset);
        const uint16_t chunk = (remaining > CHUNK) ? CHUNK : remaining;
        vdp_reg_write_burst((uint32_t)(COPPER_RAM_BASE + offset),
                            prog + offset, chunk);
        offset = (uint16_t)(offset + chunk);
    }
    vdp_copper_swap_request();
}

```

---

## FILE 6 / 21: `vdp_copper.h`

```h
/**
 * vdp_copper.h — Minimal Copper program helpers for libvdp.
 *
 * Encodes Copper opcodes per Copper.scala BH-1 contract.
 * All opcodes are little-endian 16-bit words.
 */
#ifndef VDP_COPPER_H
#define VDP_COPPER_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Encode a legacy WAIT(Y) opcode (1 word).
 * Stalls copper until vCounter == Y AND hCounter == 0 (single-cycle match
 * window per frame, per Copper.scala sWaitStall). If the FSM misses the
 * match cycle, the WAIT waits a full frame.
 */
static inline uint16_t vdp_copper_wait(uint16_t y)
{
    return (uint16_t)(y & 0x3FFu);
}

/**
 * Encode a pixel-precise WAIT(X,Y) opcode header (2 words).
 * Returns the first word; caller must append Y as second word.
 */
static inline uint16_t vdp_copper_wait_xy(uint16_t x)
{
    return (uint16_t)(0x2000u | (x & 0x3FFu));
}

/**
 * Encode a WRITE_SEQ header word.
 * @param addr       11-bit register address
 * @param count_m1   N-1 where N = number of data words (0..7)
 * @return header word; caller must append data words after this.
 */
static inline uint16_t vdp_copper_write_seq_hdr(uint16_t addr, uint8_t count_m1)
{
    return (uint16_t)(0x8000u | (((uint16_t)(count_m1 & 0x7u)) << 11) | (addr & 0x7FFu));
}

/**
 * Encode a single WRITE opcode header (1 word).
 * The data word must follow immediately in the program stream.
 * addr is a 14-bit register-bus address (bits [13:0]).
 */
static inline uint16_t vdp_copper_write_op(uint16_t addr)
{
    return (uint16_t)(0x4000u | (addr & 0x3FFFu));
}

/**
 * Encode a JUMP opcode (1 word).
 */
static inline uint16_t vdp_copper_jump(uint16_t target_pc)
{
    return (uint16_t)(0xC000u | (target_pc & 0x1FFu));
}

/**
 * Encode a SKIP opcode (BH-2, 1 word).
 * @param cond   3-bit condition code
 * @param offset 5-bit skip offset in program words
 */
static inline uint16_t vdp_copper_skip_op(uint8_t cond, uint8_t offset)
{
    return (uint16_t)(0xE000u | (((uint16_t)(cond & 0x7u)) << 5) | (offset & 0x1Fu));
}

/**
 * Upload a copper program into FPGA copper RAM starting at 0x0400.
 * Uses vdp_reg_write_burst() for efficient contiguous writes.
 * @param prog     pointer to little-endian 16-bit opcode array
 * @param nwords   number of words (max 1024)
 */
void vdp_copper_upload(const uint16_t *prog, uint16_t nwords);

/**
 * Enable or disable the copper via VDP_CTRL @ 0x0310 bit[0].
 */
void vdp_copper_enable(bool en);

/**
 * Request an atomic bank swap on the next vSyncStart.
 * Copper must already be enabled. The swap promotes the inactive bank to
 * active and resets pc to 0. HW auto-clears the request bit after commit.
 * Writes 0x0003 to VDP_CTRL @ 0x0310 (keeps COPPER_ENABLE set).
 */
void vdp_copper_swap_request(void);

/**
 * Upload a copper program to the inactive bank and request an atomic swap.
 * Convenience wrapper that combines burst upload with swap request.
 * Precondition: copper must already be enabled so writes route to the
 * inactive bank. This helper closes the stale-bank hazard (CopperSim case 11)
 * by making the swap step unskippable.
 */
void vdp_copper_upload_and_swap(const uint16_t *prog, uint16_t nwords);

#ifdef __cplusplus
}
#endif

#endif /* VDP_COPPER_H */

```

---

## FILE 7 / 21: `vdp_crc8.h`

```h
#ifndef VDP_CRC8_H
#define VDP_CRC8_H

#include <stddef.h>
#include <stdint.h>

/* QSPI-CRC8-185 host contract: CRC-8-CCITT, poly 0x07, init 0x00,
 * MSB-first, no reflection, no final XOR. */
static inline uint8_t vdp_crc8_ccitt_update(uint8_t crc, uint8_t data)
{
    crc ^= data;
    for (unsigned bit = 0; bit < 8u; ++bit) {
        crc = (crc & 0x80u) ? (uint8_t)((crc << 1) ^ 0x07u)
                            : (uint8_t)(crc << 1);
    }
    return crc;
}

/* Compute the CRC over one wire-order write frame after CMD and ADDR have
 * already been separated by the SPI controller. `frame` starts with the
 * two wire LEN bytes (LEN_lo, LEN_hi), followed by the payload bytes. The
 * address must be the encoded 24-bit address actually sent on the wire,
 * including the header-parity bit when that mode is enabled. */
static inline uint8_t vdp_crc8_qspi_write_frame(uint8_t cmd, uint32_t wire_addr,
                                                 const uint8_t *frame, size_t frame_len)
{
    uint8_t crc = 0u;

    crc = vdp_crc8_ccitt_update(crc, cmd);
    crc = vdp_crc8_ccitt_update(crc, (uint8_t)((wire_addr >> 16) & 0xFFu));
    crc = vdp_crc8_ccitt_update(crc, (uint8_t)((wire_addr >> 8) & 0xFFu));
    crc = vdp_crc8_ccitt_update(crc, (uint8_t)(wire_addr & 0xFFu));
    for (size_t i = 0; i < frame_len; ++i) {
        crc = vdp_crc8_ccitt_update(crc, frame[i]);
    }
    return crc;
}

#endif /* VDP_CRC8_H */

```

---

## FILE 8 / 21: `vdp_host.c`

```c
/**
 * vdp_host.c — Host transport layer implementation.
 */
#include "vdp_host.h"
#include "vdp_platform.h"

#if defined(VDP_HOST_BACKEND_I80_GPIO)
#include <Arduino.h>
#if defined(CONFIG_IDF_TARGET_ESP32S3)
#include "soc/gpio_reg.h"
#include "soc/soc.h"
#endif

static bool s_initialized = false;
static int s_last_error = 0;

#ifndef VDP_I80_CPU_HZ
#define VDP_I80_CPU_HZ 240000000u
#endif

static const uint8_t s_i80_data_pins[8] = {
    VDP_PIN_I80_D0, VDP_PIN_I80_D1, VDP_PIN_I80_D2, VDP_PIN_I80_D3,
    VDP_PIN_I80_D4, VDP_PIN_I80_D5, VDP_PIN_I80_D6, VDP_PIN_I80_D7
};

#if defined(CONFIG_IDF_TARGET_ESP32S3) && !defined(VDP_I80_DISABLE_FAST_GPIO)
#define VDP_I80_FAST_GPIO 1
static uint32_t s_i80_half_period_cycles = 8u;

static const uint32_t VDP_I80_DATA_MASK =
    (1u << VDP_PIN_I80_D0) | (1u << VDP_PIN_I80_D1) |
    (1u << VDP_PIN_I80_D2) | (1u << VDP_PIN_I80_D3) |
    (1u << VDP_PIN_I80_D4) | (1u << VDP_PIN_I80_D5) |
    (1u << VDP_PIN_I80_D6) | (1u << VDP_PIN_I80_D7);
static const uint32_t VDP_I80_DC_MASK = (1u << VDP_PIN_I80_DC);
static const uint32_t VDP_I80_CS_MASK = (1u << VDP_PIN_I80_CS_N);
static const uint32_t VDP_I80_WR_MASK = (1u << VDP_PIN_I80_WR_N);
static const uint32_t VDP_I80_RD_MASK = (1u << VDP_PIN_I80_RD_N);

static inline void vdp_i80_gpio_set(uint32_t mask)
{
    REG_WRITE(GPIO_OUT_W1TS_REG, mask);
}

static inline void vdp_i80_gpio_clear(uint32_t mask)
{
    REG_WRITE(GPIO_OUT_W1TC_REG, mask);
}

static inline uint32_t vdp_i80_cycle_count(void)
{
    uint32_t ccount;
    __asm__ __volatile__("rsr.ccount %0" : "=a"(ccount));
    return ccount;
}

static inline void vdp_i80_fast_delay(void)
{
    const uint32_t start = vdp_i80_cycle_count();
    while ((uint32_t)(vdp_i80_cycle_count() - start) < s_i80_half_period_cycles) {}
}
#endif

int vdp_last_error(void) { return s_last_error; }

static bool vdp_transport_ready(void)
{
    if (!s_initialized) {
        s_last_error = VDP_HOST_ERR_NOT_INITIALIZED;
        return false;
    }
    return true;
}

static void vdp_i80_set_data_output(void)
{
    for (uint8_t i = 0; i < 8; ++i) pinMode(s_i80_data_pins[i], OUTPUT);
}

static void vdp_i80_set_data_input(void)
{
    for (uint8_t i = 0; i < 8; ++i) pinMode(s_i80_data_pins[i], INPUT);
}

static void vdp_i80_write_data(uint8_t value)
{
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_clear(VDP_I80_DATA_MASK);
    vdp_i80_gpio_set(((uint32_t)value << VDP_PIN_I80_D0) & VDP_I80_DATA_MASK);
#else
    for (uint8_t bit = 0; bit < 8; ++bit) {
        digitalWrite(s_i80_data_pins[bit], (value & (uint8_t)(1u << bit)) ? HIGH : LOW);
    }
#endif
}

static uint8_t vdp_i80_read_data(void)
{
    uint8_t value = 0;
    for (uint8_t bit = 0; bit < 8; ++bit) {
        if (digitalRead(s_i80_data_pins[bit]) != LOW) value |= (uint8_t)(1u << bit);
    }
    return value;
}

static void vdp_i80_pulse_wr(void)
{
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_clear(VDP_I80_WR_MASK);
    vdp_i80_fast_delay();
    vdp_i80_gpio_set(VDP_I80_WR_MASK);
    vdp_i80_fast_delay();
#else
    delayMicroseconds(2);
    digitalWrite(VDP_PIN_I80_WR_N, LOW);
    delayMicroseconds(2);
    digitalWrite(VDP_PIN_I80_WR_N, HIGH);
    delayMicroseconds(2);
#endif
}

static void vdp_i80_write_byte(bool data_phase, uint8_t value)
{
#if defined(VDP_I80_FAST_GPIO)
    if (data_phase) {
        vdp_i80_gpio_set(VDP_I80_DC_MASK);
    } else {
        vdp_i80_gpio_clear(VDP_I80_DC_MASK);
    }
#else
    digitalWrite(VDP_PIN_I80_DC, data_phase ? HIGH : LOW);
#endif
    vdp_i80_write_data(value);
    vdp_i80_pulse_wr();
}

static uint8_t vdp_i80_read_byte(void)
{
    digitalWrite(VDP_PIN_I80_DC, HIGH);
    delayMicroseconds(2);
    digitalWrite(VDP_PIN_I80_RD_N, LOW);
    delayMicroseconds(2);
    const uint8_t value = vdp_i80_read_data();
    digitalWrite(VDP_PIN_I80_RD_N, HIGH);
    delayMicroseconds(2);
    return value;
}

void vdp_host_init(void)
{
    if (s_initialized) return;
    vdp_i80_set_data_output();
    pinMode(VDP_PIN_I80_DC, OUTPUT);
    pinMode(VDP_PIN_I80_CS_N, OUTPUT);
    pinMode(VDP_PIN_I80_WR_N, OUTPUT);
    pinMode(VDP_PIN_I80_RD_N, OUTPUT);
    digitalWrite(VDP_PIN_I80_CS_N, HIGH);
    digitalWrite(VDP_PIN_I80_WR_N, HIGH);
    digitalWrite(VDP_PIN_I80_RD_N, HIGH);
    digitalWrite(VDP_PIN_I80_DC, LOW);
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_set(VDP_I80_CS_MASK | VDP_I80_WR_MASK | VDP_I80_RD_MASK);
    vdp_i80_gpio_clear(VDP_I80_DC_MASK);
#endif
    vdp_i80_write_data(0x00);
    s_last_error = VDP_HOST_ERR_NONE;
    s_initialized = true;
}

void vdp_qspi_init(void) { vdp_host_init(); }
void vdp_pio_wait_sm_idle(void) {}
void vdp_host_set_speed_hz(uint32_t hz)
{
#if defined(VDP_I80_FAST_GPIO)
    if (hz == 0u) return;
    uint32_t half_cycles = VDP_I80_CPU_HZ / (hz * 2u);
    if (half_cycles < 8u) half_cycles = 8u;
    s_i80_half_period_cycles = half_cycles;
#else
    (void)hz;
#endif
}
void vdp_qspi_set_speed_hz(uint32_t hz) { vdp_host_set_speed_hz(hz); }

uint32_t vdp_read_status(uint8_t sel)
{
    if (!vdp_transport_ready()) return 0;
    s_last_error = VDP_HOST_ERR_NONE;
    vdp_i80_set_data_output();
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_set(VDP_I80_RD_MASK | VDP_I80_WR_MASK);
    vdp_i80_gpio_clear(VDP_I80_CS_MASK);
    vdp_i80_fast_delay();
#else
    digitalWrite(VDP_PIN_I80_RD_N, HIGH);
    digitalWrite(VDP_PIN_I80_WR_N, HIGH);
    digitalWrite(VDP_PIN_I80_CS_N, LOW);
    delayMicroseconds(5);
#endif
    vdp_i80_write_byte(false, 0x04);
    vdp_i80_write_byte(false, sel);
    vdp_i80_write_byte(false, 0x00);
    vdp_i80_write_byte(false, 0x00);
    vdp_i80_write_byte(false, 0x00);
    vdp_i80_write_byte(false, 0x00);
    vdp_i80_set_data_input();
    delayMicroseconds(2);
    const uint8_t b0 = vdp_i80_read_byte();
    const uint8_t b1 = vdp_i80_read_byte();
    const uint8_t b2 = vdp_i80_read_byte();
    const uint8_t b3 = vdp_i80_read_byte();
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_set(VDP_I80_CS_MASK);
    vdp_i80_gpio_clear(VDP_I80_DC_MASK);
#else
    digitalWrite(VDP_PIN_I80_CS_N, HIGH);
    digitalWrite(VDP_PIN_I80_DC, LOW);
#endif
    vdp_i80_set_data_output();
    vdp_i80_write_data(0x00);
    return (uint32_t)b0 | ((uint32_t)b1 << 8) |
           ((uint32_t)b2 << 16) | ((uint32_t)b3 << 24);
}

void vdp_reg_write(uint32_t addr, uint16_t data)
{
    vdp_reg_write_burst(addr, &data, 1);
}

void vdp_clear_upload_status(uint16_t mask)
{
    s_last_error = VDP_HOST_ERR_NONE;
    vdp_reg_write(VDP_UPLOAD_STATUS_CLEAR_REG, (uint16_t)(mask & VDP_UPLOAD_STATUS_CLEAR_MASK));
}

void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    if (!vdp_transport_ready()) return;
    s_last_error = VDP_HOST_ERR_NONE;
    if (num_words == 0 || words == NULL) {
        s_last_error = VDP_HOST_ERR_INVALID_ARG;
        return;
    }

    for (uint16_t i = 0; i < num_words; ++i) {
        const uint32_t reg_addr = addr + i;
        vdp_i80_set_data_output();
#if defined(VDP_I80_FAST_GPIO)
        vdp_i80_gpio_set(VDP_I80_RD_MASK | VDP_I80_WR_MASK);
        vdp_i80_gpio_clear(VDP_I80_CS_MASK);
        vdp_i80_fast_delay();
#else
        digitalWrite(VDP_PIN_I80_RD_N, HIGH);
        digitalWrite(VDP_PIN_I80_WR_N, HIGH);
        digitalWrite(VDP_PIN_I80_CS_N, LOW);
        delayMicroseconds(5);
#endif
        vdp_i80_write_byte(false, 0x00);
        vdp_i80_write_byte(false, (uint8_t)(reg_addr & 0xFFu));
        vdp_i80_write_byte(false, (uint8_t)((reg_addr >> 8) & 0xFFu));
        vdp_i80_write_byte(true, (uint8_t)(words[i] & 0xFFu));
        vdp_i80_write_byte(true, (uint8_t)((words[i] >> 8) & 0xFFu));
#if defined(VDP_I80_FAST_GPIO)
        vdp_i80_gpio_set(VDP_I80_CS_MASK);
#else
        digitalWrite(VDP_PIN_I80_CS_N, HIGH);
#endif
    }
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_clear(VDP_I80_DC_MASK);
#else
    digitalWrite(VDP_PIN_I80_DC, LOW);
#endif
    vdp_i80_write_data(0x00);
}

uint16_t vdp_reg_read(uint32_t addr)
{
    if (!vdp_transport_ready()) return 0;
    s_last_error = VDP_HOST_ERR_NONE;
    vdp_i80_set_data_output();
    digitalWrite(VDP_PIN_I80_RD_N, HIGH);
    digitalWrite(VDP_PIN_I80_WR_N, HIGH);
    digitalWrite(VDP_PIN_I80_CS_N, LOW);
    delayMicroseconds(5);
    vdp_i80_write_byte(false, 0x01);
    vdp_i80_write_byte(false, (uint8_t)(addr & 0xFFu));
    vdp_i80_write_byte(false, (uint8_t)((addr >> 8) & 0xFFu));
    vdp_i80_set_data_input();
    delayMicroseconds(5);
    const uint8_t lo = vdp_i80_read_byte();
    const uint8_t hi = vdp_i80_read_byte();
    digitalWrite(VDP_PIN_I80_CS_N, HIGH);
    vdp_i80_set_data_output();
    digitalWrite(VDP_PIN_I80_DC, LOW);
    vdp_i80_write_data(0x00);
    return (uint16_t)lo | ((uint16_t)hi << 8);
}

void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    if (!vdp_transport_ready()) return;
    s_last_error = VDP_HOST_ERR_NONE;
    if (num_words == 0 || words == NULL || num_words > 32767u) {
        s_last_error = VDP_HOST_ERR_INVALID_ARG;
        return;
    }

    const uint16_t byte_len = (uint16_t)(num_words * 2u);
    vdp_i80_set_data_output();
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_set(VDP_I80_RD_MASK | VDP_I80_WR_MASK);
    vdp_i80_gpio_clear(VDP_I80_CS_MASK);
    vdp_i80_fast_delay();
#else
    digitalWrite(VDP_PIN_I80_RD_N, HIGH);
    digitalWrite(VDP_PIN_I80_WR_N, HIGH);
    digitalWrite(VDP_PIN_I80_CS_N, LOW);
    delayMicroseconds(5);
#endif
    vdp_i80_write_byte(false, 0x02);
    vdp_i80_write_byte(false, (uint8_t)(addr & 0xFFu));
    vdp_i80_write_byte(false, (uint8_t)((addr >> 8) & 0xFFu));
    vdp_i80_write_byte(false, (uint8_t)((addr >> 16) & 0xFFu));
    vdp_i80_write_byte(false, (uint8_t)(byte_len & 0xFFu));
    vdp_i80_write_byte(false, (uint8_t)((byte_len >> 8) & 0xFFu));
    for (uint16_t i = 0; i < num_words; ++i) {
        vdp_i80_write_byte(true, (uint8_t)(words[i] & 0xFFu));
        vdp_i80_write_byte(true, (uint8_t)((words[i] >> 8) & 0xFFu));
    }
#if defined(VDP_I80_FAST_GPIO)
    vdp_i80_gpio_set(VDP_I80_CS_MASK);
    vdp_i80_gpio_clear(VDP_I80_DC_MASK);
#else
    digitalWrite(VDP_PIN_I80_CS_N, HIGH);
    digitalWrite(VDP_PIN_I80_DC, LOW);
#endif
    vdp_i80_write_data(0x00);
}

#elif defined(PICO) || defined(ARDUINO_ARCH_RP2040) || defined(ARDUINO_RASPBERRY_PI_PICO)
#include "pico/stdlib.h"
#include "hardware/pio.h"
#include "hardware/gpio.h"
#include "hardware/clocks.h"
#include "qspi_quad.pio.h"

static bool   s_initialized = false;
static int    s_last_error = 0;
static uint   s_tx_offset;

int vdp_last_error(void) { return s_last_error; }

static inline void vdp_cs_assert(void)   { gpio_put(VDP_PIN_QSPI_CS_N, 0); }
static inline void vdp_cs_deassert(void) { gpio_put(VDP_PIN_QSPI_CS_N, 1); }

static inline uint32_t vdp_pack_bytes(uint8_t b0, uint8_t b1, uint8_t b2, uint8_t b3)
{
    return ((uint32_t)b0 << 24) | ((uint32_t)b1 << 16) |
           ((uint32_t)b2 << 8)  | (uint32_t)b3;
}

void vdp_host_init(void)
{
    if (s_initialized) return;
    gpio_init(VDP_PIN_QSPI_CS_N);
    gpio_set_dir(VDP_PIN_QSPI_CS_N, GPIO_OUT);
    gpio_put(VDP_PIN_QSPI_CS_N, 1);
    for (uint p = VDP_PIN_QSPI_SCK; p <= VDP_PIN_QSPI_IO3; ++p) {
        if (p == VDP_PIN_QSPI_CS_N) continue;
        pio_gpio_init(VDP_QSPI_PIO, p);
    }
    s_tx_offset = pio_add_program(VDP_QSPI_PIO, &qspi_quad_tx_program);
    pio_sm_config c = qspi_quad_tx_program_get_default_config(s_tx_offset);
    sm_config_set_sideset_pins(&c, VDP_PIN_QSPI_SCK);
    sm_config_set_out_pins(&c, VDP_PIN_QSPI_IO0, 4);
    sm_config_set_out_shift(&c, false, true, 32);
    pio_sm_set_consecutive_pindirs(VDP_QSPI_PIO, VDP_QSPI_SM_TX, VDP_PIN_QSPI_SCK, 1, true);
    pio_sm_set_consecutive_pindirs(VDP_QSPI_PIO, VDP_QSPI_SM_TX, VDP_PIN_QSPI_IO0, 4, true);
    uint32_t sys_hz = clock_get_hz(clk_sys);
    float div = (float)sys_hz / ((float)VDP_QSPI_SCK_HZ * 10.0f);
    sm_config_set_clkdiv(&c, div);
    pio_sm_init(VDP_QSPI_PIO, VDP_QSPI_SM_TX, s_tx_offset, &c);
    pio_sm_set_enabled(VDP_QSPI_PIO, VDP_QSPI_SM_TX, true);
    s_initialized = true;
}

void vdp_qspi_init(void) { vdp_host_init(); }

void vdp_pio_wait_sm_idle(void)
{
    while (!pio_sm_is_tx_fifo_empty(VDP_QSPI_PIO, VDP_QSPI_SM_TX)) { /* spin */ }
    sleep_us(20);
}

void vdp_host_set_speed_hz(uint32_t hz) { (void)hz; }
void vdp_qspi_set_speed_hz(uint32_t hz) { vdp_host_set_speed_hz(hz); }

static inline void vdp_tx_word(uint32_t w) { pio_sm_put_blocking(VDP_QSPI_PIO, VDP_QSPI_SM_TX, w); }

static void vdp_tx_bytes(const uint8_t *buf, size_t n)
{
    size_t words = n / 4;
    for (size_t i = 0; i < words; ++i) {
        vdp_tx_word(vdp_pack_bytes(buf[4*i+0], buf[4*i+1], buf[4*i+2], buf[4*i+3]));
    }
    vdp_pio_wait_sm_idle();
}

static void vdp_bitbang_byte(uint8_t val)
{
    uint32_t mask = 0xFu << VDP_PIN_QSPI_IO0;
    gpio_put_masked(mask, (uint32_t)((val >> 4) & 0xF) << VDP_PIN_QSPI_IO0);
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    gpio_put_masked(mask, (uint32_t)(val & 0xF) << VDP_PIN_QSPI_IO0);
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
}

static uint8_t vdp_bitbang_read_byte(void)
{
    uint8_t rx = 0;
    uint32_t pins;
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    pins = gpio_get_all();
    rx = (uint8_t)(((pins >> VDP_PIN_QSPI_IO0) & 0xF) << 4);
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    pins = gpio_get_all();
    rx |= (uint8_t)((pins >> VDP_PIN_QSPI_IO0) & 0xF);
    return rx;
}

uint32_t vdp_read_status(uint8_t sel)
{
    gpio_set_function(VDP_PIN_QSPI_SCK, GPIO_FUNC_SIO);
    gpio_set_dir(VDP_PIN_QSPI_SCK, GPIO_OUT);
    gpio_put(VDP_PIN_QSPI_SCK, 0);
    for (uint i = 0; i < 4; i++) {
        gpio_set_function(VDP_PIN_QSPI_IO0 + i, GPIO_FUNC_SIO);
        gpio_set_dir(VDP_PIN_QSPI_IO0 + i, GPIO_OUT);
    }
    vdp_cs_assert();
    uint8_t hdr[6] = { 0x04, sel, 0x00, 0x00, 0x00, 0x00 };
    for (int i = 0; i < 6; i++) vdp_bitbang_byte(hdr[i]);
    for (uint i = 0; i < 4; i++) gpio_set_dir(VDP_PIN_QSPI_IO0 + i, GPIO_IN);
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 0);  busy_wait_us_32(1);
    gpio_put(VDP_PIN_QSPI_SCK, 1);  busy_wait_us_32(1);
    uint8_t b0 = vdp_bitbang_read_byte();
    uint8_t b1 = vdp_bitbang_read_byte();
    uint8_t b2 = vdp_bitbang_read_byte();
    uint8_t b3 = vdp_bitbang_read_byte();
    gpio_put(VDP_PIN_QSPI_SCK, 0);
    vdp_cs_deassert();
    pio_gpio_init(VDP_QSPI_PIO, VDP_PIN_QSPI_SCK);
    for (uint i = 0; i < 4; i++) pio_gpio_init(VDP_QSPI_PIO, VDP_PIN_QSPI_IO0 + i);
    pio_sm_set_consecutive_pindirs(VDP_QSPI_PIO, VDP_QSPI_SM_TX, VDP_PIN_QSPI_SCK, 1, true);
    pio_sm_set_consecutive_pindirs(VDP_QSPI_PIO, VDP_QSPI_SM_TX, VDP_PIN_QSPI_IO0, 4, true);
    sleep_us(10);
    return (uint32_t)b0 | ((uint32_t)b1 << 8) | ((uint32_t)b2 << 16) | ((uint32_t)b3 << 24);
}

// ---- ESP32-S3 Hardware SPI2 (Quad, DMA) Implementation ----------------------
#elif defined(VDP_QSPI_BACKEND_SPI2)
#include <Arduino.h>
#include <driver/spi_master.h>

static spi_device_handle_t s_spi = NULL;
static bool s_bus_initialized = false;
static bool s_initialized = false;
static int s_last_error = 0;

int vdp_last_error(void) { return s_last_error; }

static inline void vdp_cs_assert(void)   { digitalWrite(VDP_PIN_QSPI_CS_N, LOW); }
static inline void vdp_cs_deassert(void) {
    digitalWrite(VDP_PIN_QSPI_CS_N, HIGH);
    delayMicroseconds(10);
}

void vdp_host_init(void)
{
    if (s_initialized) return;
    pinMode(VDP_PIN_QSPI_CS_N, OUTPUT);
    vdp_cs_deassert();
    if (!s_bus_initialized) {
        spi_bus_config_t buscfg = {0};
        buscfg.mosi_io_num    = VDP_PIN_QSPI_IO0;
        buscfg.miso_io_num    = VDP_PIN_QSPI_IO1;
        buscfg.sclk_io_num    = VDP_PIN_QSPI_SCK;
        buscfg.quadwp_io_num  = VDP_PIN_QSPI_IO2;
        buscfg.quadhd_io_num  = VDP_PIN_QSPI_IO3;
        buscfg.max_transfer_sz = 4096;
        buscfg.flags          = SPICOMMON_BUSFLAG_MASTER | SPICOMMON_BUSFLAG_QUAD;
        if (spi_bus_initialize(SPI2_HOST, &buscfg, SPI_DMA_CH_AUTO) != ESP_OK) { s_last_error = 3; return; }
        s_bus_initialized = true;
    }
    spi_device_interface_config_t devcfg = {0};
    devcfg.clock_speed_hz = VDP_QSPI_SCK_HZ;
    devcfg.mode           = 0;
    devcfg.spics_io_num   = -1;
    devcfg.queue_size     = 4;
    devcfg.flags          = SPI_DEVICE_HALFDUPLEX;
    if (spi_bus_add_device(SPI2_HOST, &devcfg, &s_spi) != ESP_OK) { s_spi = NULL; s_initialized = false; s_last_error = 4; return; }
    s_initialized = true;
}

void vdp_qspi_init(void) { vdp_host_init(); }

void vdp_pio_wait_sm_idle(void) {}

void vdp_host_set_speed_hz(uint32_t hz)
{
    if (!s_bus_initialized) return;
    if (hz > VDP_QSPI_SCK_WRITE_HZ) hz = VDP_QSPI_SCK_WRITE_HZ;
    vdp_cs_deassert();
    if (s_spi) {
        if (spi_bus_remove_device(s_spi) != ESP_OK) {
            s_last_error = 4;
            return;
        }
        s_spi = NULL;
        s_initialized = false;
    }
    spi_device_interface_config_t devcfg = {0};
    devcfg.clock_speed_hz = (int)hz;
    devcfg.mode           = 0;
    devcfg.spics_io_num   = -1;
    devcfg.queue_size     = 4;
    devcfg.flags          = SPI_DEVICE_HALFDUPLEX;
    if (spi_bus_add_device(SPI2_HOST, &devcfg, &s_spi) != ESP_OK) {
        s_spi = NULL;
        s_initialized = false;
        s_last_error = 4;
        return;
    }
    s_initialized = true;
    vdp_cs_deassert();
}

void vdp_qspi_set_speed_hz(uint32_t hz) { vdp_host_set_speed_hz(hz); }

static bool vdp_spi_ready(void)
{
    if (!s_initialized || s_spi == NULL) {
        s_last_error = 4;
        return false;
    }
    return true;
}

static void vdp_tx_bytes(const uint8_t *buf, size_t n)
{
    if (n == 0) return;
    if (!vdp_spi_ready()) return;
    spi_transaction_t t = {0};
    t.flags     = SPI_TRANS_MODE_QIO;
    t.length    = n * 8u;
    t.tx_buffer = buf;
    if (spi_device_polling_transmit(s_spi, &t) != ESP_OK) s_last_error = 5;
}

uint32_t vdp_read_status(uint8_t sel)
{
    if (!vdp_spi_ready()) return 0;
    uint8_t hdr[6] = { 0x04, sel, 0x00, 0x00, 0x00, 0x00 };
    uint8_t rx[4]  = { 0, 0, 0, 0 };
    vdp_cs_assert();
    spi_transaction_t tx = {0};
    tx.flags     = SPI_TRANS_MODE_QIO;
    tx.length    = 6u * 8u;
    tx.tx_buffer = hdr;
    esp_err_t err = spi_device_polling_transmit(s_spi, &tx);
    if (err != ESP_OK) { vdp_cs_deassert(); s_last_error = 5; return 0; }
    spi_transaction_ext_t rd = {0};
    rd.base.flags    = SPI_TRANS_MODE_QIO | SPI_TRANS_VARIABLE_DUMMY;
    rd.base.rxlength = 4u * 8u;
    rd.base.rx_buffer = rx;
    rd.dummy_bits    = 2;
    err = spi_device_polling_transmit(s_spi, (spi_transaction_t *)&rd);
    vdp_cs_deassert();
    if (err != ESP_OK) { s_last_error = 6; return 0; }
    return (uint32_t)rx[0] | ((uint32_t)rx[1] << 8) | ((uint32_t)rx[2] << 16) | ((uint32_t)rx[3] << 24);
}

// ---- ESP32 / ESP8266 Arduino Bit-bang Implementation -------------------------
#elif defined(ARDUINO)
#include <Arduino.h>
static bool s_initialized = false;
static int s_last_error = 0;
int vdp_last_error(void) { return s_last_error; }

static inline void vdp_cs_assert(void)   { digitalWrite(VDP_PIN_QSPI_CS_N, LOW); }
static inline void vdp_cs_deassert(void) {
    digitalWrite(VDP_PIN_QSPI_CS_N, HIGH);
    delayMicroseconds(10);
}

#if defined(ESP32)
#define HALF_PERIOD_US 1
#define MASK_SCK    (1u << VDP_PIN_QSPI_SCK)
#define MASK_IO0    (1u << VDP_PIN_QSPI_IO0)
#define MASK_IO1    (1u << VDP_PIN_QSPI_IO1)
#define MASK_IO2    (1u << VDP_PIN_QSPI_IO2)
#define MASK_IO3    (1u << VDP_PIN_QSPI_IO3)
#define MASK_IO_ALL (MASK_IO0 | MASK_IO1 | MASK_IO2 | MASK_IO3)
static inline void vdp_drive_nibble(uint8_t n) {
    uint32_t set = 0;
    if (n & 0x1) set |= MASK_IO0; if (n & 0x2) set |= MASK_IO1;
    if (n & 0x4) set |= MASK_IO2; if (n & 0x8) set |= MASK_IO3;
    REG_WRITE(GPIO_OUT_W1TC_REG, MASK_IO_ALL);
    if (set) REG_WRITE(GPIO_OUT_W1TS_REG, set);
}
static inline void vdp_set_sck(bool high) { REG_WRITE(high ? GPIO_OUT_W1TS_REG : GPIO_OUT_W1TC_REG, MASK_SCK); }
static inline uint8_t vdp_read_nibble(void) {
    uint32_t pins = REG_READ(GPIO_IN_REG);
    uint8_t n = 0;
    if (pins & MASK_IO0) n |= 0x1; if (pins & MASK_IO1) n |= 0x2;
    if (pins & MASK_IO2) n |= 0x4; if (pins & MASK_IO3) n |= 0x8;
    return n;
}
#elif defined(ESP8266)
#define HALF_PERIOD_US 4
#define MASK_SCK    (1u << VDP_PIN_QSPI_SCK)
#define MASK_IO0    (1u << VDP_PIN_QSPI_IO0)
#define MASK_IO1    (1u << VDP_PIN_QSPI_IO1)
#define MASK_IO2    (1u << VDP_PIN_QSPI_IO2)
#define MASK_IO_LOW (MASK_IO0 | MASK_IO1 | MASK_IO2)
static inline void vdp_drive_nibble(uint8_t n) {
    uint32_t set = 0;
    if (n & 0x1) set |= MASK_IO0; if (n & 0x2) set |= MASK_IO1;
    if (n & 0x4) set |= MASK_IO2;
    GPOC = MASK_IO_LOW; if (set) GPOS = set;
    digitalWrite(VDP_PIN_QSPI_IO3, (n & 0x8) ? HIGH : LOW);
}
static inline void vdp_set_sck(bool high) { if (high) GPOS = MASK_SCK; else GPOC = MASK_SCK; }
static inline uint8_t vdp_read_nibble(void) {
    uint32_t pins = GPI;
    uint8_t n = 0;
    if (pins & MASK_IO0) n |= 0x1; if (pins & MASK_IO1) n |= 0x2;
    if (pins & MASK_IO2) n |= 0x4; if (digitalRead(VDP_PIN_QSPI_IO3)) n |= 0x8;
    return n;
}
#endif

static void vdp_send_nibble(uint8_t n) { vdp_drive_nibble(n); vdp_set_sck(false); delayMicroseconds(HALF_PERIOD_US); vdp_set_sck(true); delayMicroseconds(HALF_PERIOD_US); }
static void vdp_send_byte(uint8_t b) { vdp_send_nibble((b >> 4) & 0x0F); vdp_send_nibble( b       & 0x0F); }

void vdp_host_init(void)
{
    if (s_initialized) return;
    pinMode(VDP_PIN_QSPI_SCK,  OUTPUT); pinMode(VDP_PIN_QSPI_CS_N, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO0,  OUTPUT); pinMode(VDP_PIN_QSPI_IO1,  OUTPUT);
    pinMode(VDP_PIN_QSPI_IO2,  OUTPUT); pinMode(VDP_PIN_QSPI_IO3,  OUTPUT);
    vdp_cs_deassert(); vdp_set_sck(false); s_initialized = true;
}

void vdp_pio_wait_sm_idle(void) {}
void vdp_host_set_speed_hz(uint32_t hz) { (void)hz; }
void vdp_qspi_init(void) { vdp_host_init(); }
void vdp_qspi_set_speed_hz(uint32_t hz) { vdp_host_set_speed_hz(hz); }
static void vdp_tx_bytes(const uint8_t *buf, size_t n) { for (size_t i = 0; i < n; ++i) vdp_send_byte(buf[i]); }

uint32_t vdp_read_status(uint8_t sel)
{
    vdp_set_sck(false);
    pinMode(VDP_PIN_QSPI_IO0, OUTPUT); pinMode(VDP_PIN_QSPI_IO1, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO2, OUTPUT); pinMode(VDP_PIN_QSPI_IO3, OUTPUT);
    vdp_cs_assert();
    uint8_t hdr[6] = { 0x04, sel, 0x00, 0x00, 0x00, 0x00 };
    vdp_tx_bytes(hdr, 6);
    pinMode(VDP_PIN_QSPI_IO0, INPUT); pinMode(VDP_PIN_QSPI_IO1, INPUT);
    pinMode(VDP_PIN_QSPI_IO2, INPUT); pinMode(VDP_PIN_QSPI_IO3, INPUT);
    for (int i = 0; i < 2; i++) { vdp_set_sck(false); delayMicroseconds(HALF_PERIOD_US); vdp_set_sck(true);  delayMicroseconds(HALF_PERIOD_US); }
    uint8_t bytes[4];
    for (int i = 0; i < 4; i++) {
        vdp_set_sck(false); delayMicroseconds(HALF_PERIOD_US); vdp_set_sck(true);  uint8_t hi = vdp_read_nibble(); delayMicroseconds(HALF_PERIOD_US);
        vdp_set_sck(false); delayMicroseconds(HALF_PERIOD_US); vdp_set_sck(true);  uint8_t lo = vdp_read_nibble(); delayMicroseconds(HALF_PERIOD_US);
        bytes[i] = (hi << 4) | lo;
    }
    vdp_set_sck(false); vdp_cs_deassert();
    pinMode(VDP_PIN_QSPI_IO0, OUTPUT); pinMode(VDP_PIN_QSPI_IO1, OUTPUT);
    pinMode(VDP_PIN_QSPI_IO2, OUTPUT); pinMode(VDP_PIN_QSPI_IO3, OUTPUT);
    delayMicroseconds(10);
    return (uint32_t)bytes[0] | ((uint32_t)bytes[1] << 8) | ((uint32_t)bytes[2] << 16) | ((uint32_t)bytes[3] << 24);
}
#endif

// ---- Common Shared Implementation -------------------------------------------
#if !defined(VDP_HOST_BACKEND_I80_GPIO)

void vdp_reg_write(uint32_t addr, uint16_t data) { vdp_reg_write_burst(addr, &data, 1); }

void vdp_clear_upload_status(uint16_t mask)
{
    vdp_reg_write(VDP_UPLOAD_STATUS_CLEAR_REG, (uint16_t)(mask & VDP_UPLOAD_STATUS_CLEAR_MASK));
}

void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    uint8_t frame[512] __attribute__((aligned(4)));
    if (num_words == 0 || num_words > 253u || words == NULL) { s_last_error = 2; return; }
    size_t n = 6 + 2 * (size_t)num_words;
    frame[0] = 0x01;
    frame[1] = (uint8_t)(addr & 0xFF); frame[2] = (uint8_t)((addr >> 8) & 0xFF); frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = (uint8_t)(num_words & 0xFF); frame[5] = (uint8_t)((num_words >> 8) & 0xFF);
    for (size_t i = 0; i < num_words; ++i) {
        frame[6 + 2*i + 0] = (uint8_t)(words[i] & 0xFF);
        frame[6 + 2*i + 1] = (uint8_t)((words[i] >> 8) & 0xFF);
    }
    vdp_cs_assert();
    vdp_tx_bytes(frame, n);
    vdp_cs_deassert();
}

void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words)
{
    uint8_t frame[512] __attribute__((aligned(4)));
    if (num_words == 0 || num_words > 253u || words == NULL) { s_last_error = 2; return; }
    size_t n = 6 + 2 * (size_t)num_words;
    frame[0] = 0x02;
    frame[1] = (uint8_t)(addr & 0xFF); frame[2] = (uint8_t)((addr >> 8) & 0xFF); frame[3] = (uint8_t)((addr >> 16) & 0xFF);
    frame[4] = (uint8_t)(num_words & 0xFF); frame[5] = (uint8_t)((num_words >> 8) & 0xFF);
    for (size_t i = 0; i < num_words; ++i) {
        frame[6 + 2*i + 0] = (uint8_t)(words[i] & 0xFF);
        frame[6 + 2*i + 1] = (uint8_t)((words[i] >> 8) & 0xFF);
    }
    vdp_cs_assert();
    vdp_tx_bytes(frame, n);
    vdp_cs_deassert();
}

uint16_t vdp_reg_read(uint32_t addr)
{
    (void)addr;
    s_last_error = VDP_HOST_ERR_RX;
    return 0;
}
#endif

```

---

## FILE 9 / 21: `vdp_host.h`

```h
/**
 * vdp_host.h — Transport layer for the VDP host driver library.
 *
 * Encapsulates the active host transport so application code never
 * hand-frames packets. The current Tang Nano 20K deployment uses i80;
 * legacy QSPI backends remain available through deprecated aliases.
 *
 * All functions are synchronous / blocking. Errors are reported via
 * `vdp_last_error()`; return value of `bool` APIs is `true` on success.
 */
#ifndef VDP_HOST_H
#define VDP_HOST_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VDP_UPLOAD_STATUS_CLEAR_REG 0x0323u
#define VDP_UPLOAD_STATUS_BUSY      0x0001u
#define VDP_UPLOAD_STATUS_DONE      0x0002u
#define VDP_UPLOAD_STATUS_ERROR     0x0004u
#define VDP_UPLOAD_STATUS_OVERFLOW  0x0008u
#define VDP_UPLOAD_STATUS_TXN_DROPPED 0x0010u
#define VDP_UPLOAD_STATUS_CLEAR_MASK \
    (VDP_UPLOAD_STATUS_ERROR | VDP_UPLOAD_STATUS_OVERFLOW | \
     VDP_UPLOAD_STATUS_TXN_DROPPED)

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

#define VDP_QSPI_ERR_NONE             VDP_HOST_ERR_NONE
#define VDP_QSPI_ERR_INVALID_ARG      VDP_HOST_ERR_INVALID_ARG
#define VDP_QSPI_ERR_BUS_INIT         VDP_HOST_ERR_BUS_INIT
#define VDP_QSPI_ERR_DEVICE           VDP_HOST_ERR_DEVICE
#define VDP_QSPI_ERR_TX               VDP_HOST_ERR_TX
#define VDP_QSPI_ERR_RX               VDP_HOST_ERR_RX
#define VDP_QSPI_ERR_NOT_INITIALIZED  VDP_HOST_ERR_NOT_INITIALIZED
#define VDP_QSPI_ERR_INVALID_SELECTOR VDP_HOST_ERR_INVALID_SELECTOR

/**
 * One-time bring-up of the active host pins/peripheral.
 * Must be called once after `stdio_init_all()` and before any other
 * library call. Idempotent after first call (subsequent calls no-op).
 */
void vdp_host_init(void);
void vdp_qspi_init(void);

/**
 * Issue a REG_WRITE (CMD=0x01) transaction writing a single 16-bit
 * word to the specified 15-bit VDP register address.
 * @param addr 15-bit register address (e.g. 0x0300 LAYER_ENABLE)
 * @param data little-endian 16-bit payload
 */
void vdp_reg_write(uint32_t addr, uint16_t data);

/**
 * Issue a REG_WRITE burst (CMD=0x01) writing `num_words` consecutive
 * 16-bit words starting at the specified register address.
 *
 * The FPGA decoder auto-increments the register address once per word.
 * Use this for contiguous register blocks to amortize header and CS
 * overhead. The payload is little-endian 16-bit words.
 *
 * @param num_words 1..253 (capped by the 253-word local frame buffer)
 */
void vdp_reg_write_burst(uint32_t addr, const uint16_t *words, uint16_t num_words);

/**
 * Clear upload-status sticky bits using the RTL W1C register at 0x0323.
 *
 * Valid Fix B bits are VDP_UPLOAD_STATUS_ERROR (bit 2) and
 * VDP_UPLOAD_STATUS_OVERFLOW (bit 3). Pass only bits intended to clear.
 *
 * Note: the 0x0323 clear decode is not yet implemented in the current
 * bitstream; the helper issues the write, but hardware ignores it until
 * the RTL change lands (FULL-DOC-AUDIT-151).
 */
void vdp_clear_upload_status(uint16_t mask);

/**
 * Issue a READ_STATUS (CMD=0x04) transaction and return the 32-bit
 * little-endian response word for the requested selector.
 *
 * Note: READ_STATUS is implemented only on legacy QSPI builds. The i80
 * RTL decoder does not currently decode opcode 0x04, so this function
 * returns undefined data on i80 hosts (use normal register reads instead).
 *
 * @param sel   0 = magic 0x51560002, 1 = rx_cmd_cnt, 2 = last_addr,
 *              3 = last_data, 4 = last_error, 5 = status sticky,
 *              6 = upload status (busy/done bits), 7 = live mode,
 *              8 = diagnostic SDRAM dword readback
 * @return 32-bit response assembled from 4 bit-banged bytes (byte 0 = LSB)
 */
uint32_t vdp_read_status(uint8_t sel);
uint16_t vdp_reg_read(uint32_t addr);

/**
 * Issue an SDRAM_WRITE (CMD=0x02) transaction streaming `num_words`
 * 16-bit little-endian words into the FPGA's SDRAM starting at the
 * 24-bit byte address `addr`. Host must paced bursts to vblank via
 * `vdp_upload_asset()` for clean visible-render results; this low-level
 * call fires the entire transaction in one PIO stream.
 * @param addr      target SDRAM byte address (24-bit low)
 * @param words     pointer to little-endian 16-bit words
 * @param num_words LEN field (max 65535, capped by local frame buffer)
 */
void vdp_sdram_write(uint32_t addr, const uint16_t *words, uint16_t num_words);

/**
 * Last error code (0 = none). Cleared by vdp_host_init(); otherwise
 * sticky across calls. Currently only used by helpers that return
 * bool; the blocking write/read calls cannot fail in library-visible
 * ways beyond an upstream FPGA HOST_ERROR which must be polled via
 * READ_STATUS sel=4.
 */
int vdp_last_error(void);

/**
 * Change the host transport speed at runtime. Currently only effective on
 * legacy ESP32-S3 hardware SPI2/QSPI compatibility builds. On i80 and
 * bit-bang platforms this is a no-op. Pass a frequency in Hz; the actual
 * rate may be rounded to the nearest divisor of the bus clock.
 *
 * Safe to call between transactions; do not call mid-transaction. The
 * SPI2/QSPI compatibility builds clamp requests to `VDP_HOST_SCK_WRITE_HZ`
 * (`VDP_QSPI_SCK_WRITE_HZ` legacy alias).
 */
void vdp_host_set_speed_hz(uint32_t hz);
void vdp_qspi_set_speed_hz(uint32_t hz);

/**
 * Wait for the PIO TX FIFO to drain + a proven 20 µs OSR margin.
 *
 * MUST be called after any PIO TX burst before:
 *   - deasserting CS_N
 *   - switching pin function (PIO → SIO for bit-bang read)
 *   - beginning an unrelated PIO sequence
 *
 * The wait is two phases: spin on `pio_sm_is_tx_fifo_empty()`, then
 * `sleep_us(20)` for the final nibble to shift out of the OSR. At the
 * proven 2 MHz SCK the final nibble needs ~5 µs, so 20 µs is a 4×
 * margin (Task 38c). Do not reduce without re-validating on hardware.
 */
void vdp_pio_wait_sm_idle(void);

#ifdef __cplusplus
}
#endif

#endif /* VDP_HOST_H */

```

---

## FILE 10 / 21: `vdp_i80.h`

```h
/**
 * vdp_i80.h - ESP32-S3 i80 host transport facade.
 *
 * The implementation is shared with the historical transport unit so existing
 * Mode0 helper code keeps linking, but new firmware should include this header
 * and use the host-neutral register/upload calls below.
 */
#ifndef VDP_I80_H
#define VDP_I80_H

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
#define VDP_UPLOAD_STATUS_TXN_DROPPED  0x0010u
#define VDP_UPLOAD_STATUS_CLEAR_MASK \
    (VDP_UPLOAD_STATUS_ERROR | VDP_UPLOAD_STATUS_OVERFLOW | \
     VDP_UPLOAD_STATUS_TXN_DROPPED)

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

```

---

## FILE 11 / 21: `vdp_legacySpi.h`

```h
/**
 * vdp_legacySpi.h — Deprecated compatibility header for legacy SPI sketches.
 *
 * New firmware should include `vdp_host.h` and call `vdp_host_init()`.
 * This shim preserves older sketches that still include `vdp_legacySpi.h`
 * or call the legacy `vdp_legacy_spi_*` aliases.
 */
#ifndef VDP_LEGACY_SPI_H
#define VDP_LEGACY_SPI_H

#include "vdp_host.h"

#ifdef __cplusplus
extern "C" {
#endif

void vdp_legacy_spi_init(void);
void vdp_legacy_spi_set_speed_hz(uint32_t hz);

#ifdef __cplusplus
}
#endif

#endif /* VDP_LEGACY_SPI_H */

```

---

## FILE 12 / 21: `vdp_mode0.c`

```c
#include "vdp_mode0.h"

#include "vdp_host.h"

#if defined(ARDUINO)
#include <Arduino.h>
#endif

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

uint16_t vdp_mode0_border_ctrl_inner(bool enable, bool inner_enable, uint8_t palette_index)
{
    return (uint16_t)((enable ? 1u : 0u) |
                      (inner_enable ? 0x0002u : 0u) |
                      (((uint16_t)palette_index & 0x1Fu) << 8));
}

uint16_t vdp_mode0_scale_ctrl(uint8_t scale_x, uint8_t scale_y, bool auto_center)
{
    return (uint16_t)((((uint16_t)scale_x) & 0x7u) |
                      ((((uint16_t)scale_y) & 0x7u) << 4) |
                      (auto_center ? 0x0080u : 0u));
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

bool vdp_mode0_soft_reset(void)
{
    const uint32_t timeout_ms = 1000u;
#if defined(ARDUINO)
    const uint32_t start_ms = millis();
#else
    const uint32_t start_ms = 0u;
#endif

    vdp_reg_write(VDP_MODE0_REG_VDP_CTRL, 0x0004u);

    while (true) {
        if ((vdp_reg_read(VDP_MODE0_REG_VDP_CTRL) & 0x0004u) == 0u) {
            return true;
        }
#if defined(ARDUINO)
        if ((uint32_t)(millis() - start_ms) >= timeout_ms) {
            return false;
        }
        delayMicroseconds(50);
#else
        (void)start_ms;
        (void)timeout_ms;
        return false;
#endif
    }
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

void vdp_mode0_set_trans_key(uint8_t layer, uint8_t key)
{
    uint16_t addr;
    if (layer > 3u) return;
    addr = (uint16_t)(VDP_MODE0_REG_L0_TRANS_KEY + layer);
    vdp_reg_write(addr, (uint16_t)(key & 0x0Fu));
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
    if (entry_index >= 128u) return false;
    vdp_reg_write((uint16_t)(VDP_MODE0_REG_VSCROLL_BASE + (layer * 128u) + entry_index),
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

void vdp_mode0_set_backdrop_index(uint8_t index)
{
    vdp_reg_write(VDP_MODE0_REG_BACKDROP_INDEX, (uint16_t)(index & 0x7Fu));
}

void vdp_mode0_set_inner_border(uint16_t left, uint16_t right, uint16_t top, uint16_t bottom)
{
    const uint16_t words[4] = { left, right, top, bottom };
    vdp_mode0_write_block(VDP_MODE0_REG_INNER_BORDER_L, words, 4);
}

void vdp_mode0_set_scale_ctrl(uint16_t ctrl)
{
    vdp_reg_write(VDP_MODE0_REG_SCALE_CTRL, (uint16_t)(ctrl & 0x00FFu));
}

void vdp_mode0_set_logic_size(uint16_t width, uint16_t height)
{
    vdp_reg_write(VDP_MODE0_REG_LOGIC_WIDTH, (uint16_t)(width & 0x07FFu));
    vdp_reg_write(VDP_MODE0_REG_LOGIC_HEIGHT, (uint16_t)(height & 0x07FFu));
}

void vdp_mode0_set_scale_mode(uint8_t scale_x, uint8_t scale_y, bool auto_center,
                              uint16_t width, uint16_t height)
{
    // Program dimensions first so the scaler never sees an out-of-date logic size.
    vdp_mode0_set_logic_size(width, height);
    vdp_mode0_set_scale_ctrl(vdp_mode0_scale_ctrl(scale_x, scale_y, auto_center));
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
    const uint16_t words[8] = {
        cfg->ctrl,
        (uint16_t)(cfg->bitmap_base & 0xFFFFu),
        (uint16_t)((cfg->bitmap_base >> 16) & 0xFFFFu),
        (uint16_t)(cfg->attr_base & 0xFFFFu),
        (uint16_t)((cfg->attr_base >> 16) & 0xFFFFu),
        cfg->bitmap_stride,
        cfg->attr_stride,
        cfg->height
    };
    vdp_mode0_write_block(VDP_MODE0_REG_BITMAP_CTRL, words, 8);
}

void vdp_mode0_set_bitmap_ctrl(uint16_t ctrl)
{
    vdp_reg_write(VDP_MODE0_REG_BITMAP_CTRL, ctrl);
}

void vdp_mode0_set_bitmap_base(uint32_t base)
{
    vdp_reg_write(VDP_MODE0_REG_BITMAP_BASE_LO, (uint16_t)(base & 0xFFFFu));
    vdp_reg_write(VDP_MODE0_REG_BITMAP_BASE_HI, (uint16_t)((base >> 16) & 0xFFFFu));
}

void vdp_mode0_set_attr_base(uint32_t base)
{
    vdp_reg_write(VDP_MODE0_REG_ATTR_BASE_LO, (uint16_t)(base & 0xFFFFu));
    vdp_reg_write(VDP_MODE0_REG_ATTR_BASE_HI, (uint16_t)((base >> 16) & 0xFFFFu));
}

void vdp_mode0_request_bitmap_swap(uint32_t bitmap_base, uint32_t attr_base)
{
    const uint16_t words[5] = {
        (uint16_t)(bitmap_base & 0xFFFFu),
        (uint16_t)((bitmap_base >> 16) & 0xFFFFu),
        (uint16_t)(attr_base & 0xFFFFu),
        (uint16_t)((attr_base >> 16) & 0xFFFFu),
        VDP_MODE0_BITMAP_SWAP_REQUEST
    };
    vdp_mode0_write_block(VDP_MODE0_REG_BITMAP_BASE_PENDING_LO, words, 5);
}

uint16_t vdp_mode0_read_bitmap_swap_ctrl(void)
{
    return vdp_reg_read(VDP_MODE0_REG_BITMAP_SWAP_CTRL);
}

void vdp_mode0_clear_bitmap_swap_committed(void)
{
    vdp_reg_write(VDP_MODE0_REG_BITMAP_SWAP_CTRL, VDP_MODE0_BITMAP_SWAP_COMMITTED);
}

void vdp_mode0_set_bitmap_stride(uint16_t stride)
{
    vdp_reg_write(VDP_MODE0_REG_BITMAP_STRIDE, stride);
}

void vdp_mode0_set_attr_stride(uint16_t stride)
{
    vdp_reg_write(VDP_MODE0_REG_ATTR_STRIDE, stride);
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

bool vdp_sprite_upload(uint8_t slot,
                       const uint16_t *pattern, uint16_t pattern_start, uint16_t pattern_pixels,
                       const uint32_t *palette, uint8_t palette_start, uint8_t palette_count,
                       const vdp_mode0_sprite_cfg_t *cfg)
{
    if (slot >= 32u) return false;

    /* 1. Optional palette upload */
    if (palette != NULL && palette_count > 0u) {
        uint8_t entry = palette_start;
        for (uint8_t i = 0; i < palette_count; ++i) {
            uint32_t rgb = palette[i];
            uint8_t r = (uint8_t)((rgb >> 16) & 0xFFu);
            uint8_t g = (uint8_t)((rgb >>  8) & 0xFFu);
            uint8_t b = (uint8_t)( rgb        & 0xFFu);
            vdp_mode0_palette_write_rgb888(entry, r, g, b);
            ++entry;
        }
    }

    /* 2. Pattern RAM upload */
    if (pattern != NULL && pattern_pixels > 0u) {
        vdp_mode0_set_pattern_ptr(pattern_start);
        for (uint16_t i = 0; i < pattern_pixels; ++i) {
            vdp_mode0_write_pattern_data(pattern[i] & 0x000Fu);
        }
    }

    /* 3. Sprite descriptor configuration */
    if (cfg != NULL) {
        vdp_mode0_set_sprite(slot, cfg);
    }

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

void vdp_mode0_set_planar_width(uint16_t width)
{
    vdp_reg_write(VDP_MODE0_REG_PLANAR_WIDTH, (uint16_t)(width & 0x03FFu));
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

```

---

## FILE 13 / 21: `vdp_mode0.h`

```h
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
#define VDP_MODE0_REG_L0_TRANS_KEY      0x0314u
#define VDP_MODE0_REG_L1_TRANS_KEY      0x0315u
#define VDP_MODE0_REG_L2_TRANS_KEY      0x0316u
#define VDP_MODE0_REG_L3_TRANS_KEY      0x0317u
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
#define VDP_MODE0_REG_BACKDROP_INDEX    0x0348u
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
#define VDP_MODE0_REG_BITMAP_BASE_PENDING_LO 0x0358u
#define VDP_MODE0_REG_BITMAP_BASE_PENDING_HI 0x0359u
#define VDP_MODE0_REG_ATTR_BASE_PENDING_LO   0x035Au
#define VDP_MODE0_REG_ATTR_BASE_PENDING_HI   0x035Bu
#define VDP_MODE0_REG_BITMAP_SWAP_CTRL       0x035Cu

#define VDP_MODE0_BITMAP_SWAP_REQUEST   0x0001u
#define VDP_MODE0_BITMAP_SWAP_COMMITTED 0x0002u

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
#define VDP_MODE0_REG_PLANAR_CTRL       0x0D4Au
#define VDP_MODE0_REG_PLANAR_WIDTH      0x0D4Bu
#define VDP_MODE0_PLANAR_CTRL_ENABLE    0x0001u

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
    uint8_t  bpp_sel;   // 2 bits (0..2): 0 = 4bpp, 1 = 2bpp, 2 = 1bpp
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
bool vdp_mode0_soft_reset(void);
void vdp_mode0_set_tile_mode(uint8_t mode);
void vdp_mode0_set_attr_mode(uint8_t mode);
void vdp_mode0_set_mode_select(uint16_t mode_select);
void vdp_mode0_set_trans_key(uint8_t layer, uint8_t key);
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
void vdp_mode0_set_backdrop_index(uint8_t index);
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
void vdp_mode0_request_bitmap_swap(uint32_t bitmap_base, uint32_t attr_base);
uint16_t vdp_mode0_read_bitmap_swap_ctrl(void);
void vdp_mode0_clear_bitmap_swap_committed(void);
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
void vdp_mode0_set_planar_width(uint16_t width);

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

```

---

## FILE 14 / 21: `vdp_palette_lut.c`

```c
/**
 * vdp_palette_lut.c — Per-platform palette LUT helpers.
 *
 * Each helper converts a platform-native palette value to RGB888 and
 * writes it through vdp_mode0_palette_write_rgb888().
 */
#include "vdp_palette_lut.h"
#include "vdp_mode0.h"

/* ------------------------------------------------------------------
 *  TMS9918A fixed palette
 *  Source: EP994A VHDL reference (kb/TMS9918/references/EP994A/tms9918.vhd)
 *  citing MSX.org forum consensus values.
 * ------------------------------------------------------------------ */
static const uint8_t tms9918_palette[16][3] = {
    /* 0  transparent */ {0x00, 0x00, 0x00},
    /* 1  black       */ {0x00, 0x00, 0x00},
    /* 2  medium green*/ {0x00, 0xF1, 0x14},
    /* 3  light green */ {0x44, 0xF9, 0x56},
    /* 4  dark blue   */ {0x55, 0x4F, 0xFF},
    /* 5  light blue  */ {0x80, 0x6F, 0xFF},
    /* 6  dark red    */ {0xFA, 0x50, 0x33},
    /* 7  cyan        */ {0x0C, 0xFF, 0xFF},
    /* 8  medium red  */ {0xFF, 0x51, 0x34},
    /* 9  light red   */ {0xFF, 0x73, 0x56},
    /* A  dark yellow */ {0xE2, 0xD2, 0x04},
    /* B  light yellow*/ {0xF2, 0xD9, 0x47},
    /* C  dark green  */ {0x04, 0xD4, 0x13},
    /* D  magenta     */ {0xE7, 0x50, 0xE5},
    /* E  gray        */ {0xD0, 0xD0, 0xD0},
    /* F  white       */ {0xFF, 0xFF, 0xFF},
};

void vdp_tms9918_load_palette(void)
{
    for (uint8_t i = 0; i < 16u; ++i) {
        vdp_mode0_palette_write_rgb888(i,
            tms9918_palette[i][0],
            tms9918_palette[i][1],
            tms9918_palette[i][2]);
    }
}

/* ------------------------------------------------------------------
 *  SMS 6-bit: --BBGGRR  (2 bits per channel)
 *  Expansion policy: replicate bits to fill 8 bits.
 *    2-bit -> 8-bit:  (v << 6) | (v << 4) | (v << 2) | v
 *    e.g. 0 -> 0, 1 -> 0x55, 2 -> 0xAA, 3 -> 0xFF
 * ------------------------------------------------------------------ */
void vdp_sms_palette_write(uint8_t idx, uint8_t native_val)
{
    uint8_t r = (native_val >> 0) & 0x03u;
    uint8_t g = (native_val >> 2) & 0x03u;
    uint8_t b = (native_val >> 4) & 0x03u;

    r = (r << 6) | (r << 4) | (r << 2) | r;
    g = (g << 6) | (g << 4) | (g << 2) | g;
    b = (b << 6) | (b << 4) | (b << 2) | b;

    vdp_mode0_palette_write_rgb888(idx, r, g, b);
}

/* ------------------------------------------------------------------
 *  Game Gear 12-bit: --------BBBBGGGGRRRR  (4 bits per channel)
 *  Expansion policy: replicate nibble to fill 8 bits.
 *    4-bit -> 8-bit:  (v << 4) | v
 *    e.g. 0 -> 0, 0xF -> 0xFF
 * ------------------------------------------------------------------ */
void vdp_gg_palette_write(uint8_t idx, uint16_t native_val)
{
    uint8_t r = (uint8_t)(native_val >> 0)  & 0x0Fu;
    uint8_t g = (uint8_t)(native_val >> 4)  & 0x0Fu;
    uint8_t b = (uint8_t)(native_val >> 8)  & 0x0Fu;

    r = (r << 4) | r;
    g = (g << 4) | g;
    b = (b << 4) | b;

    vdp_mode0_palette_write_rgb888(idx, r, g, b);
}

/* ------------------------------------------------------------------
 *  Atari ST 9-bit: 0000 0RRR 0GGG 0BBB  (3 bits per channel)
 *  Expansion policy: bit-replication to fill 8 bits.
 *    3-bit -> 8-bit:  (v << 5) | (v << 2) | (v >> 1)
 *    e.g. 0 -> 0, 7 -> 0xFF
 * ------------------------------------------------------------------ */
void vdp_atarist_palette_write(uint8_t idx, uint16_t native_val)
{
    uint8_t r = (uint8_t)(native_val >> 8) & 0x07u;
    uint8_t g = (uint8_t)(native_val >> 4) & 0x07u;
    uint8_t b = (uint8_t)(native_val >> 0) & 0x07u;

    r = (r << 5) | (r << 2) | (r >> 1);
    g = (g << 5) | (g << 2) | (g >> 1);
    b = (b << 5) | (b << 2) | (b >> 1);

    vdp_mode0_palette_write_rgb888(idx, r, g, b);
}

/* ------------------------------------------------------------------
 *  Atari STE 12-bit: 0000 Rrrr Gggg Bbbb  (4 bits per channel)
 *  Expansion policy: replicate nibble to fill 8 bits.
 * ------------------------------------------------------------------ */
void vdp_atariste_palette_write(uint8_t idx, uint16_t native_val)
{
    uint8_t r = (uint8_t)(native_val >> 8) & 0x0Fu;
    uint8_t g = (uint8_t)(native_val >> 4) & 0x0Fu;
    uint8_t b = (uint8_t)(native_val >> 0) & 0x0Fu;

    r = (r << 4) | r;
    g = (g << 4) | g;
    b = (b << 4) | b;

    vdp_mode0_palette_write_rgb888(idx, r, g, b);
}

```

---

## FILE 15 / 21: `vdp_palette_lut.h`

```h
/**
 * vdp_palette_lut.h — Per-platform palette LUT helpers.
 *
 * Converts platform-native palette values into RGB888 and writes them
 * through the generic Mode0 palette primitives. No FPGA changes needed.
 */
#ifndef VDP_PALETTE_LUT_H
#define VDP_PALETTE_LUT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------
 *  TMS9918A — fixed 16-color palette
 * ------------------------------------------------------------------ */

/** Load the canonical TMS9918A fixed palette into Mode0 palette RAM.
 *  Fills entries 0..15. Entry 0 is transparent (black).
 *  Source: EP994A VHDL reference (kb/TMS9918/references/EP994A/tms9918.vhd)
 *          citing MSX.org forum consensus values.
 */
void vdp_tms9918_load_palette(void);

/* ------------------------------------------------------------------
 *  Sega Master System — 6-bit CRAM  (--BBGGRR)
 * ------------------------------------------------------------------ */

/** Write one SMS palette entry.
 *  @param idx          Mode0 palette entry index (0..255)
 *  @param native_val   SMS CRAM byte: --BBGGRR (2 bits per channel)
 */
void vdp_sms_palette_write(uint8_t idx, uint8_t native_val);

/* ------------------------------------------------------------------
 *  Game Gear — 12-bit CRAM  (--------BBBBGGGGRRRR)
 * ------------------------------------------------------------------ */

/** Write one Game Gear palette entry.
 *  @param idx          Mode0 palette entry index (0..255)
 *  @param native_val   GG CRAM word: --------BBBBGGGGRRRR (4 bits per channel)
 */
void vdp_gg_palette_write(uint8_t idx, uint16_t native_val);

/* ------------------------------------------------------------------
 *  Atari ST  — 9-bit palette  (0000 0RRR 0GGG 0BBB)
 * ------------------------------------------------------------------ */

/** Write one Atari ST palette entry.
 *  @param idx          Mode0 palette entry index (0..255)
 *  @param native_val   ST palette word: 0000 0RRR 0GGG 0BBB (3 bits per channel)
 */
void vdp_atarist_palette_write(uint8_t idx, uint16_t native_val);

/* ------------------------------------------------------------------
 *  Atari STE — 12-bit palette  (0000 Rrrr Gggg Bbbb)
 * ------------------------------------------------------------------ */

/** Write one Atari STE palette entry.
 *  @param idx          Mode0 palette entry index (0..255)
 *  @param native_val   STE palette word: 0000 Rrrr Gggg Bbbb (4 bits per channel)
 */
void vdp_atariste_palette_write(uint8_t idx, uint16_t native_val);

#ifdef __cplusplus
}
#endif

#endif /* VDP_PALETTE_LUT_H */

```

---

## FILE 16 / 21: `vdp_platform.h`

```h
/**
 * vdp_platform.h — Board-specific pin map and constants for the VDP host
 *                  driver library (Task 39).
 *
 * Isolates Raspberry Pi Pico 2 (RP2350) + Tang Nano 20K specifics from
 * the rest of the library. Future multi-MCU support would provide an
 * alternate platform header without touching vdp_host / vdp_status /
 * vdp_upload bodies.
 */
#ifndef VDP_PLATFORM_H
#define VDP_PLATFORM_H

#if defined(PICO) || defined(ARDUINO_ARCH_RP2040) || defined(ARDUINO_RASPBERRY_PI_PICO)
#include "hardware/pio.h"

/* Pico 2 GPIO → Tang Nano 20K pin mapping (Task 27 full-quad-fidelity) */
#define VDP_PIN_SPI_SCK   8   /* Tang pin 41 */
#define VDP_PIN_SPI_CS_N  9   /* Tang pin 42 */
#define VDP_PIN_SPI_IO0  10   /* Tang pin 48 */
#define VDP_PIN_SPI_IO1  11   /* Tang pin 49 */
#define VDP_PIN_SPI_IO2  12   /* Tang pin 51 */
#define VDP_PIN_SPI_IO3  13   /* Tang pin 54 */

/* PIO unit + state-machine indices reserved for the legacy VDP SPI transport. */
#define VDP_SPI_PIO       pio0
#define VDP_SPI_SM_TX     0

#elif defined(CONFIG_IDF_TARGET_ESP32S3) || defined(ARDUINO_ESP32S3_DEV) || defined(ARDUINO_ESP32S3_DEV_KIT_C_1)
#include <Arduino.h>

#if defined(VDP_SPI_BACKEND_SPI2)

/* ESP32-S3 FSPI/IOMUX SPI host harness.
 *
 * This path is selected explicitly by including `vdp_host.h` before the
 * platform header. The default ESP32-S3 host remains i80.
 */
#define VDP_PIN_SPI_CS_N  10
#define VDP_PIN_SPI_SCK   12
#define VDP_PIN_SPI_IO0   11
#define VDP_PIN_SPI_IO1   13
#define VDP_PIN_SPI_IO2   14
#define VDP_PIN_SPI_IO3    9

#else

/* ESP32-S3-DevKitC-1 8-bit i80 host harness.
 *
 * i80 is the active ESP32-S3 backend. Include `vdp_i80.h` instead of
 * `vdp_host.h` only when intentionally building an i80 sketch.
 *
 *   D0..D7 GPIO4..11 -> Tang pins 25/26/27/28/29/30/31/41
 *   DC     GPIO15    -> Tang pin 85
 *   CS#    GPIO16    -> Tang pin 76
 *   WR#    GPIO17    -> Tang pin 77
 *   RD#    GPIO18    -> Tang pin 80
 */
#define VDP_PIN_I80_D0     4
#define VDP_PIN_I80_D1     5
#define VDP_PIN_I80_D2     6
#define VDP_PIN_I80_D3     7
#define VDP_PIN_I80_D4     8
#define VDP_PIN_I80_D5     9
#define VDP_PIN_I80_D6    10
#define VDP_PIN_I80_D7    11
#define VDP_PIN_I80_DC    15
#define VDP_PIN_I80_CS_N  16
#define VDP_PIN_I80_WR_N  17
#define VDP_PIN_I80_RD_N  18

#ifndef VDP_HOST_BACKEND_I80_GPIO
#define VDP_HOST_BACKEND_I80_GPIO 1
#endif

#endif

#elif defined(ESP32)
#include <Arduino.h>

/* ESP32 dev1 GPIO → Tang Nano 20K pin mapping (BronzeGate #8987) */
#define VDP_PIN_SPI_SCK   18
#define VDP_PIN_SPI_CS_N  19
#define VDP_PIN_SPI_IO0   23
#define VDP_PIN_SPI_IO1   22
#define VDP_PIN_SPI_IO2   25
#define VDP_PIN_SPI_IO3   27

#elif defined(ESP8266)
#include <Arduino.h>

/* ESP8266 NodeMCU 1.0 GPIO → Tang Nano 20K pin mapping (BronzeGate #9123) */
#define VDP_PIN_SPI_SCK   14   /* D5 */
#define VDP_PIN_SPI_CS_N  12   /* D6 */
#define VDP_PIN_SPI_IO0   13   /* D7 */
#define VDP_PIN_SPI_IO1    5   /* D1 */
#define VDP_PIN_SPI_IO2    4   /* D2 */
#define VDP_PIN_SPI_IO3   16   /* D0 - RTC pad, needs digitalWrite */

#else
#error "Unsupported platform for libvdp"
#endif

/* SCK frequency policy on legacy SPI/SPI2 backends (bench-validated
 * 2026-05-23 via the throughput sweep sketch on FSPI IOMUX pins 9..14):
 *
 *   - Reads (READ_STATUS, sticky status, etc.): FPGA QspiSlave response FSM
 *     caps cleanly at 3 MHz. Above 3 MHz, reads fail 100% binary (likely
 *     pixel-clock bound on the FPGA side). Read throughput is CPU-overhead-
 *     bound anyway (~103 µs per call) so SCK rate doesn't matter for reads.
 *
 *   - Writes (REG_WRITE, SDRAM_WRITE): bench-clean at 80 MHz (IOMUX max).
 *     However, Phase 1A physical constraints (25.2 MHz oversampler) dictate
 *     a strict Nyquist ceiling of 12.6 MHz, and the current wiring shows
 *     intermittent byte/nibble corruption at 8 MHz (QSPI-SI-CEILING-183).
 *     The maximum stable production write speed is therefore capped at 4 MHz.
 *
 * Default = 3 MHz so first-call READ_STATUS magic works out of the box.
 * Sketches doing bulk uploads should call:
 *
 *     vdp_host_set_speed_hz(4000000u);   // before write-heavy section
 *     ...
 *     vdp_host_set_speed_hz( 3000000u);   // before next read
 *
 * Bit-bang platforms (ESP8266 / legacy ESP32) keep their canonical 2 MHz
 * cadence — the set_speed_hz call is a no-op there. */
#if defined(VDP_SPI_BACKEND_SPI2)
#define VDP_SPI_SCK_HZ    3000000u    /* boot/read default */
#define VDP_SPI_SCK_WRITE_HZ 4000000u  /* firmware physical cap */
#else
#define VDP_SPI_SCK_HZ    2000000u
#endif

/* Host-neutral aliases. The VDP_SPI_* names remain ABI/source-compatible
 * for legacy sketches and platform branches. New code should prefer these
 * VDP_HOST_* names unless it explicitly targets the SPI backend. */
#if defined(VDP_PIN_SPI_SCK) && !defined(VDP_PIN_HOST_SCK)
#define VDP_PIN_HOST_SCK   VDP_PIN_SPI_SCK
#endif
#if defined(VDP_PIN_SPI_CS_N) && !defined(VDP_PIN_HOST_CS_N)
#define VDP_PIN_HOST_CS_N  VDP_PIN_SPI_CS_N
#endif
#if defined(VDP_PIN_SPI_IO0) && !defined(VDP_PIN_HOST_IO0)
#define VDP_PIN_HOST_IO0   VDP_PIN_SPI_IO0
#endif
#if defined(VDP_PIN_SPI_IO1) && !defined(VDP_PIN_HOST_IO1)
#define VDP_PIN_HOST_IO1   VDP_PIN_SPI_IO1
#endif
#if defined(VDP_PIN_SPI_IO2) && !defined(VDP_PIN_HOST_IO2)
#define VDP_PIN_HOST_IO2   VDP_PIN_SPI_IO2
#endif
#if defined(VDP_PIN_SPI_IO3) && !defined(VDP_PIN_HOST_IO3)
#define VDP_PIN_HOST_IO3   VDP_PIN_SPI_IO3
#endif
#if defined(VDP_SPI_PIO) && !defined(VDP_HOST_PIO)
#define VDP_HOST_PIO       VDP_SPI_PIO
#endif
#if defined(VDP_SPI_SM_TX) && !defined(VDP_HOST_SM_TX)
#define VDP_HOST_SM_TX     VDP_SPI_SM_TX
#endif
#if defined(VDP_SPI_SCK_HZ) && !defined(VDP_HOST_SCK_HZ)
#define VDP_HOST_SCK_HZ    VDP_SPI_SCK_HZ
#endif
#if defined(VDP_SPI_SCK_WRITE_HZ) && !defined(VDP_HOST_SCK_WRITE_HZ)
#define VDP_HOST_SCK_WRITE_HZ VDP_SPI_SCK_WRITE_HZ
#endif

#endif /* VDP_PLATFORM_H */

```

---

## FILE 17 / 21: `vdp_qspi.h`

```h
/**
 * vdp_qspi.h — Deprecated compatibility header.
 *
 * New firmware should include `vdp_host.h` and call `vdp_host_init()`.
 * This shim preserves legacy sketches that still include `vdp_qspi.h`
 * or call `vdp_qspi_init()`.
 */
#ifndef VDP_QSPI_H
#define VDP_QSPI_H

#include "vdp_host.h"

#endif /* VDP_QSPI_H */

```

---

## FILE 18 / 21: `vdp_status.c`

```c
/**
 * vdp_status.c — Status polling + sticky bit helpers.
 */
#include "vdp_status.h"
#include "vdp_host.h"
#include "vdp_platform.h"

#if defined(PICO) || defined(ARDUINO_ARCH_RP2040) || defined(ARDUINO_RASPBERRY_PI_PICO)
#include "pico/stdlib.h"
#elif defined(ARDUINO)
#include <Arduino.h>
#endif

void vdp_clear_sticky(uint16_t mask)
{
    vdp_reg_write(0x0320u, mask);
}

bool vdp_wait_sticky(uint16_t bit_mask, uint32_t timeout_us)
{
    while (true) {
        uint32_t s = vdp_read_status(5);
        if ((s & bit_mask) == bit_mask) return true;
        if (timeout_us == 0) return false;
        uint32_t step = (timeout_us < 50u) ? timeout_us : 50u;
#if defined(PICO) || defined(ARDUINO_ARCH_RP2040) || defined(ARDUINO_RASPBERRY_PI_PICO)
        busy_wait_us_32(step);
#else
        delayMicroseconds(step);
#endif
        timeout_us -= step;
    }
}

bool vdp_wait_vblank(uint32_t timeout_us)
{
    vdp_clear_sticky(VDP_STICKY_RASTER_MATCH);
    return vdp_wait_sticky(VDP_STICKY_RASTER_MATCH, timeout_us);
}

```

---

## FILE 19 / 21: `vdp_status.h`

```h
/**
 * vdp_status.h — Status polling + sticky bit helpers.
 *
 * Builds on the host transport (READ_STATUS sel=5 = sticky status bank,
 * write-to-0x0320 = clear-1-to-clear). Sticky bit mapping (low byte):
 *   bit 0 RASTER_MATCH   — fires at the raster trigger line
 *   bit 1 SPRITE_OVERFLOW
 *   bit 2 HOST_READY      — pulses on every accepted host command
 *   bit 3 HOST_ERROR      — level-high while host last_error != 0
 */
#ifndef VDP_STATUS_H
#define VDP_STATUS_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VDP_STICKY_RASTER_MATCH    0x0001
#define VDP_STICKY_SPRITE_OVERFLOW 0x0002
#define VDP_STICKY_HOST_READY      0x0004
#define VDP_STICKY_HOST_ERROR      0x0008
#define VDP_STICKY_QSPI_READY      VDP_STICKY_HOST_READY
#define VDP_STICKY_QSPI_ERROR      VDP_STICKY_HOST_ERROR
/* Task 29 — sprite collision flags (write-1-to-clear @ 0x0320). */
#define VDP_STICKY_SPRITE_0_HIT    0x0010  /* slot-0 non-transparent over non-transparent BG */
#define VDP_STICKY_SPRITE_BG_HIT   0x0020  /* any sprite non-transparent over non-transparent BG */

/* Task 47/49 — DMA/Blitter done sticky bits */
#define VDP_STICKY_DMA_DONE        0x0100  /* DMA transfer complete */
#define VDP_STICKY_BLIT_DONE       0x0200  /* Blitter block transfer complete */

/* Task 51 — MODE_SELECT committed */
#define VDP_STICKY_MODE_SELECT_CHANGED 0x0800  /* MODE_SELECT latched at V=0 */

/**
 * Block until `bit_mask` bits are all set in the sticky register, or
 * timeout expires. Polls sel=5 with 50 µs wait between polls.
 * @param bit_mask    OR of VDP_STICKY_* flags to wait for
 * @param timeout_us  absolute wait limit; 0 = no wait, returns current state
 * @return true if all requested bits observed set within timeout
 */
bool vdp_wait_sticky(uint16_t bit_mask, uint32_t timeout_us);

/**
 * Convenience wrapper for vblank sync: clears RASTER_MATCH then waits
 * for it to re-assert (next vblank entry). Suitable for host-paced
 * SDRAM upload loops.
 * @param timeout_us absolute wait limit (one frame = ~16700 µs at 60 Hz)
 * @return true if vblank reached within timeout
 */
bool vdp_wait_vblank(uint32_t timeout_us);

/**
 * Write-1-to-clear the sticky register. Bits set in `mask` are cleared;
 * bits set to 0 in `mask` are preserved.
 */
void vdp_clear_sticky(uint16_t mask);

#ifdef __cplusplus
}
#endif

#endif /* VDP_STATUS_H */

```

---

## FILE 20 / 21: `vdp_upload.c`

```c
/**
 * vdp_upload.c — vblank-paced SDRAM upload.
 *
 * Strategy proven by Task 34 Checkpoint C (#7704 / commit 222c1c0):
 * each burst is one SDRAM_WRITE transaction paced to vblank, so we can
 * amortize the header cost across a small contiguous chunk. Between
 * bursts we re-sync to the next vblank via vdp_wait_vblank() to avoid
 * the active-video single-byte-latch race inside QspiSdramBridge.
 *
 * Practical tuning note:
 * - Faster SCK shortens the wire-time of each chunk, but the safe chunk
 *   size is still bounded by the vblank window and the bridge's small
 *   buffering margin.
 * - Keep `VDP_UPLOAD_WORDS_PER_VBLANK` conservative unless the active
 *   host clock and capture target have both been revalidated together.
 */
#include "vdp_upload.h"
#include "vdp_host.h"
#include "vdp_status.h"

bool vdp_upload_asset(uint32_t sdram_addr, const uint16_t *words,
                      uint16_t num_words, vdp_upload_cb cb)
{
    const uint32_t vblank_timeout_us = 20000u;   /* one frame margin */
    uint16_t sent = 0;

    while (sent < num_words) {
        if (!vdp_wait_vblank(vblank_timeout_us)) return false;

        uint16_t chunk = VDP_UPLOAD_WORDS_PER_VBLANK;
        if ((uint32_t)sent + chunk > num_words) chunk = num_words - sent;

        /* Send each vblank slice as one contiguous SDRAM_WRITE burst.
         * This keeps the same pacing model while amortizing the command
         * header and CS turn-around across more payload bytes. */
        vdp_sdram_write(sdram_addr + (uint32_t)sent * 2u, &words[sent], chunk);

        sent += chunk;
        if (cb) cb(sent, num_words);
    }

    return true;
}

```

---

## FILE 21 / 21: `vdp_upload.h`

```h
/**
 * vdp_upload.h — vblank-paced SDRAM asset upload.
 *
 * Chunks a word stream into bursts small enough to fit inside a single
 * ~1.4 ms vblank window, syncs each burst to the raster trigger, and
 * optionally calls back into application code for progress tracking.
 */
#ifndef VDP_UPLOAD_H
#define VDP_UPLOAD_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Progress callback signature. Invoked once after each burst completes.
 * Host code must NOT issue nested host-transport transactions from within the callback
 * (re-entrancy is not supported). Callback latency directly reduces the
 * usable vblank window — prefer lightweight logging only.
 */
typedef void (*vdp_upload_cb)(uint16_t words_sent, uint16_t words_total);

/**
 * Stream `num_words` 16-bit words into SDRAM starting at `sdram_addr`,
 * pacing the transfer to land each burst inside a vblank window.
 *
 *   default burst = VDP_UPLOAD_WORDS_PER_VBLANK (16, still comfortably
 *   within the 1.4 ms vblank budget while halving header overhead vs. the
 *   previous 8-word pacing).
 *
 * @param sdram_addr target SDRAM byte address (24-bit)
 * @param words      pointer to little-endian 16-bit words (host-owned,
 *                   must remain valid for the full call duration)
 * @param num_words  total word count
 * @param cb         optional progress callback (may be NULL)
 * @return true if all words transmitted (does NOT guarantee SDRAM
 *         commit — call vdp_wait_sticky for HOST_ERROR to check);
 *         false if a vblank timeout occurred mid-upload
 */
bool vdp_upload_asset(uint32_t sdram_addr, const uint16_t *words,
                      uint16_t num_words, vdp_upload_cb cb);

#define VDP_UPLOAD_WORDS_PER_VBLANK 16u

#ifdef __cplusplus
}
#endif

#endif /* VDP_UPLOAD_H */

```

---

