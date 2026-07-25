cmake_minimum_required(VERSION 3.13)

# Target Pico 2 (RP2350). Both vars MUST be set before the SDK include —
# otherwise the SDK silently defaults PICO_PLATFORM to `rp2040`, and the UF2
# bootloader on the Pico 2 silently rejects mismatched-family images
# (BOOTSEL drive stays mounted, no firmware ever runs). Allow CLI override.
if(NOT DEFINED PICO_PLATFORM)
    set(PICO_PLATFORM rp2350-arm-s)
endif()
if(NOT DEFINED PICO_BOARD)
    set(PICO_BOARD pico2)
endif()

include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(test_mode0_bad_apple C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

pico_sdk_init()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../libvdp libvdp_build)

add_executable(test_mode0_bad_apple
    test_mode0_bad_apple.c
)

target_link_libraries(test_mode0_bad_apple
    libvdp
)

pico_enable_stdio_usb(test_mode0_bad_apple 1)
pico_enable_stdio_uart(test_mode0_bad_apple 1)
pico_add_extra_outputs(test_mode0_bad_apple)
target_compile_options(test_mode0_bad_apple PRIVATE -Wall -Wextra)
