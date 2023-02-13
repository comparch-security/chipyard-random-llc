

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
import freechips.rocketchip.subsystem.L2SetIdxHash._

import scala.math.{max, min}

object RTAL
{
  // locations
  val SZ = L2RTAL.SZ
  def LEFT  = L2RTAL.LEFT //left
  def RIGH  = L2RTAL.RIGH //right
}

class RandomTableReqIO(val w: Int) extends Bundle {
  val blkadr  = UInt(width = w) //cache block address
}

class RandomTableBankCmd extends Bundle {
  val rst  = Bool()
  val send = Bool()
  val loc  = UInt(width = RTAL.SZ)
}

class RandomTableBankResp(val w: Int) extends Bundle {
  val lhset  = UInt(width = w)
  val rhset  = UInt(width = w)
}

class RandomTableIO(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params) {
  val cmd     = Valid(new RandomTableBankCmd).asInput
  val mix     = Valid(UInt(width = 32)).asInput
  val req     = Vec(params.remap.channels, Decoupled(new RandomTableReqIO(params.maxblkBits)).flip)  //C(release) X(ie:flush) A(acquire) R(remaper)  channel
  val resp    = Vec(params.remap.channels, Valid(new RandomTableBankResp(params.setBits)).asOutput)         //C(release) X(ie:flush) A(acquire) R(remaper)  channel
  val sendRan = Decoupled(new L2SetIdxHashFillRan())
  val wipeDone  = Bool()
  val sendDone  = Bool()
}

class Maurice2015Hua2011Table(params: InclusiveCacheParameters) extends Module {

  val setBits   = params.setBits // l2 setbits
  val tagBits   = params.tagBits // l2 tagbits
  val channels  = params.remap.channels
  val sets      = params.cache.sets
  val blkBits   = L2SetIdxHashFun.blkBits
  val rtdelay   = params.remap.rtdelay
  val rtsize    = params.remap.rtsize
  val hkeyBits  = params.remap.hkeyBits
  val rtidxBits = log2Up(rtsize)

  val io        = new RandomTableIO(params)
  val sethash   = Module(new Maurice2015Hua2011(channels, setBits, blkBits, rtsize, hkeyBits))
  val sendRanQ  = Module(new Queue(io.sendRan.bits.cloneType, 1, pipe = false, flow = false))
  val litPNGor  = Module(new PermutationsGenerator((1<<1) - 1))
  //val bigPNGor  = Module(new PermutationsGenerator(sets - 1))

  val s_0 :: s_1 :: s_2 :: s_f :: Nil = Enum(Bits(), 4)
  val cmdloc     = RegEnable(io.cmd.bits.loc, io.cmd.valid && io.cmd.bits.rst)
  val wipeCount  = RegInit(UInt(1 << rtidxBits,  width = rtidxBits + 1))
  val wipeDone   = wipeCount(rtidxBits)
  val wipeStage  = Reg(init = s_f)
  io.wipeDone    := wipeStage === s_f
  io.sendDone    := io.wipeDone

  when( !wipeDone                                ) {   wipeCount := wipeCount + 1.U                   }
  when( wipeDone  && wipeStage =/= s_f           ) {   wipeCount := 0.U; wipeStage := wipeStage + 1.U }
  when( io.cmd.valid && io.cmd.bits.rst          ) {   wipeCount := 0.U; wipeStage := s_0             }

  //repermute
  litPNGor.io.swap.valid             := io.mix.valid
  litPNGor.io.swap.bits              := io.mix.bits
  //bigPNGor.io.swap.valid             := RegNext(io.mix.valid)
  //bigPNGor.io.swap.bits              := io.mix.bits(setBits, 0)

  //fill
  litPNGor.io.active                 := io.cmd.valid && io.cmd.bits.rst
  //bigPNGor.io.active                 := io.cmd.valid && io.cmd.bits.rst
  sethash.io.fillRan.valid           := wipeStage === s_0
  sethash.io.fillRan.bits.loc        := cmdloc
  sethash.io.fillRan.bits.addr       := wipeCount
  sethash.io.fillRan.bits.random     := io.mix.bits
  sethash.io.fillSram.valid          := wipeStage === s_1
  //sethash.io.fillSram.bits           := litPNGor.io.fill.bits
  sethash.io.fillSram.bits.addr      := wipeCount
  sethash.io.fillSram.bits.number    := io.mix.bits
  sethash.io.fillSram.bits.loc       := cmdloc
  litPNGor.io.fill.ready             := true.B
  //bigPNGor.io.fill.ready             := true.B

