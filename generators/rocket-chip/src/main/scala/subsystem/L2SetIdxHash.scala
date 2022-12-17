// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package freechips.rocketchip.subsystem.L2SetIdxHash

import Chisel._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.DescribedSRAM

object L2RTAL
{
  // locations
  val SZ = 1
  def LEFT  = UInt(0, SZ) //left
  def RIGH  = UInt(1, SZ) //right
}

object L2SetIdxHashFun { //How to parametrize ?
  val twinsInTile        = true
  val blkBits            = 32 - 6
  val l2setBits          = 10
  val usel2setBitsIdxRan = false
  val blkBitsUsedIdxRan  = if(usel2setBitsIdxRan) blkBits else blkBits - l2setBits
  val firIdxBit          = if(usel2setBitsIdxRan)       0 else l2setBits
  val randoms            = 2 * blkBitsUsedIdxRan
  def apply(rans: Seq[UInt], blkadr: UInt) : UInt = {
    val ranSel = (firIdxBit until blkBits).zipWithIndex.map { case(bit, i) => Mux(blkadr(bit) === 1.U, rans(i), rans(2 * i + 1)) }
    val result = if(usel2setBitsIdxRan) ranSel.reduce(_^_) else ranSel.reduce(_^_) ^ blkadr(l2setBits - 1, 0)
    result
  }
  def getr(data: UInt):         UInt = { Cat(data.asBools)                                } //get reverse
  def getl(full: UInt, w: Int): UInt = { full(w-1, 0)                                     } //get low part
  def getm(full: UInt, w: Int): UInt = { full(w+(full.getWidth-w)/2, (full.getWidth-w)/2) } //get middle part
  def geth(full: UInt, w: Int): UInt = { full(full.getWidth-1,       full.getWidth-1-w)   } //get high part
  /*def ranGen(full: UInt, w: Int): UInt = {
      val result  = Wire(UInt(width = w))
      val sel     = RegInit(0.U(5.W))
      val low     = getl(full, w)
      val mid     = getm(full, w)
      val hig     = geth(full, w)
      val part0    = Mux(sel(0), low, getr(low))
      val part1    = Mux(sel(1), mid, getr(mid))
      val part2    = Mux(sel(2), hig, getr(hig))
    sel    := sel + getr(part0(sel.getWidth - 1, 0) ^ part1(sel.getWidth - 1, 0) ^ part2(sel.getWidth - 1, 0))
    result := part0 + part1 + part2
    when(sel(4, 3) === 1.U) { result := part0 - part1 - part2 }
    when(sel(4, 3) === 2.U) { result := part0 ^ part1 ^ part2 }
    when(sel(4, 3) === 3.U) { result := part0 + part1 ^ part2 }
    result
  }*/

  //Hua2011 Non-Crypto Hardware Hash Functions for High Performance Networking ASICs
  def XorStage(in: UInt): UInt = {
    val highest = in.getWidth - 1
    var result  = (in(0) ^ in(2) ^ in(highest)).asUInt()
    (1 to highest).map { case l => { result = Cat(in(l-1) ^ in(l),  result) }}
    require(in.getWidth == result.getWidth)
    result(highest, 0)
  }

  def SBlock(in: UInt): UInt = {
    val inwidth = in.getWidth
    require(0 < inwidth && inwidth <= 3)
    var ina = false.B
    var inb = false.B
    val inc = in(0)
    if(inwidth == 3) ina = in(2)
    if(inwidth >= 2) inb = in(1)

    //(in(2) & in(1)) | (in(2) & in(0)) | (in(1) & in(0))
    val Qa      = (~ina &  inb) | (~inb &  inc) | ( inb &  inc)
    val Qb      = ( ina &  inb) | ( inb & ~inc) | ( inb & ~inc)
    val Qc      = (~ina &  inb) | (~inb & ~inc) | ( inb & ~inc)
    Cat(Qa, Qb, Qc)
  }

  def SBoxStage(in: UInt): UInt = {
    val highest = in.getWidth - 1
    var result  = SBlock(in(2, 0))
    (3 to highest by 3).map { case l => {
      if(l + 2 <= highest)  { result    = Cat(SBlock(in(l + 2,   l)),  result) }
      else                  { result    = Cat(SBlock(in(highest, l)),  result) }
    }}
    result(highest, 0)
  }

