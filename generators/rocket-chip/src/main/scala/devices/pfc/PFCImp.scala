package freechips.rocketchip.pfc

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config.{Field}

object PFCManagerIds {
  val L2Bank0pfcIds   = Seq((8,  8)) 
  val L2Bank1pfcIds   = Seq((9,  9)) 
  val L2Bank2pfcIds   = Seq((10, 10))
  val L2Bank3pfcIds   = Seq((11, 11)) 
  val TCpfcIds        = Seq((12, 12))
  val maxIds          = 13
}

/*trait HasPFCNetImp extends LazyModuleImp  {
  implicit val p: Parameters
  val outer:   freechips.rocketchip.subsystem.BareSubsystem

  val nClients = p(freechips.rocketchip.subsystem.RocketTilesKey).length
  
  val ClientIds  = (0 until nClients).map(i => i).toList.toSeq
  val TilepfcIds = (0 until nClients).map(i => (i,i)).toList.toSeq
  val ManagerIds = TilepfcIds ++ PFCManagerIds.L2Bank0pfcIds

  //val tilespfc = tiles.map(_.pfcnode.makeSink()(p))  // in HasTiles
  val pfcnetwork = Module(new PFCNetwork(ClientIds, TilepfcIds))
  import freechips.rocketchip.subsystem.HasTiles
  if(outer.isInstanceOf[HasTiles]) {
    val tilespfc =  outer.asInstanceOf[HasTiles].tilespfc.map(_.bundle)
    (0 until tilespfc.size).foreach( i => {
      tilespfc(i).client  <> pfcnetwork.io.clients(i)
      tilespfc(i).manager <> pfcnetwork.io.managers(i)
    })                            
  }
}*/

trait HasPFCClient { this: freechips.rocketchip.rocket.CSRFile =>
  val pfcclient = Module(new PFCClient(hartId))
  def connectPFC = {
    io.pfcclient <> pfcclient.io.client
    pfcclient.io.access.addr       := io.rw.addr
    pfcclient.io.access.cmd        := io.rw.cmd
    pfcclient.io.access.wdata      := io.rw.wdata
    pfcclient.io.access.retire     := io.retire.asBool()
    pfcclient.io.access.interrupt  := false.B
  }
}

trait HasL1IPFC { this: freechips.rocketchip.rocket.ICacheModule =>
  def connectPFC = {
    val missSetIdx = (refill_paddr >> blockOffBits)(log2Up(nSets)-1, 0)
    io.pfcupdate.setmiss.valid := refill_fire
    io.pfcupdate.setmiss.addr  := missSetIdx
 }
}

trait HasL1DPFC { this: freechips.rocketchip.rocket.NonBlockingDCacheModule =>
  def connectPFC = {
    val missSetIdx  = WireInit((tl_out.a.bits.address >> blockOffBits)(log2Up(nSets)-1, 0))
    val wbSetaddr   = WireInit((tl_out.c.bits.address >> blockOffBits)(log2Up(nSets)-1, 0))
    io.pfcupdate.setmiss.valid := edge.done(tl_out.a)
    io.pfcupdate.setmiss.addr  := missSetIdx
    io.pfcupdate.setwb.valid := edge.done(tl_out.c)
    io.pfcupdate.setwb.addr  := wbSetaddr
 }
}

