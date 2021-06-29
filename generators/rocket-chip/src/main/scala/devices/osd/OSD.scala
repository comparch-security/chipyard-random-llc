// DOC include start: MyDeviceController
package freechips.rocketchip.osd

import freechips.rocketchip.diplomacy._
import Chisel.{defaultCompileOptions => _, _}
import chisel3.util.{HasBlackBoxResource}


class DIIIO extends Bundle {
  val last    = Bool()
  val data    = UInt(INPUT, 16.W)
}

class GLIPUARTPortIO extends Bundle {
  val rst       = Bool(INPUT)    //direction !
  val rxd       = Bool(INPUT)
  val txd       = Bool(OUTPUT)
  val com_rst   = Bool(OUTPUT)
  val osd_rst   = Bool(OUTPUT)
}

//port direction is from UART view !!!! 
//change direction node can't connect? UARTPeriphery.scala: osd.io.uartdem  <> osduart ????
class OSDUARTDEMIO extends Bundle {
  val drop = Bool(INPUT) //osd-cli enable uart dem
  val in   = Decoupled(UInt(8.W)).flip
  val out  = Decoupled(UInt(8.W))  //from uart dem to RING to HIM to GLIP to PC
}

class OSDSCMIO extends Bundle {
  val sys_rst = Bool(OUTPUT)    
  val cpu_rst = Bool(OUTPUT)
}

class OSDIO extends Bundle {
  val glip    = new GLIPUARTPortIO()
  val uartdem = new OSDUARTDEMIO().flip
  val scm     = new OSDSCMIO()
  val mam     = new OSDMAMIO().flip
  val ringin  = Vec(2, Decoupled(new DIIIO()).flip)
  val ringout = Vec(2, Decoupled(new DIIIO()))
}

class OSDTopBlackBoxIO extends Bundle {
   val clk             = Clock(INPUT)
   val clk_io          = Clock(INPUT)

   //glip_uart
   val rx              = Bool(INPUT)
   val tx              = Bool(OUTPUT)
   val cts             = Bool(INPUT)
   val rts             = Bool(OUTPUT)
   val rst             = Bool(INPUT)
   val com_rst         = Bool(OUTPUT)
   val osd_rst         = Bool(OUTPUT)

   //osd_uart: UART Device Emulation Module
   val uart_drop       = Bool(OUTPUT)
   val uart_in_char    = UInt(OUTPUT, 8.W)
   val uart_in_valid   = Bool(OUTPUT)
   val uart_in_ready   = Bool(INPUT)
   val uart_out_char   = UInt(INPUT, 8.W)
   val uart_out_valid  = Bool(INPUT)
   val uart_out_ready  = Bool(OUTPUT)

   //osd_scm: Subnet Control Module
   val sys_rst         = Bool(OUTPUT)
   val cpu_rst         = Bool(OUTPUT)

   //osd_mam: Memory Access Module
   val req_valid       = Bool(OUTPUT)
   val req_ready       = Bool(INPUT)
   val req_rw          = Bool(OUTPUT)
   val req_addr        = UInt(OUTPUT, 32.W)
   val req_burst       = Bool(OUTPUT)
   val req_beats       = UInt(OUTPUT, 14.W)
   val read_valid      = Bool(INPUT)
   val read_ready      = Bool(OUTPUT)
   val read_data       = UInt(INPUT,  16.W)
   val write_valid     = Bool(OUTPUT)
   val write_ready     = Bool(INPUT)
   val write_data      = UInt(OUTPUT, 16.W)
   val write_strb      = UInt(OUTPUT, (16/8).W)

   //ring extend
   val ring_in0_valid  = Bool(INPUT)
   val ring_in0_last   = Bool(INPUT)
   val ring_in0_data   = UInt(INPUT, 16.W)
   val ring_in0_ready  = Bool(OUTPUT)
   val ring_in1_valid  = Bool(INPUT)
   val ring_in1_last   = Bool(INPUT)
   val ring_in1_data   = UInt(INPUT, 16.W)
   val ring_in1_ready  = Bool(OUTPUT)
   val ring_out0_valid = Bool(OUTPUT)
   val ring_out0_last  = Bool(OUTPUT)
   val ring_out0_data  = UInt(OUTPUT, 16.W)
   val ring_out0_ready = Bool(INPUT)
   val ring_out1_valid = Bool(OUTPUT)
   val ring_out1_last  = Bool(OUTPUT)
   val ring_out1_data  = UInt(OUTPUT, 16.W)
   val ring_out1_ready = Bool(INPUT)
}

class OSDRingRouterBlackBoxIO extends Bundle {
   val clk             = Clock(INPUT)
   val rst             = Bool(INPUT)
   val id              = UInt(INPUT, 10.W)

