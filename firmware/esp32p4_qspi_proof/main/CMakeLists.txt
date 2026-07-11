idf_component_register(SRCS "main.c"
                       INCLUDE_DIRS ".")

target_add_binary_data(${COMPONENT_LIB}
                       "${CMAKE_CURRENT_LIST_DIR}/../assets/ham6_320x240_codes_words_le.bin"
                       BINARY
                       RENAME_TO ham6_codes_words_le)