trait HasRocketCorePFC { this: freechips.rocketchip.rocket.Rocket =>
  def connectPFC = {
    import freechips.rocketchip.rocket.{ ALU, CSR }
    //import freechips.rocketchip.util  // no effect ??? can't use isOneOf
    //import freechips.rocketchip.rocket.constants.MemoryOpConstants  //no effect ??  can't  use M_XLR  M_XSC

   //copy from MemoryOpConstants
   //trait MemoryOpConstants {
    def M_XRD        = ("b00000").U // int load
    def M_XWR        = ("b00001").U // int store
    def M_PFR        = ("b00010").U // prefetch with intent to read
    def M_PFW        = ("b00011").U // prefetch with intent to write
    def M_XA_SWAP    = ("b00100").U
    def M_FLUSH_ALL  = ("b00101").U // flush all lines
    def M_XLR        = ("b00110").U
    def M_XSC        = ("b00111").U
    def M_XA_ADD     = ("b01000").U
    def M_XA_XOR     = ("b01001").U
    def M_XA_OR      = ("b01010").U
    def M_XA_AND     = ("b01011").U
    def M_XA_MIN     = ("b01100").U
    def M_XA_MAX     = ("b01101").U
    def M_XA_MINU    = ("b01110").U
    def M_XA_MAXU    = ("b01111").U
    def M_FLUSH      = ("b10000").U // write back dirty data and cede R/W permissions
    def M_PWR        = ("b10001").U // partial (masked) store
    def M_PRODUCE    = ("b10010").U // write back dirty data and cede W permissions
    def M_CLEAN      = ("b10011").U // write back dirty data and retain R/W permissions
    def M_SFENCE     = ("b10100").U // flush TLB
    def M_WOK        = ("b10111").U // check write permissions but don't perform a write

    def isAMOLogical(cmd: UInt) =  Seq(M_XA_SWAP, M_XA_XOR, M_XA_OR, M_XA_AND).map(_ === cmd).reduce(_||_)
    def isAMOArithmetic(cmd: UInt) = Seq(M_XA_ADD, M_XA_MIN, M_XA_MAX, M_XA_MINU, M_XA_MAXU).map(_ === cmd).reduce(_||_)
    def isAMO(cmd: UInt) = isAMOLogical(cmd) || isAMOArithmetic(cmd)
    //}

     val csr = rocketImpl.csr
     val id_ctrl  = rocketImpl.id_ctrl
     val ex_ctrl  = rocketImpl.ex_ctrl
     val mem_ctrl = rocketImpl.mem_ctrl
     val wb_ctrl  = rocketImpl.wb_ctrl
     val wb_valid = rocketImpl.wb_valid
     val wb_xcpt  = rocketImpl.wb_xcpt

     val id_ex_hazard                   = rocketImpl.id_ex_hazard
     val id_mem_hazard                  = rocketImpl.id_mem_hazard
     val id_wb_hazard                   = rocketImpl.wb_xcpt
     val id_sboard_hazard               = rocketImpl.id_sboard_hazard
     val icache_blocked                 = rocketImpl.icache_blocked
     val dcache_blocked                 = rocketImpl.dcache_blocked
     val mem_direction_misprediction    = rocketImpl.mem_direction_misprediction
     val mem_misprediction              = rocketImpl.mem_misprediction
     val mem_cfi                        = rocketImpl.mem_cfi
     val id_stall_fpu                   = rocketImpl.id_stall_fpu
     val pipelinedMul                   = rocketImpl.pipelinedMul
     val take_pc_mem                    = rocketImpl.take_pc_mem
     val wb_reg_flush_pipe              = rocketImpl.wb_reg_flush_pipe     
     val replay_wb                      = rocketImpl.replay_wb          

     def pipelineIDToWB[T <: Data](x: T): T = rocketImpl.pipelineIDToWB(x)  

    io.pfcclient <> csr.io.pfcclient

    io.pfcupdate.eventG0.cycle                  := true.B              //event0
    io.pfcupdate.eventG0.instruction            := csr.io.retire       //event1
    io.pfcupdate.eventG0.exception              := csr.io.exception    //event2 different with perfEvents.exception!
    io.pfcupdate.eventG0.load                   := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.mem && id_ctrl.mem_cmd === M_XRD && !id_ctrl.fp)   //event3
    io.pfcupdate.eventG0.store                  := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.mem && id_ctrl.mem_cmd === M_XWR && !id_ctrl.fp)    //event4
    //io.pfcupdate.eventG0.amo                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.mem && (isAMO(id_ctrl.mem_cmd) || id_ctrl.mem_cmd.isOneOf(M_XLR, M_XSC))) && usingAtomics.B   //event5
    io.pfcupdate.eventG0.amo                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.mem && (isAMO(id_ctrl.mem_cmd) || Seq(M_XLR, M_XSC).map(_ === id_ctrl.mem_cmd).reduce(_||_))) && usingAtomics.B   //event5
    io.pfcupdate.eventG0.system                 := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.csr =/= CSR.N)  //event6
    io.pfcupdate.eventG0.arith                  := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.wxd && !(id_ctrl.jal || id_ctrl.jalr || id_ctrl.mem || id_ctrl.fp || id_ctrl.mul || id_ctrl.div || id_ctrl.csr =/= CSR.N))  //event7
    io.pfcupdate.eventG0.branch                 := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.branch)  //event8
    io.pfcupdate.eventG0.jal                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.jal)     //event9
    io.pfcupdate.eventG0.jalr                   := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.jalr)    //event10
    if(usingMulDiv &&  pipelinedMul) {
    io.pfcupdate.eventG0.mul                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.mul)
    io.pfcupdate.eventG0.div                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.div)
    }
    if(usingMulDiv && !pipelinedMul) {
    io.pfcupdate.eventG0.mul                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.div && (id_ctrl.alu_fn & ALU.FN_DIV) =/= ALU.FN_DIV)
    io.pfcupdate.eventG0.div                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.div && (id_ctrl.alu_fn & ALU.FN_DIV) === ALU.FN_DIV)
    }
    if(usingFPU) {
    io.pfcupdate.eventG0.fp_load                := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && io.fpu.dec.ldst && io.fpu.dec.wen)
    io.pfcupdate.eventG0.fp_store               := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && io.fpu.dec.ldst && !io.fpu.dec.wen)
    io.pfcupdate.eventG0.fp_add                 := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && io.fpu.dec.fma && io.fpu.dec.swap23)
    io.pfcupdate.eventG0.fp_mul                 := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && io.fpu.dec.fma && !io.fpu.dec.swap23 && !io.fpu.dec.ren3)
    io.pfcupdate.eventG0.fp_muladd              := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && io.fpu.dec.fma && io.fpu.dec.ren3)
    io.pfcupdate.eventG0.fp_divsqrt             := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp &&  (io.fpu.dec.div || io.fpu.dec.sqrt))
    io.pfcupdate.eventG0.fp_other               := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && !(io.fpu.dec.ldst || io.fpu.dec.fma || io.fpu.dec.div || io.fpu.dec.sqrt))
    }
    io.pfcupdate.eventG0.load_use_interlock     := id_ex_hazard && ex_ctrl.mem || id_mem_hazard && mem_ctrl.mem || id_wb_hazard && wb_ctrl.mem
    io.pfcupdate.eventG0.long_latency_interlock := id_sboard_hazard
    io.pfcupdate.eventG0.csr_interlock          := id_ex_hazard && ex_ctrl.csr =/= CSR.N || id_mem_hazard && mem_ctrl.csr =/= CSR.N || id_wb_hazard && wb_ctrl.csr =/= CSR.N
    io.pfcupdate.eventG0.Iblocked               := icache_blocked
    io.pfcupdate.eventG0.Dblocked               := id_ctrl.mem && dcache_blocked
    io.pfcupdate.eventG0.branch_misprediction   := take_pc_mem && mem_direction_misprediction
    io.pfcupdate.eventG0.cft_misprediction      := take_pc_mem && mem_misprediction && mem_cfi && !mem_direction_misprediction && !icache_blocked //controlflow_target_misprediction
    io.pfcupdate.eventG0.flush                  := wb_reg_flush_pipe
    io.pfcupdate.eventG0.replay                 := replay_wb
    if(usingMulDiv) {
    io.pfcupdate.eventG0.muldiv_interlock       := id_ex_hazard && (ex_ctrl.mul || ex_ctrl.div) || id_mem_hazard && (mem_ctrl.mul || mem_ctrl.div) || id_wb_hazard && wb_ctrl.div
    }
    if(usingFPU) {  
    io.pfcupdate.eventG0.fp_interlock           := id_ex_hazard && ex_ctrl.fp || id_mem_hazard && mem_ctrl.fp || id_wb_hazard && wb_ctrl.fp || id_ctrl.fp && id_stall_fpu
    }
    io.pfcupdate.eventG0.Imiss                  := io.imem.perf.acquire
    io.pfcupdate.eventG0.Dmiss                  := io.dmem.perf.acquire
    io.pfcupdate.eventG0.Drelease               := io.dmem.perf.release
    io.pfcupdate.eventG0.ITLBmiss               := io.imem.perf.tlbMiss
    io.pfcupdate.eventG0.DTLBmiss               := io.dmem.perf.tlbMiss
    io.pfcupdate.eventG0.L2TLBmiss              := io.ptw.perf.l2miss    //event35
  } 
}