   val ring_in0        = UInt(INPUT, 18.W)   //Cat(valid, last ,data)
   val ring_in0_ready  = Bool(OUTPUT)
   val ring_in1        = UInt(INPUT, 18.W)   //Cat(valid, last ,data)
   val ring_in1_ready  = Bool(OUTPUT)
   val ring_out0       = UInt(OUTPUT, 18.W)  //Cat(valid, last ,data)
   val ring_out0_ready = Bool(INPUT)
   val ring_out1       = UInt(OUTPUT, 18.W)  //Cat(valid, last ,data)
   val ring_out1_ready = Bool(INPUT)
   val local_in        = UInt(INPUT, 18.W)   //Cat(valid, last ,data)
   val local_in_ready  = Bool(OUTPUT)
   val local_out       = UInt(INPUT, 18.W)   //Cat(valid, last ,data)
   val local_out_ready = Bool(OUTPUT)
}

class OSDRingRouterBlackBox extends HasBlackBoxResource {
    val io = IO(new OSDRingRouterBlackBoxIO())
    addResource("/vsrc/ring_router.sv") //opensocdebug/hardware/interconnect/common/ring_router.sv
}

class OSDRing(val ports: Int, val starID: Int) extends Module() {
    val io = new Bundle {
      val ringin    = Vec(2, Decoupled(new DIIIO()).flip)
      val ringout   = Vec(2, Decoupled(new DIIIO())) 
      val localin   = Vec(ports, Decoupled(new DIIIO()).flip)
      val localout  = Vec(ports, Decoupled(new DIIIO()))
    }
    if(ports==0) {
      io.ringout <> io.ringin
    } else{
       val routers = (0 until ports).map { _ =>  Module(new OSDRingRouterBlackBox) }
       //each router connect to clock reset id  and localIO
       (0 until ports).foreach { i => 
         routers(i).io.clk             := clock
         routers(i).io.rst             := reset
         routers(ports-1).io.id        := (starID+i).U
         routers(i).io.local_in(17)    := io.localin(i).valid
         routers(i).io.local_in(16)    := io.localin(i).bits.last
         routers(i).io.local_in(15,0)  := io.localin(i).bits.data
         io.localin(i).ready           := routers(i).io.local_in_ready
         io.localout(i).valid          := routers(i).io.local_out(17)
         io.localout(i).bits.last      := routers(i).io.local_out(16)
         io.localout(i).bits.data      := routers(i).io.local_out(15,0)
         routers(i).io.local_out_ready := io.localout(i).ready
       }
       //first router connect to ringin
       routers(0).io.ring_in0          := Cat(io.ringin(0).valid, io.ringin(0).bits.last, io.ringin(0).bits.data)
       io.ringin(0).ready              := routers(0).io.ring_in0_ready
       routers(0).io.ring_in1          := Cat(io.ringin(1).valid, io.ringin(1).bits.last, io.ringin(1).bits.data)
       io.ringin(1).ready              := routers(0).io.ring_in1_ready
       //last router connect to ringout
       io.ringout(0).valid                 := routers(ports-1).io.ring_out0(17)
       io.ringout(0).bits.last             := routers(ports-1).io.ring_out0(16)
       io.ringout(0).bits.data             := routers(ports-1).io.ring_out0(15, 0)
       routers(ports-1).io.ring_out0_ready := io.ringout(0).ready
       io.ringout(1).valid                 := routers(ports-1).io.ring_out1(17)
       io.ringout(1).bits.last             := routers(ports-1).io.ring_out1(16)
       io.ringout(1).bits.data             := routers(ports-1).io.ring_out1(15, 0)
       routers(ports-1).io.ring_out0_ready := io.ringout(1).ready
       //routers connect each other
       (1 until ports).foreach { i =>
          routers(i).io.ring_in0           := routers(i-1).io.ring_out0
          routers(i-1).io.ring_out0_ready  := routers(i).io.ring_in0_ready
          routers(i-1).io.ring_in0         := routers(i).io.ring_out0
          routers(i).io.ring_out0_ready    := routers(i-1).io.ring_in0_ready
       }

    }
}

class OSDTopBlackBox extends HasBlackBoxResource {
    val io = IO(new OSDTopBlackBoxIO())
    addResource("/vsrc/OSDTopBlackBox.sv")
}

class OSD extends Module() {
  // Open SoC Debug
  val io = new OSDIO

  val osdtbb = Module(new OSDTopBlackBox)
  osdtbb.io.clk      := clock
  osdtbb.io.clk_io   := clock

  //glip_uart
  io.glip.txd        := osdtbb.io.tx
  osdtbb.io.rx       := io.glip.rxd
  osdtbb.io.cts      := Bool(false)
  osdtbb.io.rst      := RegNext(io.glip.rst)
  io.glip.com_rst    := RegNext(osdtbb.io.com_rst)
  io.glip.osd_rst    := RegNext(osdtbb.io.osd_rst)

  //osd_scm: Subnet Control Module
  io.scm.sys_rst     := RegNext(osdtbb.io.sys_rst)
  io.scm.cpu_rst     := RegNext(osdtbb.io.cpu_rst)

