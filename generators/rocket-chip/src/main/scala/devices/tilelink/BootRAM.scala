// See LICENSE.SiFive for license details.

package freechips.rocketchip.devices.tilelink

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxInline
//import chisel3.util.experimental._
//import chisel3.util.experimental.loadMemoryFromFile
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.subsystem.{BaseSubsystem, HierarchicalLocation, HasTiles, TLBusWrapperLocation}
//import freechips.rocketchip.util._
//import freechips.rocketchip.util.property._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci.{ClockSinkDomain}


/** Size, location and contents of the boot rom. */
case class BootRAMParams(
  address: BigInt = 0x20000,
  size: Int = 0x10000) { require(isPow2(size)) }

class BlackBoxBootram(dwidth: Int, awidth: Int) extends 
      BlackBox(Map("BRAM_DW" -> dwidth, "BRAM_AW" -> awidth)) with HasBlackBoxInline {

  val size = (dwidth/8)*(1<<awidth) //bytes
  val RAMB36size = 128*256/8 //4kbytes
  require(dwidth>8 && isPow2(dwidth))
  require(isPow2(size) && size>=RAMB36size)  //match RAMB36
  require(size<=0x10000) //too big  

  val io = IO(new Bundle {
    val clk    = Input(Clock())
    val addr   = Input(UInt(awidth.W))
    val wdata  = Input(UInt(dwidth.W))
    val rdata  = Output(UInt(dwidth.W)) 
    val en     = Input(Bool())    
    val wmode  = Input(Bool())
    val wmask  = Input(UInt((dwidth/8).W))       
  })
  setInline("BlackBoxBootram.sv",
    """module BlackBoxBootram #(parameter BRAM_DW = 64, parameter BRAM_AW = 13)(
      |    input                  clk,
      |    input  [BRAM_AW-1:0]   addr,
      |    input  [BRAM_DW-1:0]   wdata,
      |    output [BRAM_DW-1:0]   rdata,
      |    input                  en,
      |    input                  wmode,
      |    input  [BRAM_DW/8-1:0] wmask
      |);
      |
      |(* ram_style = "block" *) reg [BRAM_DW-1:0] ram [0:((1<<BRAM_AW)-1)]; //8*(2^12) =64kbytes
      |reg [BRAM_AW-1:0] add_r;
      |
      |assign rdata = ram[add_r];
      |
      |always @(posedge clk) begin
      |  if(en & wmode) begin
      |     foreach (wmask[i])
      |        if(wmask[i]) ram[addr][i*8 +:8] <= wdata[i*8 +: 8];
      |  end
      |  if (en & ~wmode) begin   //READ
      |      add_r <= addr;
      |  end
      |end
      |initial begin $readmemh("./obj/boot.mem", ram); end
      |      
      |endmodule
    """.stripMargin)

    /*|      if(wmask[7]) ram[addr][63:56] <= wdata[63:56];
      |      if(wmask[6]) ram[addr][55:48] <= wdata[55:48];
      |      if(wmask[5]) ram[addr][47:40] <= wdata[47:40];
      |      if(wmask[4]) ram[addr][39:32] <= wdata[39:32];
      |      if(wmask[3]) ram[addr][31:24] <= wdata[31:24]; 
      |      if(wmask[2]) ram[addr][23:16] <= wdata[23:16]; 
      |      if(wmask[1]) ram[addr][15: 8] <= wdata[15: 8];   
      |      if(wmask[0]) ram[addr][7 : 0] <= wdata[7 : 0];*/

    /*       |end
    """.stripMargin + """initial begin $readmemh("""" + contentFileName.get + """", ram); end"""  +  """
      |      
      |endmodule
    """.stripMargin)*/
}