  def PermutationStage(in: UInt): UInt = {
    val inwidth        = in.getWidth
    val permutations: Array[Int] = Array.fill(inwidth)(0)
    val remainder      = inwidth % 3
    val span           = (inwidth/3).toInt
    val p0             = List(0,            span)
    val p1             = List(    span, 2 * span)
    val p2             = List(2 * span,  inwidth)

    if(remainder == 1) { permutations(inwidth - 1) =  p2(0) }
    if(remainder == 2) {
      permutations(inwidth - 2) =  p2(0)
      permutations(inwidth - 1) =  p2(0) + 1
    }
    var p0sel       = 0
    var p1sel       = span
    var p2sel       = 2 * span + remainder
    (0 until inwidth - remainder).map{ case i => {
      if(i < p0(1))        {
        if((i % 2) == 0)   {
          permutations(i) = p1sel
          p1sel     = p1sel + 1
        } else             {
          permutations(i) = p2sel
          p2sel     = p2sel + 1
        }
      } else if(i < p1(1)) {
        if((i % 2) == 0)   {
          permutations(i) = p0sel
          p0sel     = p0sel + 1
        } else             {
          permutations(i) = p2sel
          p2sel     = p2sel + 1
        }
      } else if(i < p2(1)) {
        if((i % 2) == 0)    {
          permutations(i) = p0sel
          p0sel     = p0sel + 1
        } else           {
          permutations(i) = p1sel
          p1sel     = p1sel + 1
    }}}}

    var result  = in(permutations(0)).asUInt()
    (1 until inwidth).map{ case i => {
      result = Cat(in(permutations(i)), result)
    }}
    result
  }

  def tx3nt(in: UInt, stages: Int): UInt = {
    var result  = PermutationStage(in)
    result      = PermutationStage(SBoxStage(result))
    result      = PermutationStage(XorStage(result))
    result      = PermutationStage(XorStage(result))
    result      = PermutationStage(XorStage(result))
    (1 until stages).map{case i => {
      result    = PermutationStage(SBoxStage(result))
      result    = PermutationStage(XorStage(result))
      result    = PermutationStage(XorStage(result))
      result    = PermutationStage(XorStage(result))
    }}
    result      = SBoxStage(result)
    result
  }

  //Maurice2015 Reverse Engineering Intel Last-Level Cache Complex Addressing Using Performance Counters
  def Maurice2015(in: UInt, hkeys: Seq[UInt]): UInt = {
    val inwidth = in.getWidth
    var result  = (in & hkeys(0)).asBools().reduce(_^_).asUInt()
    (0 until hkeys.size).map{case i => { require(inwidth == hkeys(i).getWidth) }}
    (1 until hkeys.size).map{case i => { result = Cat((in & hkeys(i)).asBools().reduce(_^_), result) }}
    require(result.getWidth == hkeys.size)
    result
  }

  def XorConcentrate(in: UInt, owidth: Int): UInt = {
    val inwidth = in.getWidth
    require(inwidth >= owidth)
    var result = in(owidth - 1, 0)
    (owidth until inwidth by owidth).map{case i => { result = result ^ in((i + owidth - 1).min(inwidth - 1), i) }}
    result
  }
}

class L2SetIdxHashReq extends Bundle {
  val blkadr  = UInt(width = L2SetIdxHashFun.blkBits) //cache block address
}

class L2SetIdxHashResp extends Bundle {
  val lhset  = UInt(width = L2SetIdxHashFun.l2setBits)
  val rhset  = UInt(width = L2SetIdxHashFun.l2setBits)
}

class L2SetIdxHashFillRan extends Bundle {
  val loc     = UInt(width = L2RTAL.SZ)
  val fin     = Bool()
  val addr    = UInt(width =  6)
  val random  = UInt(width = L2SetIdxHashFun.blkBits)
}

class L2RanNetMasterIO  extends Bundle {
  val send = Decoupled(new L2SetIdxHashFillRan())
}

class L2RanNetSlaveIO  extends Bundle {
  val rece = Flipped(Decoupled(new L2SetIdxHashFillRan()))
}


class TileL2SetIdxIO extends Bundle {
    val req     = Decoupled(new L2SetIdxHashReq())
    val resp    = Valid(new L2SetIdxHashResp()).asInput
    val revoke  = Bool().asInput
}

