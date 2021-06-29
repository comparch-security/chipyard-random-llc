package freechips.rocketchip.pfc

import chisel3._
import chisel3.util._
import Chisel.fromBitsable
//import chisel3.util.experimental.{forceName, InlineInstance}

class PFCDummyPage (nClients: Int = 0) extends MultiIOModule {
  val iomanager = IO(new PFCManagerIO(nClients))

  iomanager.req.ready  := true.B
  iomanager.resp.valid := false.B
  iomanager.resp.bits  := new PFCResp(nClients).fromBits(0.U)
}


class PFCTruePage (nClients: Int, pfcbt: PFCBundle) extends PFCDummyPage(nClients) {
  override val desiredName = pfcbt.name
  //val iomanager = IO(new PFCManagerIO(nClients))
  val busy     = RegInit(false.B)
  val reqreg   = Reg(new PFCReq(nClients, 1))
  val respque  = Module(new Queue(iomanager.resp.bits.cloneType, 1))
  
  respque.io.enq.bits.dst       := 0.U       //no use just for pass
  respque.io.enq.bits.src       := 0.U       //no use just for pass
  respque.io.enq.bits.programID := 0.U       //no use just for pass

  iomanager.req.ready  := !busy
  when(iomanager.req.fire()) {
    busy    := true.B
    reqreg  := iomanager.req.bits
  }
  
  //respque.deq                ---> iomanager.resp
  iomanager.resp.valid           := respque.io.deq.valid
  iomanager.resp.bits.dst        := reqreg.src
  iomanager.resp.bits.src        := reqreg.dst 
  iomanager.resp.bits.data       := respque.io.deq.bits.data
  iomanager.resp.bits.last       := respque.io.deq.bits.last
  iomanager.resp.bits.bitmapUI   := respque.io.deq.bits.bitmapUI
  iomanager.resp.bits.programID  := reqreg.programID
  respque.io.deq.ready           := iomanager.resp.ready

  when(iomanager.resp.fire()) {  // respque.io.deq.fire()
    busy := Mux(iomanager.resp.bits.last, false.B, true.B)
  }
}

class PFCRegPage (nClients: Int, rebt: PFCRegBundle ) extends PFCTruePage(nClients, rebt) {
  val events  = rebt.elements.toSeq.reverse  //!!reverse
  val nCounters = events.length
  require(nCounters<=64)

  val ioupdate = IO(MixedVec((0 until events.length).map( i => ((Input(events(i)._2.cloneType))))))
  val bitmap   = RegInit(0.U(nCounters.W))
  val counters = (0 until nCounters).map(i => RegInit(0.U(rebt.couws(i).W)))
  
  (0 until nCounters).map(i => { 
    counters(i).suggestName(events(i)._1)
    //chisel3.util.experimental.forceName(bitmap, "bitmapppp") //no effect ??? but chisel3.util.experimental.forceName(busy, "busyyyyyyyyy") in OSDMAM can work 
    counters(i) := counters(i) + RegNext(ioupdate(i).asUInt()) 
  })
  
  //io.manager.req             ---> reqreg
  when(iomanager.req.fire()) {
    bitmap    := iomanager.req.bits.bitmap(nCounters-1,0)
  }
  when(busy && !RegNext(busy)) {
    when(bitmap === 0.U) { busy := false.B } // receive bitmap 0000
  }

  //counters                    ---> respque.enq
  respque.io.enq.valid          := bitmap =/= 0.U
  respque.io.enq.bits.last      := PopCount(bitmap) === 1.U
  respque.io.enq.bits.data      := counters(nCounters-1)
  respque.io.enq.bits.bitmapUI  := (nCounters-1).U
  (nCounters-2 to 0 by -1).map( i => {      //low bit high priority
    when(bitmap(i)){
      respque.io.enq.bits.data     := counters(i)
      respque.io.enq.bits.bitmapUI := i.U
    } 
  })
  when(respque.io.enq.fire()) {
    when(bitmap(bitmap.getWidth-1)) { bitmap := 0.U }
    (bitmap.getWidth-2 to 0 by -1).map( i => {      //low bit high priority
      when(bitmap(i)){ bitmap  := Cat(bitmap(bitmap.getWidth-1, i+1), 0.U((i+1).W)) } 
    })
  }

