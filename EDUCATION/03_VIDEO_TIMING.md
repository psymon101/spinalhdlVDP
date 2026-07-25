# Video Timing Deep Dive: VGA and HDMI Standards

This document explains the principles of video raster timing and details the specific timing implementation inside the VDP Mode0 architecture.

---

## 1. Video Raster Scan Principles

A video controller generates a sequential stream of pixels representing a 2D image. Displays read this stream sequentially, line-by-line, from left to right, top to bottom. 

To organize this stream, three sync components are added to the video data:
1. **Pixel Clock (`clk_pixel`)**: Coordinates individual pixel transmissions.
2. **Horizontal Sync (`hsync`)**: Tells the display when a line ends and a new line begins.
3. **Vertical Sync (`vsync`)**: Tells the display when a frame ends and the scan resets to the top-left of the screen.

### The Raster Scanline Structure
```
Horizontal Scanline:
┌─────────────────────────────────────────────────────────────┐
│  Active Video  │  Front Porch  │  Sync Pulse  │  Back Porch │
│   (Pixels visible)  │ (Blanking)    │ (Sync trigger)│ (Blanking) │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. VESA DMT 640×480 @ 60Hz Specification

The VDP Mode0 baseline outputs standard **640×480 resolution at 60 Hz** refresh rate. The parameters are defined by the VESA Discrete Monitor Timing (DMT) standard:

### Horizontal Timing Parameters (in pixel clock cycles)
* **Active Display**: 640 cycles
* **Front Porch**: 16 cycles
* **Sync Width**: 96 cycles
* **Back Porch**: 48 cycles
* **Total Line Width (`hTotal`)**: 800 cycles

### Vertical Timing Parameters (in lines)
* **Active Display**: 480 lines
* **Front Porch**: 10 lines
* **Sync Width**: 2 lines
* **Back Porch**: 33 lines
* **Total Frame Height (`vTotal`)**: 525 lines

### Timing Calculations
* **Pixel Clock**: 
  $$\text{Pixel Clock} = \text{hTotal} \times \text{vTotal} \times \text{Refresh Rate} = 800 \times 525 \times 60\text{ Hz} = 25.20\text{ MHz}$$
* **Polarity**: Both `hsync` and `vsync` are active-low (negative polarity).

---

## 3. SpinalHDL Implementation

The timing generator inside [VdpTop.scala](file:///home/itadmin/github/spinalhdlVDP/hw/spinal/spinalhdlvdp/VdpTop.scala#L256-L289) runs continuously on the `clk_pixel` domain. It maintains two counters, `hCounter` and `vCounter`.

```scala
// From VdpTop.scala Timing Generator
val hCounter = Reg(UInt(10 bits)) init 0
val vCounter = Reg(UInt(10 bits)) init 0

// Horizontal Counter Logic
when(hCounter === hTotal - 1) {
  hCounter := 0
  // Vertical Counter Logic
  when(vCounter === vTotal - 1) {
    vCounter := 0
  } otherwise {
    vCounter := vCounter + 1
  }
} otherwise {
  hCounter := hCounter + 1
}

// Generate Syncs & Blanking (Active Low)
io.hsync := !(hCounter >= hSyncStart && hCounter < hSyncEnd)
io.vsync := !(vCounter >= vSyncStart && vCounter < vSyncEnd)
val displayEnable = (hCounter < hActive) && (vCounter < vActive)
```

### Constant Bindings
* `hSyncStart` = $640 + 16 = 656$
* `hSyncEnd` = $656 + 96 = 752$
* `vSyncStart` = $480 + 10 = 490$
* `vSyncEnd` = $490 + 2 = 492$

---

## 4. HDMI and TMDS Serialization

HDMI uses **TMDS (Transition Minimized Differential Signaling)** to transmit video data. 
* A TMDS encoder converts 8-bit color channels (Red, Green, Blue) into transition-minimized 10-bit symbols.
* It also encodes control signals (`hsync`, `vsync`) into 10-bit symbols during the blanking intervals (when `displayEnable` is False).

### Clocking Architecture
In the Tang Nano 20K top-level wrapper ([TopTang20kHdmi.scala](file:///home/itadmin/github/spinalhdlVDP/fpga/tang20k/tang20k_sdram_pll.v)), clocks are generated as follows:
1. **rPLL Primitive**: Multiplies the onboard 27 MHz reference oscillator up to **126 MHz** (`clk_x5`).
2. **Gowin Clkdiv Primitive**: Divides the 126 MHz clock by 5 to generate the stable **25.2 MHz** pixel clock (`clk_pixel`).
3. **TMDS Serializer**: Serializes the 10-bit symbols using a 5:1 ratio operating on double-data-rate (DDR) registers driven by the 126 MHz serial clock (`clk_x5`).

```
                         Gowin PLL / Clock Network
                      ┌──────────────────────────────┐
                      │                              │──→ 126 MHz (clk_x5) ──→ [TMDS Serializer]
[27 MHz Osc] ──(Pin)──┤ rPLL (Multiplies by 4.666)   │
                      │                              │──→ Clkdiv (÷5) ──→ 25.2 MHz (clk_pixel) ──→ [VDP Logic]
                      └──────────────────────────────┘
```
