/*
 * Copyright 2019 SiFive, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You should have received a copy of LICENSE.Apache2 along with
 * this software. If not, you may obtain a copy at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sifive.blocks.inclusivecache

import Chisel._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.rocket._
import freechips.rocketchip.pfc._
import scala.math._
import chisel3.util.random.FibonacciLFSR

object MultiplierTag {
  val SZ   = 2
  val ERRSQ   = UInt(0, SZ)  //Error Square
  val SAR     = UInt(1, SZ)  //SuccessiveApproximateRegister
  val EMAZS1  = UInt(2, SZ)  //ExponentialMovingAverageZScore Step 1 tmp1 = ev * (ev - evAver)
  val EMAZS2  = UInt(3, SZ)  //ExponentialMovingAverageZScore Step 2 emaz = tmp1 * evStdDevReci
}

object SuccessiveApproximateRegisterFn {
  val SZ   = 1
  def DIV  = UInt(0, SZ)
  def SQRT = UInt(1, SZ)
}

class SuccessiveApproximateRegisterReq(inWidth: Int) extends Bundle {
  val fn     = Bits(SuccessiveApproximateRegisterFn.SZ.W)
  val in     = UInt(inWidth.W)
  val neg    = Bool()
  val target = UInt((2 * inWidth).W)
  override def cloneType = new SuccessiveApproximateRegisterReq(inWidth).asInstanceOf[this.type]
}

class SuccessiveApproximateRegisterResp(inWidth: Int) extends Bundle {
  val neg    = Bool()
  val result = UInt(inWidth.W)
  override def cloneType = new SuccessiveApproximateRegisterResp(inWidth).asInstanceOf[this.type]
}

class ErrorAnalysis(setBits        : Int,
                    evIntWidth     : Int, evFracWidth      :   Int,
                    evSqIntWidth   : Int, evSqFracWidth    :   Int,
                    emazIntWidth   : Int, emazFracWidth    :   Int) extends Bundle {
  val evSum           = UInt(width = evIntWidth + setBits)
  val evAvera         = UInt(width = evIntWidth + setBits - evFracWidth)
  val evSqSum         = UInt(width = evSqIntWidth + evSqFracWidth + setBits)
  val evSqAvera       = UInt(width = evSqIntWidth + evSqFracWidth)
  val evStdDev        = UInt(width = evIntWidth + evFracWidth)
  val evStdDevReci    = UInt(width = evIntWidth + evFracWidth)

  val set             = UInt(width = setBits)
  val ev              = UInt(width = evIntWidth + evFracWidth)
  val evErrAbs        = UInt(width = evIntWidth + evFracWidth)       //|ev - evAver|
  val evErrNeg        = Bool()
  val evMulErr        = UInt(width = 2 * evIntWidth + evFracWidth)   //evMulErr  = ev * evErrAbs = ev * |ev - evAver|
  val evWZscore       = UInt(width = 3 * evIntWidth + evFracWidth)   //evWZscore = evMulErr * evStdDevReci
  val delta           = UInt(width = emazIntWidth + emazFracWidth)
  val deltaNeg        = Bool()
  val emaz            = UInt(width = emazIntWidth + emazFracWidth)
  val detected        = Bool()

  override def cloneType = new ErrorAnalysis(setBits        ,
                                             evIntWidth     ,     evFracWidth    ,
                                             evSqIntWidth   ,     evSqFracWidth  ,
                                             emazIntWidth   ,     emazFracWidth  ).asInstanceOf[this.type]
}

class SuccessiveApproximateRegister(inWidth: Int,  mulWidth: Int) extends Module {
  val io = new Bundle {
    val req       = Flipped(Decoupled(new SuccessiveApproximateRegisterReq(inWidth)))
    val resp      = Valid(new SuccessiveApproximateRegisterResp(inWidth))
    val mulreq    = Decoupled(new MultiplierReq(mulWidth, MultiplierTag.SZ))
    val mulresp   = Flipped(Valid(new MultiplierResp(mulWidth, MultiplierTag.SZ)))
  }

  val req       = Reg(new SuccessiveApproximateRegisterReq(inWidth))
  val result    = Reg(UInt(inWidth.W))
  val curBit    = Reg(UInt(log2Up(inWidth).W))
  val nextBit   = Reg(UInt(log2Up(inWidth).W))
  val s_idle :: s_mul_req :: s_mul_resp :: s_finish :: Nil = Enum(UInt(), 4)
  val state = Reg(init = s_idle)

  //s_idle
  io.req.ready := state === s_idle
  when(io.req.fire()) {
    req        := io.req.bits
    state      := s_mul_req
    curBit     := (inWidth - 1).U
    nextBit    := (inWidth - 2).U
    result     := (1 << (result.getWidth - 1)).U
    when(io.req.bits.fn === SuccessiveApproximateRegisterFn.DIV) {
      when(io.req.bits.in === 0.U || io.req.bits.target === 0.U) {
        state  := s_finish
        result := ((1 << result.getWidth) - 1).U
      }
    }
  }

  //s_mul_req
  io.mulreq.valid    := state === s_mul_req
  io.mulreq.bits.tag := MultiplierTag.SAR
  io.mulreq.bits.in1 := req.in.toBits
  io.mulreq.bits.in2 := result.toBits
  when(req.fn === SuccessiveApproximateRegisterFn.SQRT) {
    io.mulreq.bits.in1 := result.toBits
  }
  when(io.mulreq.fire()) {
    state := s_mul_resp
  }

  //s_mul_resp
  val keepCurBitHigh          = io.mulresp.bits.data <= req.target
  val resultCurBitCleared     = (result & ~(1.U << curBit))
  when(state === s_mul_resp && io.mulresp.valid && io.mulresp.bits.tag === MultiplierTag.SAR) {
    state        := s_mul_req
    result       := resultCurBitCleared | (1.U << nextBit)
    curBit       := Mux(curBit  === 0.U,  curBit,  curBit - 1.U)
    nextBit      := Mux(nextBit === 0.U, nextBit, nextBit - 1.U)
    when(curBit === 0.U && nextBit === 0.U) {
      state      := s_finish
      result     := resultCurBitCleared
      when(keepCurBitHigh) {
        result   := result
      }
    }.elsewhen(keepCurBitHigh) {
      result     := result | (1.U << nextBit)
    }
  }

  //s_finish
  io.resp.valid        := state === s_finish
  io.resp.bits.result  := result.asUInt()
  io.resp.bits.neg     := req.neg
  when(io.resp.valid || reset)  { state := s_idle }

}

class ExponentialMovingAverageZScoreReq(setBits: Int, evWidth: Int, discountWidth: Int) extends Bundle {
  val set             = UInt(width = setBits)
  val ev              = UInt(width = evWidth)
  val evErrAbs        = UInt(width = evWidth) //|ev - evAver|
  val evErrNeg        = Bool()
  val evStdDevReci    = UInt(width = evWidth)
  val discount        = UInt(width = discountWidth)
  override def cloneType = new ExponentialMovingAverageZScoreReq(setBits, evWidth, discountWidth).asInstanceOf[this.type]
}

class ExponentialMovingAverageZScore(
          val evIntWidth:   Int,  val evFracWidth:   Int,
          val evSqIntWidth: Int,  val evSqFracWidth: Int,
          val emazIntWidth: Int,  val emazFracWidth: Int,
          val setBits:      Int,  val mulWidth:      Int, val discountWidth: Int) extends Module {

  class emazwrite extends Bundle {
    val set    = UInt(width = setBits)
    val emaz   = UInt(width = emazIntWidth + emazFracWidth)
  }

  class TempResult extends ExponentialMovingAverageZScoreReq(setBits, evIntWidth + evFracWidth, discountWidth) {
     val evMulErr     = UInt(width = 2 * evIntWidth + evFracWidth)   //evMulErr  = ev * evErrAbs = ev * |ev - evAver|
     val evWZscore    = UInt(width = 3 * evIntWidth + evFracWidth)   //evWZscore = evMulErr * evStdDevReci
     val emazAddend   = Vec(2, UInt(width = 3 * evIntWidth + emazFracWidth))
     val result       = UInt(width = emazIntWidth + emazFracWidth)   //result = emazAddend(0) + emazAddend(1)
     //override def cloneType = new TempResult(setBits, emazWidth, emazWidth, discountWidth).asInstanceOf[this.type]
  }
  val io = new Bundle {
    val req       = Flipped(Decoupled(new ExponentialMovingAverageZScoreReq(setBits, evIntWidth + evFracWidth, discountWidth)))
    val mulreq    = Decoupled(new MultiplierReq(mulWidth, MultiplierTag.SZ))
    val mulresp   = Flipped(Valid(new MultiplierResp(mulWidth, MultiplierTag.SZ)))
    val emazread  = Decoupled(UInt(width = setBits))
    val emazresp  = Flipped(Valid(UInt(width = emazIntWidth + emazFracWidth)))
    val emazwrite = Valid(new emazwrite())
    val max       = new TempResult()
    val erranl    = Valid(new ErrorAnalysis(setBits, evIntWidth, evFracWidth, evSqIntWidth, evSqFracWidth, emazIntWidth, emazFracWidth))
  }

  val lfsr       = FibonacciLFSR.maxPeriod(16, true.B, seed = Some(11))
  val mulreqArb  = Module(new Arbiter(new MultiplierReq(mulWidth, MultiplierTag.SZ), 2))
  val s_idle :: s_req :: s_resp :: s_finish :: Nil = Enum(Bits(), 4)
  class Stage extends Bundle {
    val state  = UInt(width = 2)
    val temp   = new TempResult()
  }
  val maxLatch = Reg(new Stage())
  val stage = Seq.fill(4) { Reg(new Stage()) }
  io.mulreq <> mulreqArb.io.out

  //stage 1 : evMulErr  = ev * evErrAbs = ev * |ev - evAver|
  io.req.ready := stage(0).state === s_idle
  when(io.req.fire()) {
    stage(0).state                 := s_req
    stage(0).temp                  := io.req.bits
    when(io.req.bits.set === 0.U) {
      maxLatch.temp.result         := 0.U
    }
  }
  mulreqArb.io.in(0).valid         :=  stage(0).state === s_req
  mulreqArb.io.in(0).bits.tag      :=  MultiplierTag.EMAZS1
  mulreqArb.io.in(0).bits.in1      :=  stage(0).temp.ev.toBits
  mulreqArb.io.in(0).bits.in2      :=  stage(0).temp.evErrAbs.toBits
  when(mulreqArb.io.in(0).fire())  { stage(0).state  := s_resp }
  when(stage(0).state === s_resp   && io.mulresp.valid && io.mulresp.bits.tag === MultiplierTag.EMAZS1) {
    stage(0).state                 := s_finish
    stage(0).temp.evMulErr         := io.mulresp.bits.data.asUInt() >> evFracWidth
  }
  when(stage(0).state === s_finish && stage(1).state === s_idle) { stage(0).state := s_idle }

  //stage 2 : evWZscore = evMulErr * evStdDevReci
  when(stage(0).state === s_finish && stage(1).state === s_idle) {
    stage(1).state                 := s_req
    stage(1).temp                  := stage(0).temp
  }
  mulreqArb.io.in(1).valid         :=  stage(1).state === s_req
  mulreqArb.io.in(1).bits.tag      :=  MultiplierTag.EMAZS2
  mulreqArb.io.in(1).bits.in1      :=  stage(1).temp.evMulErr.toBits
  mulreqArb.io.in(1).bits.in2      :=  stage(1).temp.evStdDevReci.toBits
  when(mulreqArb.io.in(1).fire()) { stage(1).state  := s_resp }
  when(stage(1).state === s_resp   && io.mulresp.valid && io.mulresp.bits.tag === MultiplierTag.EMAZS2) {
    stage(1).state                 := s_finish
    stage(1).temp.evWZscore        := io.mulresp.bits.data.asUInt() >> evFracWidth
  }
  when(stage(1).state === s_finish && stage(2).state === s_idle) { stage(1).state := s_idle }

  //stage 3 : emazAddend(0) = evWZscore >> discount emazAddend(1) = emazresp - (emazresp >> discount)
  when(stage(1).state === s_finish && stage(2).state === s_idle) {
    stage(2).state                 := s_req
    stage(2).temp                  := stage(1).temp
    stage(2).temp.emazAddend(0)    := stage(1).temp.evWZscore >> (evFracWidth - emazFracWidth).U >> io.req.bits.discount
  }
  io.emazread.valid                := stage(2).state === s_req
  io.emazread.bits                 := stage(2).temp.set
  when(io.emazread.fire())        { stage(2).state := s_resp }
  when(stage(2).state === s_resp   && io.emazresp.valid) {
    stage(2).state                 := s_finish
    stage(2).temp.emazAddend(1)    := io.emazresp.bits - (io.emazresp.bits >> io.req.bits.discount)
    when((io.emazresp.bits >> io.req.bits.discount) > 1.U) {
      stage(2).temp.emazAddend(1)  := io.emazresp.bits - (io.emazresp.bits >> io.req.bits.discount)
    }
    when((io.emazresp.bits >> io.req.bits.discount) === 0.U) {
      (1 to 7).map(i => { when(io.req.bits.discount === i.U && lfsr(i-1, 0) < io.emazresp.bits(i-1, 0)) { stage(2).temp.emazAddend(1) := io.emazresp.bits - 1.U }})
    }
    when(io.req.bits.discount === 0.U || io.emazresp.bits === 0.U) { stage(2).temp.emazAddend(1) := 0.U }
  }
  when(stage(2).state === s_finish && stage(3).state === s_idle) { stage(2).state := s_idle }

  //stage 4 : result = emazAddend(0) + emazAddend(1)
  val emazAddendAdd  = stage(2).temp.emazAddend(1) + stage(2).temp.emazAddend(0)
  val emazAddendSub  = stage(2).temp.emazAddend(1) - stage(2).temp.emazAddend(0)
  val resultMax      = (1 << stage(2).temp.result.getWidth) - 1
  val overflowHi     = emazAddendAdd >= resultMax.U
  val overflowLo     = stage(2).temp.emazAddend(1) <= stage(2).temp.emazAddend(0)
  when(stage(2).state === s_finish && stage(3).state === s_idle) {
    stage(3).state                 := s_req
    stage(3).temp                  := stage(2).temp
    stage(3).temp.result           := Mux(stage(2).temp.evErrNeg, Mux(overflowLo,         0.U, emazAddendSub),
                                                                  Mux(overflowHi, resultMax.U, emazAddendAdd))
  }
  io.emazwrite.valid               := stage(3).state === s_req && !io.emazread.valid
  io.emazwrite.bits.set            := stage(3).temp.set
  io.emazwrite.bits.emaz           := stage(3).temp.result
  when(io.emazwrite.valid) {
    stage(3).state := s_idle
    when(maxLatch.temp.result < stage(3).temp.result) {
      maxLatch.temp := stage(3).temp
    }
  }

  io.max := maxLatch.temp
  when(reset) { (0 until 4).map(stage(_).state := s_idle ) }

  //error analy
  io.erranl.valid             := io.emazwrite.valid
  io.erranl.bits.set          := io.emazwrite.bits.set
  io.erranl.bits.ev           := stage(3).temp.ev
  io.erranl.bits.evErrAbs     := stage(3).temp.evErrAbs
  io.erranl.bits.evErrNeg     := stage(3).temp.evErrNeg
  io.erranl.bits.evMulErr     := stage(3).temp.evMulErr      //evMulErr  = ev * evErrAbs = ev * |ev - evAver|
  io.erranl.bits.evWZscore    := stage(3).temp.evWZscore     //evWZscore = evMulErr * evStdDevReci
  io.erranl.bits.delta        := stage(3).temp.emazAddend(0)
  io.erranl.bits.deltaNeg     := stage(3).temp.evErrNeg
  io.erranl.bits.emaz         := io.emazwrite.bits.emaz
}

class AttackDetectorConfig0 extends Bundle {
  val athreshold      = UInt(width = 31)   //access threshold
  val enath           = Bool()
  val ethreshold      = UInt(width = 31)   //evict threshold
  val eneth           = Bool() //lowest bit
}

class AttackDetectorConfig1 extends Bundle {
  val reserve         = UInt(width = 35)
  val discount        = UInt(width =  4)   //discount factor right shift amount
  val period          = UInt(width = 20)   //sample period
  val zthreshold      = UInt(width =  4)    //z-vaule threshold
  val enzth           = Bool()             //lowest bit
}


class AttackDetector(params: InclusiveCacheParameters) extends Module
{
  //fix point parameters
  val evIntWidth                     = 5
  val evFracWidth                    = params.setBits
  val evSqIntWidth                   = 2 * evIntWidth
  val evSqFracWidth                  = 2 * evFracWidth
  val emazIntWidth                   = 4                                              //exponential moving average
  val emazFracWidth                  = evFracWidth                                    //exponential moving average
  val mulWidth                       = 3 * evIntWidth + evSqFracWidth
  val mulLatency                     = 2
  require(evFracWidth   > 2)
  require(emazFracWidth > 1)
  require(evFracWidth >= emazFracWidth)

  val io = new Bundle {
    val config0   = new AttackDetectorConfig0().asInput
    val config1   = new AttackDetectorConfig1().asInput
    val remap     = Decoupled(new RemaperReqIO())
    val evict     = Valid(new DirectoryRead(params)).flip
    val access    = Valid(new DirectoryRead(params)).flip
    //pfc
    val pfcupdate  = (new RemaperPFCReg()).flip
    //error analy
    val debug      = new Bundle {
      val fifoready   = Bool().asOutput
      val wipedone    = Bool().asOutput
      val calculate   = Bool().asOutput
      val calsqsum    = Bool().asOutput
      val calemaz     = Bool().asOutput
      val trigger     = Bool().asInput
      val erranl      = Decoupled(new ErrorAnalysis(params.setBits, evIntWidth, evFracWidth, evSqIntWidth, evSqFracWidth, emazIntWidth, emazFracWidth))
    }
  }
  require(io.config1.zthreshold.getWidth == emazIntWidth)

  val laseSet = (1 << params.setBits) - 1
  def UIntMax(u:        UInt):  UInt = {((1.toLong << u.getWidth) -1).U  }
  def evInt2Fix(evicts: UInt):  UInt = { Cat(evicts, 0.U(evFracWidth.W)) }
  def evFix2Int(evicts: UInt):  UInt = { evicts >> evFracWidth           }
  def fracAllign(frac:  UInt):  UInt = {
    val offset  = frac.getWidth % 4
    val result  = if(offset == 0) frac else Cat(frac, 0.U((4 - offset).W))
    result
  }
  def frac2Int10(frac:  UInt):  UInt = {
    val fracW   = frac.getWidth
    val int10W: Int= {
      var int10Max : BigInt = 0
      (fracW - 1 to 0 by -1).map( i =>  int10Max = int10Max * 10 + pow(5, fracW - i).toInt )
      log2Ceil(int10Max)
    }
    var int10  = Wire(init = 0.U(int10W.W))
    (fracW - 1 to 0 by -1).map( i => int10 = ((int10 * 10.U) + Mux(frac(i), pow(5, fracW - i).toInt.U, 0.U))(int10W - 1, 0) )
    int10
  }

  val (sram_evicts, _)  = DescribedSRAM(
    name = "sram_evicts",
    desc = "Evicts RAM",
    size = params.cache.sets,
    data = UInt(width = 2*evIntWidth)   //alternate record: half for record half for sample
  )
  val (sram_emaz, _)  = DescribedSRAM(
    name = "sram_emaz",
    desc = "Zscore RAM",
    size = params.cache.sets,
    data = UInt(width = emazIntWidth + emazFracWidth)
  )
  val mul                        = Module(new PipelinedMultiplier(width = mulWidth, latency = mulLatency, nXpr = 1 << MultiplierTag.SZ))
  val sar                        = Module(new SuccessiveApproximateRegister(evIntWidth + evFracWidth, mulWidth))
  val emaz                       = Module(new  ExponentialMovingAverageZScore(evIntWidth, evFracWidth, evSqIntWidth, evSqFracWidth, emazIntWidth, emazFracWidth, params.setBits, mulWidth, io.config1.discount.getWidth))
  val mulBusyCnt                 = RegInit(0.U(log2Up(mulLatency).W))
  val mulBusy                    = mulBusyCnt =/= mulBusyCnt
  val evicts                     = Wire(UInt(width = evIntWidth))
  val recordWay                  = RegInit(UInt(0,  width = 1))
  val wipeCount                  = RegInit(UInt(0,  width = params.setBits + 1))
  val wipeDone                   = wipeCount(params.setBits)
  when(!wipeDone)  { wipeCount := wipeCount + 1.U }
  val s_idle :: s_SqSum :: s_StdDevReq :: s_StdDevResp :: s_ReciReq :: s_ReciResp :: s_EMAZ :: Nil = Enum(Bits(), 7)
  val state                      = Reg(init = s_idle)
  val count_access               = RegInit(0.U(32.W))
  val count_evicts               = RegInit(0.U(32.W))
  val count_period               = RegInit(0.U(20.W))
  val max_emaz                   = RegInit(0.U((emazIntWidth + emazFracWidth).W))
  val evLatch                    = Reg(Vec(2, UInt(0, width = evIntWidth + params.setBits))) //alternate record: half for record half for sample
  val evSum                      = Mux(recordWay === 0.U, evLatch(1), evLatch(0))
  val evAvera                    = evSum >> (params.setBits - evFracWidth)
  val evFix                      = evInt2Fix(evicts)
  val evErrNeg                   = evFix < evAvera
  val evErrAbs                   = Mux(evErrNeg, evAvera - evInt2Fix(evicts), evInt2Fix(evicts) - evAvera)
  val evSqSumLatch               = Reg(Vec(2, UInt(0, width = evSqIntWidth + params.setBits)))
  val evSqSum                    = Wire(UInt((evSqIntWidth + evSqFracWidth + params.setBits).W))
  val evSqAvera                  = evSqSum(evSqSum.getWidth - 1, params.setBits)
  val evStdDev                   = Reg(UInt((evIntWidth + evFracWidth).W)) //Root Mean Square
  val evStdDevReci               = Reg(UInt((evIntWidth + evFracWidth).W))
  val evOverFlow                 = Reg(Bool())


  io.remap.bits.atdetec := (max_emaz > Cat(io.config1.zthreshold, 0.U(emazFracWidth.W))) || evOverFlow

  io.remap.valid   := false.B
  when(count_access > io.config0.athreshold && io.config0.enath)      { io.remap.valid := true.B }
  when(count_evicts > io.config0.ethreshold && io.config0.eneth)      { io.remap.valid := true.B }
  when(evSum       ===    UIntMax(evSum)    && io.config1.enzth)      { io.remap.valid := true.B }
  when(io.remap.bits.atdetec                && io.config1.enzth)      { io.remap.valid := true.B }
  when(count_period > io.config1.period     && state =/= s_idle)      { io.remap.valid := true.B }  //not finish z in time
  when(io.access.valid) {
    when(count_access =/= UIntMax(count_access)                 ) { count_access    := count_access   + 1.U }
    when(count_period =/= UIntMax(count_period)                 ) { count_period    := count_period   + 1.U }
  }
  when(io.evict.valid) {
    when(count_evicts =/= UIntMax(count_evicts)                 ) { count_evicts    := count_evicts   + 1.U  }
    when(recordWay === 0.U && evLatch(0) =/= UIntMax(evLatch(0))) { evLatch(0)      := evLatch(0)     + 1.U  }
    when(recordWay === 1.U && evLatch(1) =/= UIntMax(evLatch(1))) { evLatch(1)      := evLatch(1)     + 1.U  }
  }
  when(io.remap.fire() || !wipeDone) {
    count_access     := 0.U
    count_evicts     := 0.U
    count_period     := 0.U
    max_emaz         := 0.U
    evLatch(0)       := 0.U
    evLatch(1)       := 0.U
    evSqSumLatch(0)  := 0.U
    evSqSumLatch(1)  := 0.U
    evOverFlow       := false.B
    state            := s_idle
  }
  when(io.remap.fire()) {
    wipeCount        := 0.U
  }

  class evwrite extends Bundle {
    val set    = UInt(width = params.setBits)
    val evicts = UInt(width = 2 * evIntWidth)
  }

  class emazwrite extends Bundle {
    val set    = UInt(width = params.setBits)
    val emaz   = UInt(width = emazIntWidth + emazFracWidth)
  }

  val calReadEvS1   = Reg(Valid(UInt(width = params.setBits)))
  val calReadEvS2   = Reg(Valid(UInt(width = params.setBits)))
  val evUpdQue      = Module(new Queue(UInt(width = params.setBits), 4, pipe = false, flow = false))
  //val emazReqQue     = Module(new Queue(new ExponentialMovingAverageZScoreReq(params.setBits, evIntWidth + evFracWidth, io.config1.discount.getWidth), 2, pipe = false, flow = false))
  val emazReqQue    = Module(new Queue(emaz.io.req.bits.cloneType, 2, pipe = false, flow = false))
  val evReadArb     = Module(new Arbiter(UInt(width = params.setBits), 2))
  val evWriteArb    = Module(new Arbiter(new evwrite(), 3))
  val emazWriteArb  = Module(new Arbiter(new emazwrite(), 2))

  //state
  when(reset) {
    calReadEvS1.valid := false.B
    calReadEvS2.valid := false.B
  }
  calReadEvS2.valid := evReadArb.io.in(1).fire() && state =/= s_idle
  calReadEvS2.bits  := Mux(evReadArb.io.in(1).fire() && state =/= s_idle, calReadEvS1.bits, calReadEvS2.bits)
  when( state === s_idle ) {
    when(count_period > io.config1.period && io.config1.enzth) {
      state                  := s_StdDevReq
      count_period           := 0.U
      calReadEvS1.valid      := true.B
      calReadEvS1.bits       := 0.U
      when(recordWay === 0.U) { recordWay := 1.U; evLatch(1) := 0.U; evSqSumLatch(1) := 0.U }
      when(recordWay === 1.U) { recordWay := 0.U; evLatch(0) := 0.U; evSqSumLatch(0) := 0.U }
    }
  }
  when( state === s_SqSum ) {
    when(evReadArb.io.in(1).fire()) {
      when(calReadEvS1.bits < laseSet.U) {
        calReadEvS1.valid    := true.B
        calReadEvS1.bits     := calReadEvS1.bits + 1.U
      }.otherwise { calReadEvS1.valid    := false.B }
    }
    when(!calReadEvS1.valid && !calReadEvS2.valid && calReadEvS1.bits === laseSet.U && !mulBusy) {
      state                  := s_StdDevReq
    }
  }
  when( state === s_StdDevReq                       )  { state   := s_StdDevResp }
  when( state === s_StdDevResp && sar.io.resp.valid )  { state   := s_ReciReq    }
  when( state === s_ReciReq                         )  { state   := s_ReciResp   }
  when( state === s_ReciResp && sar.io.resp.valid   )  {
    state                   := s_EMAZ
    calReadEvS1.valid       := true.B
    calReadEvS1.bits        := 0.U
  }
  when( state === s_EMAZ                            )  {
    when(evReadArb.io.in(1).fire()) {
      when(calReadEvS1.bits < laseSet.U) {
        calReadEvS1.valid    := true.B
        calReadEvS1.bits     := calReadEvS1.bits + 1.U
      }.otherwise { calReadEvS1.valid    := false.B }
    }
    when(emaz.io.emazwrite.valid && emaz.io.emazwrite.bits.set === laseSet.U) {
      state                  := s_idle
    }
  }
  when( io.remap.fire() || !io.config1.enzth        )  {
    state                   := s_idle
    calReadEvS1.valid       := false.B
    calReadEvS2.valid       := false.B
  }

  //sram_evicts: read
  evUpdQue.io.enq.valid         := io.evict.valid
  evUpdQue.io.enq.bits          := io.evict.bits.set
  evReadArb.io.in(0)            <> evUpdQue.io.deq
  evReadArb.io.in(1).valid      := calReadEvS1.valid && (state === s_SqSum || state === s_EMAZ)
  evReadArb.io.in(1).bits       := calReadEvS1.bits
  when(state === s_EMAZ) {
    when( calReadEvS2.valid        )   { evReadArb.io.in(1).valid := false.B }
    when( !emazReqQue.io.enq.ready )   { evReadArb.io.in(1).valid := false.B }
  }
  evReadArb.io.out.ready       := !evWriteArb.io.out.valid || !wipeDone
  val evresp = {
    val sram_resp = sram_evicts.read(evReadArb.io.out.bits, evReadArb.io.out.valid && !evWriteArb.io.out.valid)
    Seq(sram_resp(2*evIntWidth-1, evIntWidth), sram_resp(evIntWidth - 1, 0)).reverse
  }
  evicts := evresp(0)
  when(RegNext(evReadArb.io.in(0).fire) && recordWay === 1.U) { evicts := evresp(1) }
  when(RegNext(evReadArb.io.in(1).fire) && recordWay === 0.U) { evicts := evresp(1) }
  //update evSqSum
  when(RegNext(evReadArb.io.in(0).fire)) {
    when(evicts =/= UIntMax(evicts)) {
      when(recordWay === 0.U) { evSqSumLatch(0) := evSqSumLatch(0) + Cat(evicts, 1.U(1.W)) }
      when(recordWay === 1.U) { evSqSumLatch(1) := evSqSumLatch(1) + Cat(evicts, 1.U(1.W)) }
    }
  }
  evSqSum := Cat(Mux(recordWay === 0.U, evSqSumLatch(1), evSqSumLatch(0)), 0.U(evSqFracWidth.W))
  //sram_evicts: write
  val increaseEvicts = Mux(evicts === UIntMax(evicts), evicts, evicts + 1.U)
  evWriteArb.io.in(0).valid         := !wipeDone
  evWriteArb.io.in(0).bits.set      := wipeCount(params.setBits-1, 0)
  evWriteArb.io.in(0).bits.evicts   := 0.U
  evWriteArb.io.in(1).valid         := RegNext(evReadArb.io.in(0).fire())
  evWriteArb.io.in(1).bits.set      := RegNext(evReadArb.io.in(0).bits)
  evWriteArb.io.in(1).bits.evicts   := Mux(recordWay === 1.U, Cat(increaseEvicts,    evresp(0)),  Cat(evresp(1), increaseEvicts))
  evWriteArb.io.in(2).valid         := calReadEvS2.valid && state === s_EMAZ
  evWriteArb.io.in(2).bits.set      := calReadEvS2.bits
  evWriteArb.io.in(2).bits.evicts   := Mux(recordWay === 1.U, Cat(evresp(1), 0.U(evIntWidth.W)),  Cat(0.U(evIntWidth.W), evresp(0)))
  when(evWriteArb.io.out.valid) { sram_evicts.write(evWriteArb.io.out.bits.set, evWriteArb.io.out.bits.evicts) }
  evWriteArb.io.out.ready           := true.B
  when(evWriteArb.io.in(1).valid && (evicts === UIntMax(evicts))) {
    evOverFlow := true.B
  }

  //sram_emaz: read
  emaz.io.emazread.ready            := wipeDone && !emazWriteArb.io.out.valid
  emaz.io.emazresp.bits             := sram_emaz.read(emaz.io.emazread.bits, emaz.io.emazread.valid && wipeDone && !emazWriteArb.io.out.valid)
  emaz.io.emazresp.valid            := RegNext(emaz.io.emazread.valid && wipeDone && !emazWriteArb.io.out.valid)
  //sram_emaz: write
  emazWriteArb.io.in(0).valid       := !wipeDone
  emazWriteArb.io.in(0).bits.set    := wipeCount(params.setBits-1, 0)
  emazWriteArb.io.in(0).bits.emaz   := 0.U
  emazWriteArb.io.in(1).valid       := emaz.io.emazwrite.valid
  emazWriteArb.io.in(1).bits.set    := emaz.io.emazwrite.bits.set
  emazWriteArb.io.in(1).bits.emaz   := emaz.io.emazwrite.bits.emaz
  when(emazWriteArb.io.out.valid) { sram_emaz.write(emazWriteArb.io.out.bits.set, emazWriteArb.io.out.bits.emaz) }
  emazWriteArb.io.out.ready         := true.B

  //mul: req
  mulBusyCnt := Mux(mul.io.req.valid, mulLatency.U, Mux(mulBusyCnt === 0.U, 0.U, mulBusyCnt - 1.U))
  val mulReqArb = Module(new Arbiter(new MultiplierReq(mulWidth, MultiplierTag.SZ), 3))
  mulReqArb.io.in(0).valid     := false.B
  mulReqArb.io.in(0).bits.in1  := evInt2Fix(evicts)
  mulReqArb.io.in(0).bits.in2  := evInt2Fix(evicts)
  mulReqArb.io.in(0).bits.tag  := MultiplierTag.ERRSQ
  mulReqArb.io.in(1)           <> sar.io.mulreq
  mulReqArb.io.in(2)           <> emaz.io.mulreq
  mul.io.req.valid             := mulReqArb.io.out.valid
  mul.io.req.bits              := mulReqArb.io.out.bits
  mul.io.req.bits.fn           := UInt(0)    // FN_MUL = FN_ADD = UInt(0)
  mul.io.req.bits.dw           := Bool(true) // DW_64  = Bool(true)
  mulReqArb.io.out.ready       := Bool(true)
  //mul: resp
  sar.io.mulresp               := mul.io.resp
  emaz.io.mulresp              := mul.io.resp

  //sar: req
  sar.io.req.valid             := state === s_StdDevReq || state === s_ReciReq
  sar.io.req.bits.fn           := Mux(state === s_StdDevReq, SuccessiveApproximateRegisterFn.SQRT, SuccessiveApproximateRegisterFn.DIV)
  sar.io.req.bits.in           := evStdDev //when s_StdDevReq in is useless
  sar.io.req.bits.target       := Mux(state === s_StdDevReq, evSqAvera,  1.U << evSqFracWidth)
  //sar: resp
  when(sar.io.resp.valid) {
    when( state === s_StdDevResp ) { evStdDev     := sar.io.resp.bits.result }
    when( state === s_ReciResp   ) { evStdDevReci := sar.io.resp.bits.result }
    assert( state === s_StdDevResp || state === s_ReciResp )
    /*when(state === s_StdDevResp) {
      printf("s_StdDevResp: In Null Target: %d.%d_D Result: %d.%d_D\n",
             evErrSqAvera(evErrSqAvera.getWidth - 1, evSqFracWidth),                     frac2Int10(evErrSqAvera(evSqFracWidth - 1, 0)),
             sar.io.resp.bits.result(sar.io.resp.bits.result.getWidth - 1, evFracWidth), frac2Int10(sar.io.resp.bits.result(evFracWidth - 1, 0)),
             )
    }
    when(state === s_ReciResp) {
      printf("s_ReciResp: In %d.%d_D Target: 1.0_D Result: %d.%d_D\n",
             evStdDev(evStdDev.getWidth - 1, evFracWidth),                               frac2Int10(evStdDev(evFracWidth - 1, 0)),
             sar.io.resp.bits.result(sar.io.resp.bits.result.getWidth - 1, evFracWidth), frac2Int10(sar.io.resp.bits.result(evFracWidth - 1, 0)),
            )
    }*/
  }

  //emaz: req
  emazReqQue.io.enq.valid              := state === s_EMAZ && calReadEvS2.valid
  emazReqQue.io.enq.bits.set           := calReadEvS2.bits
  emazReqQue.io.enq.bits.ev            := evFix
  emazReqQue.io.enq.bits.evErrAbs      := evErrAbs
  emazReqQue.io.enq.bits.evErrNeg      := evErrNeg
  emazReqQue.io.enq.bits.evStdDevReci  := evStdDevReci
  emaz.io.req                          <> emazReqQue.io.deq
  emaz.io.req.bits.discount            := io.config1.discount
  //emaz: resp
  //write sram_emaz
  when(emaz.io.emazwrite.valid) {
    max_emaz := Mux(emaz.io.emazwrite.bits.emaz > max_emaz, emaz.io.emazwrite.bits.emaz, max_emaz)
  }

  //abort calculate
  val queReset         = reset | RegNext(io.remap.fire() || !io.config1.enzth)
  val calculatorReset  = reset | RegNext(io.remap.fire() || !io.config1.enzth || (state === s_idle && RegNext(state =/= s_idle)))
  evUpdQue.reset       := queReset
  emazReqQue.reset     := queReset
  mul.reset            := calculatorReset
  sar.reset            := calculatorReset
  emaz.reset           := calculatorReset

  //pfc
  io.pfcupdate.atcheck := state === s_idle && RegNext(state =/= s_idle)
  io.pfcupdate.atdetec := io.remap.fire() && io.remap.bits.atdetec

  //debug
  io.debug.fifoready          := evUpdQue.io.enq.ready
  io.debug.wipedone           := wipeDone
  io.debug.calculate          := state =/= s_idle || count_period > io.config1.period
  io.debug.calsqsum           := state === s_SqSum
  io.debug.calemaz            := state === s_EMAZ
  when(io.debug.trigger) {
    count_period := io.config1.period + 1.U
  }
  //error analy
  io.debug.erranl.valid                  := emaz.io.erranl.valid
  io.debug.erranl.bits                   := emaz.io.erranl.bits
  io.debug.erranl.bits.evSum             := evSum
  io.debug.erranl.bits.evAvera           := evAvera
  io.debug.erranl.bits.evSqSum           := evSqSum
  io.debug.erranl.bits.evSqAvera         := evSqAvera
  io.debug.erranl.bits.evStdDev          := evStdDev
  io.debug.erranl.bits.evStdDevReci      := evStdDevReci
  io.debug.erranl.bits.detected          := io.remap.valid
  require(io.debug.erranl.bits.evSum.getWidth      == evSum.getWidth     )
  require(io.debug.erranl.bits.evSqSum.getWidth    == evSqSum.getWidth   )
  require(io.debug.erranl.bits.evSqAvera.getWidth  == evSqAvera.getWidth )


  //log
  /*
  when((io.remap.fire() && io.remap.bits.atdetec) || RegNext(emaz.io.emazwrite.valid && emaz.io.emazwrite.bits.set === laseSet.U)) {
    val maxLatch = emaz.io.max
    printf("max_emazSet: %x_H emaz %d_D ev: %d_D oldDisced %d_D evSum: %d_D evSqSum: %d_D evStdDev: %d_D 1/evStdDev: %d_D\n",
            maxLatch.set,
            maxLatch.result,
            maxLatch.ev(maxLatch.ev.getWidth - 1, evFracWidth),
            maxLatch.emazAddend(1),
            evSum,
            evSqSum,
            evStdDev,
            evStdDevReci
          )
    assert(max_emaz <= Cat(io.config1.zthreshold, 0.U(emazFracWidth.W)))
  }*/
}


