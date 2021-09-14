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

class FSourceX(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val front  = Decoupled(new SourceXRequest(params)).flip
    val back   = Decoupled(new SourceXRequest(params))        //to Source X
    //for use by rempaer
    val rx       = Decoupled(new SourceXRequest(params))  //Remaper SourceXResp
  }

  io.back.valid   := io.front.valid & io.front.bits.source === 1.U
  io.rx.valid     := io.front.valid & io.front.bits.source === 0.U
  io.back.bits    := io.front.bits
  io.rx.bits      := io.front.bits
  io.front.ready  := Mux(io.front.bits.source === 0.U, io.rx.ready, io.back.ready) | io.front.bits.source === 2.U

  params.ccover(io.front.valid && !io.front.ready, "SOURCEX_STALL", "Backpressure when sending a control message")
}

class FSinkX(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val front    = Decoupled(new SinkXRequest(params)).flip
    val back     = Decoupled(new SinkXRequest(params))      //BackendX
    val diradr   = Decoupled(new DirectoryRead(params))
    //for use by rempaer
    val rx       = Decoupled(new DirectoryRead(params)).flip //remaper x
    val rstatus  = new RemaperStatusIO(params.setBits).asInput  //Remaper Status
    val rtab     = new SourceSinkRandomTableIO(params.blkadrBits, params.setBits)
    val idle     = Bool() //only when idle we can remap safely
  }

  io.diradr.valid  := io.back.valid
  val (tag, set, offset) = params.parseAddress(io.front.bits.address)
  val blkadr  = Cat(tag, set)
  val s_idle  = RegInit(true.B)

  val blkadrR = Reg(UInt(width = params.blkadrBits))
  val lhset   = Module(new Queue(UInt(width = params.setBits), 1))
  val rhset   = Module(new Queue(UInt(width = params.setBits), 1))

  //io.front          --->  rtab
  io.rtab.req.valid         := s_idle & !io.rstatus.blockSinkX & io.front.valid
  io.rtab.req.bits.blkadr   := blkadr
  io.front.ready            := s_idle & !io.rstatus.blockSinkX & io.rtab.req.ready
  when(io.front.fire())     { blkadrR := blkadr }

  params.ccover(io.front.valid && !s_idle, "SINKX_STALL", "Backpressure when accepting a control message")

  //rtab      --->  hset
  lhset.io.enq.valid        := io.rtab.resp.valid && (!io.rstatus.oneloc || io.rstatus.cloc === RTAL.LEFT)
  rhset.io.enq.valid        := io.rtab.resp.valid && (!io.rstatus.oneloc || io.rstatus.cloc === RTAL.RIGH)
  lhset.io.enq.bits         := io.rtab.resp.bits.lhset
  rhset.io.enq.bits         := io.rtab.resp.bits.rhset

  //to BackendSinkX
  io.back.valid         := io.rx.valid || lhset.io.deq.valid || rhset.io.deq.valid
  io.back.bits.source   := Mux(io.rx.valid, 0.U           , Mux(lhset.io.deq.valid && rhset.io.deq.valid, 2.U,               1.U              ))
  io.diradr.bits.set    := Mux(io.rx.valid, io.rx.bits.set, Mux(lhset.io.deq.valid,                       lhset.io.deq.bits, rhset.io.deq.bits))
  io.diradr.bits.tag    := Mux(io.rx.valid, io.rx.bits.tag,                                                                            blkadrR )
  lhset.io.deq.ready    := !io.rx.valid && io.back.ready
  rhset.io.deq.ready    := !io.rx.valid && io.back.ready && !lhset.io.deq.valid
  io.rx.ready           := io.back.ready

  //when frontendX or backendX is busy we can not remap safely
  io.idle  := s_idle
  when(io.front.fire())                                                 {  s_idle := false.B  }
  when(io.diradr.fire() & (lhset.io.deq.valid ^ rhset.io.deq.valid))    {  s_idle := true.B   }

}

