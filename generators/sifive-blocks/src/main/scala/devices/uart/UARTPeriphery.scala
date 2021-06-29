// See LICENSE for license details.
package sifive.blocks.devices.uart

import freechips.rocketchip.config.Field
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.{BaseSubsystem, PeripheryBusKey}

case object PeripheryUARTKey extends Field[Seq[UARTParams]](Nil)

trait HasPeripheryUART { this: BaseSubsystem =>
  p(PeripheryUARTKey).zipWithIndex.map { case(ps,i) =>
    if(ps.isosddem) require(i==0, "use first usrt as osd uart daemon!")
  }
  val isosddem = p(PeripheryUARTKey).head.isosddem
  val (uartNodes, osduartNode) = {
    val tluart = p(PeripheryUARTKey).map { ps => UARTAttachParams(ps).attachTo(this) }
    (tluart.map(_.ioNode.makeSink()), tluart.map(_.osduartNodes.makeSink()).head)
  }
}

trait HasPeripheryUARTBundle {
  val uart: Seq[UARTPortIO]
}


trait HasPeripheryUARTModuleImp extends LazyModuleImp with HasPeripheryUARTBundle {
  val outer: HasPeripheryUART
  val uart = outer.uartNodes.zipWithIndex.map { case(n,i) => n.makeIO()(ValName(s"uart_$i")) }
  val osduart = outer.osduartNode.bundle
  if(this.isInstanceOf[freechips.rocketchip.osd.HasOSDImp]) {
    val osd = this.asInstanceOf[freechips.rocketchip.osd.HasOSDImp].osd
    if(outer.isosddem) {
      uart(0).txd     := osd.io.glip.txd
      osd.io.glip.rxd := uart(0).rxd
      osd.io.uartdem  <> osduart
    }
  }
}
