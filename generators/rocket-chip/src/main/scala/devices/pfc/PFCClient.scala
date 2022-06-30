package freechips.rocketchip.pfc

import chisel3._
import chisel3.util._
import Chisel.fromBitsable
import freechips.rocketchip.rocket.CSR
import freechips.rocketchip.rocket.CSRs
import freechips.rocketchip.osd.OSDMAMReq

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

class CSRPFCClient(val clientID: Int, val nClients: Int) extends Module {
  val io = IO(new Bundle {
    val access = new PFCCSRAccessIO()
    val client = new PFCClientIO(nClients)
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

class OSDPFCClient(clientID: Int, nClients: Int, use: Boolean) extends Module { //OSDPFC use the last client ID
  val io = IO(new Bundle {
    val req    = Flipped(Decoupled(new OSDMAMReq()))
    val resp   = Decoupled(UInt(64.W))
    val client = new PFCClientIO(nClients)
  })

  if(!use) {
    io.req.ready            := false.B
    io.resp.valid           := false.B
    io.client.req.valid     := false.B
    io.client.resp.ready    := false.B
  } else {
    val (s_IDLE :: s_REQ :: s_REC :: s_TAIL ::  Nil) = Enum(4)
    val state = RegInit(s_IDLE)
    val req   = Reg(new PFCReq(nClients))
    val resp  = Module(new Queue(UInt(64.W), 1))
    val beats = Reg(UInt(11.W))
    val timecount = RegInit(63.U)

    //io.req   ---> req
    val pfcmid       = io.req.bits.addr(31, 24)
    val pfcram       = io.req.bits.addr(23)
    val pfcpage      = io.req.bits.addr(22, 16)
    /*val addrfour     = (0 until 16).map(i => Cat(io.req.bits.addr(i), io.req.bits.addr(i), io.req.bits.addr(i), io.req.bits.addr(i))) //narrow bit map
    val pfcrebitmap  = Cat(addrfour(15), addrfour(14), addrfour(13), addrfour(12),
                           addrfour(11), addrfour(10), addrfour(9),  addrfour(8),
                           addrfour(7),  addrfour(6),  addrfour(5),  addrfour(4),
                           addrfour(3),  addrfour(2),  addrfour(1),  "b1111".U) //ignore the lowest bit because it's always 0(align)
    val pfcrabitmap  = Cat(io.req.bits.addr(15, 1), "b0".U) //ignore the lowest bit because it's always 0(align)
    */
    val pfcrebitmap  = "h_ffff_ffff_ffff_ffff".U
    val pfcrabitmap  = 0.U(64.W)
    val pfcbitmap    = Mux(pfcram, pfcrabitmap,  pfcrebitmap)

    when(io.req.fire()) {
      beats         := io.req.bits.beats(12,2) //16bits per io.req.bits.beats
      req.dst       := pfcmid
      req.ram       := pfcram
      req.page      := pfcpage
      req.bitmap    := pfcbitmap
      //req.programID := req.programID + 1.U
    }
    when(io.client.resp.fire() && io.client.resp.bits.last) {
      when(req.ram)  {  req.bitmap := req.bitmap + 1.U } //read the remaning content in ram page: ram has more than 64 counters
      when(!req.ram) {  req.page   := req.page + 1.U   } //read the next reg page: reg page has 64 counters at most
    }
    io.req.ready    := state === s_IDLE

    //req   --> io.client.req
    io.client.req.valid           := state === s_REQ
    io.client.req.bits.src        := clientID.U
    io.client.req.bits.dst        := req.dst
    io.client.req.bits.ram        := req.ram
    io.client.req.bits.page       := req.page
    io.client.req.bits.bitmap     := Mux(req.ram, pfcrabitmap, pfcrebitmap) //pfcbitmap
    io.client.req.bits.programID  := 0.U //req.programID

    //io.client.resp  ---> resp
    resp.io.enq.valid     :=  io.client.resp.valid || (state === s_TAIL)
    resp.io.enq.bits      :=  Cat(Mux(state === s_TAIL, 1.U(1.W), 0.U(1.W)), io.client.resp.bits.data(62,0))
    io.client.resp.ready  :=  resp.io.enq.ready
    when(resp.io.enq.fire()) {
       beats := Mux(beats=/=0.U, beats-1.U, 0.U)
    }

    //resp    ---> io.resp
    io.resp  <> resp.io.deq

    //timeout
    when((state === s_REQ)  || (state === s_REC))     { timecount := timecount-1.U  }
    when(resp.io.enq.fire())                          { timecount := 63.U           }
    when((state === s_IDLE) || (state === s_TAIL))    { timecount := 63.U           }

    //state
    when(io.client.req.fire())                                       {  state := s_REC  }
    when(timecount === 0.U)                                          {  state := s_TAIL }
    when(io.client.resp.fire() && io.client.resp.bits.last)          {  state := s_REQ  }
    when(beats === 0.U || ((beats === 1.U) && resp.io.enq.fire()))   {  state := s_IDLE }
    when(io.req.fire())                                              {  state := s_REQ  }
  }

}
