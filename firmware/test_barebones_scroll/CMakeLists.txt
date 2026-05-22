cmake_minimum_required(VERSION 3.13)

if(NOT DEFINED PICO_PLATFORM)
    set(PICO_PLATFORM rp2350-arm-s)
endif()
if(NOT DEFINED PICO_BOARD)
    set(PICO_BOARD pico2)
endif()

include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(test_barebones_scroll C CXX ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

pico_sdk_init()

add_executable(test_barebones_scroll
    test_barebones_scroll.c
)

target_link_libraries(test_barebones_scroll pico_stdlib)

pico_enable_stdio_usb(test_barebones_scroll 1)
pico_enable_stdio_uart(test_barebones_scroll 0)

pico_add_extra_outputs(test_barebones_scroll)
