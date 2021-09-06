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

class AttackDetectorConfig extends Bundle {
  val en              = Bool() //lowest bit
}

class AttackDetector(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val config   = new AttackDetectorConfig().asInput
    val remap    = Decoupled(new RemaperReqIO())
    val evict    = Valid(new DirectoryRead(params)).flip
  }

  val count = RegInit(UInt(1, width = 30))
  val doremap = count > (2 * params.cache.sets * params.cache.ways).U
  io.remap.valid := doremap
  when(!doremap && io.evict.valid && io.remap.ready) { count := count + 1.U }
  when(io.remap.fire()) { count := 0.U }

  val config = Reg(new AttackDetectorConfig())
  when(io.config.en) { config := config }
  config.en := io.config.en

}