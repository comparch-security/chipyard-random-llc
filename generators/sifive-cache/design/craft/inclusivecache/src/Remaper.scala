

package sifive.blocks.inclusivecache

import Chisel._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.DescribedSRAM
import chisel3.VecInit
import chisel3.util.random.FibonacciLFSR
import MetaData._
import freechips.rocketchip.util._
import freechips.rocketchip.pfc._

import scala.math.{max, min}

object RTAL
{
  // locations
  val SZ = 1
  def LEFT  = UInt(0,SZ) //left
  def RIGH  = UInt(1,SZ) //right
}

class RandomTableReqIO(val w: Int) extends Bundle {
  val blkadr  = UInt(width = w) //cache block address
}

class RandomTableBase(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val rst   = Bool().asInput
    val mix   = UInt(width = params.remap.hkeyw ).asInput
    val req   = Decoupled(new RandomTableReqIO(params.blkadrBits)).flip //cache block address
    val resp  = UInt(width = params.setBits).asOutput    
  }

  val setBits    = params.setBits // l2 setbits
  val tagBits    = params.tagBits // l2 tagbits
  val hkeyw      = params.remap.hkeyw
  val hkeyspb    = params.remap.hkeyspb    
  val hkeyspbw   = params.remap.hkeyspbw   //idxw
  val userspbw   = params.remap.userspbw
  val banksw     = params.remap.banksw

  val reqtag     = io.req.bits.blkadr >> setBits
  val reqset     = io.req.bits.blkadr(setBits-1, 0)
  val rtab_resp  = Wire(UInt(width = hkeyw))
  
  def getr(data: UInt):         UInt = { Cat(data.asBools)                                } //get reverse
  def getl(full: UInt, w: Int): UInt = { full(w-1, 0)                                     } //get low part
  def getm(full: UInt, w: Int): UInt = { full(w+(full.getWidth-w)/2, (full.getWidth-w)/2) } //get middle part
  def geth(full: UInt, w: Int): UInt = { full(full.getWidth-1,       full.getWidth-1-w)   } //get high part

  val lfsr      = FibonacciLFSR.maxPeriod(hkeyw.max(setBits).max(tagBits)+2, true.B, seed = Some(1))
  val refblfsr  = Wire(Bool())                           //refersh
  val selectOH  = RegInit(UInt(1, width = 3))
  val blfsr     = RegEnable(lfsr + io.mix,             refblfsr)  //base lfsr
  val blfsrr    = getr(blfsr)
  val htag      = Cat((tagBits-1 to 0 by -2).map { i => getr(reqtag(i, 0.max(i-1)) + blfsr(i, 0.max(i-1)))  })
  val hset      = Cat((setBits-1 to 0 by -2).map { i => getr(reqset(i, 0.max(i-1)) + blfsrr(i, 0.max(i-1))) })
  val ghkey     =  {
      val selOH = Mux(reqtag(3) ^ blfsr(0), Mux(reqtag(4) ^ blfsr(1),  selectOH, Cat(selectOH(1,0), selectOH(2))), Cat(selectOH(0), selectOH(2,1)))
      RegNext(Mux1H(selOH, Seq(hset, reqset, getr(hset))) ^ geth(htag, setBits) ^ getm(htag, setBits) ^ getl(htag, setBits) ^ blfsrr)
  }
  when(refblfsr) { selectOH := Cat(selectOH(1,0), selectOH(2)) }
  
  //wiping the rtab with 0s on reset has ultimate priority
  val wipeData  = Reg(UInt(width = hkeyw))
  val wipeCount = RegInit(UInt(0,  width = hkeyspbw + 1))
  val wipeDone  = wipeCount(hkeyspbw)
  val wipeSet   = wipeCount(hkeyspbw - 1,0)

  val req = Wire(new Bundle {
    val v    = Bool()
    val idx  = UInt(width = hkeyspbw)
  })

  //req
  req.v         :=  io.req.fire()
  req.idx       :=  Mux1H(selectOH, Seq(reqset, getr(reqset), getm(Cat(getr(reqset), reqset), hkeyspbw))) ^ geth(reqtag, hkeyspbw) ^ getm(reqtag, hkeyspbw) ^ getl(reqtag, hkeyspbw)
  io.req.ready  :=  wipeDone

  //resp
  io.resp       := Cat(rtab_resp, getr(rtab_resp))  ^ ghkey(params.setBits-1, 0)

  //update
  when(!wipeDone) { wipeData := wipeData + (lfsr ^ io.mix) }
  when(reset)     { wipeData := io.mix }
  refblfsr  := !wipeDone && wipeData(0)
  wipeCount := Mux(io.rst, 0.U, Mux(wipeDone, wipeCount, wipeCount+1.U))

  if(params.remap.en) {
    val (rtab, _) = DescribedSRAM(name = "rtab", desc = "RandomTable RAM", size = hkeyspb, data = UInt(hkeyw.W))
    rtab_resp  := rtab.read(req.idx, req.v)
    when(!wipeDone) { rtab.write(wipeSet, wipeData) }
  } else {
    io.resp    := RegNext(reqset)    
  }
}

class RandomTableBankCmd extends Bundle {
  val rst  = Bool()
  val loc  = UInt(width = RTAL.SZ)
}

