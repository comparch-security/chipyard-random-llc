package mdf.macrolib.test

import mdf.macrolib._
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import scala.io.Source


class FlipChipMacroSpec extends FlatSpec with Matchers {
  "Parsing flipchipmacros" should "work" in {
    val stream = getClass.getResourceAsStream("/bumps.json")
    val mdf = Utils.readMDFFromString(scala.io.Source.fromInputStream(stream).getLines().mkString("\n"))
    mdf match {
      case Some(Seq(fcp: FlipChipMacro)) => println(fcp.visualize)
    }
  }
}