  (0 until channels).map { ch => {
    //req
    sethash.io.req(ch).valid         := io.req(ch).valid
    sethash.io.req(ch).bits.blkadr   := io.req(ch).bits.blkadr
    io.req(ch).ready                 := sethash.io.req(ch).ready
    //resp
    io.resp(ch).valid                := sethash.io.resp(ch).valid
    io.resp(ch).bits.lhset           := sethash.io.resp(ch).bits.lhset
    io.resp(ch).bits.rhset           := sethash.io.resp(ch).bits.rhset

    //resp
    require(rtdelay > 0)
    if(rtdelay == 1) {
      io.resp(ch).valid                := sethash.io.resp(ch).valid
      io.resp(ch).bits.lhset           := sethash.io.resp(ch).bits.lhset
      io.resp(ch).bits.rhset           := sethash.io.resp(ch).bits.rhset
    } else if(rtdelay > 1) {
      val resp_valid_delay            = Reg(Vec((rtdelay - 1), Bool()))
      val resp_value_delay            = Reg(Vec((rtdelay - 1), new RandomTableBankResp(params.setBits)))
      resp_valid_delay(0)            := sethash.io.resp(ch).valid
      resp_value_delay(0).lhset      := sethash.io.resp(ch).bits.lhset
      resp_value_delay(0).rhset      := sethash.io.resp(ch).bits.rhset
      for(i <- 1 until rtdelay-1) {
        resp_valid_delay(i) := resp_valid_delay(i-1)
        resp_value_delay(i) := resp_value_delay(i-1)
      }
      io.resp(ch).valid              := resp_valid_delay(rtdelay - 2)
      io.resp(ch).bits               := resp_value_delay(rtdelay - 2)
    }
  }}

}

