package freechips.rocketchip.pfc

import chisel3._
import chisel3.util._

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.HellaPeekingArbiter


class PFCReq(val nClients: Int, val npages: Int=64) extends Bundle() {
  require(npages<=64)
  val src        = UInt(log2Up(nClients).W)
  val dst        = UInt(log2Up(PFCManagerIds.maxIds).W)
  val ram        = Bool()
  val page       = UInt(log2Up(npages).W)
  val bitmap     = Bits(64.W)
  val programID  = Bits(4.W)
}

class PFCResp(val nClients: Int) extends Bundle() {
  val src        = UInt(log2Up(PFCManagerIds.maxIds).W)
  val dst        = UInt(log2Up(nClients).W)
  val data       = UInt(64.W)
  val last       = Bool()
  val bitmapUI   = UInt(6.W)
  val programID  = UInt(4.W)
}

class PFCClientIO(val nClients: Int) extends Bundle() {
  val req  = Decoupled(new PFCReq(nClients))
  val resp = Flipped(Decoupled(new PFCResp(nClients)))
}

class PFCManagerIO(val nClients: Int) extends Bundle() {
  val req  = Flipped(Decoupled(new PFCReq(nClients)))
  val resp = Decoupled(new PFCResp(nClients))
}


class TilePFCCMIO(val nClients: Int) extends Bundle() {  //tile not only has client but also has manager
  val client  = new PFCClientIO(nClients)
  val manager = new PFCManagerIO(nClients)
}

/*
class PFCClientIO(val nClients: Int) extends Bundle() {
  val req  = Flipped(Decoupled(new PFCReq(nClients)))
  val resp = Decoupled(new PFCResp(nClients))
}

class PFCManagerIO(val nClients: Int) extends Bundle() {
  val req  = Decoupled(new PFCReq(nClients))
  val resp = Flipped(Decoupled(new PFCResp(nClients)))
}

class TilePFCCMIO(val nClients: Int) extends Bundle() {  //tile not only has client but also has manager
  val client  = Decoupled(new PFCClientIO(nClients))
  val manager = Decoupled(new PFCManagerIO(nClients))
}*/

class NetworkBundle[T <: Data](nNodes: Int, payloadTyp: T) extends Bundle() {
  val netId = UInt(log2Ceil(if(nNodes > 0) nNodes else 1).W)
  val payload = payloadTyp.cloneType
  val last = Bool()

  override def cloneType =
    new NetworkBundle(nNodes, payloadTyp).asInstanceOf[this.type]
}

class NetworkIO[T <: Data](nIn: Int, nOut: Int, payloadTyp: T, netIdRange: Option[Int] = None) extends Bundle() {
  val nNodes = netIdRange.getOrElse(nOut)
  def bundleType(dummy: Int = 0) = new NetworkBundle(nNodes, payloadTyp)

  val in = Flipped(Vec(nIn, Decoupled(bundleType())))
  val out = Vec(nOut, Decoupled(bundleType()))

  override def cloneType = new NetworkIO(nIn, nOut, payloadTyp, netIdRange).asInstanceOf[this.type]
}

class NetworkXbar[T <: Data](nInputs: Int, nOutputs: Int, payloadTyp: T, rr: Boolean = false, dstIds: Seq[(Int, Int)]) extends Module {
  require(dstIds.length == nOutputs)  
  val io = IO(new NetworkIO(nInputs, nOutputs, payloadTyp, Some(1+dstIds.map(_._2).max - dstIds.map(_._1).min)))
  
  val fanout = if (nOutputs > 1) {
    io.in.map { in =>
      in.ready := false.B
      val outputs = Seq.fill(nOutputs) { Wire(Decoupled(io.bundleType())) }
      outputs.zip(dstIds).foreach { case (out, id) =>
        require(id._1 <= id._2, "start id should not greater than end id")
        require(dstIds.count(_._1 == id._1) == 1, "start id duplicicate")
        require(dstIds.count(_._2 == id._2) == 1, "end id duplicicate")
        out.bits := in.bits
        if(id._1 == id._2) {
          out.valid := in.valid  && in.bits.netId === id._1.U
          when(in.bits.netId === id._1.U) { in.ready  := out.ready }
        } else {
          out.valid := in.valid  && in.bits.netId >= id._1.U && in.bits.netId <= id._1.U
          when(in.bits.netId >= id._1.U && in.bits.netId <= id._1.U) { in.ready  := out.ready }
        }
      }
      outputs
    }
  } else {
    io.in.map(in => Seq(in))
  }

  val arbiters = Seq.fill(nOutputs) { Module(new HellaPeekingArbiter(io.bundleType(), nInputs, (b: NetworkBundle[T]) => b.last, rr = rr)) }

  io.out <> arbiters.zipWithIndex.map { case (arb, i) =>
    arb.io.in <> fanout.map(fo => fo(i))
    arb.io.out
  }

}

