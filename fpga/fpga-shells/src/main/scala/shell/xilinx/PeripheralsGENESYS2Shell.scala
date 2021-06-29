// See LICENSE for license details.
package sifive.fpgashells.shell.xilinx

import chisel3._
import chisel3.experimental.{attach, Analog, IO}
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.SyncResetSynchronizerShiftReg
import sifive.fpgashells.clocks._
import sifive.fpgashells.shell._
import sifive.fpgashells.ip.xilinx._
import sifive.blocks.devices.chiplink._
import sifive.fpgashells.devices.xilinx.xilinxgenesys2mig._
import sifive.fpgashells.devices.xilinx.xdma._
import sifive.fpgashells.ip.xilinx.xxv_ethernet._



class UARTPeripheralGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: UARTDesignInput, val shellInput: UARTShellInput)
  extends UARTXilinxPlacedOverlay(name, designInput, shellInput, true)
{
    shell { InModuleBody {
    val uartLocations = List(List("Y20", "Y23"))
    val packagePinsWithPackageIOs = Seq((uartLocations(shellInput.index)(0), IOPin(io.rxd)),
                                        (uartLocations(shellInput.index)(1), IOPin(io.txd)))

    packagePinsWithPackageIOs foreach { case (pin, io) => {
      shell.xdc.addPackagePin(io, pin)
      shell.xdc.addIOStandard(io, "LVCMOS33")
      shell.xdc.addIOB(io)
    } }
  } }
}

class UARTPeripheralGENESYS2ShellPlacer(val shell: GENESYS2ShellBasicOverlays, val shellInput: UARTShellInput)(implicit val valName: ValName)
  extends UARTShellPlacer[GENESYS2ShellBasicOverlays]
{
  def place(designInput: UARTDesignInput) = new UARTPeripheralGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}


class QSPIPeripheralGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: SPIFlashDesignInput, val shellInput: SPIFlashShellInput)
  extends SPIFlashXilinxPlacedOverlay(name, designInput, shellInput)
{
    shell { InModuleBody {
    val qspiLocations = List(List("R28", "R29", "R26", "R30", "R29", "T30"))
//FIX when built in spi flash is integrated
    val packagePinsWithPackageIOs = Seq((qspiLocations(shellInput.index)(0), IOPin(io.qspi_sck)),
                                        (qspiLocations(shellInput.index)(1), IOPin(io.qspi_cs)),
                                        (qspiLocations(shellInput.index)(2), IOPin(io.qspi_dq(0))),
                                        (qspiLocations(shellInput.index)(3), IOPin(io.qspi_dq(1))),
                                        (qspiLocations(shellInput.index)(4), IOPin(io.qspi_dq(2))),
                                        (qspiLocations(shellInput.index)(5), IOPin(io.qspi_dq(3))))

    packagePinsWithPackageIOs foreach { case (pin, io) => {
      shell.xdc.addPackagePin(io, pin)
      shell.xdc.addIOStandard(io, "LVCMOS33")
      shell.xdc.addIOB(io)
    } }
    packagePinsWithPackageIOs drop 1 foreach { case (pin, io) => {
      shell.xdc.addPullup(io)
    } }
  } }
}

class QSPIPeripheralGENESYS2ShellPlacer(val shell: GENESYS2ShellBasicOverlays, val shellInput: SPIFlashShellInput)(implicit val valName: ValName)
  extends SPIFlashShellPlacer[GENESYS2ShellBasicOverlays]
{
  def place(designInput: SPIFlashDesignInput) = new QSPIPeripheralGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

class GPIOPeripheralGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: GPIODesignInput, val shellInput: GPIOShellInput)
  extends GPIOXilinxPlacedOverlay(name, designInput, shellInput)
{
    shell { InModuleBody {
    val gpioLocations = List("AU11", "AT12", "AV11", "AU12", "AW13", "AK15", "AY13", "AL15", "AN16", "AL14", "AP16", "AM14", "BF9", "BA15", "BC11", "BC14") //J20 pins 5-16, J1 pins 7-10
    val iosWithLocs = io.gpio.zip(gpioLocations)
    val packagePinsWithPackageIOs = iosWithLocs.map { case (io, pin) => (pin, IOPin(io)) }
    println(packagePinsWithPackageIOs)

    packagePinsWithPackageIOs foreach { case (pin, io) => {
      shell.xdc.addPackagePin(io, pin)
      shell.xdc.addIOStandard(io, "LVCMOS18")
      shell.xdc.addIOB(io)
    } }
  } }
}