trait HasTilePFCNode { this: freechips.rocketchip.tile.BaseTile =>
   //val pfcnode   = BundleBridgeSource(() => (new TilePFCCMIO(p(freechips.rocketchip.subsystem.RocketTilesKey).length).cloneType))
   //val pfc       = InModuleBody { pfcnode.bundle }
   //val pfclnode  = new BundleBridgeSink(Some(() => (new PFCClientIO(p(freechips.rocketchip.subsystem.RocketTilesKey).length).cloneType)))  //not work :PFCNetwork.scala: clnodes
   //val pfcmaode  = new BundleBridgeSink(Some(() => (new PFCManagerIO(p(freechips.rocketchip.subsystem.RocketTilesKey).length).cloneType))) //not work :PFCNetwork.scala: manodes
   val pfclnode  = BundleBridgeSource(() => (new PFCClientIO(p(freechips.rocketchip.subsystem.RocketTilesKey).length).cloneType))
   val pfcmaode  = BundleBridgeSource(() => (new PFCManagerIO(p(freechips.rocketchip.subsystem.RocketTilesKey).length).cloneType))
   val pfccl     = InModuleBody { pfclnode.bundle }
   val pfcma     = InModuleBody { pfcmaode.bundle }
}

/*trait HasTilePFCManager { this: freechips.rocketchip.tile.RocketTileModuleImp =>
  val pfcmanager = Module(new PFCManager(
    nClients = p(freechips.rocketchip.subsystem.RocketTilesKey).length, 
    rebt = Some(Seq(new P0RocketCorePFCReg(),  // order !!!!!
                    new P1RocketCorePFCReg(),
                    new P2FrontendPFCReg(),
                    new P3L1IPFCReg(),
                    new P4L1DPFCReg(),
                    new P5MSHRPFCReg()
                )),
    rabt = Some(Seq(new SetEventPFCRam(64),
                    new SetEventPFCRam(64),
                    new SetEventPFCRam(64)
                ))
   ))

  val CoreEventG0    = pfcmanager.io.update.reg.get(0) 
  val CoreEventG1    = pfcmanager.io.update.reg.get(1)
  val FrontendEvent  = pfcmanager.io.update.reg.get(2)
  val L1IEvent   = pfcmanager.io.update.reg.get(3)
  val L1DEvent   = pfcmanager.io.update.reg.get(4)
  val MSHREvent  = pfcmanager.io.update.reg.get(5)

  val L1ISetMiss =  pfcmanager.io.update.ram.get(0)
  val L1DSetMiss =  pfcmanager.io.update.ram.get(1)
  val L1DSetWB   =  pfcmanager.io.update.ram.get(2)

  //import freechips.rocketchip.tile._
  def connectPFC = {
    outer.pfc.client  <> core.io.pfcclient
    outer.pfc.manager <> pfcmanager.io.manager

  /*outer.pfccl.req.valid               := core.io.pfcclient.req.valid
   outer.pfccl.req.bits                := core.io.pfcclient.req.bits
   core.io.pfcclient.req.ready         := outer.pfccl.req.ready
   core.io.pfcclient.resp.valid        := outer.pfccl.resp.valid
   core.io.pfcclient.resp.bits         := outer.pfccl.resp.bits
   outer.pfccl.resp.ready              := core.io.pfcclient.resp.ready 
   pfcmanager.io.manager.req.valid     := outer.pfcma.req.valid
   pfcmanager.io.manager.req.bits      := outer.pfcma.req.bits
   outer.pfcma.req.ready               := pfcmanager.io.manager.req.ready
   outer.pfcma.resp.valid              := pfcmanager.io.manager.resp.valid
   outer.pfcma.resp.bits               := pfcmanager.io.manager.resp.bits
   pfcmanager.io.manager.resp.ready    := outer.pfcma.resp.ready*/ 
   
    CoreEventG1.elements.foreach(_._2 := false.B)  
    FrontendEvent.elements.foreach(_._2 := false.B)
    L1IEvent.elements.foreach(_._2 := false.B)
    L1DEvent.elements.foreach(_._2 := false.B)
    MSHREvent.elements.foreach(_._2 := false.B) 

    CoreEventG0  := core.io.pfcupdate.eventG0
    L1ISetMiss   := outer.frontend.module.io.pfcupdate.setmiss 
    L1DSetMiss   := outer.dcache.module.io.pfcupdate.setmiss
    L1DSetWB     := outer.dcache.module.io.pfcupdate.setwb
    
    /*
    if these above code is build in RocketTileModuleImp some connect order should change
    {
      outer.pfc.client  <> core.io.pfcclient
      outer.pfc.manager <> pfcmanager.io.manager
      //CoreEventG0 <> core.io.pfcupdate.eventG0    // wrong ????!!! // used as a SinkFlow but can only be used as a SourceFlow
      //CoreEventG0 := core.io.pfcupdate.eventG0    // wrong ????!!!
      //core.io.pfcupdate.eventG0    <> CoreEventG0 // ok  
      core.io.pfcupdate.eventG0   :=  CoreEventG0

      outer.frontend.module.io.pfcupdate.setmiss  :=  L1ISetMiss
      outer.dcache.module.io.pfcupdate.setmiss    :=  L1DSetMiss
      outer.dcache.module.io.pfcupdate.setwb      :=  L1DSetWB
    }*/

  }
}*/