class RandomTableBankResp(val w: Int) extends Bundle {
  val lhset  = UInt(width = w)
  val rhset  = UInt(width = w)
}

class RandomTableBank(params: InclusiveCacheParameters) extends Module {
  val io = new Bundle {
    val mix     = UInt(width = params.remap.hkeyw ).asInput
    val req     = Decoupled(new RandomTableReqIO(params.blkadrBits)).flip //cache block address
    val cmd     = Valid(new RandomTableBankCmd).asInput
    val resp    = Valid(new RandomTableBankResp(params.setBits)).asOutput
    val readys  = Bool() //both side are ready
  }
  val lrtab = Module(new RandomTableBase(params))
  val rrtab = Module(new RandomTableBase(params))

  //req
  io.req.ready := RegNext(lrtab.io.req.ready | rrtab.io.req.ready)
  lrtab.io.req.valid     := io.req.valid
  lrtab.io.req.bits      := io.req.bits
  lrtab.io.rst           := io.cmd.valid && io.cmd.bits.rst && io.cmd.bits.loc === RTAL.LEFT
  lrtab.io.mix           := RegNext(io.mix ^ rrtab.io.resp)
  rrtab.io.req.valid     := io.req.valid
  rrtab.io.req.bits      := io.req.bits
  rrtab.io.rst           := io.cmd.valid && io.cmd.bits.rst && io.cmd.bits.loc === RTAL.RIGH
  rrtab.io.mix           := RegNext(Cat(io.mix(0), io.mix(params.remap.hkeyw-1, 1)) ^ lrtab.io.resp)

  //resp
  io.resp.valid         := RegNext(io.req.fire())
  io.resp.bits.lhset    := lrtab.io.resp
  io.resp.bits.rhset    := rrtab.io.resp

  io.readys             := lrtab.io.req.ready && rrtab.io.req.ready
}

class RandomTable(params: InclusiveCacheParameters) extends Module {

  val setBits   = params.setBits // l2 setbits
  val tagBits   = params.tagBits // l2 tagbits
  val channels  = params.remap.channels
  val hkeyw     = params.remap.hkeyw
  val banks     = params.remap.banks
  val banksw    = params.remap.banksw
  def bankID(req: RandomTableReqIO): UInt = if(banks == 1)  0.U  else req.blkadr(tagBits+banksw-1, tagBits) ^ req.blkadr(banksw-1, 0)

  val io = new Bundle{
    val cmd     = Valid(new RandomTableBankCmd).asInput
    val mix     = UInt(width = 128).asInput
    val req     = Vec(channels, Decoupled(new RandomTableReqIO(params.blkadrBits)).flip)  //C(release) X(ie:flush) A(acquire) R(remaper)  channel
    val resp    = Vec(channels, Valid(new RandomTableBankResp(setBits)).asOutput)         //C(release) X(ie:flush) A(acquire) R(remaper)  channel
    val readys  = Bool() //all banks are ready
  }

  val reqs      = (0 until channels).map(io.req(_))       //high prio -----> low prio
  val dsts      = Seq.fill(banks) { Reg(Vec(channels, Bool())) }
  val resps     = (0 until channels).map(io.resp(_))      //high prio -----> low prio

  val rtabs = Seq.fill(banks) { Module(new RandomTableBank(params)) }
  val req_arbs     = Seq.fill(banks)    { Module(new Arbiter(new RandomTableReqIO(params.blkadrBits),  channels)) }
  val resp_arbs    = Seq.fill(channels) { Module(new Arbiter(new RandomTableBankResp(params.setBits),  banks))    }

  //wiping the rtab with 0s on reset has ultimate priority
  val rstDone  = RegNext(Cat((0 until banks).map(rtabs(_).io.req.ready)).andR())
  //val rstDone  = RegNext(rtabs(0).io.req.ready)
  rtabs.zipWithIndex.map { case(rtab, id) => {
    rtab.io.mix  := io.mix((id+1)*hkeyw-1, id*hkeyw) + (id+1).U
    rtab.io.cmd  := io.cmd
  } }

  //req
  (0 until banks).map( b => {
    rtabs(b).io.req.bits      := req_arbs(b).io.out.bits
    rtabs(b).io.req.valid     := req_arbs(b).io.out.valid
    req_arbs(b).io.out.ready  := rstDone
    (0 until channels).map( c => {
      dsts(b)(c) := req_arbs(b).io.in(c).fire()
      req_arbs(b).io.in(c).bits  := reqs(c).bits
      req_arbs(b).io.in(c).valid := reqs(c).valid && (bankID(reqs(c).bits) === b.U) 
      when(bankID(reqs(c).bits) === b.U) { reqs(c).ready := req_arbs(b).io.in(c).ready }
    })
  })
  reqs(0).ready := rstDone //has the hightest priority

  //resp
  (0 until channels).map( c => {
    resps(c).bits  := resp_arbs(c).io.out.bits
    resps(c).valid := RegNext(reqs(c).fire())
    resp_arbs(c).io.out.ready := true.B
    (0 until banks).map( b => {
      resp_arbs(c).io.in(b).bits  := rtabs(b).io.resp.bits
      resp_arbs(c).io.in(b).valid := rtabs(b).io.resp.valid && dsts(b)(c)
    })
  })

