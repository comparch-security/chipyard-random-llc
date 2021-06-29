package chipyard

import chisel3._

import scala.collection.mutable.{ArrayBuffer}

import freechips.rocketchip.prci.{ClockGroupIdentityNode, ClockSinkParameters, ClockSinkNode, ClockGroup}
import freechips.rocketchip.subsystem.{BaseSubsystem, SubsystemDriveAsyncClockGroupsKey}
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.{BundleBridgeSource, InModuleBody}
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp, LazyRawModuleImp, LazyModuleImpLike, BindingScope}
import freechips.rocketchip.util.{ResetCatchAndSync}
import chipyard.iobinders._

import barstools.iocell.chisel._

case object BuildSystem extends Field[Parameters => LazyModule]((p: Parameters) => new DigitalTop()(p))


/**
 * The base class used for building chips. This constructor instantiates a module specified by the BuildSystem parameter,
 * named "system", which is an instance of DigitalTop by default. The diplomatic clocks of System, as well as its implicit clock,
 * is aggregated into the clockGroupNode. The parameterized functions controlled by ClockingSchemeKey and GlobalResetSchemeKey
 * drive clock and reset generation
 */

class ChipTop(implicit p: Parameters) extends LazyModule with BindingScope
    with HasTestHarnessFunctions with HasIOBinders {
  // The system module specified by BuildSystem
  lazy val lazySystem = LazyModule(p(BuildSystem)(p)).suggestName("system")

  // The implicitClockSinkNode provides the implicit clock and reset for the System
  val implicitClockSinkNode = ClockSinkNode(Seq(ClockSinkParameters(name = Some("implicit_clock"))))
  //val sys_rstNode  = BundleBridgeSource(() => (new Bool()).cloneType)
  //val sys_rst  = InModuleBody { sys_rstNode.bundle }
  // Generate Clocks and Reset
  p(ClockingSchemeKey)(this)

  // NOTE: Making this a LazyRawModule is moderately dangerous, as anonymous children
  // of ChipTop (ex: ClockGroup) do not receive clock or reset.
  // However. anonymous children of ChipTop should not need an implicit Clock or Reset
  // anyways, they probably need to be explicitly clocked.
  lazy val module: LazyModuleImpLike = new LazyRawModuleImp(this) {
    // These become the implicit clock and reset to the System
    val implicit_clock = implicitClockSinkNode.in.head._1.clock
    val implicit_reset = implicitClockSinkNode.in.head._1.reset
    val glip_rst:  Reset = IO(Input(Reset())).autoSeed("glip_rst")     //glip reset signal(only key)
    val sys_rst:   Reset = IO(Output(Reset())).autoSeed("sys_rst")     //glip reset signal(only key)

    // Connect the implicit clock/reset, if present
    lazySystem.module match { case l: LazyModuleImp => {
      l.clock := implicit_clock
      l.reset := implicit_reset
      if(l.isInstanceOf[DigitalTopModule[_]]) {
        sys_rst := l.asInstanceOf[DigitalTopModule[_]].sys_rst.asBool()
        l.asInstanceOf[DigitalTopModule[_]].glip_rst := glip_rst.asBool()
      }
    }}


  }
}