trait CanAttachTiletoPFC { this:  freechips.rocketchip.subsystem.CanAttachTile =>
  import freechips.rocketchip.tile.TilePRCIDomain
  def connectPFC (domain: TilePRCIDomain[TileType], context: TileContextType, hartID: Int): Unit = {
    implicit val p = context.p
    val pfbus = context.asInstanceOf[HasPFCnetwork].pfbus

    val TilePFCCLNode  = domain.tile.asInstanceOf[HasTilePFCNode].pfclnode
    val TilePFCMANode  = domain.tile.asInstanceOf[HasTilePFCNode].pfcmaode

    //TilePFCCLNode       := pfbu.clnodes(hartID)
    //TilePFCMANode       := pfbu.manodes(hartID)
    pfbus.clnodes(hartID) := TilePFCCLNode
    pfbus.manodes(hartID) := TilePFCMANode
  }
}

trait HasTilePFCManager { this: freechips.rocketchip.tile.RocketTileModuleImp =>

  val pfcmanager = Module(new PFCManager(
    nClients = p(freechips.rocketchip.subsystem.RocketTilesKey).length, 
    rebt = Some(Seq(new P0RocketCorePFCReg(),  // order !!!!!
                    new P1RocketCorePFCReg(),
                    new P2FrontendPFCReg(),
                    new P3L1IPFCReg(),
                    new P4L1DPFCReg(),
                    new P5MSHRPFCReg()
                )),
    rabt = Some(Seq(new SetEventPFCRam(64),
                    new SetEventPFCRam(64),
                    new SetEventPFCRam(64)
                ))
   ))

