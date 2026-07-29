idf_component_register(
    SRCS "main.c" "../../libvdp/vdp_host_p4.c" "../../libvdp/vdp_mode0.c"
    INCLUDE_DIRS "." "../../libvdp")

if(DEFINED ENV{SCALER_PROOF_MODE})
    target_compile_definitions(${COMPONENT_LIB} PRIVATE
                               SCALER_PROOF_MODE=$ENV{SCALER_PROOF_MODE})
endif()
