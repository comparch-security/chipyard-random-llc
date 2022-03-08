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

object XOPCODE //remaper cmd
{
  // locations
  val SZ = 1
  def FLUSH  = UInt(0,SZ)
  def SWAP   = UInt(1,SZ)
}

class SinkXRequest(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val address = UInt(width = params.inner.bundle.addressBits)
  val source  = UInt(width = 1) //0: from core control; 1: from  remaper
  val opcode  = UInt(width = XOPCODE.SZ)
  //req from core need read randomtable
  //req from remaper don't need read randomtable
  val tag     = UInt(width = if(params.remap.en) params.blkadrBits else params.tagBits)
  val set     = UInt(width = params.setBits)
  val loc     = UInt(width = RTAL.SZ       )
}

class SinkX(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val req = Decoupled(new FullRequest(params))
    val x = Decoupled(new SinkXRequest(params)).flip
    val fldone  = Bool().asInput                               //only one fluh req inflight
    val rx      = Decoupled(new SinkXRequest(params)).flip    //remaper x
    val hset    = Vec(2, Decoupled(new Hset(params.setBits)).flip)
    val rereq   = Decoupled(new FullRequest(params)).flip
    val sset    = UInt(width = params.setBits) //swap_set
    val blocksr = Bool().asInput  //block swap req
    val busy    = Bool().asOutput
  }

  val x = Queue(io.x, 1)
  val hset = Seq(Queue(io.hset(0), 1), Queue(io.hset(1), 1))
  hset(0).ready := x.ready
  hset(1).ready := x.ready
  val (tag, set, offset) = params.parseAddress(x.bits.address)

  x.ready      := io.req.ready
  io.req.valid := x.valid

  params.ccover(x.valid      && !x.ready,      "SINKX_STALL", "Backpressure when accepting a control message")

  io.req.bits.prio   := Vec(UInt(1, width=3).asBools) // same prio as A
  io.req.bits.control:= Bool(true)
  io.req.bits.opcode := UInt(0)
  io.req.bits.param  := UInt(0)
  io.req.bits.size   := UInt(params.offsetBits)
  // The source does not matter, because a flush command never allocates a way.
  // However, it must be a legal source, otherwise assertions might spuriously fire.
  io.req.bits.source := UInt(params.inner.client.clients.map(_.sourceId.start).min)
  io.req.bits.offset := UInt(0)
  io.req.bits.set    := set
  io.req.bits.tag    := tag

  if(params.remap.en) {
    val rereq_entries = 1
    val fldone = RegInit(true.B)
    val reqarb = Module(new Arbiter(io.req.bits.cloneType, 2))
    val flush_arb = Module(new Arbiter(io.req.bits.cloneType, 3))
    val flush_req = Module(new Queue(io.req.bits.cloneType, 1, pipe = false, flow = false ))
    val swap_req  = Module(new Queue(io.req.bits.cloneType, 1, pipe = false, flow = false ))

    //rereq_flush     -> flush_arb
    flush_arb.io.in(0)                     <> Queue(io.rereq, 1, pipe = false, flow = false)
    //firereq_flush   -> flush_arb
    flush_arb.io.in(1).valid               := x.valid && fldone
    flush_arb.io.in(1).bits.opcode         := XOPCODE.FLUSH
    flush_arb.io.in(1).bits.source         := UInt(0)
    flush_arb.io.in(1).bits.offset         := offset
    flush_arb.io.in(1).bits.newset.valid   := hset(1).valid
    flush_arb.io.in(1).bits.newset.bits    := hset(1).bits.set
    flush_arb.io.in(1).bits.loc(1)         := hset(1).bits.loc
    flush_arb.io.in(1).bits.set            := hset(0).bits.set
    flush_arb.io.in(1).bits.loc(0)         := hset(0).bits.loc
    flush_arb.io.in(1).bits.tag            := tag
    x.ready                                := flush_arb.io.in(1).ready && fldone
    when( x.fire()  )                     { fldone := false.B }
    when( io.fldone )                     { fldone := true.B  }
    //remaper_flush   -> flush_arb
    flush_arb.io.in(2).valid               := io.rx.valid && io.rx.bits.opcode === XOPCODE.FLUSH
    flush_arb.io.in(2).bits.opcode         := XOPCODE.FLUSH
    flush_arb.io.in(2).bits.source         := UInt(1)
    flush_arb.io.in(2).bits.newset.valid   := false.B
    flush_arb.io.in(2).bits.set            := io.rx.bits.set
    flush_arb.io.in(2).bits.loc(0)         := io.rx.bits.loc
    flush_arb.io.in(2).bits.tag            := io.rx.bits.tag
    //flush_arb      -> flush_req
    flush_req.io.enq                       <> flush_arb.io.out
    
    //remaper_swap   -> swap_req
    swap_req.io.enq.valid                 := io.rx.valid && io.rx.bits.opcode === XOPCODE.SWAP
    swap_req.io.enq.bits.opcode           := XOPCODE.SWAP
    swap_req.io.enq.bits.source           := UInt(1)
    swap_req.io.enq.bits.newset.valid     := false.B
    swap_req.io.enq.bits.set              := io.rx.bits.set
    swap_req.io.enq.bits.loc(0)           := io.rx.bits.loc
    swap_req.io.enq.bits.tag              := io.rx.bits.tag
    io.sset                               := swap_req.io.deq.bits.set

    //flush_req       -> reqarb
    reqarb.io.in(0)                       <> flush_req.io.deq
    //swap_req        -> reqarb
    reqarb.io.in(1)                       <> swap_req.io.deq
    reqarb.io.in(1).valid                 := swap_req.io.deq.valid  && !io.blocksr
    swap_req.io.deq.ready                 := reqarb.io.in(1).ready  && !io.blocksr

    io.rx.ready                           := Mux(io.rx.bits.opcode === XOPCODE.FLUSH, flush_arb.io.in(2).ready, swap_req.io.enq.ready)

    //reqarb         -> io.req
    io.req                                <> reqarb.io.out
    io.req.bits.prio                      := Vec(UInt(1, width=3).asBools)  // same prio as A
    io.req.bits.control                   := Bool(true)
    io.req.bits.param                     := UInt(0)
    io.req.bits.size                      := UInt(params.offsetBits)
    io.req.bits.offset                    := UInt(0)

    io.busy  := x.valid || !fldone || flush_arb.io.in(0).valid

    when(io.rereq.valid) { assert(io.rereq.ready) }
  }
}