  io.readys :=  RegNext(!io.cmd.valid && Cat(rtabs.map { _.io.readys }.reverse).orR())

  //ASSERT
  reqs zip resps  map { case(req, resp) => {
    when(resp.valid) { assert(Mux1H(1.U << RegNext(bankID(req.bits)), rtabs.map(_.io.resp.valid))) }
  }}


}

class SwaperReq(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params) {
  val set      = UInt(width = params.setBits)
  val way      = UInt(width = params.wayBits)
  val cloc     = UInt(width = RTAL.SZ) //current loc
  val nloc     = UInt(width = RTAL.SZ) //next loc
  val head     = Bool()
  val tail     = Bool()
}

class SwaperResp(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params) {
  val tag      = UInt(width = if(params.remap.en) params.blkadrBits else params.tagBits)
  val set      = UInt(width = params.setBits)  
  val way      = UInt(width = params.wayBits)
  val nots     = UInt(width = params.wayBits+1)   //have not swap counter
  val nop      = Bool()
  val head     = Bool()
  val tail     = Bool()                           //reach tail of remap chain
  val evict    = Valid(new DirectoryRead(params)) //need evict
  val dbhead   = Bool() //need head data block
  val dbswap   = Bool() //need swap data block
  val dbtail   = Bool() //need tail data block
}

