package mdf.macrolib.test

import mdf.macrolib._
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import scala.io.Source


class IOPropertiesSpec extends FlatSpec with Matchers {
  "Parsing io_properties" should "work" in {
    val stream = getClass.getResourceAsStream("/io_properties.json")
    val mdf = Utils.readMDFFromString(scala.io.Source.fromInputStream(stream).getLines().mkString("\n"))
    mdf match {
      case Some(Seq(fcp: IOProperties)) =>
    }
  }
}

