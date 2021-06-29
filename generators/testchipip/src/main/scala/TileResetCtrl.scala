package testchipip

import chisel3._
import chisel3.experimental.{IO}
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.prci.{ClockSinkDomain, ClockGroupIdentityNode}

// initResetHarts: list of hartids which will stay in reset until its reset-ctrl register is cleared
case class TileResetCtrlParams(initResetHarts: Seq[Int] = Nil, address: BigInt=0x100000, slaveWhere: TLBusWrapperLocation = PBUS)
case object TileResetCtrlKey extends Field[TileResetCtrlParams](TileResetCtrlParams())

object TLTileResetCtrl {
  def apply(sys: BaseSubsystem with InstantiatesTiles)(implicit p: Parameters) = {
    val resetCtrlParams = p(TileResetCtrlKey)
    val tlbus = sys.locateTLBusWrapper(resetCtrlParams.slaveWhere)
    val domain = sys { LazyModule(new ClockSinkDomain(name=Some("tile-reset-ctrl"))) }
    domain.clockNode := tlbus.fixedClockNode
    val resetCtrl = domain {
      LazyModule(new TLTileResetCtrl(tlbus.beatBytes, resetCtrlParams, sys.tile_prci_domains))
    }
    tlbus.toVariableWidthSlave(Some("tile-reset-ctrl")) { resetCtrl.node := TLBuffer() }
    resetCtrl.tileResetProviderNode
  }
}

class TLTileResetCtrl(w: Int, params: TileResetCtrlParams, tile_prci_domains: Seq[TilePRCIDomain[_]])(implicit p: Parameters) extends LazyModule {
  val device = new SimpleDevice("tile-reset-ctrl", Nil)
  val node = TLRegisterNode(Seq(AddressSet(params.address, 4096-1)), device, "reg/control", beatBytes=w)
  val tileResetProviderNode = ClockGroupIdentityNode()

  lazy val module = new LazyModuleImp(this) {
    val nTiles = p(TilesLocated(InSubsystem)).size
    require (nTiles <= 4096)
    val r_tile_resets = (0 until nTiles).map({ i =>
      Module(new AsyncResetRegVec(w=1, init=(if (params.initResetHarts.contains(i)) 1 else 0)))
    })
    node.regmap((0 until nTiles).map({ i =>
      i -> Seq(RegField.rwReg(1, r_tile_resets(i).io)),
    }): _*)

    val tileMap = tile_prci_domains.zipWithIndex.map({ case (d, i) =>
        d.tile_reset_domain.clockNode.portParams(0).name.get -> r_tile_resets(i).io.q
    })
    (tileResetProviderNode.out zip tileResetProviderNode.in).map { case ((o, _), (i, _)) =>
      (o.member.elements zip i.member.elements).foreach { case ((name, oD), (_, iD)) =>
        oD.clock := iD.clock
        oD.reset := iD.reset
        for ((n, r) <- tileMap) {
          if (name.contains(n)) {
            oD.reset := r.asBool || iD.reset.asBool
          }
        }
      }
    }
  }
}

