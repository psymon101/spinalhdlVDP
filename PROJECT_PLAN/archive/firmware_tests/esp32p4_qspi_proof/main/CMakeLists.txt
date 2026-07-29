idf_component_register(SRCS "main.c"
                       INCLUDE_DIRS ".")

if(DEFINED ENV{P4_QSPI_RUN_CRC8_ONLY})
    target_compile_definitions(${COMPONENT_LIB} PRIVATE
                               P4_QSPI_RUN_CRC8_ONLY=$ENV{P4_QSPI_RUN_CRC8_ONLY})
endif()
