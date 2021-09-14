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
  val max_access      = UInt(width = 15)  //max_access per block
  val en_access       = Bool()
  val max_evicts      = UInt(width = 7)   //max_evicts per block
  val en_evicts       = Bool() //lowest bit
}

class AttackDetector(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val config   = new AttackDetectorConfig().asInput
    val remap    = Decoupled(new RemaperReqIO())
    val evict    = Valid(new DirectoryRead(params)).flip
    val access   = Valid(new DirectoryRead(params)).flip
  }

  val ways     = params.cache.ways
  val sets     = params.cache.sets
  val blocks   = sets*ways

  val config = io.config

  val count_access = RegInit(UInt(0, width = 32))
  val count_evicts = RegInit(UInt(0, width = 32))
  val doremap = count_access > Cat(config.max_access, (blocks - 1).U) || count_evicts > Cat(config.max_evicts, (blocks - 1).U)
  io.remap.valid := doremap
  when(!doremap && io.access.valid && io.remap.ready) { count_access := count_access + 1.U }
  when(!doremap && io.evict.valid  && io.remap.ready) { count_evicts := count_evicts + 1.U }
  when(io.remap.fire() || !config.en_access) { count_access := 0.U }
  when(io.remap.fire() || !config.en_evicts) { count_evicts := 0.U }

}