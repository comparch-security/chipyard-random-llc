// DOC include start: MyDeviceController
package freechips.rocketchip.osd

import Chisel._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.pfc._

object OSDMAMParams {
  val dw:    Int = 16       //IODataWidth bits 
  val aw:    Int = 32       //IOAddrWidth bits
  val bw:    Int = 13       //IOBeatWidth bits
  val dwB:   Int = dw/8     //IODataWidth Bytes
  val lgdwB: Int = log2Ceil(dwB)

  require(dw==16)    //debug_system.sv : 74 : must be 16 
  require(isPow2(dw))
  require(isPow2(dwB))
}

//port direction is from OSDTopBlackBoxIO view
class OSDMAMReq() extends Bundle() {
  val rw    = Bool()    // 0: Read, 1: Write
  val addr  = UInt(OSDMAMParams.aw.W) //allign c.dwB
  val burst = Bool()    // 0: single, 1: incremental burst
  val beats = UInt(OSDMAMParams.bw.W)
  val pfc   = Bool()    // 0: mem, 1: pfc
}

class OSDMAMWData() extends Bundle() {
  val data = UInt(OSDMAMParams.dw.W)
  val strb = UInt(OSDMAMParams.dwB.W)
}

class OSDMAMRData() extends Bundle() {
  val data = UInt(OSDMAMParams.dw.W)
}

class OSDMAMIO() extends Bundle() {
  val req   = Decoupled(new OSDMAMReq()).flip
  val wdata = Decoupled(new OSDMAMWData()).flip
  val rdata = Decoupled(new OSDMAMRData())
}

class OSDMAM(implicit p: Parameters) extends LazyModule {
  val posdmam   = OSDMAMParams
  val mam_dw    = posdmam.dw
  val mam_dwB   = posdmam.dw/8
  val mam_mw    = log2Ceil(mam_dwB)
  val lgmam_dwB = log2Ceil(mam_dwB)

