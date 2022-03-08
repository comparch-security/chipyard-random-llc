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

class SinkCResponse(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val last   = Bool()
  val set    = UInt(width = params.setBits)
  val tag    = UInt(width = if(params.remap.en) params.blkadrBits else params.tagBits)
  val source = UInt(width = params.inner.bundle.sourceBits)
  val sink   = UInt(width = params.inner.bundle.sourceBits)
  val param  = UInt(width = 3)
  val data   = Bool()
}

class PutBufferCEntry(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val data = UInt(width = params.inner.bundle.dataBits)
  val corrupt = Bool()
}

class SinkC(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val req = Decoupled(new FullRequest(params)) // Release
    val resp = Valid(new SinkCResponse(params)) // ProbeAck
    val c = Decoupled(new TLBundleC(params.inner.bundle)).flip
    val rereq   = Decoupled(new FullRequest(params)).flip
    val rrfree  = UInt(width = log2Up(params.mshrs+1)).asInput //free rereq zone
    val sinkA_safe  = Bool().asOutput
    val sinkAD_safe = UInt(width = 1 << params.inner.bundle.sourceBits).asOutput
    val set_sinkAD_safe = Valid(UInt(width = params.inner.bundle.sourceBits)).asInput
    // Find 'set' 'way' and 'swz' via MSHR CAM lookup
    val sink   = UInt(width = params.outer.bundle.sinkBits)
    val set    = UInt(width = params.setBits).flip
    val way    = UInt(width = params.wayBits).flip
    val swz    = Bool().flip //swap zone
    // ProbeAck write-back
    val bs_adr = Decoupled(new BankedStoreInnerAddress(params))
    val bs_dat = new BankedStoreInnerPoison(params)
    // SourceD sideband
    val rel_pop  = Decoupled(new PutBufferPop(params)).flip
    val rel_beat = new PutBufferCEntry(params)
    //for use by rempaer
    val busy     = Bool()
    val rstatus  = new RemaperStatusIO(params).asInput  //Remaper Status
    val rtab     = new SourceSinkRandomTableIO(params.blkadrBits, params.setBits)
  }

  if (params.firstLevel) {
    // Tie off unused ports
    io.req.valid := Bool(false)
    io.resp.valid := Bool(false)
    io.c.ready := Bool(true)
    io.set := UInt(0)
    io.bs_adr.valid := Bool(false)
    io.rel_pop.ready := Bool(true)
  } else {
    // No restrictions on the type of buffer
    val cin     = Wire(new DecoupledIO(io.c.bits))
    cin.valid  := io.c.valid //&&  nbreq.io.c.ready
    cin.bits   := io.c.bits
    io.c.ready := cin.ready  // cin.ready := c.io.enq.ready
    val c = params.micro.innerBuf.c(cin)

    val (    tag,     set,     offset) = params.parseAddress(c.bits.address)
    val (cin_tag, cin_set, cin_offset) = params.parseAddress(cin.bits.address)
    val (    first,     last, _,     beat) = params.inner.count(c)
    val (cin_first, cin_last, _, cin_beat) = params.inner.count(cin)
    val     hasData = params.inner.hasData(c.bits)
    val cin_hasData = params.inner.hasData(cin.bits)
    val     raw_resp = c.  bits.opcode === TLMessages.ProbeAck || c.  bits.opcode === TLMessages.ProbeAckData
    val cin_raw_resp = cin.bits.opcode === TLMessages.ProbeAck || cin.bits.opcode === TLMessages.ProbeAckData
    val resp = Mux(c.valid, raw_resp, RegEnable(raw_resp, c.valid))

    //safe sinkA req and safe sinkA->sinkD req
    val sinkA_safe  = RegInit(true.B)
    val sinkAD_safe = RegInit(~UInt(0, width = 1 << params.inner.bundle.sourceBits))             
    /*when(c.fire()   &&     first && !    raw_resp) { sinkA_safe := true.B  }
    when(io.c.valid && cin_first && !cin_raw_resp) { sinkA_safe := false.B }

    sinkAD_safe := (sinkAD_safe |
                    Mux(io.set_sinkAD_safe.valid,                 UIntToOH(io.set_sinkAD_safe.bits), UInt(0))) & // set_sourceD_safe
                   ~Mux(io.c.valid && cin_first && !cin_raw_resp, UIntToOH(cin.bits.source),         UInt(0))    // clr_sourceD_safe
    */
    io.sinkA_safe  :=  sinkA_safe
    io.sinkAD_safe :=  sinkAD_safe

    // Handling of C is broken into two cases:
    //   ProbeAck
    //     if hasData, must be written to BankedStore
    //     if last beat, trigger resp
    //   Release
    //     if first beat, trigger req
    //     if hasData, go to putBuffer
    //     if hasData && first beat, must claim a list

    assert (!(c.valid && c.bits.corrupt), "Data poisoning unavailable")

    io.sink := Mux(c.valid, c.bits.sink, RegEnable(c.bits.sink, c.valid)) // finds us the way

    // Cut path from inner C to the BankedStore SRAM setup
    //   ... this makes it easier to layout the L2 data banks far away
    val bs_adr = Wire(io.bs_adr)
    io.bs_adr <> Queue(bs_adr, 1, pipe=true)
    io.bs_dat.data   := RegEnable(c.bits.data,    bs_adr.fire())
    bs_adr.valid     := resp && (!first || (c.valid && hasData))
    bs_adr.bits.noop := !c.valid
    bs_adr.bits.set  := io.set
    bs_adr.bits.way  := io.way
    bs_adr.bits.swz  := io.swz
    bs_adr.bits.beat := Mux(c.valid, beat, RegEnable(beat + bs_adr.ready.asUInt, c.valid))
    bs_adr.bits.mask := ~UInt(0, width = params.innerMaskBits)
    params.ccover(bs_adr.valid && !bs_adr.ready, "SINKC_SRAM_STALL", "Data SRAM busy")

    io.resp.valid := resp && c.valid && (first || last) && (!hasData || bs_adr.ready)
    io.resp.bits.last   := last
    io.resp.bits.sink   := c.bits.sink
    io.resp.bits.set    := io.set
    io.resp.bits.tag    := Mux(params.remap.en.B, Cat(tag, set), tag)
    io.resp.bits.source := c.bits.source
    io.resp.bits.param  := c.bits.param
    io.resp.bits.data   := hasData

    val putbuffer = Module(new ListBuffer(ListBufferParameters(new PutBufferCEntry(params), params.relLists, params.relBeats, false)))
    val lists = RegInit(UInt(0, width = params.relLists))

    val lists_set = Wire(init = UInt(0, width = params.relLists))
    val lists_clr = Wire(init = UInt(0, width = params.relLists))
    lists := (lists | lists_set) & ~lists_clr

    val free = !lists.andR()
    val freeOH = ~(leftOR(~lists) << 1) & ~lists
    val freeIdx = OHToUInt(freeOH)

    val req_block = Wire(init = first && !io.req.ready)
    val buf_block = hasData && !putbuffer.io.push.ready
    val set_block = hasData && first && !free

    params.ccover(c.valid && !raw_resp && req_block, "SINKC_REQ_STALL", "No MSHR available to sink request")
    params.ccover(c.valid && !raw_resp && buf_block, "SINKC_BUF_STALL", "No space in putbuffer for beat")
    params.ccover(c.valid && !raw_resp && set_block, "SINKC_SET_STALL", "No space in putbuffer for request")

    c.ready := Mux(raw_resp, !hasData || bs_adr.ready, !req_block && !buf_block && !set_block)

    io.req.valid := !resp && c.valid && first && !buf_block && !set_block
    putbuffer.io.push.valid := !resp && c.valid && hasData && !req_block && !set_block
    when (!resp && c.valid && first && hasData && !req_block && !buf_block) { lists_set := freeOH }

    val put = Mux(c.valid && first && !raw_resp, freeIdx, RegEnable(freeIdx, c.valid && first && !raw_resp))

    io.req.bits.prio   := Vec(UInt(4, width=3).asBools)
    io.req.bits.control:= Bool(false)
    io.req.bits.opcode := c.bits.opcode
    io.req.bits.param  := c.bits.param
    io.req.bits.size   := c.bits.size
    io.req.bits.source := c.bits.source
    io.req.bits.offset := offset
    io.req.bits.set    := set
    io.req.bits.tag    := tag
    io.req.bits.put    := put

    putbuffer.io.push.bits.index := put
    putbuffer.io.push.bits.data.data    := c.bits.data
    putbuffer.io.push.bits.data.corrupt := c.bits.corrupt

    // Grant access to pop the data
    putbuffer.io.pop.bits := io.rel_pop.bits.index
    putbuffer.io.pop.valid := io.rel_pop.fire()
    io.rel_pop.ready := putbuffer.io.valid(io.rel_pop.bits.index)
    io.rel_beat := putbuffer.io.data

    when (io.rel_pop.fire() && io.rel_pop.bits.last) {
      lists_clr := UIntToOH(io.rel_pop.bits.index, params.relLists)
    }

    if(params.remap.en) {
      req_block := false.B  //no block data flow
      val rereq_entries = params.relLists
      val nbreq   = Module(new NBSinkCReq(params))
      val reqarb  = Module(new Arbiter(io.req.bits.cloneType, 2))
      val rrbook  = RegInit(UInt(0, width = rereq_entries)) //book a rereq fifo space
      val block   = (c.valid && !raw_resp && set_block) || rrbook(rereq_entries-1)
      val rrbook_shiftl = (reqarb.io.in(1).fire() &&  reqarb.io.in(1).bits.newset.valid).asUInt
      val rrbook_shiftr = (reqarb.io.in(0).fire() && !reqarb.io.in(0).bits.newset.valid).asUInt + io.rrfree(1, 0)

      rrbook := rrbook << rrbook_shiftl >> rrbook_shiftr
      when(rrbook === 0.U && rrbook_shiftl.asBool()) { rrbook := 1.U }

      //io.c
      cin.valid             :=  io.c.valid  &&  nbreq.io.c.ready
      io.c.ready            :=  cin.ready   &&  nbreq.io.c.ready
      nbreq.io.c.valid      :=  io.c.valid  &&  cin.ready
      nbreq.io.c.bits       :=  cin.bits
      nbreq.io.fire         :=  io.c.fire()

      //get hset
      nbreq.io.rstatus      := io.rstatus
      io.rtab.req           <> nbreq.io.rtab.req
      nbreq.io.rtab.resp    := io.rtab.resp

      //rereq
      reqarb.io.in(0)                     <> Queue(io.rereq, rereq_entries, pipe = false, flow = false)
      //firstreq
      reqarb.io.in(1).valid               := nbreq.io.req.valid && !block
      reqarb.io.in(1).bits                := nbreq.io.req.bits
      reqarb.io.in(1).bits.put            := put
      nbreq.io.req.ready                  := reqarb.io.in(1).ready && !block

      io.req.valid                        := reqarb.io.out.valid
      io.req.bits                         := reqarb.io.out.bits
      io.req.bits.prio                    := Vec(UInt(4, width=3).asBools)
      io.req.bits.control                 := Bool(false)
      reqarb.io.out.ready                 := io.req.ready

      io.sinkA_safe                       := sinkA_safe && !nbreq.io.busy && rrbook === 0.U

      io.busy                             := c.valid || nbreq.io.busy || rrbook =/= 0.U

      when(io.rereq.valid) { assert(io.rereq.ready) }
    }
  }
}