class AttackDetectorTrace(params: InclusiveCacheParameters) extends Module {

  val attackdetector  = Module(new AttackDetector(params))
  val evIntWidth                     = attackdetector.evIntWidth
  val evFracWidth                    = attackdetector.evFracWidth
  val evSqIntWidth                   = attackdetector.evSqIntWidth
  val evSqFracWidth                  = attackdetector.evSqFracWidth
  val emazIntWidth                   = attackdetector.emazIntWidth         //exponential moving average
  val emazFracWidth                  = attackdetector.emazFracWidth        //exponential moving average
  require(evFracWidth   > 2)
  require(emazFracWidth > 1)
  require(evFracWidth >= emazFracWidth)

  val io = new Bundle {
    val tracein      = Flipped(Decoupled(new ErrorAnalysis(params.setBits, evIntWidth, evFracWidth, evSqIntWidth, evSqFracWidth, emazIntWidth, emazFracWidth)))
    val traceout     = Decoupled(new ErrorAnalysis(params.setBits, evIntWidth, evFracWidth, evSqIntWidth, evSqFracWidth, emazIntWidth, emazFracWidth))
    val remapfire    = Bool().asOutput
    val mix          = UInt(width = 128)
  }

  //val evictsl       = Reg(Vec(params.cache.sets, UInt(0, width = io.tracein.bits.ev.getWidth)))
  //val deltal        = Reg(Vec(params.cache.sets, UInt(0, width = io.tracein.bits.delta.getWidth)))
  //val evictsr       = Reg(Vec(params.cache.sets, UInt(0, width = io.tracein.ev.getWidth)))
  //val erranll       = Reg(new ErrorAnalysis(params.setBits, evIntWidth, evFracWidth, evSqIntWidth, evSqFracWidth, emazIntWidth, emazFracWidth))
  //val erranlr       = Reg(new ErrorAnalysis(params.setBits, evIntWidth, evFracWidth, evSqIntWidth, evSqFracWidth, emazIntWidth, emazFracWidth))
  val tracein       = Reg(new ErrorAnalysis(params.setBits, evIntWidth, evFracWidth, evSqIntWidth, evSqFracWidth, emazIntWidth, emazFracWidth))
  val tracecheckR   = Reg(Vec(2, new ErrorAnalysis(params.setBits, evIntWidth, evFracWidth, evSqIntWidth, evSqFracWidth, emazIntWidth, emazFracWidth)))
  val tracecheck    = Wire(new ErrorAnalysis(params.setBits, evIntWidth, evFracWidth, evSqIntWidth, evSqFracWidth, emazIntWidth, emazFracWidth))

