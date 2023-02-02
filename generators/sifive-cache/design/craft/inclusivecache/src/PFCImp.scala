package freechips.rocketchip.pfc

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import sifive.blocks.inclusivecache.Scheduler
import freechips.rocketchip.tilelink.TLMessages

trait HasInclusiveCachePFCManager { this: sifive.blocks.inclusivecache.InclusiveCache =>

  val (pfmanode, pfmaio) = {
    val nClients = p(freechips.rocketchip.subsystem.RocketTilesKey).length
    //val node = new BundleBridgeSink(Some(() =>  Flipped((new PFCManagerIO(nClients).cloneType))))
    val node = BundleBridgeSource(() => Flipped((new PFCManagerIO(nClients).cloneType)))
    val io  = InModuleBody { node.bundle }
    (node, io)
  }
  def createPFCManager(schedulers: Seq[Scheduler], nsets: Int, nclients: Int) = {
    val nschedulers = schedulers.size
    val pfcmanager  = Module(new PFCManager(
    nClients = nclients,
    rebt = Some((0 until nschedulers).map(_ =>
                  Seq(new P0L2PFCReg(),  // order !!!!!
                      new RemaperPFCReg(),
                      new TileLinkPFCReg(),
                      new TileLinkPFCReg()
                  )).reduce(_++_)),
    rabt = Some((0 until nschedulers).map(_ =>
                  Seq(new SetEventPFCRam(nsets),
                      new SetEventPFCRam(nsets)
                  )).reduce(_++_))
   ))
   
   pfcmanager.io.manager <>  pfmaio

   val rePagesperScheduler = pfcmanager.io.update.reg.size / nschedulers
   val raPagesperScheduler = pfcmanager.io.update.ram.size / nschedulers
   (0 until nschedulers).map( i => {
      val restartid          = rePagesperScheduler * i
      val rastartid          = raPagesperScheduler * i
      val EventG0            = pfcmanager.io.update.reg.get(restartid+0)
      val RemaperEvent       = pfcmanager.io.update.reg.get(restartid+1)
      val ITLinkEvent        = pfcmanager.io.update.reg.get(restartid+2).asInstanceOf[TileLinkPFCReg]
      val OTLinkEvent        = pfcmanager.io.update.reg.get(restartid+3).asInstanceOf[TileLinkPFCReg]
      val SetMiss            = pfcmanager.io.update.ram.get(rastartid+0)
      val SetEV              = pfcmanager.io.update.ram.get(rastartid+1)

      EventG0               := schedulers(i).io.pfcupdate.g0
      RemaperEvent          := schedulers(i).io.pfcupdate.remaper
      ITLinkEvent           := schedulers(i).io.pfcupdate.itlink
      OTLinkEvent           := schedulers(i).io.pfcupdate.otlink
      SetMiss               := schedulers(i).io.pfcupdate.setmiss
      SetEV                 := schedulers(i).io.pfcupdate.setev

   })
  } 
}

trait HasSchedulerPFC { this: sifive.blocks.inclusivecache.Scheduler =>

  def connectPFC(params: sifive.blocks.inclusivecache.InclusiveCacheParameters) = {
    io.pfcupdate.g0.elements.foreach(_._2 := false.B)
    io.pfcupdate.remaper          := remaper.io.pfcupdate
    io.pfcupdate.remaper.atcheck  := attackdetector.io.pfcupdate.atcheck
    io.pfcupdate.remaper.atdetec  := attackdetector.io.pfcupdate.atdetec

    freechips.rocketchip.pfc.connect.connectTileLinkPFC(io.in,  params.inner, io.pfcupdate.itlink)
    freechips.rocketchip.pfc.connect.connectTileLinkPFC(io.out, params.outer, io.pfcupdate.otlink)

    io.pfcupdate.setmiss.valid := RegNext(sourceA.io.req.fire() && sourceA.io.req.bits.block)
    io.pfcupdate.setmiss.addr  := RegNext(sourceA.io.req.bits.set)
    io.pfcupdate.setev.valid   := RegNext(sourceC.io.req.fire() && sourceC.io.req.bits.opcode === TLMessages.ReleaseData)
    io.pfcupdate.setev.addr    := RegNext(sourceC.io.req.bits.set)
  } 

}