class NBSinkCReq(params: InclusiveCacheParameters) extends Module //no block release data and send req to mshr
{
  val io = new Bundle {
    val req = Decoupled(new FullRequest(params)) // Release
    val c = Decoupled(new TLBundleC(params.inner.bundle)).flip
    val fire  = Bool().asInput  
    //for use by rempaer
    val busy       = Bool()
    val rstatus    = new RemaperStatusIO(params).asInput  //Remaper Status
    val rtab       = new SourceSinkRandomTableIO(params.blkadrBits, params.setBits)
  }

  val c          =  Reg(new TLBundleC(params.inner.bundle))
  val rtabreq    =  Module(new Queue(UInt(width = params.blkadrBits),         1, pipe = false, flow = true))
  val hset       =  Seq.fill(2) { Module(new Queue(new Hset(params.setBits),  1, pipe = false, flow = true )) }
  val busy       =  RegInit(false.B)

  val (s0tag, s0set, s0offset) = params.parseAddress(io.c.bits.address)
  val s0blkadr                 = Wire(init = Cat(s0tag, s0set))
  val (s1tag, s1set, s1offset) = params.parseAddress(c.address)
  val s1blkadr                 = Wire(init = Cat(s1tag, s1set))
  val (first, _, _, _)         = params.inner.count(io.c.bits, io.fire)
  val raw_resp                 = io.c.bits.opcode === TLMessages.ProbeAck || io.c.bits.opcode === TLMessages.ProbeAckData
  val needset                  = Wire(init = first && !raw_resp) //need true set
  val swapped                  = Wire(init = ((io.rstatus.cloc === RTAL.LEFT && io.rtab.resp.bits.lhset < io.rstatus.head) ||
                                              (io.rstatus.cloc === RTAL.RIGH && io.rtab.resp.bits.rhset < io.rstatus.head) ||
                                              (io.rtab.resp.bits.lhset === io.rtab.resp.bits.rhset                       ) ))
  io.c.ready := !busy || !needset
  when( io.c.fire() && needset )  { c     := io.c.bits }
  when( io.c.fire() && needset )  { busy  := true.B    }
  when( io.req.fire()          )  { busy  := false.B   }

