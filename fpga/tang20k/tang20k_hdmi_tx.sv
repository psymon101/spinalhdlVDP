// Tang Nano 20K DVI/HDMI transport wrapper.
//
// This keeps the raster generator in SpinalHDL and uses a board-proven output
// strategy for the TMDS pins:
// - encode RGB/control with Sameer Puri's TMDS channel implementation
// - serialize data lanes with Gowin OSER10
// - drive the TMDS clock pair directly from the pixel clock
//
// The encoding logic in tmds_channel.sv is adapted from hdl-util/hdmi
// (MIT license, copyright Sameer Puri). See
// fpga/tang20k/third_party/hdl_util_hdmi/LICENSE-MIT.

module tang20k_hdmi_tx (
    input  wire       clk_pixel,
    input  wire       clk_pixel_x5,
    input  wire       reset,
    input  wire       hsync,
    input  wire       vsync,
    input  wire       de,
    input  wire [7:0] red,
    input  wire [7:0] green,
    input  wire [7:0] blue,
    output wire       tmds_clk_p,
    output wire       tmds_clk_n,
    output wire [2:0] tmds_data_p,
    output wire [2:0] tmds_data_n
);

    // In DVI mode only channel 0 carries sync control symbols during blanking.
    wire [1:0] control_ch0 = {vsync, hsync};
    wire [1:0] control_zero = 2'b00;
    wire [2:0] mode = de ? 3'd1 : 3'd0;

    wire [9:0] tmds_internal [0:2];
    wire [2:0] tmds_serial;

    tmds_channel #(.CN(0)) tmds_blue (
        .clk_pixel(clk_pixel),
        .video_data(blue),
        .data_island_data(4'b0000),
        .control_data(control_ch0),
        .mode(mode),
        .tmds(tmds_internal[0])
    );

    tmds_channel #(.CN(1)) tmds_green (
        .clk_pixel(clk_pixel),
        .video_data(green),
        .data_island_data(4'b0000),
        .control_data(control_zero),
        .mode(mode),
        .tmds(tmds_internal[1])
    );

    tmds_channel #(.CN(2)) tmds_red (
        .clk_pixel(clk_pixel),
        .video_data(red),
        .data_island_data(4'b0000),
        .control_data(control_zero),
        .mode(mode),
        .tmds(tmds_internal[2])
    );

    // Tang Nano 20K uses one OSER10 per TMDS data lane.
    OSER10 serializer_blue (
        .Q(tmds_serial[0]),
        .D0(tmds_internal[0][0]),
        .D1(tmds_internal[0][1]),
        .D2(tmds_internal[0][2]),
        .D3(tmds_internal[0][3]),
        .D4(tmds_internal[0][4]),
        .D5(tmds_internal[0][5]),
        .D6(tmds_internal[0][6]),
        .D7(tmds_internal[0][7]),
        .D8(tmds_internal[0][8]),
        .D9(tmds_internal[0][9]),
        .PCLK(clk_pixel),
        .FCLK(clk_pixel_x5),
        .RESET(reset)
    );

    OSER10 serializer_green (
        .Q(tmds_serial[1]),
        .D0(tmds_internal[1][0]),
        .D1(tmds_internal[1][1]),
        .D2(tmds_internal[1][2]),
        .D3(tmds_internal[1][3]),
        .D4(tmds_internal[1][4]),
        .D5(tmds_internal[1][5]),
        .D6(tmds_internal[1][6]),
        .D7(tmds_internal[1][7]),
        .D8(tmds_internal[1][8]),
        .D9(tmds_internal[1][9]),
        .PCLK(clk_pixel),
        .FCLK(clk_pixel_x5),
        .RESET(reset)
    );

    OSER10 serializer_red (
        .Q(tmds_serial[2]),
        .D0(tmds_internal[2][0]),
        .D1(tmds_internal[2][1]),
        .D2(tmds_internal[2][2]),
        .D3(tmds_internal[2][3]),
        .D4(tmds_internal[2][4]),
        .D5(tmds_internal[2][5]),
        .D6(tmds_internal[2][6]),
        .D7(tmds_internal[2][7]),
        .D8(tmds_internal[2][8]),
        .D9(tmds_internal[2][9]),
        .PCLK(clk_pixel),
        .FCLK(clk_pixel_x5),
        .RESET(reset)
    );

    // The Tang Nano 20K HDMI clock lane works from the raw pixel clock.
    ELVDS_OBUF tmds_bufds [3:0] (
        .I({clk_pixel, tmds_serial}),
        .O({tmds_clk_p, tmds_data_p}),
        .OB({tmds_clk_n, tmds_data_n})
    );

endmodule