  //respque.deq                ---> iomanager.resp (PFCOnePage)

}

class PFCRamPage (nClients: Int, rabt: PFCRamBundle) extends PFCTruePage(nClients, rabt) {
  val width  = rabt.ramw
  val length = rabt.raml
  val ioupdate = IO(rabt.cloneType)
  val s1_control = Wire(new Bundle {
    val resv  = Bool()  //resp valid
    val updv  = Bool()  //update valid
    val addr  = UInt(log2Up(length).W)  //update addr
    val data  = UInt(width.W)
  })
  val s2_control = Reg(s1_control.cloneType)
  val req_addr   = RegInit((length+1).U) //+1  ensure req_addr can > end_addr
  val end_addr   = Reg(UInt(log2Up(length).W))
  val bitmapUI   = Reg(UInt(log2Up(length).min(64).W)) 
  val updque     = Module(new Queue(UInt(log2Up(length).W),1))
  val (counters, _)   = freechips.rocketchip.util.DescribedSRAM(name = "record", desc = "PFC RAM", size = length, data = UInt(width.W))
  val readArb    = Module(new Arbiter(UInt(length.W), 2))
  val rst_cnt    = RegInit(0.U(log2Up(length+1).W))
  val rst        = WireInit(rst_cnt < length.U)
  
  //io.update       ---> updque.io.enq
  updque.io.enq.valid := ioupdate.valid
  updque.io.enq.bits  := ioupdate.addr

  //io.manager.req  ---> reqreg
  when(iomanager.req.fire()) {
    bitmapUI  := 0.U 
    if(length <= 64) {
      req_addr := 0.U
      end_addr := (length-1).U
    }
    else {
      req_addr  := Cat(iomanager.req.bits.bitmap(log2Up(length/64), 0), 0.U(6.W))
      val addr_add63 = Wire(Cat(iomanager.req.bits.bitmap(log2Up(length/64), 0), 63.U(6.W)))
      end_addr := Mux(addr_add63 > (length-1).U, (length-1).U, addr_add63)
    }
  }
  when(busy && !RegNext(busy)) {
    when(req_addr > end_addr) { busy := false.B }
  }


  // readArb
  readArb.io.in(0).valid := (req_addr <= end_addr) && updque.io.enq.ready && !respque.io.enq.valid && respque.io.enq.ready
  readArb.io.in(0).bits  := req_addr
  readArb.io.in(1).valid := updque.io.deq.valid
  readArb.io.in(1).bits  := updque.io.deq.bits
  updque.io.deq.ready    := readArb.io.in(1).ready
  readArb.io.out.ready   := !s2_control.updv

  // stage1 control
  s1_control.resv := RegNext(readArb.io.in(0).fire())
  s1_control.updv := RegNext(readArb.io.in(1).fire())
  s1_control.addr := RegNext(readArb.io.in(1).bits)  //updque.io.deq.bits
  s1_control.data := counters.read(readArb.io.out.bits, readArb.io.out.fire())
  // stage1 respque
  when(respque.io.enq.fire()) {
    req_addr := req_addr+1.U 
    bitmapUI := bitmapUI + 1.U    
  }
  respque.io.enq.valid          := s1_control.resv
  respque.io.enq.bits.last      := req_addr === end_addr
  respque.io.enq.bits.data      := s1_control.data
  respque.io.enq.bits.bitmapUI  := bitmapUI

  //respque.deq                ---> iomanager.resp (PFCOnePage)

  //stage2 updated
  rst_cnt         := Mux(rst, rst_cnt + 1.U, rst_cnt)
  s2_control.updv := Mux(rst, true.B,  s1_control.updv)
  s2_control.addr := Mux(rst, rst_cnt, s1_control.addr)
  s2_control.data := Mux(rst, 0.U,     s1_control.data + 1.U)
  when(s2_control.updv) { counters.write(s2_control.addr, s2_control.data) }
}