class GPIOPeripheralGENESYS2ShellPlacer(val shell: GENESYS2ShellBasicOverlays, val shellInput: GPIOShellInput)(implicit val valName: ValName)
  extends GPIOShellPlacer[GENESYS2ShellBasicOverlays] {

  def place(designInput: GPIODesignInput) = new GPIOPeripheralGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

object PMODGENESYS2PinConstraints {
  val pins = Seq(Seq("AY14","AV16","AY15","AU16","AW15","AT15","AV15","AT16"),
                 Seq("N28","P29","M30","L31","N30","M31","P30","R29"))
}
class PMODGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: PMODDesignInput, val shellInput: PMODShellInput)
  extends PMODXilinxPlacedOverlay(name, designInput, shellInput, packagePin = PMODGENESYS2PinConstraints.pins(shellInput.index), ioStandard = "LVCMOS18")
class PMODGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: PMODShellInput)(implicit val valName: ValName)
  extends PMODShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: PMODDesignInput) = new PMODGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

class PMODJTAGGENESYS2PlacedOverlay(val shell: GENESYS2ShellBasicOverlays, name: String, val designInput: JTAGDebugDesignInput, val shellInput: JTAGDebugShellInput)
  extends JTAGDebugXilinxPlacedOverlay(name, designInput, shellInput)
{
  shell { InModuleBody {
    shell.sdc.addClock("JTCK", IOPin(io.jtag_TCK), 10)
    shell.sdc.addGroup(clocks = Seq("JTCK"))
    shell.xdc.clockDedicatedRouteFalse(IOPin(io.jtag_TCK))
    val packagePinsWithPackageIOs = Seq(("AW15", IOPin(io.jtag_TCK)),
                                        ("AU16", IOPin(io.jtag_TMS)),
                                        ("AV16", IOPin(io.jtag_TDI)),
                                        ("AY14", IOPin(io.jtag_TDO)),
                                        ("AY15", IOPin(io.srst_n))) 
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      shell.xdc.addPackagePin(io, pin)
      shell.xdc.addIOStandard(io, "LVCMOS18")
      shell.xdc.addPullup(io)
      shell.xdc.addIOB(io)
    } }
  } }
}
class PMODJTAGGENESYS2ShellPlacer(shell: GENESYS2ShellBasicOverlays, val shellInput: JTAGDebugShellInput)(implicit val valName: ValName)
  extends JTAGDebugShellPlacer[GENESYS2ShellBasicOverlays] {
  def place(designInput: JTAGDebugDesignInput) = new PMODJTAGGENESYS2PlacedOverlay(shell, valName.name, designInput, shellInput)
}

abstract class PeripheralsGENESYS2Shell(implicit p: Parameters) extends GENESYS2ShellBasicOverlays{
  //val pmod_female      = Overlay(PMODOverlayKey, new PMODGENESYS2ShellPlacer(this, PMODShellInput(index = 0)))
  //val pmodJTAG = Overlay(JTAGDebugOverlayKey, new PMODJTAGGENESYS2ShellPlacer(this, JTAGDebugShellInput()))
  //val gpio           = Overlay(GPIOOverlayKey,       new GPIOPeripheralGENESYS2ShellPlacer(this, GPIOShellInput()))
  val uart  = Seq.tabulate(1) { i => Overlay(UARTOverlayKey, new UARTPeripheralGENESYS2ShellPlacer(this, UARTShellInput(index = i))(valName = ValName(s"uart$i"))) }
  val qspi      = Seq.tabulate(1) { i => Overlay(SPIFlashOverlayKey, new QSPIPeripheralGENESYS2ShellPlacer(this, SPIFlashShellInput(index = i))(valName = ValName(s"qspi$i"))) }


  val topDesign = LazyModule(p(DesignKey)(designParameters))
  p(ClockInputOverlayKey).foreach(_.place(ClockInputDesignInput()))

  override lazy val module = new LazyRawModuleImp(this) {
    val reset = IO(Input(Bool()))
    val por_clock = sys_clock.get.get.asInstanceOf[SysClockGENESYS2PlacedOverlay].clock
    val powerOnReset = PowerOnResetFPGAOnly(por_clock)

    xdc.addPackagePin(reset, "R19")
    xdc.addIOStandard(reset, "LVCMOS33")

    val reset_ibuf = Module(new IBUF)
    reset_ibuf.io.I := reset

    sdc.addAsyncPath(Seq(powerOnReset))

    /*
    val ereset: Bool = chiplink.get() match {
      case Some(x: ChipLinkGENESYS2PlacedOverlay) => !x.ereset_n
      case _ => false.B
    }
   pllReset := reset_ibuf.io.O || powerOnReset || ereset
   */
   pllReset := reset_ibuf.io.O || powerOnReset   
  }
}
