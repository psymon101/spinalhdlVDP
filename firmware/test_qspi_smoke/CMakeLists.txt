cmake_minimum_required(VERSION 3.13)

# Pico SDK init — expects PICO_SDK_PATH in the environment.
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(test_qspi_smoke C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Target the Pico 2 (RP2350).
set(PICO_BOARD pico2)

pico_sdk_init()

add_executable(test_qspi_smoke
    test_qspi_smoke.c
)

pico_generate_pio_header(test_qspi_smoke
    ${CMAKE_CURRENT_SOURCE_DIR}/qspi_quad.pio
)

target_link_libraries(test_qspi_smoke
    pico_stdlib
    hardware_pio
    hardware_gpio
    hardware_clocks
)

# UART stdio over the Pico's default USB-UART pins (optional; helpful
# for bring-up diagnostics).
pico_enable_stdio_usb(test_qspi_smoke 1)
pico_enable_stdio_uart(test_qspi_smoke 0)

# Produce the .uf2 for drag-and-drop flashing (plus .elf/.bin/.hex).
pico_add_extra_outputs(test_qspi_smoke)
