idf_component_register(SRCS "main.c"
                       INCLUDE_DIRS ".")

if(DEFINED ENV{CHECKERBOARD_SKIP_CRC8_CORRUPTION})
    target_compile_definitions(${COMPONENT_LIB} PRIVATE
                               CHECKERBOARD_SKIP_CRC8_CORRUPTION=$ENV{CHECKERBOARD_SKIP_CRC8_CORRUPTION})
endif()