  val trigger     = Reg(UInt(0, width = 8))
  when( trigger =/= 0.U ) { trigger := trigger - 1.U }

  val insel        = Reg(UInt(0, width = 1))
  val checksel     = Reg(UInt(0, width = 1))
  tracecheck      := tracecheckR(checksel)

  attackdetector.io.config0.enath              := false.B
  attackdetector.io.config0.athreshold         := 163840.U
  attackdetector.io.config0.eneth              := false.B
  attackdetector.io.config0.ethreshold         := 163840.U
  attackdetector.io.config1.discount           := 5.U
  attackdetector.io.config1.period             := 4096.U
  attackdetector.io.config1.zthreshold         := 5.U
  attackdetector.io.config1.enzth              := true.B
  io.tracein.ready                             := tracein.ev === 0.U
  when( attackdetector.io.debug.calculate ) {
    io.tracein.ready := false.B
    when(tracein.ev === 0.U && !io.tracein.bits.detected) {
      when( attackdetector.io.debug.calsqsum ) {
        io.tracein.ready := io.tracein.bits.set < (params.cache.sets >> 1).U
      }
      when( attackdetector.io.debug.calemaz )  {
        io.tracein.ready := io.tracein.bits.set < (params.cache.sets - 1).U
      }
    }
  }
  when( trigger    =/= 0.U                        )                { io.tracein.ready  := false.B }
  when( RegNext(attackdetector.io.debug.trigger)  )                { io.tracein.ready  := false.B }
  when( !attackdetector.io.debug.wipedone         )                { io.tracein.ready  := false.B }