class DirectoryEntrySwaper(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    //data req
    val write    = Valid(new DirectoryWrite(params)).flip
    val read     = Valid(new DirectoryRead(params)).flip // sees same-cycle write
    val result   = Valid(new DirectoryResult(params))
    //swap
    val iread    = Decoupled(new DirectoryRead(params))   //inspect read (inspect which way has not been remapped)
    val iresp    = Valid(Vec(params.cache.ways, new DirectoryEntry(params))).flip //all way result
    val iwrite   = Decoupled(new DirectoryWrite(params))  //inspect write
    val busy     = Bool()
    //remap
    val rreq     = Decoupled(new SwaperReq(params)).flip
    val rresp    = Valid(new SwaperResp(params))
    val newset   = Valid(UInt(width = params.setBits)).flip
  }
  val iways        = io.iresp.bits
  val rreq         = Reg(new SwaperReq(params))
  val swap_entry   = Reg(new DirectoryWrite(params))
  val resp_entry   = Wire(new DirectoryWrite(params)) //read from dir
  when(reset) {
    swap_entry.data.dirty     := false.B
    swap_entry.data.state     := INVALID
    swap_entry.data.clients   := 0.U
  }

  //sel the swap way
  val frees        = Cat(iways.map { case w => w.state   === INVALID                        }.reverse)
  val hits         = Cat(iways.map { case w => w.state   =/= INVALID && w.loc === rreq.cloc }.reverse) //need remap
  val evsels       = Cat(iways.map { case w => w.clients === 0.U     && w.dirty === false.B }.reverse) //eviction select
  val free         = frees.orR
  val hit          = hits.orR
  val evsel        = evsels.orR
  val frees_OH     = ~(leftOR(frees)   << 1)  & frees
  val hits_OH      = ~(leftOR(hits)    << 1)  & hits
  val evsels_OH    = ~(leftOR(evsels)  << 1)  & evsels
  val swapev       = evsel && (swap_entry.data.clients =/= 0.U || swap_entry.data.dirty =/= false.B) //swap eviction

  val s_idle       = RegInit(true.B)

  //swap_entry: read and write for MSHR
  //io.read
  //swap zone only belong to one set(either source or dest)
  io.result.valid         := RegNext(io.read.valid && io.read.bits.set === Mux(io.newset.valid, io.newset.bits, swap_entry.set) &&
                               Mux(io.write.valid, io.write.bits.data.state =/= INVALID && io.read.bits.tag === io.write.bits.data.tag,
                                                   swap_entry.data.state =/= INVALID && io.read.bits.tag === swap_entry.data.tag))
  io.result.bits          := swap_entry.data
  io.result.bits.swz      := true.B
  io.result.bits.hit      := io.result.valid
  io.result.bits.way      := 0.U
  //io.write
  when( io.write.valid )   {
    swap_entry := io.write.bits
    assert( io.write.bits.swz                         )
    assert( io.write.bits.set      === swap_entry.set )
    assert( swap_entry.data.state  =/= INVALID        )
  }

  //io.busy
  io.busy                 := io.rreq.valid || RegNext(io.rreq.valid)

  //io.rreq:   receive cmd from remaper
  io.rreq.ready           := s_idle && io.iread.ready
  when(io.rreq.fire())    { rreq  := io.rreq.bits }
  //io.iread:  read dir
  io.iread.valid          := s_idle && io.rreq.valid
  io.iread.bits.set       := io.rreq.bits.set
  io.iread.bits.tag       := 0.U

  //io.iresp:  receive dir
  val resp_entry_wayOH     = Mux(hit, hits_OH, Mux(free, frees_OH, evsels_OH))
  resp_entry.set          := rreq.set
  resp_entry.way          := OHToUInt(resp_entry_wayOH)
  resp_entry.data         := Mux1H(resp_entry_wayOH, iways)
  when( rreq.head & !hit ) { resp_entry.data.state :=  INVALID  }

  //io.iwrite: write dir
  io.iwrite.valid           :=  io.rresp.valid && !io.rresp.bits.nop && !(io.rresp.bits.evict.valid && !swapev)
  io.iwrite.bits.set        :=  rreq.set //resp_entry.set
  io.iwrite.bits.way        :=  resp_entry.way
  io.iwrite.bits.data       :=  swap_entry.data
  io.iwrite.bits.data.loc   :=  rreq.nloc
  when( rreq.head        )     {
    io.iwrite.bits.data.dirty    := false.B
    io.iwrite.bits.data.state    := INVALID
    io.iwrite.bits.data.clients  := 0.U
  }
  when( io.rreq.fire()   )     { swap_entry.set               :=  io.rreq.bits.set     }
  when( io.newset.valid  )     { swap_entry.set               :=  io.newset.bits       }
  when( io.iwrite.fire() )     {
    swap_entry.way          := resp_entry.way
    swap_entry.data         := resp_entry.data
    swap_entry.data.loc     := rreq.nloc
  }
  when( (io.rresp.valid && io.rresp.bits.nop) || (io.iwrite.fire() && io.rresp.bits.tail && !io.rresp.bits.evict.valid)) {
    swap_entry.data.dirty     := false.B
    swap_entry.data.state     := INVALID
    swap_entry.data.clients   := 0.U
  }

  //io.rresp:   resp next blkadr(tag) to remaper
  io.rresp.valid                 := io.iresp.valid
  io.rresp.bits.tag              := resp_entry.data.tag
  io.rresp.bits.set              := resp_entry.set
  io.rresp.bits.way              := resp_entry.way
  io.rresp.bits.nots             := PopCount(hits)
  io.rresp.bits.nop              := io.rresp.bits.head && io.rresp.bits.tail
  io.rresp.bits.head             := rreq.head
  io.rresp.bits.tail             := !hit
  io.rresp.bits.evict.valid      := io.rresp.valid && !io.rresp.bits.head && io.rresp.bits.tail && !free
  io.rresp.bits.evict.bits.swz   := true.B
  io.rresp.bits.evict.bits.tag   := Mux(swapev, Mux1H(evsels_OH, iways.map(_.tag)), swap_entry.data.tag)
  io.rresp.bits.evict.bits.set   := swap_entry.set
  io.rresp.bits.dbhead           := io.rresp.bits.head   && !io.rresp.bits.tail
  io.rresp.bits.dbswap           := (!io.rresp.bits.head && !io.rresp.bits.tail) ||  (io.rresp.bits.evict.valid && swapev)
  io.rresp.bits.dbtail           := !io.rresp.bits.head  && io.rresp.bits.tail   && !io.rresp.bits.evict.valid

  when( io.rreq.fire() ) { s_idle  :=  false.B }
  when( io.rresp.valid ) { s_idle  :=  true.B  }

  if(params.remap.enableDirEntrySwaperLog)  {
    val src_set = Reg(resp_entry.set.cloneType)
    val src_way = Reg(resp_entry.way.cloneType)
    when( io.iwrite.fire() )     {
      src_set       := resp_entry.set
      src_way       := resp_entry.way
    }
    when( io.rresp.valid && !(io.rresp.bits.head & io.rresp.bits.tail)) {
      when( io.rresp.bits.head                                                              )  {  printf("Entryswaper: head  tag %x from set %x way %x                                     \n", io.rresp.bits.tag,                           io.rresp.bits.set,  io.rresp.bits.way                       )
      } .elsewhen( !io.rresp.bits.head && !io.rresp.bits.tail                               )  {  printf("Entryswaper: swap  tag %x from set %x way %x to set %x way %x tag %x             \n", swap_entry.data.tag,  src_set,     src_way,  io.rresp.bits.set,  io.rresp.bits.way, resp_entry.data.tag  )
      } .elsewhen( !io.rresp.bits.head &&  io.rresp.bits.tail && !io.rresp.bits.evict.valid )  {  printf("Entryswaper: tail  tag %x from set %x way %x to set %x way %x                    \n", swap_entry.data.tag,  src_set,     src_way,  io.rresp.bits.set,  io.rresp.bits.way                       )
      } .elsewhen( !io.rresp.bits.head &&  io.rresp.bits.tail &&  io.rresp.bits.evict.valid )  {
        when(!swapev)                                                                          {  printf("Entryswaper: evict tag %x full set %x                                            \n", swap_entry.data.tag,                         io.rresp.bits.set                                           )
        }.otherwise                                                                            {  printf("Entryswaper: tail  tag %x from set %x way %x to set %x way %x evict tag %x       \n", swap_entry.data.tag,  src_set,     src_way,  io.rresp.bits.set,  io.rresp.bits.way, resp_entry.data.tag  )}
      } .otherwise                                                                             {  assert(!io.rresp.fire(), "Entryswaper: unexpected situation!!                            \n") } 
    }
  }
}