class PFCBook[REBT <: PFCRegBundle, RABT <: PFCRamBundle](nClients: Int, rebt: Option[Seq[REBT]] = None, rabt: Option[Seq[RABT]] = None) extends Module {
  require(!(rebt != None && rabt != None))
  val io = IO(new Bundle {
    val manager = new PFCManagerIO(nClients)
    val update = if(rebt != None) {
      val rebtget = rebt.get
      MixedVec((0 until rebtget.length).map( i => rebtget(i).cloneType))
    } else if(rabt != None) {
      val rabtget = rabt.get
      MixedVec((0 until rabtget.length).map( i => rabtget(i).cloneType))
    } else {
      MixedVec((0 until 1).map( i => new dummyPFCReg() ))  //dummy
    }
  })

  val nPages = if(rebt != None) rebt.get.size else if(rabt != None) rabt.get.size else 0

  require(nPages <= 64)
  
  if(nPages <= 0) {
    io.manager.req.ready  := true.B
    io.manager.resp.valid := false.B
    io.manager.resp.bits  := new PFCResp(nClients).fromBits(0.U)
  } else {

    val pfcpages = if(rabt != None) {
      val rabtget = rabt.get
      (0 until nPages).map(i => { if(rabtget(i).dummy)  Module(new PFCDummyPage()) else Module(new PFCRamPage(nClients, rabtget(i))) })
    } else {
      val rebtget = rebt.get
      (0 until nPages).map(i => { if(rebtget(i).dummy)  Module(new PFCDummyPage()) else Module(new PFCRegPage(nClients, rebtget(i))) })
    }
    
    val (s_IDLE :: s_REQ :: s_BUSY :: Nil) = Enum(3)
    val state         = RegInit(s_IDLE)
    val reqreg        = Reg(new PFCReq(nClients, nPages))
    val respque       = Module(new Queue(io.manager.resp.bits.cloneType,  1))

    //update
    if(rabt != None) {
       val rabtget = rabt.get
       (0 until nPages).map( page => {
        //chisel3.util.experimental.forceName(pfcpages(page), pfcpages(page).name)
        if(!rabtget(page).dummy) pfcpages(page).asInstanceOf[PFCRamPage].ioupdate := io.update(page).asInstanceOf[PFCRamBundle]
        //pfcpages(i).asInstanceOf[PFCRamPage].ioupdate := RegNext(io.update(i).asInstanceOf[PFCRamBundle]) //REG cannot be a bundle type with flips
      })
    } else if(rebt != None) {
      val rebtget = rebt.get
      val events  = rebtget.map(_.elements.toSeq.reverse) //all events in all reg page
      (0 until nPages).map( page => {
        //chisel3.util.experimental.forceName(pfcpages(page), pfcpages(page).name)
        val pevents = events(page)   //all events in one reg page
        val updsignals = {
          val pageioupd = io.update(page).elements
          require(pageioupd.size == pevents.size)
          (0 until pageioupd.size).map( j => pageioupd(pevents(j)._1) )
        }
        if(!rebtget(page).dummy) (0 until updsignals.size).map( j => { pfcpages(page).asInstanceOf[PFCRegPage].ioupdate(j) := RegNext(updsignals(j)) })
      })
    }

    //io.manager.req             ---> reqreg
    io.manager.req.ready   := state === s_IDLE
    when(io.manager.req.fire())   { reqreg := io.manager.req.bits }
    (0 until nPages).map( i => {
      //reqreg                  ---> pfcpages.io.manager.req
      pfcpages(i).iomanager.req.valid      := reqreg.page === i.U && state === s_REQ
      pfcpages(i).iomanager.req.bits       := reqreg

      //pfcpages.io.manager.resp ---> respque.enq
      if(i == 0) {
        respque.io.enq.valid               := pfcpages(0).iomanager.resp.valid
        respque.io.enq.bits                := pfcpages(0).iomanager.resp.bits 
      } else {
        when(reqreg.page === i.U) {
          respque.io.enq.valid             := pfcpages(i).iomanager.resp.valid
          respque.io.enq.bits              := pfcpages(i).iomanager.resp.bits  
        }
      }
      pfcpages(i).iomanager.resp.ready     := reqreg.page === i.U && respque.io.enq.ready
    })

    //respque.deq                ---> io.manager.resp
    io.manager.resp <> respque.io.deq
    io.manager.resp.bits.dst       := reqreg.src
    io.manager.resp.bits.src       := reqreg.dst
    io.manager.resp.bits.programID := reqreg.programID

    //state
    val allready = (0 until nPages).map(pfcpages(_).iomanager.req.ready).reduce(_&&_) 
    when(reqreg.page > (nPages-1).U)                                        { state  := s_IDLE }
    when(io.manager.req.fire() && io.manager.req.bits.page <= (nPages-1).U) { state  := s_REQ  }
    when(state === s_REQ  && allready)                                      { state  := s_BUSY }
    when(state === s_BUSY && allready && !respque.io.deq.valid)             { state  := s_IDLE }
  }
}

