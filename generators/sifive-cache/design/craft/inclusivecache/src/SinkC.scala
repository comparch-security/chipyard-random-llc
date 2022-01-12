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
    val busy       = Bool()
    val rstatus    = new RemaperStatusIO(params.setBits).asInput  //Remaper Status
    val rtab       = new SourceSinkRandomTableIO(params.blkadrBits, params.setBits)
    val dir_read   = Decoupled(new DirectoryRead(params))
    val dir_result = Valid(new DirectoryResult(params)).asInput
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
    cin.valid  := io.c.valid
    cin.bits   := io.c.bits
    io.c.ready := cin.ready  // cin.ready := c.io.enq.ready
    val c = params.micro.innerBuf.c(cin)

    val (tag, set, offset) = params.parseAddress(c.bits.address)
    val (first, last, _, beat) = params.inner.count(c)
    val hasData = params.inner.hasData(c.bits)
    val raw_resp = c.bits.opcode === TLMessages.ProbeAck || c.bits.opcode === TLMessages.ProbeAckData
    val resp = Mux(c.valid, raw_resp, RegEnable(raw_resp, c.valid))

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

      val nbreq   = Module(new NBSinkCReq(params))
      val block   = buf_block || set_block

      //io.c
      cin.valid             :=  io.c.valid  &&  nbreq.io.c.ready
      io.c.ready            :=  cin.ready   &&  nbreq.io.c.ready
      nbreq.io.c.valid      :=  io.c.valid  &&  cin.ready
      nbreq.io.c.bits       :=  cin.bits
      nbreq.io.fire         :=  io.c.fire()

      //get hset
      nbreq.io.rstatus      := io.rstatus
      io.rtab               := nbreq.io.rtab
      io.dir_read           := nbreq.io.dir_read
      nbreq.io.dir_result   := io.dir_result

      //io.req
      io.req.valid          :=  nbreq.io.req.valid && !block
      io.req.bits           :=  nbreq.io.req.bits
      io.req.bits.put       :=  put
      nbreq.io.req.ready    :=  io.req.ready && !block

      io.busy               := io.c.valid || c.valid || nbreq.io.busy || putbuffer.io.valid =/= 0.U

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
    val rstatus    = new RemaperStatusIO(params.setBits).asInput  //Remaper Status
    val rtab       = new SourceSinkRandomTableIO(params.blkadrBits, params.setBits)
    val dir_read   = Decoupled(new DirectoryRead(params))
    val dir_result = Valid(new DirectoryResult(params)).asInput
  }

  val c          =  Reg(new TLBundleC(params.inner.bundle))
  val dir_read   =  Module(new Queue(io.dir_read.bits.cloneType,    1, pipe = false, flow = true  ))
  val true_set   =  Module(new Queue(io.dir_read.bits.cloneType,    1, pipe = false, flow = true  ))  //trueset
  val set_arb    =  Module(new Arbiter(io.dir_read.bits.cloneType,  2))  //trueset_arb
  val busy       =  RegInit(false.B)

  val (s0tag, s0set, s0offset) = params.parseAddress(io.c.bits.address)
  val s0blkadr                 = Wire(init = Cat(s0tag, s0set))
  val (s1tag, s1set, s1offset) = params.parseAddress(c.address)
  val s1blkadr                 = Wire(init = Cat(s1tag, s1set))
  val (first, _, _, _)         = params.inner.count(io.c.bits, io.fire)
  val raw_resp                 = io.c.bits.opcode === TLMessages.ProbeAck || io.c.bits.opcode === TLMessages.ProbeAckData
  val needset                  = Wire(init = first && !raw_resp) //need true set
  val swapped                  = Wire(init = ((io.rstatus.cloc === RTAL.LEFT && io.rtab.resp.bits.lhset < io.rstatus.head) || (io.rstatus.cloc === RTAL.RIGH && io.rtab.resp.bits.rhset < io.rstatus.head)))

  val lhset                    = RegEnable(io.rtab.resp.bits.lhset, io.rtab.resp.valid)
  val rhset                    = RegEnable(io.rtab.resp.bits.rhset, io.rtab.resp.valid)
 
  io.c.ready := !busy || !needset
  when( io.c.fire() && needset )  { c     := io.c.bits }
  when( io.c.fire() && needset )  { busy  := true.B    }
  when( io.req.fire()          )  { busy  := false.B   }

  //rtab
  io.rtab.req.valid            := io.c.fire() && needset
  io.rtab.req.bits.blkadr      := s0blkadr

  //dir_read
  dir_read.io.enq.valid        := io.rtab.resp.valid && !io.rstatus.oneloc && !swapped
  dir_read.io.enq.bits.tag     := s1blkadr
  dir_read.io.enq.bits.set     := Mux(io.rstatus.cloc === RTAL.LEFT, io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)
  io.dir_read.valid            := dir_read.io.deq.valid
  io.dir_read.bits             := dir_read.io.deq.bits
  dir_read.io.deq.ready        := io.dir_read.ready

  //set_arb
  set_arb.io.in(0).valid       := RegNext(io.dir_result.valid)
  set_arb.io.in(0).bits.set    := RegEnable(Mux(io.dir_result.bits.hit, RegNext(io.dir_read.bits.set), Mux(io.rstatus.nloc === RTAL.LEFT, lhset, rhset)), io.dir_result.valid)
  set_arb.io.in(1).valid       := io.rtab.resp.valid && (io.rstatus.oneloc || swapped)
  set_arb.io.in(1).bits.set    := Mux(Mux(io.rstatus.oneloc, io.rstatus.cloc === RTAL.LEFT, io.rstatus.nloc === RTAL.LEFT), io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)

  //true_set
  true_set.io.enq.valid        :=  set_arb.io.out.valid
  true_set.io.enq.bits         :=  set_arb.io.out.bits
  set_arb.io.out.ready         :=  true_set.io.enq.ready

  //io.req
  io.req.valid                 :=  true_set.io.deq.valid
  io.req.bits.prio             :=  Vec(UInt(4, width=3).asBools)
  io.req.bits.control          :=  Bool(false)
  io.req.bits.opcode           :=  c.opcode
  io.req.bits.param            :=  c.param
  io.req.bits.size             :=  c.size
  io.req.bits.source           :=  c.source
  io.req.bits.offset           :=  s1offset
  io.req.bits.set              :=  true_set.io.deq.bits.set
  io.req.bits.tag              :=  s1blkadr
  true_set.io.deq.ready        :=  io.req.ready
  
  io.busy                      := busy

  when(set_arb.io.out.valid) { assert(set_arb.io.out.ready) }
}