  io.traceout.valid                            := attackdetector.io.debug.erranl.valid
  io.traceout.bits                             := attackdetector.io.debug.erranl.bits
  io.traceout.bits.evSqSum                     := attackdetector.io.debug.erranl.bits.evSqSum >> evSqFracWidth
  io.remapfire                                 := attackdetector.io.remap.fire()

  attackdetector.io.remap.ready     := !attackdetector.io.debug.calculate
  attackdetector.io.evict.valid     := tracein.ev =/= 0.U && attackdetector.io.debug.fifoready
  attackdetector.io.evict.bits.set  := tracein.set
  attackdetector.io.debug.trigger   := trigger === 1.U

  when(io.tracein.fire()) {
    tracein    := io.tracein.bits
    //evictsl(io.tracein.bits.set)  := io.tracein.bits.ev
    //deltal(io.tracein.bits.set)   := io.tracein.bits.delta
    when(io.tracein.bits.set === 0.U)    {
      tracecheckR(insel)  := io.tracein.bits
    }
    when(io.tracein.bits.ev  === 0.U && io.tracein.bits.set === (params.cache.sets - 1).U )     {
      trigger  := 16.U
    }
    when( io.tracein.bits.set === (params.cache.sets - 1).U ) {
      insel    := insel + 1.U
    }
  }
  when( attackdetector.io.evict.valid )  {
    tracein.ev := tracein.ev - 1.U
    when( tracein.ev === 1.U && attackdetector.io.evict.bits.set === (params.cache.sets - 1).U ) {
      trigger := 16.U
    }
  }
  when( attackdetector.io.debug.erranl.valid ) {
    when(attackdetector.io.debug.erranl.bits.set === (params.cache.sets - 1).U ) {
      checksel := checksel + 1.U
    }
  }
  when( reset ) {
    insel      := 0.U
    checksel   := 0.U
    trigger    := 0.U
    tracein.ev := 0.U
  }