class DataBlockSwaper(params: InclusiveCacheParameters) extends Module
{
  //copy from BankedStore.scala
  val innerBytes = params.inner.manager.beatBytes
  val outerBytes = params.outer.manager.beatBytes
  val rowBytes = params.micro.portFactor * max(innerBytes, outerBytes)
  require (rowBytes < params.cache.sizeBytes)
  val rowEntries = params.cache.sizeBytes / rowBytes
  val rowBits = log2Ceil(rowEntries)
  val numBanks = rowBytes / params.micro.writeBytes
  val codeBits = 8*params.micro.writeBytes

  val bankBits = log2Up(numBanks)
  val beatBits = max(params.innerBeatBits, params.outerBeatBits)
  val beatBitspb = if(numBanks == 1) beatBits   //BeatBits per bank
                   else              beatBits - bankBits

  require(params.innerBeatBits == params.outerBeatBits)          //not sure when(innerBeatBits =/= outerBeatBits) this swaper can work
  require(innerBytes           == outerBytes          )          //not sure when(innerBytes    =/= outerBytes) this swaper can work
  require((numBanks <= 8    )  && (isPow2(numBanks))  )
  class Request(val indexW: Int) extends Bundle {
    val wen      = Bool()
    val index    = UInt(width = indexW)
    val data     = UInt(width = codeBits)
  }

  val io = new Bundle {
    //data req
    val req      = Vec(numBanks, Valid(new Request(beatBitspb)).asInput)
    val resp     = Vec(numBanks, Valid(UInt(width = codeBits)).asOutput)
    //swap
    val ireq     = Vec(numBanks, Valid(new Request(rowBits)).asOutput)
    val iresp    = Vec(numBanks, UInt(width = codeBits).asInput)
    val busy     = Bool()
    //remap
    val rreq     = Decoupled(new SwaperReq(params)).flip
  }

  //(s_ = state)
  val s_idle        = RegInit(true.B)

  val rreq_ready    = RegInit(true.B)

  val rreq          = Reg(new SwaperReq(params))
  val last_Beats    = ((1 << beatBitspb) - 1).U
  val labo_Beats    = ((1 << beatBitspb) - 2).U //last but one
  val read_beats    = Reg(UInt(width = beatBitspb))
  val write_beats   = Reg(UInt(width = beatBitspb))
  val swap_data     = Seq.tabulate(numBanks) {_  =>  Reg(Vec(params.cache.blockBytes/(numBanks*params.micro.writeBytes), UInt(width = codeBits))) }

  //io.req  ---> io.resp
  val io_req        = (0 until numBanks).map( io.req(_)  )
  val io_resp       = (0 until numBanks).map( io.resp(_) )
  io_req zip io_resp zip swap_data map { case ((req, resp), data) => {
    when(req.valid) { assert(s_idle) }
    when(req.valid & req.bits.wen) { data(req.bits.index) := req.bits.data }
      resp.valid   := RegNext(req.valid & !req.bits.wen)
      resp.bits    := RegEnable(data(req.bits.index), req.valid & !req.bits.wen)
  }}

  //remap
  //io.rreq
  io.busy          := !s_idle
  io.rreq.ready    := rreq_ready
  when(io.ireq(0).valid && ((read_beats === last_Beats && rreq.head) || (write_beats === last_Beats && io.ireq(0).bits.wen))) {
    s_idle         := true.B
  }
  when(io.ireq(0).valid) {
    when(rreq.head) {
      when( read_beats === labo_Beats  )                                                     { rreq_ready     := true.B  }
    }.elsewhen(rreq.tail) {
      when( write_beats === labo_Beats )                                                     { rreq_ready     := true.B  }
    }.otherwise {
      when( read_beats === last_Beats && write_beats === last_Beats && !io.ireq(0).bits.wen) { rreq_ready     := true.B  }
    }
  }
  when(io.rreq.fire()) {
    s_idle         := false.B
    rreq_ready     := false.B
    read_beats     := 0.U
    write_beats    := 0.U
    rreq           := io.rreq.bits
  }
  //io.ireq: read write data
  //head :           copy data from bank_data to swap_data
  //tail :           copy data from swap_data to bank_data
  //!head && !tail : swap bank_data and swap_data
  //head  && tail  : error
  val io_ireq       = (0 until numBanks).map( io.ireq(_)  )
  val io_iresp      = (0 until numBanks).map( io.iresp(_) )
  val wen           = RegNext(Mux(io.rreq.fire(), io.rreq.bits.tail, (io.ireq(0).valid && !io.ireq(0).bits.wen && !rreq.head) || rreq.tail))
  val index         = Cat(rreq.way, rreq.set, Mux(wen, write_beats, read_beats))
  io_ireq zip swap_data map { case (req, data) => {
    req.valid      := !s_idle
    req.bits.wen   := wen
    req.bits.index := index
    req.bits.data  := data(write_beats)
  }}
  when(io.ireq(0).valid) {
    when(io.ireq(0).bits.wen)  { write_beats := write_beats + 1.U }
    when(!io.ireq(0).bits.wen) { read_beats  := read_beats  + 1.U }
  }
  //io.iresp: receive data
  when(RegNext(io.ireq(0).valid & !io.ireq(0).bits.wen)) {
    io_ireq zip io_iresp zip swap_data map { case ((req, resp), data) => 
      data(RegNext(req.bits.index(beatBitspb-1, 0))) := resp
    }
  }

