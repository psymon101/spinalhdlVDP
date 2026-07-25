cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/external/pico_sdk_import.cmake)
project(test_palette_lut_smoke C CXX ASM)
set(CMAKE_C_STANDARD 11)
pico_sdk_init()

# libvdp source files
set(LIBVDP_SRC
    ${CMAKE_CURRENT_LIST_DIR}/../libvdp/vdp_mode0.c
    ${CMAKE_CURRENT_LIST_DIR}/../libvdp/vdp_palette_lut.c
)

add_executable(test_palette_lut_smoke
    test_palette_lut_smoke.c
    ${LIBVDP_SRC}
)

target_include_directories(test_palette_lut_smoke PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/../libvdp
)

target_link_libraries(test_palette_lut_smoke
    pico_stdlib
    hardware_spi
)

pico_add_extra_outputs(test_palette_lut_smoke)