trait HasRandomBroadCaster { this: sifive.blocks.inclusivecache.InclusiveCache =>
  val (ranbcternode, ranbcterio) = {
    val nClients = p(freechips.rocketchip.subsystem.RocketTilesKey).length
    val node = BundleBridgeSource(() => Flipped((new L2RanNetMasterIO().cloneType)))
    
    val io  = InModuleBody { node.bundle }
    (node, io)
  }
  def createRandomBroadCaster(schedulers: Seq[Scheduler]) = {
    import chisel3.util.random.FibonacciLFSR
    val lfsr          = FibonacciLFSR.maxPeriod(64, true.B, seed = Some(123456789))
    /*val ranGen1       = Reg(UInt(width =     schedulers(0).io.ranMixOut.getWidth))
    val ranGen2       = Reg(UInt(width =     128))
    val ranGen3       = Reg(UInt(width =     128))
    val ranGen4       = Reg(UInt(width =     schedulers(0).io.ranMixIn.getWidth))
    ranGen1          := L2SetIdxHashFun.PermutationStage(L2SetIdxHashFun.tx3nt(schedulers.map(_.io.ranMixOut).reduce(_^_), 1))
    ranGen2          := L2SetIdxHashFun.PermutationStage(L2SetIdxHashFun.tx3nt(ranGen1 ^ lfsr, 2))
    ranGen3          := L2SetIdxHashFun.tx3nt(ranGen2, 3)
    ranGen4          := L2SetIdxHashFun.XorConcentrate(ranGen3, ranGen4.getWidth)
    val sendRanQ      = Module(new Queue(schedulers(0).io.sendRan.bits.cloneType, 1, pipe = false, flow = false))
    val sendRanArb    = Module(new RRArbiter(schedulers(0).io.sendRan.bits.cloneType, schedulers.length))
    schedulers zip sendRanArb.io.in map { case(s, arb) => {
      s.io.ranMixIn       := lfsr
      arb.valid           := s.io.sendRan.valid
      arb.bits            := s.io.sendRan.bits
      s.io.sendRan.ready  := arb.ready
      //require(s.io.ranMixOut.getWidth == ranGen1.getWidth)
      //require(s.io.ranMixIn.getWidth  <= ranGen4.getWidth)
    }}
    sendRanQ.io.enq        <> sendRanArb.io.out*/
    schedulers(0).io.ranMixIn := lfsr
    ranbcterio.send           <> schedulers(0).io.sendRan
  }
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
    val invblk   = Valid(new DirectoryWrite(params))     //invalid dir array block
    //swap
    val iresp    = Valid(Vec(params.cache.ways, new DirectoryEntry(params))).flip //inspect all way result
    //remap
    val rreq     = Decoupled(new SwaperReq(params)).flip
    val rresp    = Valid(new SwaperResp(params))
    val rfinish  = Valid(UInt(width = params.setBits)).flip
  }
  val wen          = io.write.valid && io.write.bits.swz
  val mta          = io.write.valid && io.write.bits.mta
  val iways        = io.iresp.bits
  val rreq         = Reg(new SwaperReq(params))
  val invblk       = Reg(Valid(new DirectoryWrite(params))) //invalid dir array block
  val mshr_entry   = Reg(new DirectoryWrite(params))  //will copied from swap_entry to meta_array by mshr
  val swap_entry   = Reg(new DirectoryWrite(params))
  val resp_entry   = Wire(new DirectoryWrite(params)) //read from dir

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
  def init_meta(meta: DirectoryEntry) {
    meta.tag       := 0.U
    meta.dirty     := false.B
    meta.state     := INVALID
    meta.clients   := 0.U
  }

  when(reset) {
    invblk.valid := false.B
    init_meta(mshr_entry.data)
    init_meta(swap_entry.data)
  }

  //swap_entry: read and write for MSHR
  //io.read
  //swap zone only belong to one set(either source or dest)
  //meta_array -> swap_entry -> mshr_entry -> meta_array
  //write -> swap_entry
  //read swap zone will trigger swap so ignore match
  val rhit_swap            = !io.read.bits.swz && io.read.bits.set === swap_entry.set && io.read.bits.tag === swap_entry.data.tag    && swap_entry.data.state    =/= INVALID  && !wen
  io.result.valid         := RegNext(io.read.valid && (io.read.bits.swz  || rhit_swap))
  io.result.bits          := RegEnable(swap_entry.data, io.read.valid)
  io.result.bits.swz      := RegEnable(rhit_swap, io.read.valid) //read swap zone will trigger swap
  io.result.bits.hit      := io.result.valid
  io.result.bits.way      := Mux(io.result.bits.swz, 0.U, resp_entry.way)
  io.result.bits.loc      := rreq.nloc
  io.result.bits.stm      := io.rresp.valid && !io.rresp.bits.nop && !(io.rresp.bits.evict.valid && !swapev)
  io.invblk               := invblk
  //io.write
  when(wen)   {
    swap_entry := io.write.bits
    val write_legal =  io.write.bits.swz && swap_entry.data.state  =/= INVALID &&
                       io.write.bits.set === swap_entry.set && (io.write.bits.data.tag === swap_entry.data.tag || io.write.bits.data.state === INVALID)
    when(!write_legal) {
      when(swap_entry.data.state === INVALID) { printf("can not update swz") }
      printf("write to swz   set_%x tag_%x\n", io.write.bits.set, io.write.bits.data.tag)
      printf("but swap_entry set_%x tag_%x\n",    swap_entry.set,    swap_entry.data.tag)
    }
    assert(write_legal)
  }

  //io.rreq:   receive cmd from remaper
  io.rreq.ready           := s_idle
  when(io.rreq.fire())                        { rreq  := io.rreq.bits }
  when(io.iresp.valid)                        { assert(!s_idle)       }
  when(io.read.valid && io.read.bits.swz )    { assert(!s_idle)       }

  //io.iresp:  receive dir
  val resp_entry_wayOH     = Mux(hit, hits_OH, Mux(free, frees_OH, evsels_OH))
  resp_entry.set          := rreq.set
  resp_entry.way          := OHToUInt(resp_entry_wayOH)
  resp_entry.data         := Mux1H(resp_entry_wayOH, iways)
  when( rreq.head & !hit ) { resp_entry.data.state :=  INVALID  }

  when(mta) { invblk.valid := false.B; init_meta(mshr_entry.data) }
  when(io.rfinish.valid)   { swap_entry.set  :=  io.rfinish.bits }
  when(io.rreq.fire())     {
    invblk.valid := false.B
    init_meta(mshr_entry.data)
    swap_entry.set            :=  io.rreq.bits.set
    swap_entry.data.loc       :=  io.rreq.bits.nloc
    when(io.rreq.bits.head)  {
      assert(swap_entry.data.state === INVALID)
      init_meta(swap_entry.data) 
    }
  }
  when(io.rresp.valid)    {
    when(io.result.bits.stm) {
      mshr_entry                := swap_entry
      invblk.valid              := true.B
      invblk.bits               := resp_entry
      swap_entry.data           := resp_entry.data
      swap_entry.data.loc       := rreq.nloc
    }.otherwise  { init_meta(mshr_entry.data) }
    when(io.rresp.bits.nop || (io.rresp.bits.tail && !io.rresp.bits.evict.valid)) { invblk.valid := false.B; init_meta(swap_entry.data) }
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
  io.rresp.bits.evict.bits.tag   := Mux(swapev, Mux1H(evsels_OH, iways.map(_.tag)), swap_entry.data.tag)
  io.rresp.bits.evict.bits.set   := swap_entry.set
  io.rresp.bits.dbhead           := io.rresp.bits.head   && !io.rresp.bits.tail
  io.rresp.bits.dbswap           := (!io.rresp.bits.head && !io.rresp.bits.tail) ||  (io.rresp.bits.evict.valid && swapev)
  io.rresp.bits.dbtail           := !io.rresp.bits.head  && io.rresp.bits.tail   && !io.rresp.bits.evict.valid

  when( io.rreq.fire() ) { s_idle  :=  false.B }
  when( io.rresp.valid ) { s_idle  :=  true.B  }

  iways.map { each_i => {
    val matchOH  = Cat(iways.map{ each_j => { each_i.state =/= INVALID && each_j.state =/= INVALID && each_i.tag === each_j.tag }}.reverse)
    val matchs   = PopCount(matchOH)
    when(RegNext(io.read.valid)) {
      when(matchs > 1.U) { printf("redundant match tag_%x set_%x match ways_%x\n", each_i.tag, RegNext(io.read.bits.set), matchOH) }
      assert(matchs <= 1.U)
    }
  }}

  if(params.remap.enableDirEntrySwaperLog)  {
    val src_set = Reg(resp_entry.set.cloneType)
    val src_way = Reg(resp_entry.way.cloneType)
    when( io.result.bits.stm )     {
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
    val resp     = Vec(numBanks, UInt(width = codeBits).asOutput)
    //swap
    val ireq     = Vec(numBanks, Decoupled(new Request(rowBits)))
    val iresp    = Vec(numBanks, UInt(width = codeBits).asInput)
    //remap
    val rreq     = Decoupled(new SwaperReq(params)).flip
  }

  //(s_ = state)
  val s_idle        = Seq.tabulate(numBanks) {_  =>  RegInit(true.B) }

  val rreq          = Reg(new SwaperReq(params))
  val last_Beats    = ((1 << beatBitspb) - 1).U
  val read_beats    = Seq.tabulate(numBanks) {_  =>  Reg(UInt(width = beatBitspb)) }
  val write_beats   = Seq.tabulate(numBanks) {_  =>  Reg(UInt(width = beatBitspb)) }
  val swap_data     = Seq.tabulate(numBanks) {_  =>  Reg(Vec(params.cache.blockBytes/(numBanks*params.micro.writeBytes), UInt(width = codeBits))) }
  val resp_data     = Seq.tabulate(numBanks) {_  =>  Module(new Queue(UInt(width = codeBits), 1, pipe = false, flow = true)) }
  //                                                         read   write
  //head :           copy data from bank_data to swap_data     Y       N
  //tail :           copy data from swap_data to bank_data     N       Y
  //!head && !tail : swap bank_data and swap_data              Y       Y
  //head  && tail  : error
  val wen           = Seq.tabulate(numBanks) { b => RegEnable(Mux(io.rreq.fire(), io.rreq.bits.tail, ((!io.ireq(b).bits.wen && !rreq.head) || rreq.tail)), io.rreq.fire() || io.ireq(b).fire()) }
  val index         = Seq.tabulate(numBanks) { b => Cat(rreq.way, rreq.set, Mux(wen(b), write_beats(b), read_beats(b)))                                                                         }

  //io.rreq.ready    := s_idle.andR
  io.rreq.ready    := (0 until numBanks).map(  b => {
    s_idle(b) || (io.ireq(b).fire() && io.ireq(b).bits.wen && write_beats(b) === last_Beats)
  }).andR
  when(io.rreq.fire()) {  rreq := io.rreq.bits  }
  (0 until numBanks).map(  b => {
    //io.req  ---> io.resp
    when(io.req(b).valid) { assert(s_idle.andR) }
    when(io.req(b).valid && io.req(b).bits.wen) { swap_data(b)(io.req(b).bits.index) := io.req(b).bits.data }
    //io.resp(b)   := swap_data(b)(RegEnable(io.req(b).bits.index(beatBitspb-1, 0), io.req(b).valid && !io.req(b).bits.wen))
    io.resp(b)   := swap_data(b)(io.req(b).bits.index)

    //remap
    //io.rreq
    when(io.ireq(b).fire()) {
      when( read_beats(b)  === last_Beats && rreq.head           ) { s_idle(b) := true.B }
      when( write_beats(b) === last_Beats && io.ireq(b).bits.wen ) { s_idle(b) := true.B }
    }
    when(io.rreq.fire()) {
      s_idle(b)        := false.B
      read_beats(b)    := 0.U
      write_beats(b)   := 0.U
    }

    //io.ireq: read write data
    io.ireq(b).valid      := !s_idle(b)
    io.ireq(b).bits.wen   := wen(b)
    io.ireq(b).bits.index := index(b)
    io.ireq(b).bits.data  := swap_data(b)(write_beats(b))
    when(io.ireq(b).fire()) {
      when( io.ireq(b).bits.wen)  { write_beats(b) := write_beats(b) + 1.U }
      when(!io.ireq(b).bits.wen)  { read_beats(b)  := read_beats(b)  + 1.U }
    }

    //io.iresp: receive data
    resp_data(b).io.enq.valid := RegNext(io.ireq(b).fire() && !io.ireq(b).bits.wen)
    resp_data(b).io.enq.bits  := io.iresp(b)
    when(resp_data(b).io.enq.valid) { assert(resp_data(b).io.enq.ready) }
    when(resp_data(b).io.deq.fire()) {
      if(beatBitspb == 0)  swap_data(b)(0) := resp_data(b).io.deq.bits
      else                 swap_data(b)(RegNext(io.ireq(b).bits.index(beatBitspb-1, 0), io.ireq(b).fire())) := resp_data(b).io.deq.bits
    }
    resp_data(b).io.deq.ready := io.ireq(b).ready || rreq.head

  })


  when(io.rreq.fire()) {
    assert(!io.rreq.bits.head || !io.rreq.bits.tail)
  }

  val stalltime = RegInit(UInt(0, width = 12))
  stalltime := Mux(s_idle.andR || io.rreq.fire(), 0.U, stalltime+1.U)
  assert(stalltime < 100.U)

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

class RemaperStatusIO(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params) {
  val cloc   = UInt(width = RTAL.SZ)  //current location
  val nloc   = UInt(width = RTAL.SZ)  //next location
  val head   = UInt(width = params.setBits)
  val curr   = UInt(width = params.setBits)
  val dbswap = Valid(new SwaperReq(params))  //indicate which data block(known set known way) is swapping now
  val dbnext = Valid(UInt(width = params.setBits))     //indicate pontential dest(known set unknown way)
  val oneloc = Bool()

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
    val status     = new RemaperStatusIO(params)
    //RandomTable
    val rtcmd      = Valid(new RandomTableBankCmd()).asOutput
    val rtreq      = Decoupled(new RandomTableReqIO(params.maxblkBits))
    val rtresp     = Valid(new RandomTableBankResp(params.setBits)).flip
    val rtwipeDone = Bool().asInput
    val rtsendDone = Bool().asInput
    //DirectoryEntrySwaper
    val dereq      = Decoupled(new SwaperReq(params))
    val deresp     = Valid(new SwaperResp(params)).flip
    val definish   = Valid(UInt(width = params.setBits))
    //DataBlockSwaper
    val dbreq      = Decoupled(new SwaperReq(params))
    val swap_req   = new SourceDHazard(params)
    val swap_safe  = Bool().flip
    //X Channel
    val xreq       = Decoupled(new SinkXRequest(params))
    val evresp     = Valid(new SourceXRequest(params)).flip
    //mshr
    val continue   = Decoupled(Bool()).flip //continue swap
    //pfc
    val pfcupdate  = (new RemaperPFCReg()).flip
    //scheduler status
    val schreq     = Bool().asInput
    val mshrbusy   = Bool().asInput
  }

  val ways     = params.cache.ways
  val sets     = params.cache.sets
  val blocks   = sets*ways
  val lastset  = params.cache.sets - 1
  val rtdelay  = params.remap.rtdelay

  val dbreq = Module(new Queue(io.dbreq.bits, 1, pipe = false, flow = true  ))
  val rtreq = Module(new Queue(io.rtreq.bits, 1, pipe = false, flow = true  ))
  val xreq  = Module(new Queue(io.xreq.bits,  1, pipe = false, flow = true  ))
  val xarb  = Module(new Arbiter(io.xreq.bits.cloneType,  2)) //0:flush req 1:swap req

  //(s_ = state), (w_ = waiting)
  val s_idle       = RegInit(true.B)
  val s_swdone     = RegInit(true.B)
  val s_newset     = RegInit(false.B) //swap to new set
  val s_finish     = RegInit(false.B)
  val s_1stpause   = RegInit(false.B) //when receive req we should wait all safe
  val s_oneloc     = RegInit(true.B)
  val w_dir        = RegInit(false.B)
  val w_continue   = RegInit(false.B)
  val w_evictdone  = RegInit(false.B)

  val loc_next     = RegInit(RTAL.LEFT)
  val loc_current  = RegInit(RTAL.RIGH)
  val p_head       = Reg(new SwaperResp(params))
  val p_current    = Reg(new SwaperResp(params))
  val dbswap       = Reg(Valid(new SwaperReq(params)))
  val dbnext       = Reg(Valid(UInt(width = params.setBits)))
  val finish       = s_finish && !io.schreq && (io.rtsendDone || L2SetIdxHashFun.twinsInTile.B)
  val newset       = Mux(loc_next === RTAL.RIGH, io.rtresp.bits.rhset, io.rtresp.bits.lhset)

  when(finish) {
     loc_next    := loc_current
     loc_current := loc_next
  }

  //io.req
  when(io.req.fire()) {
    p_head.set              := 0.U
    p_head.nots             := ways.U
    p_head.head             := true.B
    p_current.set           := 0.U
    p_current.evict.valid   := false.B
  }
  io.req.ready        := s_idle

  //data_block swap information
  when( io.deresp.valid )  { dbnext.valid := false.B }
  when( io.dbreq.ready  )  {
    dbswap.valid := false.B
    dbnext.valid := false.B
  }
  when( io.dbreq.fire() )  {
    dbswap.valid := true.B
    dbswap.bits  := io.dbreq.bits
  }
  when( io.definish.valid )  {
    dbnext.valid     := true.B
    dbnext.bits      := io.definish.bits
  }

  //io.status
  io.status.cloc           := loc_current
  io.status.nloc           := loc_next
  io.status.head           := p_head.set
  io.status.curr           := p_current.set
  io.status.oneloc         := s_oneloc
  io.status.dbswap         := dbswap
  io.status.dbnext.valid   := dbnext.valid
  io.status.dbnext.bits    := io.status.curr

  //io.rtcmd
  io.rtcmd.valid           := io.deresp.valid && io.deresp.bits.set === lastset.U && io.deresp.bits.nop
  io.rtcmd.bits.rst        := true.B
  io.rtcmd.bits.send       := false.B
  io.rtcmd.bits.loc        := loc_current
  when(!s_1stpause && (RegNext(s_1stpause))) {
    io.rtcmd.valid         := true.B
    io.rtcmd.bits.rst      := false.B
    io.rtcmd.bits.send     := true.B
    io.rtcmd.bits.loc      := loc_next
  }

  //rtreq.io.enq
  val addrRestored          = params.restoreAddress(params.expandAddress(io.deresp.bits.tag(params.blkadrBits-1, params.setBits), io.deresp.bits.tag(params.setBits-1, 0), UInt(0)))
  rtreq.io.enq.valid       := io.deresp.valid && !io.deresp.bits.nop && !io.deresp.bits.tail
  rtreq.io.enq.bits.blkadr := addrRestored >> params.offsetBits

  //io.rtreq
  io.rtreq.valid           := rtreq.io.deq.valid
  io.rtreq.bits            := rtreq.io.deq.bits
  rtreq.io.deq.ready       := io.rtreq.ready

  //io.rtresp
  when(io.rtresp.valid) { p_current.set      := newset }

  //io.dereq
  io.dereq.valid      := w_dir && (!w_continue || io.continue.fire())
  io.dereq.bits.set   := Mux(p_head.head, p_head.set, io.definish.bits)
  io.dereq.bits.cloc  := loc_current
  io.dereq.bits.nloc  := loc_next
  io.dereq.bits.head  := p_head.head
  when(io.dereq.valid) { assert(io.dereq.ready) }
  when(io.definish.valid && p_head.nots === 1.U && p_head.set =/= lastset.U && (ways > 1).B) {
    p_head.set  := p_head.set + 1.U
    p_head.nots := ways.U
  }

  io.definish.valid := (s_newset || io.rtresp.valid) && dbreq.io.enq.ready
  io.definish.bits  := Mux(io.rtresp.valid, newset, p_current.set)

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
  io.dbreq.valid           := dbreq.io.deq.valid && io.swap_safe
  io.dbreq.bits            := dbreq.io.deq.bits
  io.dbreq.bits.nloc       := loc_next
  dbreq.io.deq.ready       := io.dbreq.ready     && io.swap_safe
  io.swap_req.set          := dbreq.io.deq.bits.set
  io.swap_req.way          := dbreq.io.deq.bits.way
  io.swap_req.swz          := true.B

  //xarb.io.in(0): flush req
  xarb.io.in(0).valid         := io.deresp.bits.evict.valid
  xarb.io.in(0).bits.set      := io.deresp.bits.evict.bits.set
  xarb.io.in(0).bits.tag      := io.deresp.bits.evict.bits.tag
  xarb.io.in(0).bits.opcode   := XOPCODE.FLUSH
  when(xarb.io.in(0).valid)   { assert(xarb.io.in(0).ready) }
  //xarb.io.in(1): swap req
  xarb.io.in(1).valid         := io.rtresp.valid || (io.dereq.valid && io.dereq.bits.head)
  xarb.io.in(1).bits.set      := Mux(io.rtresp.valid, newset, io.dereq.bits.set)
  xarb.io.in(1).bits.tag      := 0.U
  xarb.io.in(1).bits.opcode   := XOPCODE.SWAP
  when(xarb.io.in(1).valid)   { assert(xarb.io.in(1).ready) }
  //xarb.out -> xreq.enq
  xreq.io.enq                 <> xarb.io.out
  //xreq.deq -> io.xreq
  io.xreq                     <> xreq.io.deq
  io.xreq.bits.loc            := loc_next

  //io.continue
  io.continue.ready           := s_swdone || (dbreq.io.enq.ready && w_dir)

  //state machine
  when( finish                                                                         ) {   s_idle       := true.B    }
  when( io.req.fire()                                                                  ) {   s_idle       := false.B   }
  when( io.req.fire()                                                                  ) {   s_1stpause   := true.B    }
  when( s_1stpause && dbreq.io.enq.ready && io.rtwipeDone && !io.schreq                ) {   s_1stpause   := false.B   }
  when( io.rtresp.valid                                                                ) {   s_newset     := true.B    }
  when( io.definish.valid                                                              ) {   s_newset     := false.B   }
  when( io.deresp.valid && io.deresp.bits.nop                                          ) {   s_swdone     := true.B    }
  when( dbreq.io.enq.ready && (w_dir || w_evictdone)                                   ) {   s_swdone     := true.B    }
  //when( dbreq.io.enq.ready && io.dbreq.ready && (w_dir || w_evictdone)                 ) {   s_swdone     := true.B    }
  when( (io.xreq.fire() && io.xreq.bits.opcode === XOPCODE.SWAP) || io.continue.fire() ) {   s_swdone     := false.B   }
  when( finish                                                                         ) {   s_oneloc     := true.B    }
  when( s_1stpause && dbreq.io.enq.ready && io.rtwipeDone && !io.schreq                ) {   s_oneloc     := false.B   }
  when( finish                                                                         ) {   s_finish     := false.B   }
  when( io.deresp.valid && io.deresp.bits.set === lastset.U && io.deresp.bits.nop      ) {   s_finish     := true.B    }

  when(s_idle | s_finish) {
    w_dir        := false.B
    w_continue   := false.B
    w_evictdone  := false.B
  }.otherwise {
    when(!w_dir && xreq.io.enq.ready) {
      when( s_1stpause && dbreq.io.enq.ready && io.rtwipeDone && !io.schreq               ) {  w_dir   := true.B   }  //first swap req
      when( io.deresp.valid ) {
        when( io.deresp.bits.nop && io.deresp.bits.set =/= lastset.U                      ) {  w_dir   := true.B   }  //need do nothing  
        when( !io.deresp.bits.nop && io.deresp.bits.tail && !io.deresp.bits.evict.valid   ) {  w_dir   := true.B   }  //tail but not need evict
      }
      when( w_evictdone && io.evresp.valid                                                ) {  w_dir   := true.B   }  //wait evict done
      if(rtdelay == 1) when( io.rtreq.fire()                                              ) {  w_dir   := true.B   }
      if(rtdelay > 1) {
        val rtreq_fire_delay  = Reg(Vec(rtdelay - 1, Bool()))
        rtreq_fire_delay(0)  := io.rtreq.fire()
        for(i <- 1 until rtdelay - 1) {
          rtreq_fire_delay(i) := rtreq_fire_delay(i-1)
        }
        //rtreq_fire_delay(rtdelay-2) is the single before rtresp
        when( rtreq_fire_delay(rtdelay-2)                                                 ) {  w_dir   := true.B   }
      }
    }
    when( io.dereq.fire() ) { w_dir := false.B }
    when( io.deresp.valid && !io.deresp.bits.nop ) {
      when(io.deresp.bits.evict.valid) {  w_evictdone   := true.B     }                 //need evict
    }
    when(io.evresp.fire())             {  w_evictdone   := false.B    }                 //evict resp
    when(io.continue.fire())           {  w_continue    := false.B    }                 //continue token from mshr
    when(io.dereq.fire())              {  w_continue    := true.B     }
  }

  //pfc
  io.pfcupdate.busy      := !s_idle
  io.pfcupdate.nop       := io.deresp.valid && io.deresp.bits.nop
  io.pfcupdate.swap      := io.dbreq.fire()
  io.pfcupdate.evict     := io.xreq.fire() && io.xreq.bits.opcode === XOPCODE.FLUSH
  io.pfcupdate.ebusy     := !s_idle && w_evictdone && io.dereq.ready
  io.pfcupdate.finish    := finish
  //io.pfcupdate.atcheck   := io.req.bits.check
  //io.pfcupdate.atdetec   := io.req.fire() && io.req.bits.atdetec

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
    val stalltime = RegInit(UInt(0, width = 10))
    stalltime := Mux(s_idle || io.dereq.fire() || io.xreq.fire(), 0.U, stalltime+1.U)
    when(stalltime > 1000.U) {
      printf("swap set %x deadlock?", RegEnable(xarb.io.in(1).bits.set, xarb.io.in(1).valid))
      assert(stalltime < 1000.U)
    }

    when(finish || s_idle)    { assert(!w_dir)              }
    when(finish || s_idle)    { assert(!io.dereq.valid)     }
    when(io.dereq.valid)      { assert(io.dereq.ready)      }
    //when(io.rtreq.valid)      { assert(io.rtreq.ready)      }
    when(dbreq.io.enq.valid)  { assert(dbreq.io.enq.ready)  }
    //when(io.dereq.valid && !io.dereq.bits.head)      { assert(io.rtresp.valid) }

  }
}
