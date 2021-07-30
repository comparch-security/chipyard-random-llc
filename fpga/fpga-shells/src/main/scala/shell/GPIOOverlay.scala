// See LICENSE for license details.
package sifive.fpgashells.shell

import chisel3._
import chisel3.experimental.Analog

import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink.TLBusWrapper
import freechips.rocketchip.interrupts.IntInwardNode
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.LogicalTreeNode

import sifive.blocks.devices.gpio._

case class GPIOShellInput()
case class GPIODesignInput(gpioParams: GPIOParams, node: BundleBridgeSource[GPIOPortIO])(implicit val p: Parameters)
case class GPIOOverlayOutput()
case object GPIOOverlayKey extends Field[Seq[DesignPlacer[GPIODesignInput, GPIOShellInput, GPIOOverlayOutput]]](Nil)
trait GPIOShellPlacer[Shell] extends ShellPlacer[GPIODesignInput, GPIOShellInput, GPIOOverlayOutput]

class ShellGPIOPortIO(val numGPIOs: Int = 4) extends Bundle {
  val gpio = Vec(numGPIOs, Analog(1.W))
}

abstract class GPIOPlacedOverlay(
  val name: String, val di: GPIODesignInput, si: GPIOShellInput)
    extends IOPlacedOverlay[ShellGPIOPortIO, GPIODesignInput, GPIOShellInput, GPIOOverlayOutput]
{
  implicit val p = di.p

  def ioFactory = new ShellGPIOPortIO(di.gpioParams.width)

  val tlgpioSink = sinkScope { di.node.makeSink }
  def overlayOutput = GPIOOverlayOutput()
}


//Directly IO
case class DIOShellInput()
case class DIODesignInput(dioParams: DIOParams, node: BundleBridgeSource[IODPortIO])(implicit val p: Parameters)
case class DIOOverlayOutput()
case object DIOOverlayKey extends Field[Seq[DesignPlacer[DIODesignInput, DIOShellInput, DIOOverlayOutput]]](Nil)
trait DIOShellPlacer[Shell] extends ShellPlacer[DIODesignInput, DIOShellInput, DIOOverlayOutput]

class ShellDIOPortIO(val c: DIOParams) extends Bundle {
  val sw   = if(c.include) Some(Input(UInt(c.sw.W)))    else None  //switch
  val but  = if(c.include) Some(Input(UInt(c.but.W)))   else None  //button
  val led  = if(c.include) Some(Output(UInt(c.led.W)))  else None
  val oled = if(c.include) Some(Output(UInt(c.oled.W))) else None
}

abstract class DIOPlacedOverlay(
  val name: String, val di: DIODesignInput, si: DIOShellInput)
    extends IOPlacedOverlay[ShellDIOPortIO, DIODesignInput, DIOShellInput, DIOOverlayOutput]
{
  implicit val p = di.p

  def ioFactory = new ShellDIOPortIO(di.dioParams)

  val tldioSink = sinkScope { di.node.makeSink }
  def overlayOutput = DIOOverlayOutput()
}
