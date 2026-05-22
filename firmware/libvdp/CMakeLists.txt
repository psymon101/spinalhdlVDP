# libvdp — host driver library for the spinalhdlVDP QSPI host-control
# loop (Task 39). CMake target `libvdp` can be linked by any Pico 2
# firmware executable via `target_link_libraries(<exe> PRIVATE libvdp)`.
#
# Must be added by the including CMakeLists with `add_subdirectory()`
# AFTER `pico_sdk_init()` so the Pico SDK targets are already present.

add_library(libvdp STATIC
    vdp_qspi.c
    vdp_status.c
    vdp_upload.c
)

pico_generate_pio_header(libvdp
    ${CMAKE_CURRENT_SOURCE_DIR}/qspi_quad.pio
)

target_include_directories(libvdp PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Make the Pico SDK platform branch explicit for the library sources.
target_compile_definitions(libvdp PUBLIC
    PICO
)

target_link_libraries(libvdp PUBLIC
    pico_stdlib
    hardware_pio
    hardware_gpio
    hardware_clocks
)

# Treat warnings from the library itself as warnings (will be promoted
# to errors by whatever enclosing project chooses -Werror).
target_compile_options(libvdp PRIVATE -Wall -Wextra)
