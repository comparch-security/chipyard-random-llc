import dii_package::dii_flit;

module OSDTopBlackBox
  #(parameter N_CORES        = 1,
    parameter FREQ_CLK_IO    = 60000000,
    parameter UART_BAUD      = 3000000,     //genesys2 FT232R uart
    parameter MAM_ADDR_WIDTH = 32,
    parameter MAM_DATA_WIDTH = 16,          // must be 16!
    parameter MAM_REGIONS    = 2,
    parameter MAM_BASE_ADDR0 = 'h20000,     //BootRAM
    parameter MAM_MEM_SIZE0  = 'h10000, 
    parameter MAM_BASE_ADDR1 = 'h80000000,  //DDR
    parameter MAM_MEM_SIZE1  = 'h40000000   //1G
)
  (
   input                         clk, 
   input                         clk_io,

   //glip_uart
   input                         rx,
   output                        tx,
   input                         cts,
   output                        rts,
   input                         rst,            //reset glip this signal from key
   output logic                  com_rst,        //reset uart
   output logic                  osd_rst,        //reset osd

   //osd_uart
   output                        uart_drop,       //osd-cli enable uart dem
   output [7:0]                  uart_in_char,    //RX
   output                        uart_in_valid,
   input                         uart_in_ready,
   input  [7:0]                  uart_out_char,   //TX
   input                         uart_out_valid,
   output                        uart_out_ready,
  
   //osd_scm
   output                        sys_rst,         //this port used to reset total system (exclude glip and osd)
   output                        cpu_rst,         //this port used to reset total tile(only tile) 

   //osd_mam
   output                        req_valid,
   input                         req_ready,
   output                        req_rw,
   output [MAM_ADDR_WIDTH-1:0]   req_addr,
   output                        req_burst,
   output [13:0]                 req_beats,
   input                         read_valid,
   output                        read_ready,
   input  [MAM_DATA_WIDTH-1:0]   read_data,
   output                        write_valid,
   input                         write_ready,
   output [MAM_DATA_WIDTH-1:0]   write_data,
   output [MAM_DATA_WIDTH/8-1:0] write_strb,
   
   //ring extend
   input                         ring_in0_valid,
   input                         ring_in0_last,
   input  [15:0]                 ring_in0_data,
   output                        ring_in0_ready,
   input                         ring_in1_valid,
   input                         ring_in1_last,
   input  [15:0]                 ring_in1_data,
   output                        ring_in1_ready,
   output                        ring_out0_valid,
   output                        ring_out0_last,
   output [15:0]                 ring_out0_data,
   input                         ring_out0_ready,
   output                        ring_out1_valid,
   output                        ring_out1_last,
   output [15:0]                 ring_out1_data,
   input                         ring_out1_ready
   );

   localparam MAX_PKT_LEN = 16;

   initial assert (MAM_DATA_WIDTH == 16) else $fatal(1, "MAM_DATA_WIDTH must be 16!");

   glip_channel #(.WIDTH(16)) fifo_in (.*);
   glip_channel #(.WIDTH(16)) fifo_out (.*);

  logic com_rst, logic_rst;
  logic osd_rst, glip_rst;
  assign glip_rst = rst;
  assign osd_rst  = glip_rst | logic_rst;

`ifdef SYNTHESIS  //set_property verilog_define {list SYNTHESIS} [current_fileset] //synth.tcl
   logic [15:0]  fifo_out_data;
   logic         fifo_out_valid;
   logic         fifo_out_ready;
   logic  [15:0] fifo_in_data;
   logic         fifo_in_valid;
   logic         fifo_in_ready;

   assign fifo_in.data   = fifo_in_data;
   assign fifo_in.valid  = fifo_in_valid;
   assign fifo_in_ready  = fifo_in.ready;
   assign fifo_out_data  = fifo_out.data;
   assign fifo_out_valid = fifo_out.valid;
   assign fifo_out.ready = fifo_out_ready;

   glip_uart_toplevel
     #(.WIDTH(16), .BAUD(UART_BAUD), .FREQ_CLK_IO(FREQ_CLK_IO))
   u_glip(.clk_io    (clk_io),
          .clk       (clk),
          .rst       (glip_rst),
          .ctrl_logic_rst (logic_rst),
          .com_rst   (com_rst),
          .fifo_in_data  (fifo_in_data[15:0]),
          .fifo_in_valid (fifo_in_valid),
          .fifo_in_ready (fifo_in_ready),
          .fifo_out_data  (fifo_out_data[15:0]),
          .fifo_out_valid (fifo_out_valid),
          .fifo_out_ready (fifo_out_ready),
          .uart_rx (rx),
          .uart_tx (tx),
          .uart_cts_n (cts),
          .uart_rts_n (rts),
          .error ());
`else // !`ifdef SYNTHESIS

   glip_tcp_toplevel
     #(.WIDTH(16))
   u_glip(.clk_io    (clk),
          .clk_logic (clk),
          .rst       (glip_rst),
          .logic_rst (logic_rst),
          .com_rst   (com_rst),
          .fifo_in   (fifo_in),
          .fifo_out  (fifo_out));