  //osd_uart: UART Device Emulation Module  connect to sifive uart
  io.uartdem.drop           := osdtbb.io.uart_drop
  io.uartdem.in.bits        := osdtbb.io.uart_in_char
  io.uartdem.in.valid       := osdtbb.io.uart_in_valid
  osdtbb.io.uart_in_ready   := io.uartdem.in.ready
  osdtbb.io.uart_out_char   := io.uartdem.out.bits
  osdtbb.io.uart_out_valid  := io.uartdem.out.valid
  io.uartdem.out.ready      := osdtbb.io.uart_out_ready

  //osd_mam: Memory Access Module
  io.mam.req.valid          := osdtbb.io.req_valid
  osdtbb.io.req_ready       := io.mam.req.ready
  io.mam.req.bits.rw        := osdtbb.io.req_rw
  io.mam.req.bits.addr      := osdtbb.io.req_addr
  io.mam.req.bits.burst     := osdtbb.io.req_burst
  io.mam.req.bits.beats     := osdtbb.io.req_beats
  osdtbb.io.read_valid      := io.mam.rdata.valid
  io.mam.rdata.ready        := osdtbb.io.read_ready
  osdtbb.io.read_data       := io.mam.rdata.bits.data
  io.mam.wdata.valid        := osdtbb.io.write_valid
  osdtbb.io.write_ready     := io.mam.wdata.ready
  io.mam.wdata.bits.data    := osdtbb.io.write_data
  io.mam.wdata.bits.strb    := osdtbb.io.write_strb

  //ring extend
  osdtbb.io.ring_in0_valid  := io.ringin(0).valid
  osdtbb.io.ring_in0_last   := io.ringin(0).bits.last
  osdtbb.io.ring_in0_data   := io.ringin(0).bits.data
  io.ringin(0).ready        := osdtbb.io.ring_in0_ready
  osdtbb.io.ring_in1_valid  := io.ringin(1).valid
  osdtbb.io.ring_in1_last   := io.ringin(1).bits.last
  osdtbb.io.ring_in1_data   := io.ringin(1).bits.data
  io.ringin(1).ready        := osdtbb.io.ring_in1_ready
  io.ringout(0).valid       := osdtbb.io.ring_out0_valid
  io.ringout(0).bits.last   := osdtbb.io.ring_out0_last
  io.ringout(0).bits.data   := osdtbb.io.ring_out0_data
  osdtbb.io.ring_out0_ready := io.ringout(0).ready
  io.ringout(1).valid       := osdtbb.io.ring_out1_valid
  io.ringout(1).bits.last   := osdtbb.io.ring_out1_last
  io.ringout(1).bits.data   := osdtbb.io.ring_out1_data
  osdtbb.io.ring_out1_ready := io.ringout(1).ready

}

trait HasOSDImp extends LazyModuleImp  {
  val outer:   freechips.rocketchip.subsystem.BareSubsystem
  val osd      = Module(new OSD)

  //glip_uart:
  //glip_uart rx and tx connect at HasPeripheryUARTModuleImp
  val glip_rst = IO(Input(Bool())).autoSeed("glip_rst")     //glip reset signal(only key)
  osd.io.glip.rst := RegNext(RegNext(glip_rst.asBool()))
  osd.io.glip.rxd := Bool(true)  //receive nothing just for pass
  
  //osd_uart: UART Device Emulation Module
  //osd_uart connect at HasPeripheryUARTModuleImp
  osd.io.uartdem.in.ready  := Bool(true)
  osd.io.uartdem.out.bits  := UInt(0)
  osd.io.uartdem.out.valid := Bool(false)

  //osd_mam: Memory Access Module
  if(outer.isInstanceOf[HasOSDMAM]) {
    osd.io.mam <> outer.asInstanceOf[HasOSDMAM].osdmamnode.bundle 
  }

  //osd_scm: Subnet Control Module
  val sys_rst  = IO(Output(Bool())).autoSeed("sys_rst")     //to reset system(tile l2 mig bus! uart spi ...)
  sys_rst     := RegNext(RegNext(osd.io.scm.sys_rst))
  import freechips.rocketchip.prci.ClockBundle
  import freechips.rocketchip.subsystem.HasTiles
  if(outer.isInstanceOf[HasTiles]) {
    //outer.asInstanceOf[HasTiles].tile_prci_domains(0).module.auto.elements.find(_._2.isInstanceOf[ClockBundle]).get._2.asInstanceOf[ClockBundle].clock := clock
    outer.asInstanceOf[HasTiles].tile_prci_domains(0).module.auto.elements.find(_._2.isInstanceOf[ClockBundle]).get._2.asInstanceOf[ClockBundle].reset := RegNext(reset.asBool() | osd.io.scm.cpu_rst)   
  }

   //ring extend
   //ID   0     1     2      3      4      5
   //    HIM   SCM   UART   MAM    CTM    STM
   val osdring    = Module(new OSDRing(0, starID=4))
   osdring.io.ringin  <> osd.io.ringout
   osdring.io.ringout <> osd.io.ringin

}