class PermutationsFill(val numWidth: Int) extends Bundle {
  val loc     = UInt(width = L2RTAL.SZ)
  val addr    = UInt(width = numWidth)
  val number  = UInt(width = numWidth)
}

class PermutationsGenerator(val rangeHi: Int) extends Module {
  val ranWidth = log2Up(rangeHi+1)

  val io = new Bundle {
    val swap      = Valid(UInt(width = ranWidth)).flip
    val fill      = Decoupled(new PermutationsFill(numWidth = ranWidth))
    val active    = Bool().asInput
    val fillDone  = Bool().asOutput
  }

  val s_Idle :: s_R0 :: s_R1 :: s_Nop :: s_W0 :: s_W1 :: s_Fill :: Nil = Enum(Bits(), 7)
  val state = Reg(init = s_Idle)
  val (sram_num, _)  = DescribedSRAM(
    name = "sram_num",
    desc = "Permutations Num RAM",
    size = rangeHi + 1,
    data = UInt(width = ranWidth)
  )
  val swap           = Reg(Vec(2, new PermutationsFill(numWidth = ranWidth)))
  val fill           = Reg(new PermutationsFill(numWidth = ranWidth))
  val readArb        = Module(new Arbiter(UInt(width = ranWidth), 3))
  val updateArb      = Module(new Arbiter(new PermutationsFill(numWidth = ranWidth), 3))
  val fillQue        = Module(new Queue(new PermutationsFill(numWidth = ranWidth), 4, pipe = false, flow = false))
  val wipeCount      = RegInit(UInt(0,  width = ranWidth))
  val wipeDone       = RegInit(false.B)
  val active         = RegInit(false.B)
  val numResp        = sram_num.read(readArb.io.out.bits, readArb.io.out.valid)
  when(io.active)   { active := true.B }
  when(!wipeDone) {
    wipeCount := wipeCount + 1.U
    wipeDone  := wipeCount === rangeHi.U
  }

  //readArb
  readArb.io.in(0).valid        := state === s_Fill && !fillQue.io.enq.valid && fillQue.io.enq.ready
  readArb.io.in(0).bits         := fill.addr
  readArb.io.in(1).valid        := state === s_R0
  readArb.io.in(1).bits         := swap(0).addr
  readArb.io.in(2).valid        := state === s_R1
  readArb.io.in(2).bits         := swap(1).addr
  readArb.io.out.ready          := true.B
  fillQue.io.enq.valid          := RegNext(readArb.io.in(0).fire())
  fillQue.io.enq.bits.addr      := RegNext(readArb.io.in(0).bits)
  fillQue.io.enq.bits.number    := numResp
  when(readArb.io.in(0).fire())  { fill.addr      := fill.addr + 1.U }
  when(io.active && !active   )  { fill.addr      := 0.U             }
  when(state === s_R1         )  { swap(0).number :=  numResp        }
  when(state === s_Nop        )  { swap(1).number :=  numResp        }

  //updateArb
  updateArb.io.in(0).valid        := !wipeDone
  updateArb.io.in(0).bits.addr    := wipeCount
  updateArb.io.in(0).bits.number  := wipeCount
  updateArb.io.in(1).valid        := state === s_W0
  updateArb.io.in(1).bits.addr    := swap(0).addr
  updateArb.io.in(1).bits.number  := swap(1).number
  updateArb.io.in(2).valid        := state === s_W1
  updateArb.io.in(2).bits.addr    := swap(1).addr
  updateArb.io.in(2).bits.number  := swap(0).number
  updateArb.io.out.ready          := true.B
  when(updateArb.io.in(1).fire()) { swap(0).addr := Mux(swap(0).addr === rangeHi.U, 0.U, swap(0).addr + 1.U) }
  when(updateArb.io.out.valid   ) { sram_num.write(updateArb.io.out.bits.addr, updateArb.io.out.bits.number) }

  //io.fill
  io.fill.valid                := fillQue.io.deq.valid
  io.fill.bits                 := fillQue.io.deq.bits
  fillQue.io.deq.ready         := io.fill.ready
  io.fillDone                  := !active && state =/= s_Fill && fillQue.io.enq.ready

