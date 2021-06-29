import mill._
import mill.scalalib._
import mill.scalalib.publish._
import coursier.maven.MavenRepository
import $file.common
import $file.firrtl.build
import $file.chisel3.build
import $file.hardfloat.build
import $file.`api-config-chipsalliance`.`build-rules`.mill.build

object firrtlRocket extends firrtl.build.firrtlCrossModule("2.12.11") {
  override def millSourcePath = os.pwd / "firrtl"
}

object chisel3Rocket extends chisel3.build.chisel3CrossModule("2.12.12") {
  override def millSourcePath = os.pwd / "chisel3"

  def firrtlModule: Option[PublishModule] = Some(firrtlRocket)
}

object configRocket extends `api-config-chipsalliance`.`build-rules`.mill.build.config with PublishModule {
  override def millSourcePath = os.pwd / "api-config-chipsalliance" / "design" / "craft"

  override def scalaVersion = T {
    rocketchip.scalaVersion()
  }

  override def pomSettings = T {
    rocketchip.pomSettings()
  }

  override def publishVersion = T {
    rocketchip.publishVersion()
  }
}

object hardfloatRocket extends hardfloat.build.hardfloat {
  override def millSourcePath = os.pwd / "hardfloat"

  override def scalaVersion = T {
    rocketchip.scalaVersion()
  }

  def chisel3Module: Option[PublishModule] = Some(chisel3Rocket)
}

object rocketchip extends common.CommonRocketChip {
  m =>
  override def scalaVersion: T[String] = T {
    "2.12.10"
  }

  def chisel3Module = Some(chisel3Rocket)

  def hardfloatModule = hardfloatRocket

  def configModule = configRocket

  def scalacPluginClasspath = super.scalacPluginClasspath() ++ Agg(
    chisel3Rocket.plugin.jar()
  )
}