  when(io.rreq.fire()) {
    assert(!io.rreq.bits.head || !io.rreq.bits.tail) 
  }

  if(params.remap.enableDataBlockSwaperLog) {
    when(io.rreq.fire()) {
      when(  io.rreq.bits.head && !io.rreq.bits.tail ) { printf("Blockswaper: head from set %x way %x                  \n",                      io.rreq.bits.set,  io.rreq.bits.way) }
      when( !io.rreq.bits.head &&  io.rreq.bits.tail ) { printf("Blockswaper: tail from set %x way %x to set %x way %x \n", rreq.set, rreq.way,  io.rreq.bits.set,  io.rreq.bits.way) }
      when( !io.rreq.bits.head && !io.rreq.bits.tail ) { printf("Blockswaper: swap from set %x way %x to set %x way %x \n", rreq.set, rreq.way,  io.rreq.bits.set,  io.rreq.bits.way) }
    }
  }

}

object RMCMD //remaper cmd
{
  // locations
  val SZ = 1
  def START  = UInt(0,SZ) //left
}

class RemaperReqIO extends Bundle {
  val cmd        = UInt(width = RMCMD.SZ)
  val atdetec    = Bool() 
}

class SourceSinkRandomTableIO(val blkadrw: Int, val setw: Int) extends Bundle {
  val req    = Decoupled(new RandomTableReqIO(blkadrw))
  val resp   = Valid(new RandomTableBankResp(setw)).asInput
}

class RemaperXChannelIO(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params) {
  val sink   = Decoupled(new DirectoryRead(params))  //flush req
  val source = Decoupled(new SourceXRequest(params))
}

class RemaperSafeIO extends Bundle {
  val mshr      = Bool()
  val sinkA     = Bool()
  val sinkC     = Bool()
  val sinkX     = Bool()
  val sourceC   = Bool()
  val sourceD   = Bool()
  val all       = Bool()
}

class RemaperStatusIO(val setw: Int) extends Bundle {
  val cloc   = UInt(width = RTAL.SZ)  //current location
  val nloc   = UInt(width = RTAL.SZ)  //next location
  val sset   = UInt(width = setw)     //swap set
  val head   = UInt(width = setw)     //next location
  val oneloc = Bool()
  val evict  = Bool()

  val blockSinkA   = Bool()
  val blockSinkC   = Bool()
  val blockSinkX   = Bool()
}

class RemaperConfig extends Bundle {
  val max_blockcycles = UInt(width = 16)
  val en              = Bool() //lowest bit
}

class Remaper(params: InclusiveCacheParameters) extends Module {
  val io = new Bundle {
    val req        = Decoupled(new RemaperReqIO()).flip
    val status     = new RemaperStatusIO(params.setBits)
    val config     = new RemaperConfig().asInput
    //RandomTable
    val rtcmd      = Valid(new RandomTableBankCmd()).asOutput
    val rtreq      = Decoupled(new RandomTableReqIO(params.blkadrBits))
    val rtresp     = Valid(new RandomTableBankResp(params.setBits)).flip
    val rtreadys   = Bool().asInput
    //DirectoryEntrySwaper
    val dereq      = Decoupled(new SwaperReq(params))
    val deresp     = Valid(new SwaperResp(params)).flip
    val newset     = Valid(UInt(width = params.setBits))
    //DataBlockSwaper
    val dbreq      = Decoupled(new SwaperReq(params))
    //X Channel
    val evreq      = Decoupled(new DirectoryRead(params))
    val evresp     = Valid(new SourceXRequest(params)).flip
    //pfc
    val pfcupdate  = (new RemaperPFCReg()).flip
    //scheduler status
    val schreq     = Bool().asInput
    val schresp    = Bool().asInput
    val mshrbusy   = Bool().asInput
    val mshralloc  = Bool().asInput
    val mshrmatch  = Bool().asInput
  }

  val ways     = params.cache.ways
  val sets     = params.cache.sets
  val blocks   = sets*ways
  val lastset  = params.cache.sets - 1

  val dereq = Module(new Queue(io.dereq.bits, 1, pipe = false, flow = false ))
  val dbreq = Module(new Queue(io.dbreq.bits, 1, pipe = true,  flow = true  ))
  val rtreq = Module(new Queue(io.rtreq.bits, 1, pipe = false, flow = true  ))
  val evreq = Module(new Queue(io.evreq.bits, 1, pipe = false, flow = true  ))

  //(s_ = state), (w_ = waiting)
  val s_idle       = RegInit(true.B)
  val s_finish     = RegInit(false.B)
  val s_1stpause   = RegInit(true.B) //when receive req we should wait all safe
  val s_dir        = RegInit(false.B)
  val s_evict      = RegInit(false.B)
  val s_oneloc     = RegInit(true.B)
  val w_rtab       = RegInit(false.B)
  val w_dir        = RegInit(false.B)
  val w_evictdone  = RegInit(false.B)

  val loc_next     = RegInit(RTAL.LEFT)
  val loc_current  = RegInit(RTAL.RIGH)
  val p_head       = Reg(new SwaperResp(params))
  val p_current    = Reg(new SwaperResp(params))
  val finish       = s_finish && !io.schreq && !io.mshrbusy