class TLBootRAM(
    val base: BigInt, val size: Int,
    beatBytes: Int = 4,
    contentFileName: Option[String] = Some("./bootram.hex"),
    resources: Seq[Resource] = new SimpleDevice("ram", Seq("bootram,ram0")).reg("mem")
  )(implicit p: Parameters) extends LazyModule
{
  require (isPow2(size))
  require (size <= (2 << 16)) //BlackBoxBootram is 64kBytes
  require (beatBytes==8)

  val node = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLSlaveParameters.v1(
      address            = List(AddressSet(base, size-1)),
      resources          = resources,
      regionType         = RegionType.IDEMPOTENT,
      executable         = true,
      supportsGet        = TransferSizes(1, beatBytes),
      supportsPutPartial = TransferSizes(1, beatBytes),
      supportsPutFull    = TransferSizes(1, beatBytes),
      supportsArithmetic = TransferSizes.none,
      supportsLogical    = TransferSizes.none,
      fifoId             = Some(0))), // requests are handled in order
    beatBytes  = beatBytes,
    minLatency = 1))) // no bypass needed for this device

  lazy val module = new LazyModuleImp(this) {
    val (in, edge) = node.in(0)
    //println(address+"!!!!!????")       //sram.scala:TLRAM:why this is null??

    val r_full      = RegInit(false.B)
    val r_opcode    = Reg(UInt(3.W))
    val r_param     = Reg(UInt(3.W))
    val r_size      = Reg(UInt(edge.bundle.sizeBits.W))
    val r_source    = Reg(UInt(edge.bundle.sourceBits.W))
    val r_read      = Reg(Bool())
    val r_mask      = Reg(UInt(beatBytes.W))
    val r_raw_data  = Wire(Bits(64.W))

    in.d.valid := r_full
    in.a.ready := ~r_full // ||in.d.ready avoid read and write address collide

    in.d.bits.opcode  := Mux(r_read, TLMessages.AccessAckData, TLMessages.AccessAck)
    in.d.bits.param   := 0.U
    in.d.bits.size    := r_size
    in.d.bits.source  := r_source
    in.d.bits.sink    := 0.U
    in.d.bits.denied  := false.B
    in.d.bits.data    := r_raw_data
    in.d.bits.corrupt := false.B


    val a_fire = in.a.fire()
    val a_read = in.a.bits.opcode === TLMessages.Get

    when (in.d.ready)  { r_full := false.B }
    when (a_fire) {
      r_full     := true.B
      r_opcode   := in.a.bits.opcode
      r_size     := in.a.bits.size
      r_source   := in.a.bits.source
      r_read     := a_read
      r_mask     := in.a.bits.mask
    }

    
    /*val mem     = Mem(size/beatBytes, UInt(width = (8*beatBytes).W))
    val mem_ren = a_fire && a_read
    val mem_wen = a_fire && !a_read
    val mem_idx   = Wire(UInt(log2Ceil(size/8).W)) //idx
    val r_mem_idx = Reg(UInt(log2Ceil(size/8).W)) //
    val mem_temp  = Wire(UInt((8*beatBytes).W))
    mem_idx := in.a.bits.address >> 3
    mem_temp := mem(in.a.bits.address >> 3)
    loadMemoryFromFile(mem, contentFileName.get) //systemverilog bind not work?

    r_raw_data := mem(r_mem_idx)
    when (mem_ren) { r_mem_idx := mem_idx }
    when (mem_wen) {  //Chisel does not currently support subword assignment
      mem(mem_idx) := Cat(Mux(in.a.bits.mask(7), in.a.bits.data(63, 56), mem_temp(63, 56)),
                          Mux(in.a.bits.mask(6), in.a.bits.data(55, 48), mem_temp(55, 48)),
                          Mux(in.a.bits.mask(5), in.a.bits.data(47, 40), mem_temp(47, 40)),
                          Mux(in.a.bits.mask(4), in.a.bits.data(39, 32), mem_temp(39, 32)),
                          Mux(in.a.bits.mask(3), in.a.bits.data(31, 24), mem_temp(31, 24)),
                          Mux(in.a.bits.mask(2), in.a.bits.data(23, 16), mem_temp(23, 16)),
                          Mux(in.a.bits.mask(1), in.a.bits.data(15,  8), mem_temp(15,  8)),
                          Mux(in.a.bits.mask(0), in.a.bits.data( 7,  0), mem_temp( 7,  0)))
    }*/
    val mem     = Module(new BlackBoxBootram(awidth=log2Ceil(size/beatBytes), dwidth=8*beatBytes))
    mem.io.clk     := clock
    mem.io.addr    := in.a.bits.address >> 3
    mem.io.wdata   := in.a.bits.data
    r_raw_data     := mem.io.rdata
    mem.io.en      := a_fire
    mem.io.wmode   := !a_read
    mem.io.wmask   := in.a.bits.mask

    // Tie off unused channels
    in.b.valid := false.B
    in.c.ready := true.B
    in.e.ready := true.B
  }
}

case class BootRAMLocated(loc: HierarchicalLocation) extends Field[Option[BootRAMParams]](None)

object BootRAM {
  /** BootRAM.attach not only instantiates a TLRAM and attaches it to the tilelink interconnect
    *    at a configurable location, but also drives the tiles' reset vectors to point
    *    at its 'hang' address parameter value.
    */
  def attach(params: BootRAMParams, subsystem: BaseSubsystem with HasTiles, where: TLBusWrapperLocation)
            (implicit p: Parameters): TLBootRAM = {
    val tlbus = subsystem.locateTLBusWrapper(where)
    val bootRAMDomainWrapper = LazyModule(new ClockSinkDomain(take = None))
    bootRAMDomainWrapper.clockNode := tlbus.fixedClockNode

    //val bootROMResetVectorSourceNode = BundleBridgeSource[UInt]()
    
    val bootram = bootRAMDomainWrapper {
      LazyModule(new TLBootRAM(base = params.address, size=params.size, beatBytes = tlbus.beatBytes))
    }


    bootram.node := tlbus.coupleTo("bootram"){ TLFragmenter(tlbus) := _ }
    // Drive the `subsystem` reset vector to the `hang` address of this Boot ROM.
    //subsystem.tileResetVectorNexusNode := bootROMResetVectorSourceNode

    bootram
  }
}