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

class SinkXRequest(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val address = UInt(width = params.inner.bundle.addressBits)
  val source  = UInt(width = 1) //0: from core control; 1: from  remaper  
}

class SinkX(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val req = Decoupled(new FullRequest(params))
    val x = Decoupled(new SinkXRequest(params)).flip
    val fldone  = Bool().asInput                               //only one fluh req inflight
    val rx      = Decoupled(new DirectoryRead(params)).flip    //remaper x
    val hset    = Vec(2, Decoupled(UInt(width = params.setBits)).flip)
    val rereq   = Decoupled(new FullRequest(params)).flip
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
    val reqarb = Module(new Arbiter(io.req.bits.cloneType, 3))

    //rereq
    reqarb.io.in(0)                     <> Queue(io.rereq, 1, pipe = false, flow = false)
    //firereq
    reqarb.io.in(1).valid               := x.valid && fldone
    reqarb.io.in(1).bits.opcode         := UInt(0)
    reqarb.io.in(1).bits.source         := UInt(0)
    reqarb.io.in(1).bits.offset         := offset
    reqarb.io.in(1).bits.newset.valid   := hset(1).valid
    reqarb.io.in(1).bits.newset.bits    := hset(1).bits
    reqarb.io.in(1).bits.set            := hset(0).bits
    reqarb.io.in(1).bits.tag            := tag
    x.ready                             := reqarb.io.in(1).ready && fldone
    when( x.fire()  )                   { fldone := false.B }
    when( io.fldone )                   { fldone := true.B  }
    //remapercmd
    reqarb.io.in(2).valid               := io.rx.valid
    reqarb.io.in(2).bits.opcode         := UInt(0)
    reqarb.io.in(2).bits.source         := UInt(1)
    reqarb.io.in(2).bits.newset.valid   := false.B
    reqarb.io.in(2).bits.set            := io.rx.bits.set
    reqarb.io.in(2).bits.tag            := io.rx.bits.tag
    io.rx.ready                         := reqarb.io.in(2).ready

    io.req                              <> Queue(reqarb.io.out, 1, pipe = false, flow = false)
    io.req.bits.prio                    := Vec(UInt(1, width=3).asBools)  // same prio as A
    io.req.bits.control                 := Bool(true)
    io.req.bits.param                   := UInt(0)
    io.req.bits.size                    := UInt(params.offsetBits)
    io.req.bits.offset                  := UInt(0)
    reqarb.io.out.ready                 := io.req.ready

    when(io.rereq.valid) { assert(io.rereq.ready) }
  }
}