  when(finish) {
     loc_next    := loc_current
     loc_current := loc_next
  }

  val config = Reg(new RemaperConfig())
  when(!s_idle)                  { config    := io.config       }
  //io.req
  when(io.req.fire()) {
    p_head.set              := 0.U
    p_head.nots             := ways.U
    p_head.head             := true.B
    p_current.set           := 0.U
    p_current.evict.valid   := false.B
  }
  io.req.ready        := s_idle

  //io.status
  io.status.cloc           := loc_current
  io.status.nloc           := loc_next
  io.status.sset           := dereq.io.enq.bits.set
  io.status.head           := p_head.set
  io.status.oneloc         := s_oneloc
  io.status.evict          := s_evict
  io.status.blockSinkA     := false.B
  io.status.blockSinkC     := false.B
  io.status.blockSinkX     := false.B

  //io.rtcmd
  io.rtcmd.valid           := finish
  io.rtcmd.bits.rst        := io.rtcmd.valid
  io.rtcmd.bits.loc        := loc_current

  //rtreq.io.enq
  rtreq.io.enq.valid       := io.deresp.valid && !io.deresp.bits.nop && !io.deresp.bits.tail
  rtreq.io.enq.bits.blkadr := io.deresp.bits.tag
  //io.rtreq
  io.rtreq.valid           := rtreq.io.deq.valid
  io.rtreq.bits            := rtreq.io.deq.bits
  rtreq.io.deq.ready       := io.rtreq.ready

  //io.rtresp
  when(io.rtresp.valid) {
    p_current.set      := Mux(loc_next === RTAL.RIGH, io.rtresp.bits.rhset, io.rtresp.bits.lhset)
  }

  //dereq.io.enq
  dereq.io.enq.valid      := w_dir && dbreq.io.enq.ready && !io.schreq && !io.schresp && !io.mshrbusy
  dereq.io.enq.bits.set   := Mux(io.rtresp.valid, Mux(loc_next === RTAL.RIGH, io.rtresp.bits.rhset, io.rtresp.bits.lhset), Mux(p_head.head, p_head.set, p_current.set))
  dereq.io.enq.bits.cloc  := loc_current
  dereq.io.enq.bits.nloc  := loc_next
  dereq.io.enq.bits.head  := p_head.head
  //io.dereq
  io.dereq.valid          := dereq.io.deq.valid
  io.dereq.bits           := dereq.io.deq.bits
  io.dereq.bits.way       := 0.U
  dereq.io.deq.ready      := io.dereq.ready
  when(io.dereq.fire()) {
    when(p_head.nots === 1.U && p_head.set =/= lastset.U) {
      p_head.set  := p_head.set + 1.U
      p_head.nots := ways.U
    }
  }
  io.newset.valid         := io.rtresp.valid
  io.newset.bits          := Mux(loc_next === RTAL.RIGH, io.rtresp.bits.rhset, io.rtresp.bits.lhset)

  //io.deresp
  //pointer
  when(io.deresp.valid) {
    p_head.head := false.B 
    p_current   := io.deresp.bits
    when(p_head.set === io.deresp.bits.set) {
      p_head.nots := io.deresp.bits.nots
      when(io.deresp.bits.nots === 0.U && p_head.set =/= lastset.U) {
        p_head.set  := p_head.set + 1.U
        p_head.nots := ways.U
      }
    }
    when(io.deresp.bits.tail) { p_head.head := true.B }
  }

  //dbreq.io.enq
  //head :           copy data from bank_data to swap_data
  //tail :           copy data from swap_data to bank_data
  //!head && !tail : swap bank_data and swap_data
  //head  && tail  : error
  dbreq.io.enq.valid       := io.deresp.valid && (io.deresp.bits.dbhead || io.deresp.bits.dbswap || io.deresp.bits.dbtail)
  dbreq.io.enq.bits.set    := io.deresp.bits.set
  dbreq.io.enq.bits.way    := io.deresp.bits.way
  dbreq.io.enq.bits.head   := io.deresp.bits.dbhead
  dbreq.io.enq.bits.tail   := io.deresp.bits.dbtail
  dbreq.io.enq.bits.nloc   := loc_next
  //io.dbreq.deq
  io.dbreq.valid           := dbreq.io.deq.valid
  io.dbreq.bits            := dbreq.io.deq.bits
  io.dbreq.bits.nloc       := loc_next
  dbreq.io.deq.ready       := io.dbreq.ready

  //evreq.io.enq
  evreq.io.enq.valid       := io.deresp.bits.evict.valid
  evreq.io.enq.bits.swz    := io.deresp.bits.evict.bits.swz
  evreq.io.enq.bits.set    := io.deresp.bits.evict.bits.set
  evreq.io.enq.bits.tag    := io.deresp.bits.evict.bits.tag
  //io.evreq
  io.evreq.valid           := evreq.io.deq.valid
  io.evreq.bits            := evreq.io.deq.bits
  evreq.io.deq.ready       := io.evreq.ready

  //io.evresq
  when(io.evresp.valid)   { w_evictdone   := false.B   }

