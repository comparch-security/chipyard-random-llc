// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package freechips.rocketchip.subsystem.L2SetIdxHash

import Chisel._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config.Parameters

object L2RTAL
{
  // locations
  val SZ = 1
  def LEFT  = UInt(0, SZ) //left
  def RIGH  = UInt(1, SZ) //right
}

object L2SetIdxHashFun { //How to parametrize ?
  val blkBits   = 32 - 6
  val l2setBits = 10
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
  def ranGen(full: UInt, w: Int): UInt = {
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
  val random  = UInt(width = L2SetIdxHashFun.l2setBits)
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
  val slav_l2setidx = Module(new L2SetIdxHash(2, twins = false))
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



