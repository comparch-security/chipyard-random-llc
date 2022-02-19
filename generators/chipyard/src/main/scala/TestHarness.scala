package chipyard

import chisel3._
import scala.collection.mutable.{ArrayBuffer}
import freechips.rocketchip.diplomacy.{LazyModule}
import freechips.rocketchip.config.{Field, Parameters}

import chipyard.harness.{ApplyHarnessBinders, HarnessBinders}
import chipyard.iobinders.HasIOBinders

// -------------------------------
// Chipyard Test Harness
// -------------------------------

case object BuildTop extends Field[Parameters => LazyModule]((p: Parameters) => new ChipTop()(p))

trait HasTestHarnessFunctions {
  val harnessFunctions = ArrayBuffer.empty[HasHarnessSignalReferences => Seq[Any]]
}

trait HasHarnessSignalReferences {
  def harnessClock: Clock
  def harnessReset: Reset
  def dutReset: Reset
  def success: Bool
}

class TestHarness(implicit val p: Parameters) extends Module with HasHarnessSignalReferences {
  val io = IO(new Bundle {
    val success = Output(Bool())
  })

  val lazyDut = LazyModule(p(BuildTop)(p)).suggestName("chiptop")
  val dut = Module(lazyDut.module)
  io.success := false.B

  var sys_rst = WireInit(reset.asBool())
  dut.asInstanceOf[freechips.rocketchip.diplomacy.LazyRawModuleImp].getPorts.foreach { ports =>
    if(ports.id.toNamed.name == "sys_rst") {
      sys_rst = WireInit(ports.id.asInstanceOf[Reset].asBool())
    }
    if(ports.id.toNamed.name == "glip_rst") {
      ports.id := WireInit(reset)
    }
  }
  val harnessClock = clock
  val harnessReset = WireInit(reset.asBool() | sys_rst)
  val success = io.success

  val dutReset = (reset.asBool() | sys_rst).asAsyncReset



  lazyDut match { case d: HasTestHarnessFunctions =>
    d.harnessFunctions.foreach(_(this))
  }
  lazyDut match { case d: HasIOBinders =>
    ApplyHarnessBinders(this, d.lazySystem, d.portMap)
  }

}

