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
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import MetaData._
import freechips.rocketchip.util._

class DirectoryEntry(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val loc     = UInt(width = RTAL.SZ)
  val dirty   = Bool() // true => TRUNK or TIP
  val state   = UInt(width = params.stateBits)
  val clients = UInt(width = params.clientBits)
  val tag     = UInt(width = if(params.remap.en) params.blkadrBits else params.tagBits)
}

class DirectoryWrite(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val swz  = Bool()     //swap zone
  val set  = UInt(width = params.setBits)
  val way  = UInt(width = params.wayBits)
  val mta  = Bool()    //mshr to array
  val data = new DirectoryEntry(params)
}

class DirectoryRead(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set = UInt(width = params.setBits)
  val tag = UInt(width = if(params.remap.en) params.blkadrBits else params.tagBits)
  val swz = Bool()  //read swap zone will trigger swap
  val update_repl_state = Bool()  //update replace state
}

class DirectoryResult(params: InclusiveCacheParameters) extends DirectoryEntry(params)
{
  val swz = Bool()     //hit at swap zone
  val hit = Bool()
  val mshr = Bool()    //hit at mshr
  val way = UInt(width = params.wayBits)
  val stm = Bool()     //swap zone to mshr(and then to array)
}

class Directory(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val write  = Decoupled(new DirectoryWrite(params)).flip
    val read   = Valid(new DirectoryRead(params)).flip // sees same-cycle write
    val result = Valid(new DirectoryResult(params))
    val ready  = Bool() // reset complete; can enable access
    //remaper
    val rreq     = Decoupled(new SwaperReq(params)).flip
    val rresp    = Valid(new SwaperResp(params))
    val rfinish  = Valid(UInt(width = params.setBits)).flip
  }

  val codeBits = new DirectoryEntry(params).getWidth

  val swaper = Module(new DirectoryEntrySwaper(params))
  swaper.io.rreq.valid := io.rreq.valid
  swaper.io.rreq.bits  := io.rreq.bits
  io.rreq.ready        := swaper.io.rreq.ready
  io.rresp.valid       := swaper.io.rresp.valid
  io.rresp.bits        := swaper.io.rresp.bits
  swaper.io.rfinish    := io.rfinish

  val (cc_dir, omSRAM) =  DescribedSRAM(
    name = "cc_dir",
    desc = "Directory RAM",
    size = params.cache.sets,
    data = Vec(params.cache.ways, UInt(width = codeBits))
  )

  val writeqin     = Wire(new DecoupledIO(io.write.bits))
  writeqin.valid  := io.write.valid && !io.write.bits.swz
  writeqin.bits   := io.write.bits
  io.write.ready := writeqin.ready
  val write = Queue(writeqin, 1) // must inspect contents => max size 1
  // a flow Q creates a WaR hazard... this MIGHT not cause a problem
  // a pipe Q causes combinational loop through the scheduler
  val write_stall_time = RegInit(UInt(0, width = 4))
  val write_block_read = write_stall_time(3)
  when(write.valid) { write_stall_time := write_stall_time + 1.U }
  when(write.ready) { write_stall_time := 0.U                    }

  // Wiping the Directory with 0s on reset has ultimate priority
  val wipeCount = RegInit(UInt(0, width = params.setBits + 1))
  val wipeOff = RegNext(Bool(false), Bool(true)) // don't wipe tags during reset
  val wipeDone = wipeCount(params.setBits)
  val wipeSet = wipeCount(params.setBits - 1,0)

  io.ready := wipeDone && !write_block_read
  when (!wipeDone && !wipeOff) { wipeCount := wipeCount + UInt(1) }
  assert (wipeDone || !io.read.valid)

  // Be explicit for dumb 1-port inference
  val ren = io.read.valid
  val wen = (!wipeDone && !wipeOff) || write.valid
  assert (!io.read.valid || wipeDone)

  require (codeBits <= 256)

  write.ready := !io.read.valid
  if(params.remap.en) {
    swaper.io.write.valid  := io.write.valid && writeqin.ready
    swaper.io.write.bits   := io.write.bits
    when ((!ren && ((!wipeDone && !wipeOff) || (write.valid && !write.bits.swz)))) {
      cc_dir.write(
      Mux(wipeDone, write.bits.set, wipeSet),
      Vec.fill(params.cache.ways) { Mux(wipeDone, write.bits.data.asUInt, UInt(0)) },
      UIntToOH(write.bits.way, params.cache.ways).asBools.map(_ || !wipeDone))
    }
  } else {
    when (!ren && wen) {
      cc_dir.write(
      Mux(wipeDone, write.bits.set, wipeSet),
      Vec.fill(params.cache.ways) { Mux(wipeDone, write.bits.data.asUInt, UInt(0)) },
      UIntToOH(write.bits.way, params.cache.ways).asBools.map(_ || !wipeDone))
    }
  }

  val ren1 = RegInit(Bool(false))
  val ren2 = if (params.micro.dirReg) RegInit(Bool(false)) else ren1
  ren2 := ren1
  ren1 := ren

  val bypass_valid = params.dirReg(write.valid && !write.bits.swz)
  val bypass = params.dirReg(write.bits, ren1 && write.valid)
  val regout = if(params.remap.en) {
    params.dirReg({
      val resp = cc_dir.read(io.read.bits.set, io.read.valid)
      swaper.io.iresp.valid := RegNext(io.read.valid && io.read.bits.swz)
      swaper.io.iresp.bits  := Vec(resp.map(d => new DirectoryEntry(params).fromBits(d)))
      resp
      },
      ren1)
  } else {
    params.dirReg(cc_dir.read(io.read.bits.set, ren), ren1)
  }
  val tag = params.dirReg(RegEnable(io.read.bits.tag, ren), ren1)
  val set = params.dirReg(RegEnable(io.read.bits.set, ren), ren1)

  // Compute the victim way in case of an evicition
  class replacerUpdate extends Bundle {
    val set    = UInt(width = params.setBits)
    val state  = UInt(width = replacer.nBits)
  }
  val replacer = new PseudoLRU(params.cache.ways)
  val (repl_state_array, _ ) =  DescribedSRAM(
    name = "repl_state_array",
    desc = "Repl_State RAM",
    size = params.cache.sets,
    data = UInt(width = replacer.nBits)
  )
  val replUpdQue   = Module(new Queue(new replacerUpdate(), params.mshrs - 2, pipe = false, flow = false))
  val s1_repl_state = repl_state_array.read(io.read.bits.set, io.read.valid && io.read.bits.update_repl_state)
  val s2_repl_state = params.dirReg(s1_repl_state, ren1)
  val s1_repl_update = RegNext(io.read.valid && io.read.bits.update_repl_state)
  val s2_repl_update = params.dirReg(s1_repl_update, ren1)
  val victimLFSR = LFSR16(params.dirReg(ren))(InclusiveCacheParameters.lfsrBits-1, 0)
  val victimSums = Seq.tabulate(params.cache.ways) { i => UInt((1 << InclusiveCacheParameters.lfsrBits)*i / params.cache.ways) }
  val victimLTE  = Cat(victimSums.map { _ <= victimLFSR }.reverse)
  val victimSimp = Cat(UInt(0, width=1), victimLTE(params.cache.ways-1, 1), UInt(1, width=1))
  val victimWay  = params.dirReg(replacer.get_replace_way(s1_repl_state), ren1)
  val victimWayOH = UIntToOH(victimWay)
  replUpdQue.io.enq.valid       := s2_repl_update && !io.result.bits.swz
  replUpdQue.io.enq.bits.set    := set
  replUpdQue.io.enq.bits.state  := replacer.get_next_state(s2_repl_state, io.result.bits.way)
  replUpdQue.io.deq.ready       := !(io.read.valid && io.read.bits.update_repl_state)
  when(!wipeDone || (replUpdQue.io.deq.valid && !(io.read.valid && io.read.bits.update_repl_state))) {
    repl_state_array.write(Mux(wipeDone, replUpdQue.io.deq.bits.set, wipeSet), Mux(wipeDone, replUpdQue.io.deq.bits.state, 0.U))
  }
  assert (!ren2 || victimLTE(0) === UInt(1))
  assert (!ren2 || ((victimSimp >> 1) & ~victimSimp) === UInt(0)) // monotone
  assert (!ren2 || PopCount(victimWayOH) === UInt(1))

  val setQuash = bypass_valid && bypass.set === set
  val tagMatch = bypass.data.tag === tag
  val wayMatch = bypass.way === victimWay
  val invBlock = swaper.io.invblk.valid && swaper.io.invblk.bits.set === set

  val ways = Vec(regout.map(d => new DirectoryEntry(params).fromBits(d)))
  val hits = Cat(ways.zipWithIndex.map { case (w, i) =>
    w.tag === tag && w.state =/= INVALID && (!setQuash || UInt(i) =/= bypass.way) && (!invBlock || UInt(i) =/= swaper.io.invblk.bits.way)
  }.reverse)
  val hit = hits.orR()

  io.result.valid := ren2
  io.result.bits := Mux(hit, Mux1H(hits, ways), Mux(setQuash && (tagMatch || wayMatch), bypass.data, Mux1H(victimWayOH, ways)))
  io.result.bits.hit := hit || (setQuash && tagMatch && bypass.data.state =/= INVALID)
  io.result.bits.way := Mux(hit, OHToUInt(hits), Mux(setQuash && tagMatch, bypass.way, victimWay))
  if(params.remap.en) {
    val regswaperresult = params.dirReg({  swaper.io.result.bits }, ren1)
    swaper.io.read.valid  := ren
    swaper.io.read.bits   := io.read.bits
    io.result.bits.hit    := regswaperresult.hit || hit || (setQuash && tagMatch && bypass.data.state =/= INVALID)
    io.result.bits.swz    := false.B
    io.result.bits.stm    := false.B
    when(regswaperresult.hit)  {
      io.result.bits      := regswaperresult
    }
  }

  params.ccover(ren2 && setQuash && tagMatch, "DIRECTORY_HIT_BYPASS", "Bypassing write to a directory hit")
  params.ccover(ren2 && setQuash && !tagMatch && wayMatch, "DIRECTORY_EVICT_BYPASS", "Bypassing a write to a directory eviction")

  def json: String = s"""{"clients":${params.clientBits},"mem":"${cc_dir.pathName}","clean":"${wipeDone.pathName}"}"""

  if(true) {
    val timers  = RegInit(UInt(0, width = 32))
    timers := timers+1.U
    when(io.write.fire() && io.write.bits.data.state =/= INVALID) { printf("clk %d tag %x set %x way %x swz %d loc %d\n",timers, io.write.bits.data.tag, io.write.bits.set, io.write.bits.way, io.write.bits.swz, io.write.bits.data.loc)}
  }
}