  val node = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v1(name = "OSD MAM", sourceId = IdRange(0, 8))))))

  val pfclnode   = BundleBridgeSource(() => (new PFCClientIO(p(freechips.rocketchip.subsystem.RocketTilesKey).length+1).cloneType))
  val pfccl      = InModuleBody { pfclnode.bundle }

  val osdmamnode = BundleBridgeSource(() => (new OSDMAMIO().flip).cloneType)
  val io = InModuleBody { osdmamnode.bundle }
  lazy val module = new LazyModuleImp(this) {
    val (mem, edge) = node.out(0)
    val mem_dw      = mem.a.bits.data.getWidth
    val mem_dwB     = mem.a.bits.data.getWidth / 8
    val mem_mw      = mem.a.bits.mask.getWidth
    val mem_sw      = mem.a.bits.size.getWidth 
    val lgmem_dwB   = log2Ceil(mem_dwB)
    val narrows     = mem_dw/mam_dw

    val req         = Reg(new OSDMAMReq())
    val req_endaddr = Reg(mem.a.bits.address)
    val busy        = RegInit(false.B)
    val putlegal    = RegInit(true.B)
    val getlegal    = RegInit(true.B)
    val srcID       = Reg(mem.a.bits.source)
    val srcIDv      = RegInit(~0.U((1 << srcID.getWidth).W))  //only used for write
    val rwaitacks   = RegInit(0.U((srcID.getWidth + 1).W))
    val rinflights  = RegInit(0.U((srcID.getWidth + 1).W))
    val wwaitacks   = RegInit(0.U((srcID.getWidth + 1).W))
    val winflights  = RegInit(0.U((srcID.getWidth + 1).W))

    val rs0_dq      = Module(new Queue(mem.d.bits,     4))    //width  read data Queue <--  fbus
    val rs2_dq      = Module(new Queue(io.wdata.bits, 32))    //narrow read data Queue -->  osd_mam
    val rs1_req     = Reg(new OSDMAMReq())
    val rs1_busy    = RegInit(false.B)
    val rs1_data    = Reg(Vec(narrows, UInt(mam_dw.W)))
    val rs1_full    = RegInit(false.B)                       //get full rs1_data
    val rs1_nasel   = rs1_req.addr(lgmem_dwB-1,lgmam_dwB)    //narrow sel 
    val next_rs1_beats  = rs1_req.beats - 1.U
    val next_rs1_addr   = rs1_req.addr + mam_dwB.U           //full addr
    val next_rs1_byaddr = next_rs1_addr(lgmem_dwB-1,0)       //byte addr in a beat

    val ws0_dq      = Module(new Queue(io.wdata.bits,  32))  //narrow write data Queue  <--  osd_mam
    val ws2_dq      = Module(new Queue(mem.a.bits,      8))  //width  write data Queue  -->  fbus
    val ws1_req     = Reg(new OSDMAMReq())
    val ws1_busy    = RegInit(false.B)
    val ws1_valid   = Wire(Bool())
    val ws1_ready   = Wire(Bool())
    val ws1_data    = Reg(Vec(narrows, UInt(mam_dw.W)))
    val ws1_mask    = Reg(Vec(narrows, UInt(mam_dwB.W)))
    val ws1_full    = RegInit(false.B)                        //get full ws1_data
    val ws1_nasel   = ws1_req.addr(lgmem_dwB-1,lgmam_dwB)     //narrow sel 
    val next_ws1_beats  = ws1_req.beats - 1.U
    val next_ws1_addr   = ws1_req.addr + mam_dwB.U            //full addr
    val next_ws1_byaddr = next_ws1_addr(lgmem_dwB-1,0)        //byte addr in a beat

    val enpfc     = true
    val pfcclient = Module(new OSDPFCClient(p(freechips.rocketchip.subsystem.RocketTilesKey).length, enpfc))
    // -------------req   stage-----------------------//
    io.req.ready := !busy
    rwaitacks    := rwaitacks  - (mem.d.fire() && !req.rw).asUInt() + (mem.a.fire() && !req.rw).asUInt()
    rinflights   := rinflights + (mem.a.fire() && !req.rw).asUInt()
    wwaitacks    := wwaitacks  - (mem.d.fire() &&  req.rw).asUInt() + (mem.a.fire() &&  req.rw).asUInt()
    winflights   := winflights + (mem.a.fire() &&  req.rw).asUInt()
    srcID        := srcID + mem.a.fire().asUInt()
    srcIDv       := (srcIDv & Mux(mem.a.fire(), ~UIntToOH(mem.a.bits.source), ~0.U((1 << srcID.getWidth).W))) | Mux(mem.d.fire(), UIntToOH(mem.d.bits.source), 0.U)
    when(io.req.fire()) {
      req         := io.req.bits
      busy        := true.B
      srcID       := 0.U
      rwaitacks   := 0.U
      rinflights  := 0.U
      wwaitacks   := 0.U
      winflights  := 0.U
      req.addr    := io.req.bits.addr // align??
      req_endaddr := io.req.bits.addr + (Mux(io.req.bits.burst, io.req.bits.beats, 1.U) << lgmam_dwB)
      when(io.req.bits.rw) {
        ws1_busy    := true.B
        ws1_full    := false.B
        ws1_req     := io.req.bits
        ws1_mask    := Vec(Fill(mam_dwB, "b0".U))
        when(!io.req.bits.burst) { ws1_req.beats := 1.U }
      }.otherwise {
        rs1_busy    := true.B
        rs1_full    := false.B
        rs1_req     := io.req.bits
        when(!io.req.bits.burst) { rs1_req.beats := 1.U }
      }
    }.otherwise {
      when(req.rw) {
        when(putlegal & !ws1_busy && !ws2_dq.io.deq.valid & !(wwaitacks.orR))  { busy := false.B }
      }.otherwise {
        when(getlegal & !rs1_busy && !rs2_dq.io.deq.valid & !(rwaitacks.orR))  { busy := false.B }
      }      
    }
    when(mem.a.fire()) {
      req.addr := req.addr + mem_dwB.U
    }

    // ------------- read stage -----------------------//
    rs0_dq.io.enq  <> mem.d
    rs0_dq.io.enq.valid     := Mux(req.pfc, pfcclient.io.resp.valid, mem.d.valid)  & rs1_busy
    rs0_dq.io.enq.bits.data := Mux(req.pfc, pfcclient.io.resp.bits,  mem.d.bits.data)
    rs0_dq.io.deq.ready     := !rs1_full
    when(rs1_busy) {
      when (rs0_dq.io.deq.fire()) {        // rs0_dq --> rs1_data
        rs1_full := true.B
        (0 until narrows).map(i => rs1_data(i) := rs0_dq.io.deq.bits.data((i+1)*mam_dw-1, i*mam_dw))
      }
      when (rs2_dq.io.enq.fire()) {
        rs1_req.addr  := next_rs1_addr
        rs1_req.beats := next_rs1_beats
        when(next_rs1_beats  === 0.U) { 
          rs1_busy := false.B 
          rs1_full := false.B
        }
        when(next_rs1_byaddr === 0.U) { rs1_full := false.B }
      }
    }

    rs2_dq.io.enq.valid      := rs1_full    
    rs2_dq.io.enq.bits.data  := rs1_data(rs1_nasel)
    io.rdata <> rs2_dq.io.deq

    // ------------- write stage -----------------------//
    ws0_dq.io.enq <>  io.wdata
    ws0_dq.io.deq.ready := ws1_ready

    when(ws1_busy) {
      when(ws0_dq.io.deq.fire()) {        // ws0_dq --> ws1_data
        ws1_full        := false.B
        ws1_req.addr    := next_ws1_addr
        ws1_req.beats   := next_ws1_beats
        when(next_ws1_beats === 0.U || next_ws1_byaddr === 0.U)  { ws1_full := true.B }
        (0 until narrows).map { i => when(ws1_nasel === i.U) {
          ws1_data(i) := ws0_dq.io.deq.bits.data
          when(ws1_req.burst === false.B) {
            ws1_mask(i) := ws0_dq.io.deq.bits.strb 
          }.otherwise {
            ws1_mask(i) := Fill(mam_dwB, "b1".U)
            (i+1 until narrows).map { ws1_mask(_) := Fill(mam_dwB, "b0".U) }
          }
        }}
      }
      when(ws1_valid && ws1_ready) {
        ws1_full := false.B
        when(ws1_req.beats === 0.U)  { ws1_busy  := false.B }
        when(ws0_dq.io.deq.fire() && (next_ws1_beats === 0.U || next_ws1_byaddr === 0.U)) { ws1_full := true.B }
      }
     }

    ws1_valid := ws1_full
    ws1_ready := !ws1_full || ws2_dq.io.enq.ready

    ws2_dq.io.enq.valid     := ws1_valid
    ws2_dq.io.enq.bits.data := ws1_data.asUInt
    ws2_dq.io.enq.bits.mask := ws1_mask.asUInt
    ws2_dq.io.deq.ready     := mem.a.ready && srcIDv(srcID)

    // ------------- mem stage -----------------------//
    val memget = edge.Get(
          fromSource = srcID,  //inflights
          toAddress  = Cat(req.addr >> lgmem_dwB, 0.U(lgmem_dwB.W)), //must align
          lgSize     = lgmem_dwB.U)
    val memput = edge.Put(
          fromSource = srcID,  //inflights
          toAddress  = Cat(req.addr >> lgmem_dwB, 0.U(lgmem_dwB.W)), //must align
          lgSize     = lgmem_dwB.U,
          data       = ws2_dq.io.deq.bits.data,
          mask       = ws2_dq.io.deq.bits.mask,
          corrupt    = false.B)
    mem.a.bits      := Mux(req.rw, memput._2, memget._2)
    mem.a.valid     := Mux(req.rw, ws2_dq.io.deq.valid, rs1_busy && (rwaitacks < 1.U) && (req.addr < req_endaddr)) && !req.pfc && srcIDv(srcID)
    mem.d.ready     := Mux(req.rw, true.B,  rs0_dq.io.enq.ready)
    /*when(mem.a.valid) {
      when(req.rw  & putlegal) { putlegal := memput._1 }
      when(!req.rw & getlegal) { getlegal := memget._1 }
    }*/

    // ------------- pfc stage -----------------------//
    pfccl <>  pfcclient.io.client
    if(enpfc) {
      val trigger = RegInit(false.B)
      when(io.req.fire() && io.req.bits.pfc && !io.req.bits.rw)  {
        trigger      :=  true.B
        rs1_req.addr := 0.U
      }
      pfcclient.io.req.bits   :=  req
      pfcclient.io.req.valid  :=  trigger
      pfcclient.io.resp.ready :=  rs0_dq.io.enq.ready
      when(pfcclient.io.req.fire()) { trigger := false.B  }
    } else {
      req.pfc := false.B
    }
  }
}


trait HasOSDMAM { this: BaseSubsystem =>
  implicit val p: Parameters
  val osdmam    = LazyModule(new OSDMAM()(p))
  val osdmamnode = osdmam.osdmamnode.makeSink()(p)
  fbus.fromPort(Some("OSD MAM"))() := osdmam.node
  pfbus.clnodes(p(freechips.rocketchip.subsystem.RocketTilesKey).length) := osdmam.pfclnode
}