cmake_minimum_required(VERSION 3.13)

include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(test_qspi_wire C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

set(PICO_BOARD pico2)
pico_sdk_init()

add_executable(test_qspi_wire
    test_qspi_wire.c
)

target_link_libraries(test_qspi_wire
    pico_stdlib
    hardware_gpio
)

pico_enable_stdio_usb(test_qspi_wire 1)
pico_enable_stdio_uart(test_qspi_wire 0)

pico_add_extra_outputs(test_qspi_wire)
