# spinalhdlVDP Project Vision and Goals

**Purpose:**  
The purpose of the `spinalhdlVDP` project is to develop a robust, open-source Video Display Processor (VDP) using SpinalHDL, targeted initially at the Tang Nano 20K FPGA with HDMI output. 

**Core Goal:**  
Our primary goal is to build a highly capable, generic `Mode0` rendering substrate. This substrate provides universal, high-performance graphics primitives without embedding platform-specific legacy quirks into the silicon. 

## Key Architectural Principles

### 1. Generic RTL Core (RTL Agnosticism)
The VDP RTL is a purely generic graphics IP. It implements universal features like SDRAM-backed tiles, planar graphics, bitmaps, affine textures, sprites, scrolling, Copper coprocessing, and HDMA.
- **No Adapters:** Platform-specific register shims (e.g. C64, ZX Spectrum) must not exist in the RTL tree.
- **No Hardcoding:** Platform-specific palettes, Copper programs, or scenario branches must not be hardcoded in production bitstreams.
- **Exception:** Test-only scenarios may exist in archived commits but must not pollute the main generic bitstream.

### 2. Firmware Personality
All platform "personality" resides entirely in the host-side firmware (`libvdp`).
- **Scope:** This includes register translation, initialization sequences, and asset uploads.
- **Quirk Isolation:** Platform-specific quirks are handled by the host library translating to generic Mode0 register writes.

### 3. Host Agnosticism
While the current canonical deployment uses an **8-bit parallel i80 bus** driven by an ESP32-S3, the architecture remains decoupled from the specific transport layer.
- **Scalability:** This decoupled design allows the VDP to scale across different microcontrollers and interfaces (e.g., the legacy QSPI path).

### 4. Hardware-Proven Reliability (The Flash Gate)
Every feature must be proven on actual silicon (Tang Nano 20K).
- **Mandatory Simulation:** Rigorous simulation is required before a hardware flash is authorized.
- **Transport Canary Mandate:** The v1 transport canary (a 16×16 bright-cyan block at specific coordinates) must remain in the production bitstream. It acts as an independent path verification. Any modification or removal of the canary must be explicitly flagged and requires a numeric OpenCV-derived hardware proof.

By maintaining a strict separation between the universal hardware rendering core and the software-defined platform personality, `spinalhdlVDP` aims to be a flexible, extensible foundation for both retro-computing implementations and modern embedded graphics applications.