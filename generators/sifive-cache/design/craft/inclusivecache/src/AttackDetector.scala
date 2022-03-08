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

class AttackDetectorConfig0 extends Bundle {
  val athreshold      = UInt(width = 31)   //access threshold
  val enath           = Bool()
  val ethreshold      = UInt(width = 31)   //evict threshold
  val eneth           = Bool() //lowest bit
}

class AttackDetectorConfig1 extends Bundle {
  val reserve         = UInt(width = 53)   //z-vaule threshold
  val zthreshold      = UInt(width = 10)   //z-vaule threshold
  val enzth           = Bool()             //lowest bit
}

class AttackDetector(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val config0   = new AttackDetectorConfig0().asInput
    val config1   = new AttackDetectorConfig1().asInput
    val remap     = Decoupled(new RemaperReqIO())
    val evict     = Valid(new DirectoryRead(params)).flip
    val access    = Valid(new DirectoryRead(params)).flip
  }

  val atdetec  = RegInit(false.B)
  io.remap.bits.atdetec := atdetec

  val count_access = RegInit(UInt(0, width = 32))
  val count_evicts = RegInit(UInt(0, width = 32))
  val zscore       = RegInit(UInt(1, width = 10)) //in case of optimizing away
  io.remap.valid   := false.B
  when(count_access > io.config0.athreshold && io.config0.enath)      { io.remap.valid := true.B }
  when(count_evicts > io.config0.ethreshold && io.config0.eneth)      { io.remap.valid := true.B }
  when(zscore       > io.config1.zthreshold && io.config1.enzth)      { io.remap.valid := true.B }
  when(io.access.valid && io.remap.ready) { count_access   := count_access + 1.U }
  when(io.evict.valid  && io.remap.ready) { count_evicts   := count_evicts + 1.U }
  when(io.remap.fire()) {
    count_access := 0.U
    count_evicts := 0.U
  }
}