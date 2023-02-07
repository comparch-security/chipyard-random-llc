
module tb;

logic clk, rst;


reg           tracein_valid;
wire          tracein_ready;
reg  [14:0]   tracein_bits_evSum;
reg  [39:0]   tracein_bits_evSqSum;
reg  [14:0]   tracein_bits_evStdDev;
reg  [14:0]   tracein_bits_evStdDevReci;
reg  [13:0]   tracein_bits_delta;
reg  [ 9:0]   tracein_bits_set;
reg  [14:0]   tracein_bits_ev;
reg           tracein_bits_detected;
reg  [ 9:0]   tracein_bits_detected_set;

wire           traceout_valid;
wire           traceout_ready;
wire  [14:0]   traceout_bits_evSum;
wire  [39:0]   traceout_bits_evSqSum;
wire  [14:0]   traceout_bits_evStdDev;
wire  [14:0]   traceout_bits_evStdDevReci;
wire  [14:0]   traceout_bits_emaz;
wire  [ 9:0]   traceout_bits_set;
wire  [13:0]   traceout_bits_delta;
wire           traceout_bits_deltaNeg;

reg sendall;
reg receall;

initial begin
  clk = 0;
  forever clk = #5 !clk;
end

initial begin
  rst = 1;
  #100;
  rst = 0;
end


AttackDetectorTrace DUT(
.clock(clk),
.reset(rst),
.io_tracein_ready(tracein_ready),
.io_tracein_valid(tracein_valid),
.io_tracein_bits_evSum(tracein_bits_evSum),
.io_tracein_bits_evAvera(),
.io_tracein_bits_evSqSum(tracein_bits_evSqSum),
.io_tracein_bits_evSqAvera(),
.io_tracein_bits_evStdDev(tracein_bits_evStdDev),
.io_tracein_bits_evStdDevReci(tracein_bits_evStdDevReci),
.io_tracein_bits_set(tracein_bits_set),
.io_tracein_bits_ev(tracein_bits_ev),
.io_tracein_bits_detected(tracein_bits_detected),
.io_traceout_bits_evErrAbs(),
.io_traceout_bits_evErrNeg(),
.io_traceout_bits_evMulErr(),
.io_traceout_bits_evWZscore(),
.io_tracein_bits_delta(tracein_bits_delta),
.io_traceout_bits_emaz(),
.io_traceout_ready(traceout_ready),
.io_traceout_valid(traceout_valid),
.io_traceout_bits_evSum(traceout_bits_evSum),
.io_traceout_bits_evAvera(),
.io_traceout_bits_evSqSum(traceout_bits_evSqSum),
.io_traceout_bits_evSqAvera(),
.io_traceout_bits_evStdDev(traceout_bits_evStdDev),
.io_traceout_bits_evStdDevReci(traceout_bits_evStdDevReci),
.io_traceout_bits_set(traceout_bits_set),
.io_traceout_bits_ev(),
.io_traceout_bits_evErrAbs(),
.io_traceout_bits_evErrNeg(),
.io_traceout_bits_evMulErr(),
.io_traceout_bits_evWZscore(),
.io_traceout_bits_delta(traceout_bits_delta),
.io_traceout_bits_deltaNeg(traceout_bits_deltaNeg),
.io_traceout_bits_emaz(traceout_bits_emaz),
.io_remapfire(),
.io_mix()
);

int file_cm_ev;
int file_cm_delta_int;
int file_cm_emaz_int;
int file_cm_globar_var_int;

int file_vm_delta_int;
int file_vm_emaz_int;
int file_vm_globar_var_int;

initial begin
  file_cm_ev                     = $fopen("./log/cmodel/cev.log","r");
  file_cm_delta_int              = $fopen("./log/cmodel/cdelta_int.log","r");
  file_cm_globar_var_int         = $fopen("./log/cmodel/cglobal_var_int.log","r");
  file_vm_delta_int              = $fopen("./log/vmodel/vdelta_int.log","a+");
  file_vm_emaz_int               = $fopen("./log/vmodel/vemaz_int.log","a+");
  file_vm_globar_var_int         = $fopen("./log/vmodel/vglobal_var_int.log","a+");