  val CoreEventG0    = pfcmanager.io.update.reg.get(0) 
  val CoreEventG1    = pfcmanager.io.update.reg.get(1)
  val FrontendEvent  = pfcmanager.io.update.reg.get(2)
  val L1IEvent   = pfcmanager.io.update.reg.get(3)
  val L1DEvent   = pfcmanager.io.update.reg.get(4)
  val MSHREvent  = pfcmanager.io.update.reg.get(5)

  val L1ISetMiss =  pfcmanager.io.update.ram.get(0)
  val L1DSetMiss =  pfcmanager.io.update.ram.get(1)
  val L1DSetWB   =  pfcmanager.io.update.ram.get(2)

  def connectPFC = {
    core.io.pfcclient <> outer.pfccl
    pfcmanager.io.manager <> outer.pfcma

    CoreEventG1.elements.foreach(_._2 := false.B)  
    FrontendEvent.elements.foreach(_._2 := false.B)
    L1IEvent.elements.foreach(_._2 := false.B)
    L1DEvent.elements.foreach(_._2 := false.B)
    MSHREvent.elements.foreach(_._2 := false.B) 

    CoreEventG0  := core.io.pfcupdate.eventG0
    L1ISetMiss   := outer.frontend.module.io.pfcupdate.setmiss 
    L1DSetMiss   := outer.dcache.module.io.pfcupdate.setmiss
    L1DSetWB     := outer.dcache.module.io.pfcupdate.setwb

  }

}

trait HasPFCnetwork  { this: freechips.rocketchip.subsystem.BaseSubsystem =>
  val pfbus = {
    val nClients = p(freechips.rocketchip.subsystem.RocketTilesKey).length
    val ClientIds  = (0 until nClients).map(i => i).toList.toSeq
    val TilepfcIds = (0 until nClients).map(i => (i,i)).toList.toSeq
    val ManagerIds = TilepfcIds ++ PFCManagerIds.L2Bank0pfcIds
    
    LazyModule(new PFCNetworkl(ClientIds, TilepfcIds))
  }
}