  //state machine
  when( finish                               )              {   s_idle       := true.B    }
  when( io.req.fire()                        )              {   s_idle       := false.B   }
  when( io.req.fire()                        )              {   s_1stpause   := true.B    }
  when( dereq.io.enq.fire()                  )              {   s_1stpause   := false.B   }
  when( io.deresp.valid                      )              {   s_dir        := true.B    }
  when( dereq.io.enq.valid || s_idle         )              {   s_dir        := false.B   }
  when( evreq.io.enq.valid                   )              {   s_evict      := true.B    }
  when( io.evresp.valid                      )              {   s_evict      := false.B   }
  when( finish                               )              {   s_oneloc     := true.B    }
  when( dereq.io.enq.fire()                  )              {   s_oneloc     := false.B   }
  when( finish                               )              {   s_finish     := false.B   }
  when( io.deresp.valid && io.deresp.bits.set === lastset.U && io.deresp.bits.nop ) {
    s_finish := true.B 
  }

  when(s_idle | s_finish) {
    w_rtab       := false.B
    w_dir        := false.B
    w_evictdone  := false.B
  }.otherwise {
    when(!w_dir && evreq.io.enq.ready) {
      when( s_1stpause && dbreq.io.enq.ready && io.rtreadys && !io.schreq && !io.mshrbusy ) {  w_dir   := true.B   }  //first swap req
      when( io.deresp.valid ) {
        when( io.deresp.bits.nop && io.deresp.bits.set =/= lastset.U                      ) {  w_dir   := true.B   }  //need do nothing  
        when( !io.deresp.bits.nop && io.deresp.bits.tail && !io.deresp.bits.evict.valid   ) {  w_dir   := true.B   }  //tail but not need evict
      }
      when( w_evictdone && io.evresp.valid                                                ) {  w_dir   := true.B   }  //wait evict done
      when( io.rtreq.fire()                                                               ) {  w_dir   := true.B   }
    }
    when( dereq.io.enq.fire() ) {  w_dir := false.B  }
    when( io.deresp.valid && !io.deresp.bits.nop ) {
      w_rtab          := true.B
      when(io.deresp.bits.tail)        {  w_rtab        := false.B    }
      when(io.deresp.bits.evict.valid) {  w_evictdone   := true.B     }                 //need evict
    }
    when(io.rtreq.fire())              {    w_rtab      := false.B    }                 //RandomTable_req
    when(io.evresp.fire())             {  w_evictdone   := false.B    }                 //evict resp
  }

  //pfc
  io.pfcupdate.busy      := !s_idle
  io.pfcupdate.nop       := io.deresp.valid && io.deresp.bits.nop
  io.pfcupdate.swap      := io.dbreq.fire()
  io.pfcupdate.evict     := io.evreq.fire()
  io.pfcupdate.ebusy     := !s_idle && w_evictdone && io.dereq.ready
  io.pfcupdate.pause     := w_dir  && dbreq.io.enq.ready && !dereq.io.enq.valid
  io.pfcupdate.finish    := finish
  io.pfcupdate.atdetec   := io.req.fire() && io.req.bits.atdetec

  if(params.remap.enableRemaperLog) {
    val timer        = RegInit(UInt(0, width = log2Up(blocks)+6))
    val nops         = RegInit(UInt(0, width = log2Up(sets)))
    val remaps       = RegInit(UInt(0, width = 10))
    val swaps        = RegInit(UInt(0, width = log2Up(blocks)+1))
    val evicts       = RegInit(UInt(0, width = log2Up(blocks)-2))
    val ebusys       = RegInit(UInt(0, width = log2Up(blocks)+4))
    val pauses       = RegInit(UInt(0, width = 20))

    when( io.pfcupdate.busy      ) { timer  := timer  + 1.U  }
    when( io.pfcupdate.nop       ) { nops   := nops   + 1.U  }
    when( io.pfcupdate.swap      ) { swaps  := swaps  + 1.U  }
    when( io.pfcupdate.evict     ) { evicts := evicts + 1.U  }
    when( io.pfcupdate.ebusy     ) { ebusys := ebusys + 1.U  }
    when( io.pfcupdate.pause     ) { pauses := pauses + 1.U  }
    when( io.pfcupdate.finish    ) {
      timer   := 0.U
      nops    := 0.U
      swaps   := 0.U
      evicts  := 0.U
      ebusys  := 0.U
      pauses  := 0.U
      remaps  := remaps + 1.U
      printf("remap_%ds: use cycle %d swaps %d evicts %d ebusys %d nops %d pauses %d\n",remaps, timer, swaps, evicts, ebusys, nops, pauses)
    }
    val stalltime = RegInit(UInt(0, width = 12))
    stalltime := Mux(s_idle || io.dereq.fire() || io.evreq.fire(), 0.U, stalltime+1.U)
    assert(stalltime < 2000.U)

    when(finish || s_idle)    { assert(!w_dir)              }
    when(finish || s_idle)    { assert(!io.dereq.valid)     }
    when(io.dereq.valid)      { assert(io.dereq.ready)      }
    //when(io.rtreq.valid)      { assert(io.rtreq.ready)      }
    when(dbreq.io.enq.valid)  { assert(dbreq.io.enq.ready)  }
    when(io.dereq.valid && !io.dereq.bits.head)      { assert(io.rtresp.valid || !w_rtab) }

  }
}
