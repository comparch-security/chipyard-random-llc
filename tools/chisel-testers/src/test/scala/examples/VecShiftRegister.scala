// SPDX-License-Identifier: Apache-2.0

package examples

import chisel3._
import chisel3.iotesters.SteppedHWIOTester

class VecShiftRegister extends Module {
  val io = new Bundle {
    val ins   = Vec(4, Input(UInt(4.W)))
    val load  = Input(Bool())
    val shift = Input(Bool())
    val out   = Output(UInt(4.W))
  }
  val delays = Reg(Vec(4, UInt()))
  when (io.load) {
    delays(0) := io.ins(0)
    delays(1) := io.ins(1)
    delays(2) := io.ins(2)
    delays(3) := io.ins(3)
  } .elsewhen(io.shift) {
    delays(0) := io.ins(0)
    delays(1) := delays(0)
    delays(2) := delays(1)
    delays(3) := delays(2)
  }
  io.out := delays(3)
}

class VecShiftRegisterTests extends SteppedHWIOTester {
  val device_under_test = Module( new VecShiftRegister )
  val c = device_under_test

  val reg = Array.fill(4) {
    0
  }
  val ins = Array.fill(4) {
    0
  }
  // Initialize the delays.
  for (i <- 0 until 4)
    poke(c.io.ins(i), 0)
  poke(c.io.load, 1)
  step(1)

  for (t <- 0 until 16) {
    for (i <- 0 until 4)
      ins(i) = rnd.nextInt(16)
    val shift = rnd.nextInt(2)
    val load = rnd.nextInt(2)
    for (i <- 0 until 4)
      poke(c.io.ins(i), ins(i))
    poke(c.io.load, load)
    poke(c.io.shift, shift)
    step(1)
    if (load == 1) {
      for (i <- 0 until 4)
        reg(i) = ins(i)
    } else if (shift == 1) {
      for (i <- 3 to 1 by -1)
        reg(i) = reg(i - 1)
      reg(0) = ins(0)
    }
    expect(c.io.out, reg(3))
  }
}