class PFCNetwork(clientIds: Seq[Int], managerIds: Seq[(Int, Int)]) extends Module {
  val nClients  = clientIds.length
  val nManagers = managerIds.length
  require(nClients == clientIds.max+1)
  val io  = IO(new Bundle {
    val clients  = Flipped(Vec(nClients,  new PFCClientIO(nClients)))
    val managers = Flipped(Vec(nManagers, new PFCManagerIO(nClients)))
  })
  val reqXbar  = Module(new NetworkXbar(nClients,  nManagers, new PFCReq(nClients),  false, managerIds))
  val respXbar = Module(new NetworkXbar(nManagers, nClients,  new PFCResp(nClients), false, clientIds.map(i => (i,i)) ))

  //client <> Xbar
  (0 until nClients).map( i => {
    //client.req --->  reqXbar.in
    reqXbar.io.in(i).valid           := io.clients(i).req.valid
    reqXbar.io.in(i).bits.last       := true.B
    reqXbar.io.in(i).bits.netId      := io.clients(i).req.bits.dst
    reqXbar.io.in(i).bits.payload    := io.clients(i).req.bits
    io.clients(i).req.ready          := reqXbar.io.in(i).ready
    //client.resp ---> respXbar.out
    io.clients(i).resp.valid         := respXbar.io.out(i).valid
    io.clients(i).resp.bits          := respXbar.io.out(i).bits.payload
    respXbar.io.out(i).ready         := io.clients(i).resp.ready
  })

  //manager <> Xbar
  (0 until nManagers).map( i => {
    //reqXbar.out --->  manager.req
    io.managers(i).req.valid         := reqXbar.io.out(i).valid
    io.managers(i).req.bits          := reqXbar.io.out(i).bits.payload
    reqXbar.io.out(i).ready          := io.managers(i).req.ready
    //manager.resp ---> respXbar.in
    respXbar.io.in(i).valid          := io.managers(i).resp.valid
    respXbar.io.in(i).bits.last      := io.managers(i).resp.bits.last    
    respXbar.io.in(i).bits.netId     := io.managers(i).resp.bits.dst
    respXbar.io.in(i).bits.payload   := io.managers(i).resp.bits
    io.managers(i).resp.ready        := respXbar.io.in(i).ready
  })
}

class PFCNetworkl(val clientIds: Seq[Int], val managerIds: Seq[(Int, Int)])(implicit p: Parameters) extends LazyModule {
  val nClients  = clientIds.length
  val nManagers = managerIds.length

  val (clnodes, clios) = {
    val nodeandio = (0 until nClients).map(i => {
      //val node = BundleBridgeSource(() => Flipped((new PFCClientIO(nClients)).cloneType)) // not work??
      val node = new BundleBridgeSink(Some(() => Flipped((new PFCClientIO(nClients)).cloneType)))
      val io   = InModuleBody { node.bundle }
      (node, io)
    })
    ((0 until nClients).map(nodeandio(_)._1),  (0 until nClients).map(nodeandio(_)._2))
  }

    val (manodes, maios) = {
    val nodeandio = (0 until nClients).map(i => {
      //val node = BundleBridgeSource(() => Flipped((new PFCManagerIO(nClients).cloneType)))
      val node = new BundleBridgeSink(Some(() =>  Flipped((new PFCManagerIO(nClients).cloneType))))
      val io   = InModuleBody { node.bundle }
      (node, io)
    })
    ((0 until nClients).map(nodeandio(_)._1),  (0 until nClients).map(nodeandio(_)._2))
  }

 lazy val module = new LazyModuleImp(this) {
   val reqXbar  = Module(new NetworkXbar(nClients,  nManagers, new PFCReq(nClients),  false, managerIds))
   val respXbar = Module(new NetworkXbar(nManagers, nClients,  new PFCResp(nClients), false, clientIds.map(i => (i,i)) ))

   //client <> Xbar
   (0 until nClients).map( i => {
     //client.req --->  reqXbar.in
     reqXbar.io.in(i).valid           := clios(i).req.valid
     reqXbar.io.in(i).bits.last       := true.B
     reqXbar.io.in(i).bits.netId      := clios(i).req.bits.dst
     reqXbar.io.in(i).bits.payload    := clios(i).req.bits
     clios(i).req.ready               := reqXbar.io.in(i).ready
     //client.resp ---> respXbar.out
     clios(i).resp.valid              := respXbar.io.out(i).valid
     clios(i).resp.bits               := respXbar.io.out(i).bits.payload
     respXbar.io.out(i).ready         := clios(i).resp.ready
   })

   //manager <> Xbar
   (0 until nManagers).map( i => {
     //reqXbar.out --->  manager.req
     maios(i).req.valid               := reqXbar.io.out(i).valid
     maios(i).req.bits                := reqXbar.io.out(i).bits.payload
     reqXbar.io.out(i).ready          := maios(i).req.ready
     //manager.resp ---> respXbar.in
     respXbar.io.in(i).valid          := maios(i).resp.valid
     respXbar.io.in(i).bits.last      := maios(i).resp.bits.last    
     respXbar.io.in(i).bits.netId     := maios(i).resp.bits.dst
     respXbar.io.in(i).bits.payload   := maios(i).resp.bits
     maios(i).resp.ready              := respXbar.io.in(i).ready
   })
  }
}
