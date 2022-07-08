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

// The control port response source
class SourceXRequest(params: InclusiveCacheParameters) extends SinkXRequest(params)
{
  val fail = Bool()
  val hit  = Bool()
}

class SourceX(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val req = Decoupled(new SourceXRequest(params)).flip
    val x = Decoupled(new SourceXRequest(params))
    val rx = Decoupled(new SourceXRequest(params))
    val fldone = Bool().asOutput //only one fluh req inflight
  }

  val x = Module(new Queue(io.req.bits, 2))

  x.io.enq.valid := io.req.valid && io.req.bits.source === 0.U
  x.io.enq.bits  := io.req.bits
  io.req.ready   := x.io.enq.ready
  io.x <> x.io.deq
  params.ccover(io.x.valid && !io.x.ready, "SOURCEX_STALL", "Backpressure when sending a control message")

  io.rx.valid := io.req.fire() && io.req.bits.source === 1.U

  io.fldone := io.x.fire()
}