  //check
  val evStdDevErr      = Wire(init = 0.U((2 * attackdetector.io.debug.erranl.bits.evStdDev.getWidth).W))
  evStdDevErr         := Mux(attackdetector.io.debug.erranl.bits.evStdDev > tracecheck.evStdDev,
                             attackdetector.io.debug.erranl.bits.evStdDev - tracecheck.evStdDev,
                             tracecheck.evStdDev - attackdetector.io.debug.erranl.bits.evStdDev)
  val evStdDevReciErr  = Wire(init = 0.U((2 * attackdetector.io.debug.erranl.bits.evStdDev.getWidth).W))
  evStdDevReciErr     := Mux(attackdetector.io.debug.erranl.bits.evStdDevReci > tracecheck.evStdDevReci,
                             attackdetector.io.debug.erranl.bits.evStdDevReci - tracecheck.evStdDevReci,
                             tracecheck.evStdDevReci - attackdetector.io.debug.erranl.bits.evStdDevReci)
  /*val deltaErr         = Wire(init = 0.U((2 * attackdetector.io.debug.erranl.bits.delta.getWidth).W))
  deltaErr            := Mux(attackdetector.io.debug.erranl.bits.delta > deltal(attackdetector.io.debug.erranl.bits.set),
                             attackdetector.io.debug.erranl.bits.delta - deltal(attackdetector.io.debug.erranl.bits.set),
                             deltal(attackdetector.io.debug.erranl.bits.set) - attackdetector.io.debug.erranl.bits.delta)*/
  val evStdDevSq       = Wire(init = 0.U((2 * attackdetector.io.debug.erranl.bits.evStdDev.getWidth).W))
  val evStdDevSqErr    = Wire(init = 0.U((2 * attackdetector.io.debug.erranl.bits.evStdDev.getWidth).W))
  evStdDevSq          := attackdetector.io.debug.erranl.bits.evStdDev * attackdetector.io.debug.erranl.bits.evStdDev
  evStdDevSqErr       := Mux(evStdDevSq > attackdetector.io.debug.erranl.bits.evSqAvera,
                             evStdDevSq - attackdetector.io.debug.erranl.bits.evSqAvera,
                             attackdetector.io.debug.erranl.bits.evSqAvera - evStdDevSq)
  val evSDMulReci      = Wire(init = 0.U((2 * attackdetector.io.debug.erranl.bits.evStdDev.getWidth).W))
  val evSDMulReciErr   = Wire(init = 0.U((2 * attackdetector.io.debug.erranl.bits.evStdDev.getWidth).W))
  evSDMulReci         := attackdetector.io.debug.erranl.bits.evStdDev * attackdetector.io.debug.erranl.bits.evStdDevReci
  evSDMulReciErr      := Mux(evSDMulReci > (1 << evSqFracWidth).U,
                             evSDMulReci - (1 << evSqFracWidth).U,
                             (1 << evSqFracWidth).U - evSDMulReci)
  when(attackdetector.io.debug.erranl.valid) {
    //assert( evictsl(attackdetector.io.debug.erranl.bits.set)    === (attackdetector.io.debug.erranl.bits.ev >> evFracWidth)           )
    assert( tracecheck.evSum  ===  attackdetector.io.debug.erranl.bits.evSum,
            "%d != %d ",
            tracecheck.evSum,
            attackdetector.io.debug.erranl.bits.evSum)
    assert( tracecheck.evSqSum  === (attackdetector.io.debug.erranl.bits.evSqSum >> evSqFracWidth),
            "%d != %d ",
            tracecheck.evSqSum,
            (attackdetector.io.debug.erranl.bits.evSqSum >> evSqFracWidth))
    assert( attackdetector.io.debug.erranl.bits.evSqAvera === (attackdetector.io.debug.erranl.bits.evSqSum >> params.setBits),
            "%d != %d ",
            attackdetector.io.debug.erranl.bits.evSqAvera,
            (attackdetector.io.debug.erranl.bits.evSqSum >> params.setBits))
     /*assert( 100.U*evStdDevErr     < tracecheck.evStdDev,
            "%d != %d error %d",
            attackdetector.io.debug.erranl.bits.evStdDev,
            tracecheck.evStdDev,
            evStdDevErr)
    assert( 100.U*evStdDevReciErr  < tracecheck.evStdDevReci,
            "%d != %d error %d",
            attackdetector.io.debug.erranl.bits.evStdDevReci,
            tracecheck.evStdDevReci,
            evStdDevReciErr)
    assert( 100.U*deltaErr        < deltal(attackdetector.io.debug.erranl.bits.set) || deltaErr === 0.U,
            "%d != %d error %d at set %d",
            attackdetector.io.debug.erranl.bits.delta,
            deltal(attackdetector.io.debug.erranl.bits.set),
            deltaErr,
            attackdetector.io.debug.erranl.bits.set)*/
    //check sar
    /*assert( 100.U*evStdDevSqErr    < attackdetector.io.debug.erranl.bits.evSqAvera,
            "(%d)^2 = %d != %d error %d",
            attackdetector.io.debug.erranl.bits.evStdDev,
            evStdDevSq,
            attackdetector.io.debug.erranl.bits.evSqAvera,
            evStdDevSqErr)
    assert( 100.U*evSDMulReciErr  < (1 << evSqFracWidth).U,
            "%d*%d = %d != %d error %d",
            attackdetector.io.debug.erranl.bits.evStdDev,
            attackdetector.io.debug.erranl.bits.evStdDevReci,
            evSDMulReci,
            (1 << evSqFracWidth).U,
            evSDMulReciErr)*/
  }

  //in case of optimize
  val counter = Reg(UInt(0, width = 128))
  when(attackdetector.io.debug.calculate) { counter := counter + 1.U }
  when(attackdetector.io.debug.trigger)   { counter := 0.U           }
  io.mix := counter ^ io.tracein.bits.toBits
}