  //state
  when(state === s_Idle && wipeDone) {
    when(active) {
      active       := false.B
      state        := s_Fill
    }.elsewhen(io.swap.valid) {
      state        := s_R0
      swap(1).addr := Mux(io.swap.bits <= rangeHi.U, io.swap.bits, ~io.swap.bits)
    }
  }
  when( state === s_R0  )   {  state  := s_R1    }
  when( state === s_R1  )   {  state  := s_Nop   }
  when( state === s_Nop )   {  state  := s_W0    }
  when( state === s_W0  )   {  state  := s_W1    }
  when( state === s_W1  )   {  state  := s_Idle  }
  when( state === s_Fill && readArb.io.in(0).fire() && fill.addr === rangeHi.U ) { state  := s_Idle }

}

class Maurice2015Hua2011(channels: Int, val setBits: Int, val blkBits: Int, val rtsize: Int, val hkeyBits: Int) extends Module
{
  val rtidxBits    = log2Up(rtsize)
  val io = new Bundle {
    val req         = Vec(channels, Decoupled(new L2SetIdxHashReq()).flip)
    val resp        = Vec(channels, Valid(new L2SetIdxHashResp().asOutput))
    val fillRan     = Valid(new L2SetIdxHashFillRan()).asInput
    val fillSram    = Valid(new PermutationsFill(numWidth = hkeyBits)).asInput
  }

  val (sramL, _)  = DescribedSRAM(
    name = "sram_left",
    desc = "sram_left",
    size = rtsize,
    data = UInt(width = hkeyBits)
  )
  val (sramR, _)  = DescribedSRAM(
    name = "sram_right",
    desc = "sram_right",
    size = rtsize,
    data = UInt(width = hkeyBits)
  )
  val sramLIndexHkey  = Reg(Vec(rtidxBits, UInt(blkBits.W)))
  val sramRIndexHkey  = Reg(Vec(rtidxBits, UInt(blkBits.W)))
  val reqArb          = Module(new Arbiter(new L2SetIdxHashReq(),  channels))

  val wipeCount       = RegInit(UInt(0,  width = rtidxBits + 1))
  val wipeDone        = wipeCount(rtidxBits)
  val sramLWValid     = !wipeDone || (io.fillSram.valid && io.fillSram.bits.loc === L2RTAL.LEFT)
  val sramLWAddr      = Mux(wipeDone, io.fillSram.bits.addr,   wipeCount)
  val sramLWData      = Mux(wipeDone, io.fillSram.bits.number, wipeCount)
  val sramRWValid     = !wipeDone || (io.fillSram.valid && io.fillSram.bits.loc === L2RTAL.RIGH)
  val sramRWAddr      = Mux(wipeDone, io.fillSram.bits.addr,   wipeCount)
  val sramRWData      = Mux(wipeDone, io.fillSram.bits.number, wipeCount)
  val blkadrHKeyL     = Reg(UInt(32.W))
  val blkadrHKeyR     = Reg(UInt(32.W))
  val blkadrHLatchL   = Reg(UInt(reqArb.io.out.bits.blkadr.getWidth.W))
  val blkadrHLatchR   = Reg(UInt(reqArb.io.out.bits.blkadr.getWidth.W))
  val sramLIndex      = L2SetIdxHashFun.Maurice2015(reqArb.io.out.bits.blkadr, sramLIndexHkey)
  val sramRIndex      = L2SetIdxHashFun.Maurice2015(reqArb.io.out.bits.blkadr, sramRIndexHkey)
  val ranL = sramL.read(sramLIndex, reqArb.io.out.valid && !sramLWValid)
  val ranR = sramR.read(sramRIndex, reqArb.io.out.valid && !sramRWValid)
  //blkadrHLatchL      := L2SetIdxHashFun.XorConcentrate(L2SetIdxHashFun.tx3nt(Cat(blkadrHKeyL, reqArb.io.out.bits.blkadr), 2), blkadrHLatchL.getWidth)
  //blkadrHLatchR      := L2SetIdxHashFun.XorConcentrate(L2SetIdxHashFun.tx3nt(Cat(blkadrHKeyR, reqArb.io.out.bits.blkadr), 2), blkadrHLatchR.getWidth)
  blkadrHLatchL      := reqArb.io.out.bits.blkadr //^ blkadrHKeyL
  blkadrHLatchR      := reqArb.io.out.bits.blkadr //^ blkadrHKeyR

