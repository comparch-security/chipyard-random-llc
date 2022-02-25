`timescale 1ns/1ps
`define CLOCK_PERIOD  5
`define RESET_DELAY   `CLOCK_PERIOD*2
`define RST_PIN_PULLUP 1

module TestDriver;

  reg clock = 1'b0;
  reg reset = `RST_PIN_PULLUP ? 1'b0 : 1'b1;

  always #(`CLOCK_PERIOD/2.0) clock = ~clock;
  initial #(`RESET_DELAY) reset = `RST_PIN_PULLUP ? 1'b1 : 1'b0;
						 
  wire success;						   
  board testHarness(
    .clock(clock),
    .reset(reset),
    .io_success(success)
  );

endmodule
