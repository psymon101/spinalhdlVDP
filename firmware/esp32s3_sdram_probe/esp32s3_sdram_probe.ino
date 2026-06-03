/**
 * BronzeGate Hardware Probe (esp32s3_sdram_probe)
 * 
 * Target: ESP32-S3 (NodeMCU-like dev board)
 * Purpose: Interactive UART probe to execute the hardware verification matrix
 *          against the Tang Nano 20K VDP.
 */

#include "vdp_platform.h"
#include "vdp_qspi.h"
#include "vdp_mode0.h"
#include "vdp_upload.h"

uint32_t current_qspi_speed = VDP_QSPI_SCK_HZ;

void select_read_speed() {
    // Fixed-rate diagnostic: never tear down/re-add SPI2 after initialization.
}

void apply_qspi_speed() {
    vdp_qspi_set_speed_hz(current_qspi_speed);
}

void do_status() {
    select_read_speed();
    uint32_t magic = vdp_read_status(0);
    uint32_t err = vdp_read_status(4);
    uint32_t sticky = vdp_read_status(5);
    Serial.printf("STATUS: magic=%08X last_err=%02X sticky=%08X\n", magic, err, sticky);
}

void setup() {
    Serial.begin(115200);
    delay(2000); // Wait for terminal
    
    Serial.println("\n\n=== BronzeGate HW Probe ===");
    Serial.println("Commands:");
    Serial.println("  w <addr> <data> : Write DWORD to SDRAM (hex)");
    Serial.println("  r <addr>        : Read DWORD from SDRAM (hex)");
    Serial.println("  s <hz>          : Set diagnostic rate (1000000 or 3000000)");
    Serial.println("  u               : Raw upload status (READ_STATUS sel=6)");
    Serial.println("  b <addr> <count> <data> : No-poll DWORD burst to SDRAM");
    Serial.println("  f               : Flush SDRAM FIFOs");
    Serial.println("  t               : Run HW data-path matrix; may set sticky uploadError");
    Serial.println("                    in tight-poll mode. Use w+u pacing for status-clean checks.");
    
    // Initialize libvdp
    vdp_qspi_init();
    apply_qspi_speed();
    Serial.printf("QSPI read-safe=%u Hz write-selected=%u Hz cap=%u Hz\n",
                  VDP_QSPI_SCK_HZ, current_qspi_speed, VDP_QSPI_SCK_WRITE_HZ);
}

void do_read(uint32_t addr) {
    uint16_t addr_lo = addr & 0xFFFF;
    uint16_t addr_hi = (addr >> 16) & 0x007F;
    select_read_speed();
    vdp_reg_write(0x0326, addr_lo);
    vdp_reg_write(0x0327, addr_hi | 0x8000); // Arm
    delay(1);
    uint32_t val = vdp_read_status(8);
    Serial.printf("READ  [%06X] = %08X @ %u Hz\n", addr, val, current_qspi_speed);
}

void do_write(uint32_t addr, uint32_t data) {
    uint16_t buffer[2] = { (uint16_t)(data & 0xFFFF), (uint16_t)(data >> 16) };
    if (vdp_upload_asset(addr, buffer, 2, NULL)) {
        Serial.printf("WRITE [%06X] = %08X @ %u Hz\n", addr, data, current_qspi_speed);
    } else {
        Serial.println("WRITE FAILED (Timeout/Busy)");
    }
    select_read_speed();
}

void do_upload_status() {
    uint32_t upload = vdp_read_status(6);
    uint32_t err = vdp_read_status(4);
    uint32_t sticky = vdp_read_status(5);
    Serial.printf("UPLOAD_STATUS: upload=%08X last_err=%08X sticky=%08X @ %u Hz\n",
                  upload, err, sticky, current_qspi_speed);
}