  //fill
  wipeCount := wipeCount + (!wipeDone).asUInt
  when(sramLWValid) { sramL.write(sramLWAddr, sramLWData) }
  when(sramRWValid) { sramR.write(sramRWAddr, sramRWData) }
  when(io.fillRan.valid && io.fillRan.bits.loc === L2RTAL.LEFT) { blkadrHKeyL := (blkadrHKeyL << 1) + io.fillRan.bits.random }
  when(io.fillRan.valid && io.fillRan.bits.loc === L2RTAL.RIGH) { blkadrHKeyR := (blkadrHKeyR << 1) + io.fillRan.bits.random }
  (0 until rtidxBits).map { i => {
    when(io.fillRan.valid && io.fillRan.bits.addr === i.U) {
      when(io.fillRan.bits.loc === L2RTAL.LEFT) { sramLIndexHkey(i) := io.fillRan.bits.random }
      when(io.fillRan.bits.loc === L2RTAL.RIGH) { sramRIndexHkey(i) := io.fillRan.bits.random }
    }
  }}

  //req & resp
  val hua2011L =  L2SetIdxHashFun.tx3nt(Cat(ranL, blkadrHLatchL), 3)
  val hua2011R =  L2SetIdxHashFun.tx3nt(Cat(ranR, blkadrHLatchR), 3)
  val resplhset    =  L2SetIdxHashFun.XorConcentrate(hua2011L, setBits)
  val resprhset    =  L2SetIdxHashFun.XorConcentrate(hua2011R, setBits)
  (0 until channels).map { ch => {
    //req
    reqArb.io.in(ch).valid    := io.req(ch).valid
    reqArb.io.in(ch).bits     := io.req(ch).bits
    io.req(ch).ready          := reqArb.io.in(ch).ready
    //resp
    io.resp(ch).valid         := RegNext(io.req(ch).fire())
    io.resp(ch).bits.lhset    := resplhset
    io.resp(ch).bits.rhset    := resprhset
  }}
  reqArb.io.out.ready         := wipeDone
}

class L2SetIdxHash(val channels: Int, val twins: Boolean = true) extends Module
{
  val io = new Bundle {
    val req       = Vec(channels, Decoupled(new L2SetIdxHashReq()).flip)
    val resp      = Vec(channels, Valid(new L2SetIdxHashResp().asOutput))
    val revoke    = Bool().asOutput
    val checkRan  = Decoupled(new L2SetIdxHashFillRan()).flip
    val sendRan   = Decoupled(new L2SetIdxHashFillRan())
    val fillRan   = Valid(new L2SetIdxHashFillRan()).asInput
  }
  val randoms     = L2SetIdxHashFun.randoms
  val hsetW       = io.resp(0).bits.lhset.getWidth
  val ranL        = Reg(Vec(randoms, UInt(hsetW.W)))
  val ranR        = Reg(Vec(randoms, UInt(hsetW.W)))
  val revoke      = Reg(Bool())
  io.revoke      := revoke || RegNext(revoke)
  require(io.sendRan.bits.addr.getWidth >= log2Up(randoms))

  //checck and send
  io.sendRan.valid         := RegNext(io.checkRan.fire())
  io.sendRan.bits          := RegNext(io.checkRan.bits)
  if(twins) {
    io.sendRan.bits.random := RegNext(Mux(io.checkRan.bits.loc === L2RTAL.LEFT, ranL(io.checkRan.bits.addr), ranR(io.checkRan.bits.addr)))
  } else {
    io.sendRan.bits.random := RegNext(ranL(io.checkRan.bits.addr))
  }
  io.checkRan.ready        := !io.sendRan.valid && io.sendRan.ready

  //fill
  revoke := io.fillRan.valid && io.fillRan.bits.fin
  if(!twins) { when(io.fillRan.valid) { revoke  := !io.fillRan.bits.fin } }
  (0 until randoms).map { i => {
    when(reset) {
      revoke    := false.B
      if(twins) {
        ranL(i) := 0.U
        ranR(i) := 0.U
      } else {
        ranL(i) := 0.U
      }
    }
    when(io.fillRan.valid && io.fillRan.bits.addr === i.U ) {
      if(twins) {
        when(io.fillRan.bits.loc === L2RTAL.LEFT) { ranL(i) := io.fillRan.bits.random }
        when(io.fillRan.bits.loc === L2RTAL.RIGH) { ranR(i) := io.fillRan.bits.random }
      } else {
        ranL(i) := io.fillRan.bits.random
      }
    }
  }}