  //rtab
  rtabreq.io.enq.valid         := io.c.fire() && needset
  rtabreq.io.enq.bits          := s0blkadr
  io.rtab.req.valid            := rtabreq.io.deq.valid
  io.rtab.req.bits.blkadr      := rtabreq.io.deq.bits
  rtabreq.io.deq.ready         := io.rtab.req.ready
  io.rtab.req.valid            := io.c.fire() && needset  //rtab(0).req.io.ready := true.B
  io.rtab.req.bits.blkadr      := s0blkadr

  //io.rtab.resp  --->  hset
  hset(0).io.enq.valid          := io.rtab.resp.valid
  hset(0).io.enq.bits.set       := Mux(Mux(!io.rstatus.oneloc && swapped, io.rstatus.nloc === RTAL.LEFT, io.rstatus.cloc === RTAL.LEFT), io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)
  hset(0).io.enq.bits.loc       := Mux(Mux(!io.rstatus.oneloc && swapped, io.rstatus.nloc === RTAL.LEFT, io.rstatus.cloc === RTAL.LEFT),              RTAL.LEFT,                RTAL.RIGH)
  hset(1).io.enq.valid          := io.rtab.resp.valid && !io.rstatus.oneloc && !swapped
  hset(1).io.enq.bits.set       := Mux(io.rstatus.nloc === RTAL.LEFT, io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)
  hset(1).io.enq.bits.loc       := Mux(io.rstatus.nloc === RTAL.LEFT,               RTAL.LEFT,               RTAL.RIGH)

  //io.req
  io.req.valid                 :=  hset(0).io.deq.valid
  io.req.bits.prio             :=  Vec(UInt(4, width=3).asBools)
  io.req.bits.control          :=  Bool(false)
  io.req.bits.opcode           :=  c.opcode
  io.req.bits.param            :=  c.param
  io.req.bits.size             :=  c.size
  io.req.bits.source           :=  c.source
  io.req.bits.offset           :=  s1offset
  io.req.bits.newset.valid     :=  hset(1).io.deq.valid
  io.req.bits.newset.bits      :=  hset(1).io.deq.bits.set
  io.req.bits.loc(1)           :=  hset(1).io.deq.bits.loc
  io.req.bits.set              :=  hset(0).io.deq.bits.set
  io.req.bits.loc(0)           :=  hset(0).io.deq.bits.loc
  io.req.bits.tag              :=  s1blkadr
  hset(0).io.deq.ready         :=  io.req.ready
  hset(1).io.deq.ready         :=  io.req.ready
  
  io.busy                      := busy

  when(io.rtab.resp.valid) { assert(hset(0).io.enq.ready) }
}