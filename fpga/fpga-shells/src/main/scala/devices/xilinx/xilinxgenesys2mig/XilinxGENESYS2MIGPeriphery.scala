// See LICENSE for license details.
package sifive.fpgashells.devices.xilinx.xilinxgenesys2mig

import Chisel._
import freechips.rocketchip.config._
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp, AddressRange}

case object MemoryXilinxDDRKey extends Field[XilinxGENESYS2MIGParams]

trait HasMemoryXilinxGENESYS2MIG { this: BaseSubsystem =>
  val module: HasMemoryXilinxGENESYS2MIGModuleImp

  val xilinxgenesys2mig = LazyModule(new XilinxGENESYS2MIG(p(MemoryXilinxDDRKey)))

  xilinxgenesys2mig.node := mbus.toDRAMController(Some("xilinxgenesys2mig"))()
}

trait HasMemoryXilinxGENESYS2MIGBundle {
  val xilinxgenesys2mig: XilinxGENESYS2MIGIO
  def connectXilinxGENESYS2MIGToPads(pads: XilinxGENESYS2MIGPads) {
    pads <> xilinxgenesys2mig
  }
}

trait HasMemoryXilinxGENESYS2MIGModuleImp extends LazyModuleImp
    with HasMemoryXilinxGENESYS2MIGBundle {
  val outer: HasMemoryXilinxGENESYS2MIG
  val ranges = AddressRange.fromSets(p(MemoryXilinxDDRKey).address)
  require (ranges.size == 1, "DDR range must be contiguous")
  val depth = ranges.head.size
  val xilinxgenesys2mig = IO(new XilinxGENESYS2MIGIO(depth))

  xilinxgenesys2mig <> outer.xilinxgenesys2mig.module.io.port
}