`endif

   parameter PERCORE = 2;
   localparam N_OSD = 4;
   localparam N = N_CORES*PERCORE+N_OSD;

   logic [N_OSD-1:0][9:0] id_map;
   assign id_map[0] = 0;        // HIM
   assign id_map[1] = 1;        // SCM
   assign id_map[2] = 2;        // UART
   assign id_map[3] = 3;        // MAM

   dii_flit [N_OSD-1:0] dii_out; logic [N_OSD-1:0] dii_out_ready;
   dii_flit [N_OSD-1:0] dii_in;  logic [N_OSD-1:0] dii_in_ready;

   osd_him
     #(.MAX_PKT_LEN(MAX_PKT_LEN))
     u_him(.*,
           .rst      (osd_rst),
           .glip_in  (fifo_in),
           .glip_out (fifo_out),
           .dii_out        ( dii_out[0]        ),
           .dii_out_ready  ( dii_out_ready[0]  ),
           .dii_in         ( dii_in[0]         ),
           .dii_in_ready   ( dii_in_ready[0]   )
           );

   osd_scm
     #(.SYSTEMID(16'hdead), .NUM_MOD(N_OSD-1),
       .MAX_PKT_LEN(MAX_PKT_LEN))
   u_scm(.*,
         .rst             ( osd_rst          ),
         .id              ( id_map[1]        ),
         .debug_in        ( dii_in[1]        ),
         .debug_in_ready  ( dii_in_ready[1]  ),
         .debug_out       ( dii_out[1]       ),
         .debug_out_ready ( dii_out_ready[1] )
         );


   osd_dem_uart
     u_uart (.*,
             .rst             ( osd_rst            ),
             .id              ( id_map[2]          ),
             .drop            ( uart_drop          ),
             .in_char         ( uart_in_char[7:0]  ),
             .in_valid        ( uart_in_valid      ),
             .in_ready        ( uart_in_ready      ),
             .out_char        ( uart_out_char[7:0] ),
             .out_valid       ( uart_out_valid     ),
             .out_ready       ( uart_out_ready     ),
             .debug_in        ( dii_in[2]          ),
             .debug_in_ready  ( dii_in_ready[2]    ),
             .debug_out       ( dii_out[2]         ),
             .debug_out_ready ( dii_out_ready[2]   )
             );

   osd_mam
     #(.DATA_WIDTH(MAM_DATA_WIDTH), .REGIONS(MAM_REGIONS),
       .BASE_ADDR0(MAM_BASE_ADDR0), .MEM_SIZE0(MAM_MEM_SIZE0),
       .BASE_ADDR1(MAM_BASE_ADDR1), .MEM_SIZE1(MAM_MEM_SIZE1),
       .ADDR_WIDTH(MAM_ADDR_WIDTH), .MAX_PKT_LEN(MAX_PKT_LEN),
       .ENDIAN(0))
   u_mam (.*,
          .rst             ( osd_rst          ),
          .id              ( id_map[3]        ),
          .debug_in        ( dii_in[3]        ),
          .debug_in_ready  ( dii_in_ready[3]  ),
          .debug_out       ( dii_out[3]       ),
          .debug_out_ready ( dii_out_ready[3] ),
          .read_data       ( read_data        ),
          .write_data      ( write_data       )
          );

   dii_flit [1:0] ext_in;  logic [1:0] ext_in_ready;
   dii_flit [1:0] ext_out; logic [1:0] ext_out_ready;

   debug_ring_expand
     #(.PORTS(N_OSD))
   u_ring(.*,
          .rst           ( osd_rst        ),
          .dii_in        ( dii_out        ),
          .dii_in_ready  ( dii_out_ready  ),
          .dii_out       ( dii_in         ),
          .dii_out_ready ( dii_in_ready   ),
          .ext_in        ( ext_in         ),
          .ext_in_ready  ( ext_in_ready   ),
          .ext_out       ( ext_out        ),
          .ext_out_ready ( ext_out_ready  )
          );
// extend
   assign ext_in[0].valid   = 1'b0;
   assign ext_in[1].valid   = ring_in0_valid;
   assign ext_in[1].last    = ring_in0_last;
   assign ext_in[1].data    = ring_in0_data;
   assign ring_in0_ready    = ext_in_ready[1];    
   assign ring_out0_valid   = ext_out[0].valid;      assign ring_out1_valid   = ext_out[1].valid;
   assign ring_out0_last    = ext_out[0].last;       assign ring_out1_last    = ext_out[1].last;
   assign ring_out0_data    = ext_out[0].data;       assign ring_out1_data    = ext_out[1].data;
   assign ext_out_ready[0]  = ring_out0_ready;       assign ext_out_ready[1]  = ring_out1_ready;
   assign ring_in1_ready    = 1'b1;

//not extend
/*   assign ext_in[0].valid = 1'b0;
   assign ext_in[1] = ext_out[0];
   assign ext_out_ready[0] = ext_in_ready[1];
   assign ext_out_ready[1] = 1'b1;
*/

endmodule // debug_system
