package freechips.rocketchip.pfc

import chisel3._
import chisel3.util._
import Chisel.fromBitsable
import freechips.rocketchip.rocket.CSR
import freechips.rocketchip.rocket.CSRs

class PFCConfigSig extends Bundle {
  val pfcmid    = UInt(log2Up(PFCManagerIds.maxIds).W)
  val pfcram    = Bool()        //bit 19
  val pfcpage   = UInt(7.W)     //bit 18 - 12
  val reserve   = UInt(7.W)     //bit 11 - 5
  val timeout   = Bool()        //bit 4
  val readerror = Bool()        //bit 3
  val empty     = Bool()        //bit 2
  val interrupt = Bool()        //bit 1
  val trigger   = Bool()        //lowest bits
}

class PFCCSRAccessIO extends Bundle {
  val addr      = Input(UInt(CSR.ADDRSZ.W))
  val cmd       = Input(UInt(CSR.SZ.W))
  val wdata     = Input(UInt(64.W))
  val rpfcc     = Output(UInt(64.W))
  val rpfcm     = Output(UInt(64.W))
  val rpfcr     = Output(UInt(64.W)) 
  val retire    = Input(Bool())
  val interrupt = Input(Bool())
}

class PFCClient(val clientID: Int) extends Module {
  val io = IO(new Bundle {
    val access = new PFCCSRAccessIO()
    val client = new PFCClientIO(clientID)
  })

  val pfcc_addr = CSRs.pfcc.U
  val pfcm_addr = CSRs.pfcm.U
  val pfcr_addr = CSRs.pfcr.U    
  val csr_pfcc  = Reg(new PFCConfigSig())
  val csr_pfcm  = Reg(UInt(64.W))
  val csr_pfcr  = Module(new Queue(UInt(64.W), 1))
  val timecount = RegInit(31.U)
  
  val (s_IDLE :: s_WREQ :: s_WRESP :: s_TIMEOUT :: Nil) = Enum(4)
  val state     = RegInit(s_IDLE)
  val ren       = io.access.cmd =/= CSR.N
  val wen       = io.access.cmd === CSR.W // || io.access.cmd === CSR.S && io.access.wdata =/= 0.U || io.access.cmd === CSR.C && io.access.wdata === 0.U 
  val stop      = wen && (io.access.addr === pfcc_addr || io.access.addr === pfcm_addr)
  val programID = RegInit(0.U(io.client.req.bits.programID.getWidth.W))
  val respque   = Module(new Queue(io.client.resp.bits.cloneType,  1))

  when((state === s_WRESP || state === s_TIMEOUT) && stop) { programID := programID+1.U}

  //csr_pfc write
  val wpfcc    = new PFCConfigSig().fromBits(io.access.wdata)
  wpfcc.interrupt  := csr_pfcc.interrupt
  wpfcc.readerror  := csr_pfcc.readerror
  wpfcc.timeout    := csr_pfcc.timeout
  when(wen) {
    when(io.access.addr === pfcc_addr) { csr_pfcc  := wpfcc }
    when(io.access.addr === pfcm_addr) {
      csr_pfcm             := io.access.wdata
      csr_pfcc.trigger     := false.B
      csr_pfcc.interrupt   := false.B
      csr_pfcc.readerror   := false.B
      csr_pfcc.timeout     := false.B
    }
  }
  //csr_pfc read
  val rpfcc    = WireInit(csr_pfcc)
  rpfcc.empty  := !csr_pfcr.io.deq.valid
  io.access.rpfcc := rpfcc.asUInt
  io.access.rpfcm := csr_pfcm
  io.access.rpfcr := csr_pfcr.io.deq.bits
  csr_pfcr.io.deq.ready := ren && io.access.addr === pfcr_addr

  //req
  io.client.req.valid := state === s_WREQ && !stop
  io.client.req.bits.src        := clientID.U
  io.client.req.bits.dst        := csr_pfcc.pfcmid
  io.client.req.bits.ram        := csr_pfcc.pfcram
  io.client.req.bits.page       := csr_pfcc.pfcpage
  io.client.req.bits.bitmap     := csr_pfcm
  io.client.req.bits.programID  := programID

  //resp     --->    respque
  respque.reset         :=  stop
  respque.io.enq.valid  :=  io.client.resp.valid
  respque.io.enq.bits   :=  io.client.resp.bits
  io.client.resp.ready  :=  respque.io.enq.ready

  //respque  --->    csr_pfcr
  csr_pfcr.reset        :=  stop
  csr_pfcr.io.enq.valid :=  respque.io.deq.valid && respque.io.deq.bits.programID === programID
  csr_pfcr.io.enq.bits  :=  respque.io.deq.bits.data
  respque.io.deq.ready  :=  csr_pfcr.io.enq.ready || state === s_TIMEOUT || respque.io.deq.bits.programID =/= programID

  //update csr_pfcc and csr_pfcm 
  when(!stop) {
    when(io.access.interrupt)    { csr_pfcc.interrupt := true.B }
    when(state === s_TIMEOUT)    { csr_pfcc.readerror := true.B }
    when(timecount === 0.U)                                  { csr_pfcc.timeout   := true.B  }
    when(!csr_pfcr.io.deq.valid && csr_pfcr.io.deq.ready)    { csr_pfcc.readerror := true.B  }
    when(csr_pfcr.io.enq.fire() && respque.io.deq.bits.last) { csr_pfcc.trigger   := false.B }


    when(io.client.req.fire()) {
      csr_pfcm := 0.U
    }.elsewhen(csr_pfcr.io.enq.fire()){
      csr_pfcm := csr_pfcm | 1.U << respque.io.deq.bits.bitmapUI
    }
  }

  //timeout 
  //csr_pfcr can only hold 2 events if software do not read, PFCManager may stall
  //after PFC resp we must read rpfcr as soon as possible otherwise it may drop data
  when(csr_pfcr.io.enq.ready) { 
    timecount := 31.U
  }.elsewhen(!csr_pfcr.io.enq.ready && csr_pfcr.io.enq.valid && io.access.retire) {
    timecount := timecount-1.U
  } 

  //update state

  when(io.client.req.fire())                                { state := s_WRESP     }
  when(state === s_WRESP && io.client.resp.bits.last)       { state := s_IDLE      }
  when(timecount === 0.U)                                   { state := s_TIMEOUT   }
  when(stop) {
    state := s_IDLE
    when(io.access.addr === pfcc_addr && wpfcc.trigger === true.B) { state := s_WREQ }
  }

  //debug
  when(csr_pfcr.io.deq.fire()) { printf("client %d get pfc %d\n", clientID.U, csr_pfcr.io.deq.bits) }
}