class PFCManager[REBT <: PFCRegBundle, RABT <: PFCRamBundle](nClients: Int, rebt: Option[Seq[PFCRegBundle]] = None, rabt: Option[Seq[PFCRamBundle]] = None) extends Module {
  val io = IO(new Bundle {
    val manager = new PFCManagerIO(nClients)
    val update = new Bundle{ 
      val reg = if(rebt == None) None else {
        val rebtget = rebt.get
        Some(MixedVec((0 until rebtget.length).map( i => rebtget(i).cloneType)))
      }
      val ram = if(rabt == None) None else {
        val rabtget = rabt.get
        Some(MixedVec((0 until rabtget.length).map( i => rabtget(i).cloneType)))
      }
    }
  })

  val regbook = Module(new PFCBook(nClients, rebt, None))
  val rambook = Module(new PFCBook(nClients, None, rabt))
  val hasramb = (rabt != None).B

  val (s_IDLE :: s_REQ :: s_BUSY :: Nil) = Enum(3)
  val state         = RegInit(s_IDLE)
  val reqreg        = Reg(new PFCReq(nClients))
  val respque       = Module(new Queue(io.manager.resp.bits.cloneType,  2))
  
  val nrebPages = if(rebt == None) 0 else rebt.get.size
  val nrabPages = if(rabt == None) 0 else rabt.get.size
  
  //book update
  (0 until nrebPages).foreach( i=> regbook.io.update(i) := io.update.reg.get(i) )
  (0 until nrabPages).foreach( i=> rambook.io.update(i) := io.update.ram.get(i) )
  if(nrabPages == 0) { rambook.io.update(0).asInstanceOf[dummyPFCReg].event := false.B }

  //io.manager.req             ---> reqreg
  reqreg := Mux(io.manager.req.fire(), io.manager.req.bits, reqreg)
  io.manager.req.ready  := state === s_IDLE

  //reqreg                     ---> book.io.manager.req
  regbook.io.manager.req.valid    := !reqreg.ram && state === s_REQ
  regbook.io.manager.req.bits     := reqreg
  rambook.io.manager.req.valid    := reqreg.ram && state === s_REQ
  rambook.io.manager.req.bits     := reqreg

  //regbook.io.manager.resp    ---> respque.enq
  respque.io.enq.valid            := Mux(hasramb && reqreg.ram,  rambook.io.manager.resp.valid, regbook.io.manager.resp.valid)
  respque.io.enq.bits             := Mux(hasramb && reqreg.ram,  rambook.io.manager.resp.bits,  regbook.io.manager.resp.bits)
  regbook.io.manager.resp.ready   := !reqreg.ram && respque.io.enq.ready
  rambook.io.manager.resp.ready   := reqreg.ram  && respque.io.enq.ready

  //respque.deq                 --> io.manager.resp
  io.manager.resp <> respque.io.deq
  io.manager.resp.bits.dst       := reqreg.src
  io.manager.resp.bits.src       := reqreg.dst
  io.manager.resp.bits.programID := reqreg.programID  

  //state
  when(Mux(hasramb && reqreg.ram, rambook.io.manager.req.ready, regbook.io.manager.req.ready)) {
    when(state === s_REQ )                                           { state  := s_BUSY }
    when(state === s_BUSY)                                           { state  := s_IDLE }
  }
  when(io.manager.req.fire())                                        { state  := s_REQ  }
}