  (0 until channels).map { ch => {
    //req
    io.req(ch).ready          := true.B
    //resp
    io.resp(ch).valid         := RegNext(io.req(ch).valid)
    io.resp(ch).bits.lhset    := RegNext(L2SetIdxHashFun(ranL, io.req(ch).bits.blkadr))
    io.resp(ch).bits.rhset    := RegNext(L2SetIdxHashFun(ranR, io.req(ch).bits.blkadr))
    if(!twins) {
      io.resp(ch).bits.rhset  := 0.U
    }
  }}
}

class L2RANNetworkl(val nClients: Int)(implicit p: Parameters) extends LazyModule {
  val (slnodes, slios) = {
    val nodeandio = (0 until nClients).map(i => {
      val node = new BundleBridgeSink(Some(() => Flipped((new L2RanNetSlaveIO().cloneType))))
      val io   = InModuleBody { node.bundle }
      (node, io)
    })
    ((0 until nClients).map(nodeandio(_)._1),  (0 until nClients).map(nodeandio(_)._2))
  }

  val (manodes, maios) = {
    val nodeandio = (0 until 1).map(i => {
      val node = new BundleBridgeSink(Some(() => Flipped((new L2RanNetMasterIO().cloneType))))
      val io   = InModuleBody { node.bundle }
      (node, io)
    })
    (nodeandio(0)._1, nodeandio(0)._2)
  }

 lazy val module = new LazyModuleImp(this) {
    maios.send.ready      := true.B
    (0 until nClients).map( i => {
      slios(i).rece.valid := maios.send.valid
      slios(i).rece.bits  := maios.send.bits
   })
 }

}

trait HasL2RANTableSlaveNode { this: freechips.rocketchip.tile.BaseTile =>
  val l2ranslnode  = BundleBridgeSource(() => (new L2RanNetSlaveIO().cloneType))
  val l2ransl      = InModuleBody { l2ranslnode.bundle }
}

trait CanAttachTiletoL2RANTable { this:  freechips.rocketchip.subsystem.CanAttachTile =>
  import freechips.rocketchip.tile.TilePRCIDomain
  def connectSalveL2RANTable (domain: TilePRCIDomain[TileType], context: TileContextType, hartID: Int): Unit = {
    implicit val p = context.p
    val l2ranbus = context.asInstanceOf[HasL2RANnetwork].l2ranbus
   
    val L2RANSlNode  = domain.tile.asInstanceOf[HasL2RANTableSlaveNode].l2ranslnode

    //L2RANSlNode := l2ranbus.slnodes(hartID) 
    l2ranbus.slnodes(hartID) := L2RANSlNode
  }
}

trait HasSlaveL2RANTable { this: freechips.rocketchip.tile.RocketTileModuleImp =>
  val slav_l2setidx = Module(new L2SetIdxHash(2, twins = L2SetIdxHashFun.twinsInTile))
  def connectSalveL2RANTable = {
    slav_l2setidx.io.fillRan.valid := outer.l2ransl.rece.valid
    slav_l2setidx.io.fillRan.bits  := outer.l2ransl.rece.bits

    slav_l2setidx.io.req(0)                     <> outer.dcache.module.io.l2_set_idx.req
    outer.dcache.module.io.l2_set_idx.resp     := slav_l2setidx.io.resp(0)
    outer.dcache.module.io.l2_set_idx.revoke   := slav_l2setidx.io.revoke
    slav_l2setidx.io.req(1)                         <> outer.frontend.module.io.l2_set_idx.req
    outer.frontend.module.io.l2_set_idx.resp   := slav_l2setidx.io.resp(1)
    outer.frontend.module.io.l2_set_idx.revoke := slav_l2setidx.io.revoke
  }

}



trait HasL2RANnetwork  { this: freechips.rocketchip.subsystem.BaseSubsystem =>
  val l2ranbus = {
    val nTiless  = p(freechips.rocketchip.subsystem.RocketTilesKey).length
    LazyModule(new L2RANNetworkl(nTiless))
  }
}



