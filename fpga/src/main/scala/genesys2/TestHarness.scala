package chipyard.fpga.genesys2

import chisel3._
import chisel3.experimental.{IO}

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._

import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.ip.xilinx._
import sifive.fpgashells.shell._
import sifive.fpgashells.clocks._

import sifive.blocks.devices.uart._
import sifive.blocks.devices.spi._
import sifive.blocks.devices.gpio._

import chipyard.{HasHarnessSignalReferences, HasTestHarnessFunctions, BuildTop, ChipTop, ExtTLMem, CanHaveMasterTLMemPort}
import chipyard.iobinders.{HasIOBinders}
import chipyard.harness.{ApplyHarnessBinders}

case object FPGAFrequencyKey extends Field[Double](60.0)

class GENESYS2FPGATestHarness(override implicit val p: Parameters) extends GENESYS2ShellBasicOverlays {

  def dp = designParameters

  val pmod_is_sdio  = p(GENESYS2ShellPMOD) == "SDIO"
  //val jtag_location = Some(if (pmod_is_sdio) "FMC_J2" else "PMOD_J52")

  // Order matters; ddr depends on sys_clock
  val uart      = Overlay(UARTOverlayKey, new UARTGENESYS2ShellPlacer(this, UARTShellInput()))
  val sdio      = if (pmod_is_sdio) Some(Overlay(SPIOverlayKey, new SDIOGENESYS2ShellPlacer(this, SPIShellInput()))) else None
  //val jtag      = Overlay(JTAGDebugOverlayKey, new JTAGDebugGENESYS2ShellPlacer(this, JTAGDebugShellInput(location = jtag_location)))
  //val cjtag     = Overlay(cJTAGDebugOverlayKey, new cJTAGDebugGENESYS2ShellPlacer(this, cJTAGDebugShellInput()))
  //val jtagBScan = Overlay(JTAGDebugBScanOverlayKey, new JTAGDebugBScanGENESYS2ShellPlacer(this, JTAGDebugBScanShellInput()))

  val topDesign = LazyModule(p(BuildTop)(dp)).suggestName("chiptop")

// DOC include start: ClockOverlay
  // place all clocks in the shell
  require(dp(ClockInputOverlayKey).size >= 1)
  val sysClkNode = dp(ClockInputOverlayKey)(0).place(ClockInputDesignInput()).overlayOutput.node

  /*** Connect/Generate clocks ***/

  // connect to the PLL that will generate multiple clocks
  val harnessSysPLL = dp(PLLFactoryKey)()
  harnessSysPLL := sysClkNode

  // create and connect to the dutClock
  val dutClock = ClockSinkNode(freqMHz = dp(FPGAFrequencyKey))
  val dutWrangler = LazyModule(new ResetWrangler)
  val dutGroup = ClockGroup()
  dutClock := dutWrangler.node := dutGroup := harnessSysPLL
// DOC include end: ClockOverlay

  /*** UART ***/

// DOC include start: UartOverlay
  // 1st UART goes to the GENESYS2 dedicated UART

  val io_uart_bb = BundleBridgeSource(() => (new UARTPortIO(dp(PeripheryUARTKey).head)))
  dp(UARTOverlayKey).head.place(UARTDesignInput(io_uart_bb))
// DOC include end: UartOverlay

  /*** SPI ***/

  // 1st SPI goes to the GENESYS2 SDIO port

  val io_spi_bb = BundleBridgeSource(() => (new SPIPortIO(dp(PeripherySPIKey).head)))
  dp(SPIOverlayKey).head.place(SPIDesignInput(dp(PeripherySPIKey).head, io_spi_bb))

  /*** DDR ***/
  val ddrplaced = dp(DDROverlayKey).head.place(DDRDesignInput(dp(ExtTLMem).get.master.base, dutWrangler.node, harnessSysPLL))
  val ddrNode   =  ddrplaced.overlayOutput.ddr