bool poll_upload_clear(const char *label) {
    const uint32_t timeout_ms = 100;
    const uint32_t start = millis();
    uint32_t upload = 0;
    do {
        upload = vdp_read_status(6);
        if ((upload & 0x1u) == 0) {
            Serial.printf("POLL  %-8s upload=%08X last_err=%08X CLEAR\n",
                          label, upload, vdp_read_status(4));
            return true;
        }
    } while ((millis() - start) < timeout_ms);
    Serial.printf("POLL  %-8s upload=%08X last_err=%08X TIMEOUT\n",
                  label, upload, vdp_read_status(4));
    return false;
}

void do_flush() {
    Serial.println("Flushing SDRAM FIFOs (256 zeros)...");
    uint16_t zeros[128] = {0};
    vdp_upload_asset(0, zeros, 128, NULL);
    vdp_upload_asset(0, zeros, 128, NULL);
    Serial.println("Flush complete.");
}

void do_burst(uint32_t addr, uint16_t count, uint32_t data) {
    uint16_t buffer[2] = { (uint16_t)(data & 0xFFFF), (uint16_t)(data >> 16) };
    Serial.printf("BURST start=%06X count=%u data=%08X @ %u Hz (NO per-tile poll)\n",
                  addr, count, data, current_qspi_speed);
    for (uint16_t i = 0; i < count; i++) {
        uint32_t tile_addr = addr + (uint32_t)i * 4u;
        vdp_sdram_write(tile_addr, buffer, 2);
        Serial.printf("BURST_WRITE [%02u] [%06X] = %08X\n", i, tile_addr, data);
    }
    poll_upload_clear("burst");
    do_upload_status();
}

void do_matrix() {
    Serial.println("\n--- RUNNING HW MATRIX ---");
    Serial.println("NOTE: t verifies data path under tight polling. CP-A5 hardware can false-fire");
    Serial.println("      sel=6 bit2 in this mode; use manual w+u pacing for status-clean checks.");
    uint32_t sent_addr = 0x00B000;
    uint32_t sent_data = 0x22221111;
    do_write(sent_addr, sent_data);
    poll_upload_clear("sentinel");
    
    uint32_t tile_addr = 0x00A000;
    uint32_t tile_data = 0x0000FFFF;
    Serial.printf("Writing 32x %08X to %06X...\n", tile_data, tile_addr);
    for (int i=0; i<32; i++) {
        char label[9];
        snprintf(label, sizeof(label), "tile[%02d]", i);
        do_write(tile_addr + (i*4), tile_data);
        if (!poll_upload_clear(label)) break;
    }
    
    Serial.println("\nVerifying...");
    do_read(sent_addr);
    for (int i=0; i<32; i++) {
        do_read(tile_addr + (i*4));
    }
    do_read(sent_addr);
    Serial.println("--- MATRIX COMPLETE ---\n");
}

void loop() {
    if (Serial.available()) {
        String cmd = Serial.readStringUntil('\n');
        cmd.trim();
        if (cmd.length() == 0) return;
        char op = cmd.charAt(0);
        if (op == 's') {
            if (cmd.length() == 1) {
                do_status();
                return;
            }
            uint32_t hz = cmd.substring(2).toInt();
            if (hz != 1000000u && hz != VDP_QSPI_SCK_HZ) {
                Serial.println("Diagnostic rate: use 1000000 or 3000000.");
                return;
            }
            current_qspi_speed = hz;
            apply_qspi_speed();
            Serial.printf("Write speed selected: %u Hz\n", current_qspi_speed);
        }
        else if (op == 'u') {
            do_upload_status();
        }
        else if (op == 'f') {
            do_flush();
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
        else if (op == 'b') {
            int space1 = cmd.indexOf(' ', 2);
            int space2 = (space1 > 0) ? cmd.indexOf(' ', space1 + 1) : -1;
            if (space1 > 0 && space2 > 0) {
                uint32_t addr = strtoul(cmd.substring(2, space1).c_str(), NULL, 16);
                uint16_t count = (uint16_t)strtoul(cmd.substring(space1 + 1, space2).c_str(), NULL, 0);
                uint32_t data = strtoul(cmd.substring(space2 + 1).c_str(), NULL, 16);
                do_burst(addr, count, data);
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
