/**
 * BronzeGate Hardware Probe (esp32s3_sdram_probe)
 * 
 * Target: ESP32-S3 (NodeMCU-like dev board)
 * Purpose: Interactive UART probe to execute the hardware verification matrix
 *          against the Tang Nano 20K VDP.
 * Commands:
 *   w <addr_hex> <dword_hex> : Raw SDRAM write (Task 34 pipeline)
 *   r <addr_hex>             : Arm/read debug dword via 0x0326/0x0327, sel=8
 *   s <hz>                   : Set QSPI speed (500000, 3000000, 8000000)
 *   t                        : Run sentinel/tile cross-contamination matrix
 */

#include "vdp_platform.h"
#include "vdp_qspi.h"
#include "vdp_mode0.h"
#include "vdp_upload.h"

uint32_t current_qspi_speed = VDP_QSPI_SCK_HZ;

void setup() {
    Serial.begin(115200);
    delay(2000); // Wait for terminal
    
    Serial.println("\n\n=== BronzeGate HW Probe ===");
    Serial.println("Commands:");
    Serial.println("  w <addr> <data> : Write DWORD to SDRAM (hex)");
    Serial.println("  r <addr>        : Read DWORD from SDRAM (hex)");
    Serial.println("  s <hz>          : Set QSPI speed (500000, 3000000, 8000000)");
    Serial.println("  t               : Run HW validation matrix");
    
    // Initialize libvdp
    vdp_qspi_init();
    vdp_qspi_set_speed_hz(current_qspi_speed);
    Serial.printf("QSPI Initialized at %u Hz\n", current_qspi_speed);
}

void do_read(uint32_t addr) {
    // Arm the debug read registers (0x0326/0x0327)
    // 0x0326: [15:0] = Addr[15:0]
    // 0x0327: [6:0]  = Addr[22:16], [15] = Trigger (Arm)
    
    uint16_t addr_lo = addr & 0xFFFF;
    uint16_t addr_hi = (addr >> 16) & 0x007F;
    
    vdp_reg_write(0x0326, addr_lo);
    vdp_reg_write(0x0327, addr_hi | 0x8000); // Arm
    
    // Wait for read to complete (conservative)
    delay(1);
    
    // Read the result back via READ_STATUS sel=8
    uint32_t val = vdp_read_status(8);
    Serial.printf("READ  [%06X] = %08X\n", addr, val);
}

void do_write(uint32_t addr, uint32_t data) {
    // Use the optimized vdp_upload_asset function which uses the SDRAM_WRITE opcode
    uint16_t buffer[2] = { (uint16_t)(data & 0xFFFF), (uint16_t)(data >> 16) };
    if (vdp_upload_asset(addr, buffer, 2, NULL)) {
        Serial.printf("WRITE [%06X] = %08X\n", addr, data);
    } else {
        Serial.println("WRITE FAILED (Timeout/Busy)");
    }
}

void do_matrix() {
    Serial.println("\n--- RUNNING HW MATRIX ---");
    Serial.printf("Speed: %u Hz\n", current_qspi_speed);
    
    // Sentinel @ 0xB000
    uint32_t sent_addr = 0x00B000;
    uint32_t sent_data = 0x22221111;
    do_write(sent_addr, sent_data);
    
    // Tile (White) @ 0xA000 (32 dwords)
    uint32_t tile_addr = 0x00A000;
    uint32_t tile_data = 0x0000FFFF;
    Serial.printf("Writing 32x %08X to %06X...\n", tile_data, tile_addr);
    for (int i=0; i<32; i++) {
        do_write(tile_addr + (i*4), tile_data);
    }
    
    // Readback verification
    Serial.println("\nVerifying...");
    
    // Check sentinel
    do_read(sent_addr);
    
    // Check tile sample
    do_read(tile_addr);
    do_read(tile_addr + 4);
    do_read(tile_addr + 124);
    
    Serial.println("--- MATRIX COMPLETE ---\n");
}

void loop() {
    if (Serial.available()) {
        String cmd = Serial.readStringUntil('\n');
        cmd.trim();
        if (cmd.length() == 0) return;
        
        char op = cmd.charAt(0);
        
        if (op == 's') {
            uint32_t hz = cmd.substring(2).toInt();
            // Clamp to physical ceiling
            if (hz > VDP_QSPI_SCK_WRITE_HZ) {
                hz = VDP_QSPI_SCK_WRITE_HZ;
                Serial.printf("Clamped to physical ceiling: %u Hz\n", hz);
            }
            current_qspi_speed = hz;
            vdp_qspi_set_speed_hz(current_qspi_speed);
            Serial.printf("Speed set to %u Hz\n", current_qspi_speed);
        }
        else if (op == 'r') {
            uint32_t addr = strtoul(cmd.substring(2).c_str(), NULL, 16);
            do_read(addr);
        }
        else if (op == 'w') {
            int space = cmd.indexOf(' ', 2);
            if (space > 0) {
                uint32_t addr = strtoul(cmd.substring(2, space).c_str(), NULL, 16);
                uint32_t data = strtoul(cmd.substring(space + 1).c_str(), NULL, 16);
                do_write(addr, data);
            }
        }
        else if (op == 't') {
            do_matrix();
        }
        else {
            Serial.println("Unknown command.");
        }
    }
}