  // connect 1 mem. channel to the FPGA DDR
  val inParams = topDesign match { case td: ChipTop =>
    td.lazySystem match { case lsys: CanHaveMasterTLMemPort =>
      lsys.memTLNode.edges.in(0)
    }
  }
  val ddrClient = TLClientNode(Seq(inParams.master))
  ddrNode := ddrClient

  // module implementation
  override lazy val module = new GENESYS2FPGATestHarnessImp(this)
}

class GENESYS2FPGATestHarnessImp(_outer: GENESYS2FPGATestHarness) extends LazyRawModuleImp(_outer) with HasHarnessSignalReferences {

  val genesys2Outer = _outer

  val reset = IO(Input(Bool()))
  _outer.xdc.addPackagePin(reset, "R19")  //pull up to 3.3v
  _outer.xdc.addIOStandard(reset, "LVCMOS33")

  val resetIBUF = Module(new IBUF)
  resetIBUF.io.I := ~reset 

  val sysclk: Clock = _outer.sysClkNode.out.head._1.clock

  val powerOnReset: Bool = PowerOnResetFPGAOnly(sysclk)
  _outer.sdc.addAsyncPath(Seq(powerOnReset))

  /*
  val ereset: Bool = _outer.chiplink.get() match {
    case Some(x: ChipLinkGENESYS2PlacedOverlay) => !x.ereset_n
    case _ => false.B
  }

  _outer.pllReset := (resetIBUF.io.O || powerOnReset || ereset)
  */
  _outer.pllReset := (resetIBUF.io.O || powerOnReset)

  // reset setup
  val hReset = Wire(Reset())
  hReset := _outer.dutClock.in.head._1.reset

  val harnessClock = _outer.dutClock.in.head._1.clock
  val harnessReset = WireInit(hReset)
  val dutReset = hReset.asAsyncReset
  val success = false.B

  childClock := harnessClock
  childReset := harnessReset

  // harness binders are non-lazy
  _outer.topDesign match { case d: HasTestHarnessFunctions =>
    d.harnessFunctions.foreach(_(this))
  }
  _outer.topDesign match { case d: HasIOBinders =>
    ApplyHarnessBinders(this, d.lazySystem, d.portMap)
  }

  //rst from osd
  var sys_rst = WireInit((~(_outer.pllFactory.plls.getWrappedValue(0)._1.getLocked)).asInstanceOf[Reset]) //getLocked active high
  if(_outer.topDesign.asInstanceOf[ChipTop].lazySystem.isInstanceOf[freechips.rocketchip.osd.HasOSDMAM]) {
    _outer.topDesign.module.asInstanceOf[LazyRawModuleImp].getPorts.foreach { ports =>
      if(ports.id.toNamed.name == "sys_rst") { sys_rst = WireInit(ports.id.asInstanceOf[Reset]) }
  }}
  if(_outer.topDesign.asInstanceOf[ChipTop].lazySystem.isInstanceOf[freechips.rocketchip.osd.HasOSDMAM]) {
    _outer.topDesign.module.asInstanceOf[LazyRawModuleImp].getPorts.foreach { ports =>
      if(ports.id.isInstanceOf[AsyncReset])   { ports.id.asInstanceOf[Reset] := sys_rst.asAsyncReset() }
      if(ports.id.toNamed.name == "glip_rst") { ports.id := ~(_outer.pllFactory.plls.getWrappedValue(0)._1.getLocked) } //getLocked active high
      if(ports.id.toNamed.name == "sys_rst" ) {
        if(_outer.ddrplaced.isInstanceOf[DDRGENESYS2PlacedOverlay]) {
          _outer.ddrplaced.asInstanceOf[DDRGENESYS2PlacedOverlay].mig.module.reset := ports.id
        }
      }
  }}

  //fan
  val fan_pwm = IO(Output(Bool()))
  _outer.xdc.addPackagePin(fan_pwm, "W19")  //pull up to 3.3v
  _outer.xdc.addIOStandard(fan_pwm, "LVCMOS33")
  fan_pwm := _outer.ddrplaced.asInstanceOf[DDRGENESYS2PlacedOverlay].mig.module.io.port.fan_pwm

}
