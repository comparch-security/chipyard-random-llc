module board(
  input  clock,
  input  reset,
  output io_success
);
  `include "onboard_ddr3_parameters.vh"					 
  wire                               ddr3_reset_n;
  wire [DQ_WIDTH-1:0]                ddr3_dq_fpga;
  wire [DQS_WIDTH-1:0]               ddr3_dqs_p_fpga;
  wire [DQS_WIDTH-1:0]               ddr3_dqs_n_fpga;
  wire [ROW_WIDTH-1:0]               ddr3_addr_fpga;
  wire [3-1:0]                       ddr3_ba_fpga;
  wire                               ddr3_ras_n_fpga;
  wire                               ddr3_cas_n_fpga;
  wire                               ddr3_we_n_fpga;
  wire [1-1:0]                       ddr3_cke_fpga;
  wire [1-1:0]                       ddr3_ck_p_fpga;
  wire [1-1:0]                       ddr3_ck_n_fpga;
  wire [(CS_WIDTH*1)-1:0]            ddr3_cs_n_fpga;
  wire [DM_WIDTH-1:0]                ddr3_dm_fpga;
  wire [ODT_WIDTH-1:0]               ddr3_odt_fpga;
  
  wire clock_p;
  wire clock_n;
  assign clock_p = clock;
  assign clock_n = ~clock;

//FPGA
GENESYS2FPGATestHarness fpga(
  .sys_clock_p       (  clock_p          ),
  .sys_clock_n       (  clock_n          ),
  /*.uart_txd          (                   ),
  .uart_rxd          (                   ),
  .uart_rtsn         (                   ),
  .uart_ctsn         (                   ),
  .sdio_spi_clk      (                   ),
  .sdio_spi_cs       (                   ),
  .sdio_spi_dat0     (                   ),
  .sdio_spi_dat1     (                   ),
  .sdio_spi_dat2     (                   ),
  .sdio_spi_dat3     (                   ),
  .dio_sw            (                   ),
  .dio_but           (                   ),
  .dio_led           (                   ),
  .dio_oled          (                   ),*/
  .ddr_ddr3_addr     (  ddr3_addr_fpga   ),
  .ddr_ddr3_ba       (  ddr3_ba_fpga     ),
  .ddr_ddr3_ras_n    (  ddr3_ras_n_fpga  ),
  .ddr_ddr3_cas_n    (  ddr3_cas_n_fpga  ),
  .ddr_ddr3_we_n     (  ddr3_we_n_fpga   ),
  .ddr_ddr3_reset_n  (  ddr3_reset_n     ),
  .ddr_ddr3_ck_p     (  ddr3_ck_p_fpga   ),
  .ddr_ddr3_ck_n     (  ddr3_ck_n_fpga   ),
  .ddr_ddr3_cke      (  ddr3_cke_fpga    ),
  .ddr_ddr3_cs_n     (  ddr3_cs_n_fpga   ),
  .ddr_ddr3_dm       (  ddr3_dm_fpga     ),
  .ddr_ddr3_odt      (  ddr3_odt_fpga    ),
  .ddr_ddr3_dq       (  ddr3_dq_fpga     ),
  .ddr_ddr3_dqs_n    (  ddr3_dqs_n_fpga  ),
  .ddr_ddr3_dqs_p    (  ddr3_dqs_p_fpga  ),
  .reset             (  reset            )
);

//ddr3
onboard_ddr3 ddr3(
  .ddr3_addr         (  ddr3_addr_fpga   ),
  .ddr3_ba           (  ddr3_ba_fpga     ),
  .ddr3_ras_n        (  ddr3_ras_n_fpga  ),
  .ddr3_cas_n        (  ddr3_cas_n_fpga  ),
  .ddr3_we_n         (  ddr3_we_n_fpga   ),
  .ddr3_reset_n      (  ddr3_reset_n     ),
  .ddr3_ck_p         (  ddr3_ck_p_fpga   ),
  .ddr3_ck_n         (  ddr3_ck_n_fpga   ),
  .ddr3_cke          (  ddr3_cke_fpga    ),
  .ddr3_cs_n         (  ddr3_cs_n_fpga   ),
  .ddr3_dm           (  ddr3_dm_fpga     ),
  .ddr3_odt          (  ddr3_odt_fpga    ),
  .ddr3_dq           (  ddr3_dq_fpga     ),
  .ddr3_dqs_n        (  ddr3_dqs_n_fpga  ),
  .ddr3_dqs_p        (  ddr3_dqs_p_fpga  )
);

initial begin
  force ddr3.sys_rst_n           = reset;
  force ddr3.init_calib_complete = fpga.mig.island.blackbox_init_calib_complete; //50us `timescale 1ns/1ps
end

endmodule