//FrontEnd of SinkA and SinkC
class FSink[T <: TLAddrChannel](val gen: T, params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val front      = Decoupled(gen).flip
    val back       = Decoupled(gen)                               //Backend
    val diradr     = Decoupled(new DirectoryRead(params))         //must synchronize with bc!!
    //for use by rempaer
    val rstatus    = new RemaperStatusIO(params.setBits).asInput  //Remaper Status
    val rtab       = new SourceSinkRandomTableIO(params.blkadrBits, params.setBits)
    val idle       = Bool() //can remap safely
    val b_read     = Valid((new DirectoryRead(params)))           //check SourceB
    val b_result   = Valid(new SourceBRequest(params)).flip
    val dir_read   = Decoupled(new DirectoryRead(params))
    val dir_result = Valid(new DirectoryResult(params)).asInput
  }

  io.diradr.valid := io.back.valid
  val front          =  Module(new Queue(io.front.bits.cloneType,     2))
  val diradr         =  Module(new Queue(io.diradr.bits.cloneType,    2))
  val diradr_arb     =  Module(new Arbiter(io.diradr.bits.cloneType,  5))

  val block          = Wire(Bool())    //block front req
  val blkadr         = Wire(UInt())
  //(w_ = waiting)
  val w_reqrtab      = RegInit(false.B)
  val w_reqdir       = RegInit(false.B)
  val w_truehset     = RegInit(false.B)

  val cam            = Reg(Valid(new DirectoryRead(params)))
  val dir_tag        = Reg(UInt(width = params.blkadrBits))
  val lhset          = Reg(UInt(width = params.setBits))
  val rhset          = Reg(UInt(width = params.setBits))
  val swapped        = Wire(init = (!io.rstatus.oneloc && ((io.rstatus.cloc === RTAL.LEFT && io.rtab.resp.bits.lhset < io.rstatus.head) || (io.rstatus.cloc === RTAL.RIGH && io.rtab.resp.bits.rhset < io.rstatus.head))))
  val camMatch       = Wire(Bool())

  io.front.bits match {
    case a: TLBundleA => {
      val (tag, set, offset) = params.parseAddress(a.address)
      camMatch               := false.B
      block                  := io.rstatus.blockSinkA
      blkadr                 := Cat(tag, set)
    }
    case c: TLBundleC => {
      val (tag, set, offset) = params.parseAddress(c.address)
      //camMatch               := false.B
      camMatch               := cam.valid & cam.bits.tag === blkadr
      block                  := io.rstatus.blockSinkC      
      blkadr                 := Cat(tag, set)
    }
    case _  => require(false)
  }

  diradr_arb.io.in(0).valid    := io.rtab.resp.valid && io.rstatus.oneloc && (diradr.io.deq.valid || !io.back.ready)
  diradr_arb.io.in(0).bits.set := Mux(io.rstatus.cloc === RTAL.LEFT, io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)
  diradr_arb.io.in(1).valid    := io.front.valid && camMatch
  diradr_arb.io.in(1).bits.set := cam.bits.set
  diradr_arb.io.in(2).valid    := io.rtab.resp.valid && swapped
  diradr_arb.io.in(2).bits.set := Mux(io.rstatus.nloc === RTAL.LEFT, io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)
  diradr_arb.io.in(3).valid    := io.b_result.valid
  diradr_arb.io.in(3).bits.set := io.b_result.bits.set
  diradr_arb.io.in(4).valid    := io.dir_result.valid
  diradr_arb.io.in(4).bits.set := Mux(io.dir_result.bits.hit && !io.dir_result.bits.swz, RegNext(io.dir_read.bits.set), Mux(io.rstatus.nloc === RTAL.LEFT, lhset, rhset))

  //io.front      --->  front
  front.io.enq.valid           :=  io.front.valid     && ((io.rstatus.oneloc && !block && io.rtab.req.ready) || diradr_arb.io.in(1).valid || diradr_arb.io.in(2).valid || diradr_arb.io.in(3).valid || diradr_arb.io.in(4).valid)
  front.io.enq.bits            :=  io.front.bits
  io.front.ready               :=  front.io.enq.ready && ((io.rstatus.oneloc && !block && io.rtab.req.ready) || diradr_arb.io.in(1).valid || diradr_arb.io.in(2).valid || diradr_arb.io.in(3).valid || diradr_arb.io.in(4).valid)
  //diradr_arb    --->  diradr
  diradr.io.enq.bits           :=  diradr_arb.io.out.bits
  diradr.io.enq.valid          := (diradr_arb.io.in(1).valid && front.io.enq.ready) || diradr_arb.io.in(0).valid || diradr_arb.io.in(2).valid || diradr_arb.io.in(3).valid || diradr_arb.io.in(4).valid

  //to Backend
  io.back <> front.io.deq
  io.diradr.bits.set           := Mux(diradr.io.deq.valid, diradr.io.deq.bits.set, Mux(io.rstatus.cloc === RTAL.LEFT, io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset))
  diradr.io.deq.ready          := front.io.deq.ready

  //w_reqrtab
  io.rtab.req.valid            := !camMatch & !block & Mux(io.rstatus.oneloc, io.front.valid & front.io.enq.ready, w_reqrtab)
  io.rtab.req.bits.blkadr      := blkadr
  //check SourceB: the same cycle with req rtab
  io.b_read.valid              := io.rtab.req.fire() & !io.rstatus.oneloc
  io.b_read.bits.tag           := io.rtab.req.bits.blkadr 
  //w_resptab
  when(io.rtab.resp.valid) {
    lhset                      := io.rtab.resp.bits.lhset
    rhset                      := io.rtab.resp.bits.rhset
  }

  //w_reqdir
  io.dir_read.valid            := w_reqdir
  io.dir_read.bits.tag         := dir_tag
  io.dir_read.bits.set         := RegEnable(Mux(io.rstatus.cloc === RTAL.LEFT, io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset), io.rtab.resp.valid)

  //cam
  when(reset | (block ^ RegNext(block))) { cam.valid := false.B }
 /*when(io.rstatus.oneloc && io.rtab.resp.valid) {
    cam.valid                := true.B 
    cam.bits.tag             := RegNext(io.rtab.req.bits.blkadr)
    cam.bits.set             := Mux(io.rstatus.cloc === RTAL.LEFT, io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)
  }*/
  Seq(diradr_arb.io.in(2), diradr_arb.io.in(3), diradr_arb.io.in(4)).map( arb => {
    when(arb.valid) {
      cam.valid                := true.B 
      cam.bits.tag             := blkadr
      cam.bits.set             := arb.bits.set
    }
  })

  //state machine
  when(io.front.valid && front.io.enq.ready && !block && !camMatch && !w_truehset && !io.rstatus.oneloc) {
    dir_tag                   := blkadr
    w_reqrtab                 := true.B
    w_truehset                := true.B
  }
  when( io.rtab.req.fire()                                                         )  { w_reqrtab   := false.B  }
  when( io.rtab.resp.valid && !io.rstatus.oneloc && !io.b_result.valid && !swapped )  { w_reqdir    := true.B   } //if this set match sourceB or has been swapped we do not need read dir
  when( io.dir_read.fire()                                                         )  { w_reqdir    := false.B  }
  when( io.b_result.valid                                                          )  { w_truehset  := false.B  } //get true hset ahead reqdir
  when( io.rtab.resp.valid && swapped                                              )  { w_truehset  := false.B  } //get true hset ahead reqdir
  when( io.dir_result.valid                                                        )  { w_truehset  := false.B  }

  io.idle  := !front.io.deq.valid & !w_truehset

  //ASSERT  
  when(io.back.valid) {
    assert(diradr.io.deq.valid || (io.rstatus.oneloc && io.rtab.resp.valid))
  }
}