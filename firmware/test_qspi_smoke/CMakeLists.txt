cmake_minimum_required(VERSION 3.13)

# Pico SDK init — expects PICO_SDK_PATH in the environment.
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(test_qspi_smoke C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Target the Pico 2 (RP2350).
set(PICO_BOARD pico2)

pico_sdk_init()

# Task 39: host driver library. Provides vdp_qspi / vdp_status /
# vdp_upload + the qspi_quad.pio program header.
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../libvdp libvdp_build)

add_executable(test_qspi_smoke
    test_qspi_smoke.c
)

target_link_libraries(test_qspi_smoke
    libvdp
)

# stdio routing — target's native USB + debug-probe UART→CDC on /dev/ttyACM0.
pico_enable_stdio_usb(test_qspi_smoke 1)
pico_enable_stdio_uart(test_qspi_smoke 1)

# Produce the .uf2 for drag-and-drop flashing (plus .elf/.bin/.hex).
pico_add_extra_outputs(test_qspi_smoke)

# -Wall -Wextra zero-warning gate per Task 39 artifact §4.1.
target_compile_options(test_qspi_smoke PRIVATE -Wall -Wextra)