end

//trace in
always @(posedge clk) begin
  if(rst) begin
    sendall          <= 1'b0;
    receall          <= 1'b0;
    tracein_valid    <= 1'b0;
    tracein_bits_set <= 10'b0;
  end else begin
    if( tracein_valid && tracein_ready) begin
      tracein_valid    <= 1'b0;
      tracein_bits_set <= tracein_bits_set + 1'b1;
    end
    if(!tracein_valid && tracein_ready) begin
      if($fscanf(file_cm_ev,        "%d", tracein_bits_ev) == 1) begin
        tracein_valid <= 1'b1;
        $fscanf(file_cm_delta_int, "%d", tracein_bits_delta);
      end else begin
        sendall  <= 1'b1;;
      end
      if(tracein_bits_set == 0) begin
        $fscanf(file_cm_globar_var_int,     "%d" ,           tracein_bits_evSum);
        $fscanf(file_cm_globar_var_int,     "%d" ,         tracein_bits_evSqSum);
        $fscanf(file_cm_globar_var_int,     "%d" ,        tracein_bits_evStdDev);
        $fscanf(file_cm_globar_var_int,     "%d" ,    tracein_bits_evStdDevReci);
        $fscanf(file_cm_globar_var_int,     "%d" ,         tracein_bits_detected);
        $fscanf(file_cm_globar_var_int,     "%d" ,     tracein_bits_detected_set);
      end
    end
    if(traceout_valid) begin
      if(sendall && traceout_bits_set == 10'd1023) begin
        receall      <= 1'b1;
      end
    end
  end
end

//trace out
assign traceout_ready = 1'b1;
always @(posedge clk) begin
  if(rst) begin
  end else begin
    if( traceout_valid && traceout_ready) begin
      $fwrite(file_vm_emaz_int,             "%d ",  traceout_bits_emaz);
      $fwrite(file_vm_delta_int,            "%d ",  traceout_bits_delta);
      //if(traceout_bits_deltaNeg && traceout_bits_delta == 0) begin
      //  $fwrite(file_vm_delta_int,         "-%d ",  traceout_bits_delta); 
      //end else begin
      //  $fwrite(file_vm_delta_int,          "%d ",  traceout_bits_delta); 
      //end
      if(traceout_bits_set == 10'd1023) begin
        $fwrite(file_vm_globar_var_int,     "%d " ,           traceout_bits_evSum);
        $fwrite(file_vm_globar_var_int,     "%d " ,         traceout_bits_evSqSum);
        $fwrite(file_vm_globar_var_int,     "%d " ,        traceout_bits_evStdDev);
        $fwrite(file_vm_globar_var_int,     "%d " ,    traceout_bits_evStdDevReci);
        $fwrite(file_vm_globar_var_int,     "\n");
        $fwrite(file_vm_emaz_int,           "\n\n");
        $fwrite(file_vm_delta_int,          "\n\n");
      end
    end
  end
end

always @(posedge clk) begin
  if(!rst) begin
    if(sendall) begin
      $fclose(file_cm_ev);
      $fclose(file_cm_globar_var_int);
      $fclose(file_vm_delta_int);
      $fclose(file_vm_emaz_int);
      $fclose(file_vm_globar_var_int);
      $finish;
    end
  end 
end



string    vcd_name = "";
longint   unsigned max_cycle = 0;
longint   unsigned cycle_cnt = 0;

initial begin
  $value$plusargs("max-cycles=%d", max_cycle);
end // initial begin

// vcd
initial begin
  if($test$plusargs("vcd"))
    vcd_name = "test.vcd";
    $value$plusargs("vcd_name=%s", vcd_name);
    if(vcd_name != "") begin
      $dumpfile(vcd_name);
      $dumpvars(0, DUT);
      $dumpvars();
      $dumpon;
    end
end // initial begin

endmodule

