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
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.pfc._

class Scheduler(params: InclusiveCacheParameters) extends Module
    with HasSchedulerPFC
{
  val io = new Bundle {
    val in = TLBundle(params.inner.bundle).flip
    val out = TLBundle(params.outer.bundle)
    // Way permissions
    val ways = Vec(params.allClients, UInt(width = params.cache.ways)).flip
    val divs = Vec(params.allClients, UInt(width = InclusiveCacheParameters.lfsrBits + 1)).flip
    // Control port
    val req = Decoupled(new SinkXRequest(params)).flip
    val resp = Decoupled(new SourceXRequest(params))
    //config
    val config = new Bundle {
      val remaper       = new RemaperConfig().asInput
      val atdetconf0    = new AttackDetectorConfig0().asInput
      val atdetconf1    = new AttackDetectorConfig1().asInput
    }
    //pfc
    val pfcupdate = new Bundle{
      val g0      = Flipped(new P0L2PFCReg())
      val remaper = Flipped(new RemaperPFCReg())
      val itlink  = Flipped(new TileLinkPFCReg())
      val otlink  = Flipped(new TileLinkPFCReg())
      val setmiss = Flipped(new SetEventPFCRam(params.cache.sets))
      val setev   = Flipped(new SetEventPFCRam(params.cache.sets))
    }
  }

  val sourceA = Module(new SourceA(params))
  val sourceB = Module(new SourceB(params))
  val sourceC = Module(new SourceC(params))
  val sourceD = Module(new SourceD(params))
  val sourceE = Module(new SourceE(params))
  val sourceX = Module(new SourceX(params))

  io.out.a <> sourceA.io.a
  io.out.c <> sourceC.io.c
  io.out.e <> sourceE.io.e
  io.in.b <> sourceB.io.b
  io.in.d <> sourceD.io.d
  io.resp <> sourceX.io.x

  val sinkA = Module(new SinkA(params))
  val sinkC = Module(new SinkC(params))
  val sinkD = Module(new SinkD(params))
  val sinkE = Module(new SinkE(params))
  val sinkX = Module(new SinkX(params))

  sinkA.io.a <> io.in.a
  sinkC.io.c <> io.in.c
  sinkE.io.e <> io.in.e
  sinkD.io.d <> io.out.d
  sinkX.io.x <> io.req
  sinkX.io.fldone := sourceX.io.fldone 

  io.out.b.ready := Bool(true) // disconnected

  val remaper   = Module(new Remaper(params))
  val randomtable   = Module(new RandomTable(params))
  val attackdetector  = Module(new AttackDetector(params))
  val directory = Module(new Directory(params))
  val bankedStore = Module(new BankedStore(params))
  val requests = Module(new ListBuffer(ListBufferParameters(new QueuedRequest(params), 3*params.mshrs, params.secondary, false)))
  val mshrs = Seq.fill(params.mshrs) { Module(new MSHR(params)) }
  val abc_mshrs = mshrs.init.init
  val bc_mshr = mshrs.init.last
  val c_mshr = mshrs.last
  val nestedwb = Wire(new NestedWriteback(params))

  // Deliver messages from Sinks to MSHRs
  mshrs.zipWithIndex.foreach { case (m, i) =>
    m.io.sinkc.valid := sinkC.io.resp.valid && sinkC.io.resp.bits.sink   === UInt(i)
    m.io.sinkd.valid := sinkD.io.resp.valid && sinkD.io.resp.bits.source === UInt(i)
    m.io.sinke.valid := sinkE.io.resp.valid && sinkE.io.resp.bits.sink   === UInt(i)
    m.io.sinkc.bits := sinkC.io.resp.bits
    m.io.sinkd.bits := sinkD.io.resp.bits
    m.io.sinke.bits := sinkE.io.resp.bits
    m.io.nestedwb := nestedwb
  }

  // If the pre-emption BC or C MSHR have a matching set, the normal MSHR must be blocked
  def bs_stall(access: Option[Bool], schedule: Option[ScheduleRequest], status: MSHRStatus): Bool = {
    require(!access.isEmpty || !schedule.isEmpty)
    val bs_access = if(!access.isEmpty) {
      access.get
    } else {
      schedule.get.a.valid || schedule.get.b.valid || schedule.get.c.valid || schedule.get.d.valid
    }
    (bs_access && !status.bssafe)
  }
  val mshr_stall_abc = abc_mshrs.map { m =>
    bs_stall(None, Some(m.io.schedule.bits), m.io.status.bits) ||
    (bc_mshr.io.status.valid && !bc_mshr.io.status.bits.disamb && m.io.status.bits.set === bc_mshr.io.status.bits.set) ||
    ( c_mshr.io.status.valid && ! c_mshr.io.status.bits.disamb && m.io.status.bits.set ===  c_mshr.io.status.bits.set) ||
    (m.io.schedule.bits.d.valid  && !m.io.schedule.bits.d.bits.control && m.io.schedule.bits.d.bits.prio(0) && !sinkC.io.sinkAD_safe(m.io.schedule.bits.d.bits.source))
  }
  val mshr_stall_bc =
    bs_stall(None, Some(bc_mshr.io.schedule.bits), bc_mshr.io.status.bits)
    c_mshr.io.status.valid && !c_mshr.io.status.bits.disamb && bc_mshr.io.status.bits.set === c_mshr.io.status.bits.set
  val mshr_stall_c =  bs_stall(None, Some(c_mshr.io.schedule.bits), c_mshr.io.status.bits)
  val mshr_stall = mshr_stall_abc :+ mshr_stall_bc :+ mshr_stall_c


  val stall_abc = (mshr_stall_abc zip abc_mshrs) map { case (s, m) => s && m.io.status.valid }
  if (!params.lastLevel || !params.firstLevel)
    params.ccover(stall_abc.reduce(_||_), "SCHEDULER_ABC_INTERLOCK", "ABC MSHR interlocked due to pre-emption")
  if (!params.lastLevel)
    params.ccover(mshr_stall_bc && bc_mshr.io.status.valid, "SCHEDULER_BC_INTERLOCK", "BC MSHR interlocked due to pre-emption")

  // Consider scheduling an MSHR only if all the resources it requires are available
  val mshr_request = Cat((mshrs zip mshr_stall).map { case (m, s) =>
    m.io.schedule.valid && !s &&
      (sourceA.io.req.ready || !m.io.schedule.bits.a.valid) &&
      (sourceB.io.req.ready || !m.io.schedule.bits.b.valid) &&
      (sourceC.io.req.ready || !m.io.schedule.bits.c.valid) &&
      (sourceD.io.req.ready || !m.io.schedule.bits.d.valid) &&
      (sourceE.io.req.ready || !m.io.schedule.bits.e.valid) &&
      (sourceX.io.req.ready || !m.io.schedule.bits.x.valid) &&
      (directory.io.write.ready || !m.io.schedule.bits.dir.valid) &&
      (remaper.io.continue.ready || !m.io.schedule.bits.rmp.valid)
  }.reverse)

  // Round-robin arbitration of MSHRs
  val robin_filter = RegInit(UInt(0, width = params.mshrs))
  val robin_request = Cat(mshr_request, mshr_request & robin_filter)
  val mshr_selectOH2 = ~(leftOR(robin_request) << 1) & robin_request
  val mshr_selectOH = mshr_selectOH2(2*params.mshrs-1, params.mshrs) | mshr_selectOH2(params.mshrs-1, 0)
  val mshr_select = OHToUInt(mshr_selectOH)
  val schedule = Mux1H(mshr_selectOH, mshrs.map(_.io.schedule.bits))
  val scheduleTag = Mux1H(mshr_selectOH, mshrs.map(_.io.status.bits.tag))
  val scheduleSet = Mux1H(mshr_selectOH, mshrs.map(_.io.status.bits.set))
  val scheduleNop = Mux1H(mshr_selectOH, mshrs.map(_.io.status.bits.nop))

  // When an MSHR wins the schedule, it has lowest priority next time
  when (mshr_request.orR()) { robin_filter := ~rightOR(mshr_selectOH) }

  // Fill in which MSHR sends the request
  schedule.a.bits.source := mshr_select
  schedule.b.bits.sink   := mshr_select
  schedule.c.bits.source := Mux(schedule.c.bits.opcode(1), mshr_select, UInt(0)) // only set for Release[Data] not ProbeAck[Data]
  schedule.d.bits.sink   := mshr_select

  sourceA.io.req := schedule.a
  sourceB.io.req := schedule.b
  sourceC.io.req := schedule.c
  sourceD.io.req := schedule.d
  sourceE.io.req := schedule.e
  sourceX.io.req := schedule.x
  directory.io.write := schedule.dir
  remaper.io.continue := schedule.rmp

  // Forward meta-data changes from nested transaction completion
  val select_c  = mshr_selectOH(params.mshrs-1)
  val select_bc = mshr_selectOH(params.mshrs-2)
  nestedwb.set   := Mux(select_c, c_mshr.io.status.bits.set, bc_mshr.io.status.bits.set)
  nestedwb.tag   := Mux(select_c, c_mshr.io.status.bits.tag, bc_mshr.io.status.bits.tag)
  nestedwb.b_toN       := select_bc && bc_mshr.io.schedule.bits.dir.valid && bc_mshr.io.schedule.bits.dir.bits.data.state === MetaData.INVALID
  nestedwb.b_toB       := select_bc && bc_mshr.io.schedule.bits.dir.valid && bc_mshr.io.schedule.bits.dir.bits.data.state === MetaData.BRANCH
  nestedwb.b_clr_dirty := select_bc && bc_mshr.io.schedule.bits.dir.valid
  nestedwb.c_set_dirty := select_c  &&  c_mshr.io.schedule.bits.dir.valid && c_mshr.io.schedule.bits.dir.bits.data.dirty

  val request = Wire(Decoupled(new FullRequest(params)))
  // Pick highest priority request
  request.valid := directory.io.ready && ((sinkA.io.req.valid && sinkC.io.sinkA_safe) || sinkX.io.req.valid || sinkC.io.req.valid)
  request.bits := Mux(sinkC.io.req.valid, sinkC.io.req.bits,
                  Mux(sinkX.io.req.valid, sinkX.io.req.bits, sinkA.io.req.bits))
  sinkC.io.req.ready := directory.io.ready && request.ready
  sinkX.io.req.ready := directory.io.ready && request.ready && !sinkC.io.req.valid
  sinkA.io.req.ready := directory.io.ready && request.ready && !sinkC.io.req.valid && sinkC.io.sinkA_safe && !sinkX.io.req.valid

  // If no MSHR has been assigned to this set, we need to allocate one
  val setMatches = Cat(mshrs.map { m => m.io.status.valid && !m.io.status.bits.disamb && m.io.status.bits.set === request.bits.set }.reverse)
  // when release's newhset is valid and oldhset Matches must disasmbiguate in case of deadlock
  val disamb = false.B //!request.bits.prio(0) && setMatches.orR() && request.bits.newset.valid
  val alloc  = !setMatches.orR() // NOTE: no matches also means no BC or C pre-emption on this set
  // If a same-set MSHR says that requests of this type must be blocked (for bounded time), do it
  val blockB = Mux1H(setMatches, mshrs.map(_.io.status.bits.blockB)) && request.bits.prio(1)
  val blockC = Mux1H(setMatches, mshrs.map(_.io.status.bits.blockC)) && request.bits.prio(2)
  // If a same-set MSHR says that requests of this type must be handled out-of-band, use special BC|C MSHR
  // ... these special MSHRs interlock the MSHR that said it should be pre-empted.
  val nestB  = Mux1H(setMatches, mshrs.map(_.io.status.bits.nestB))  && request.bits.prio(1)
  val nestC  = Mux1H(setMatches, mshrs.map(_.io.status.bits.nestC))  && request.bits.prio(2)
  // Prevent priority inversion; we may not queue to MSHRs beyond our level
  val prioFilter = Cat(request.bits.prio(2), !request.bits.prio(0), ~UInt(0, width = params.mshrs-2))
  val lowerMatches = setMatches & prioFilter
  // If we match an MSHR <= our priority that neither blocks nor nests us, queue to it.
  // disasmbiguate and swap can not be queued
  val queue = lowerMatches.orR() && !nestB && !nestC && !blockB && !blockC && !(request.bits.control && request.bits.opcode === XOPCODE.SWAP)

  if (!params.lastLevel) {
    params.ccover(request.valid && blockB, "SCHEDULER_BLOCKB", "Interlock B request while resolving set conflict")
    params.ccover(request.valid && nestB,  "SCHEDULER_NESTB", "Priority escalation from channel B")
  }
  if (!params.firstLevel) {
    params.ccover(request.valid && blockC, "SCHEDULER_BLOCKC", "Interlock C request while resolving set conflict")
    params.ccover(request.valid && nestC,  "SCHEDULER_NESTC", "Priority escalation from channel C")
  }
  params.ccover(request.valid && queue, "SCHEDULER_SECONDARY", "Enqueue secondary miss")

  // It might happen that lowerMatches has >1 bit if the two special MSHRs are in-use
  // We want to Q to the highest matching priority MSHR.
  val lowerMatches1 =
    Mux(lowerMatches(params.mshrs-1), UInt(1 << (params.mshrs-1)),
    Mux(lowerMatches(params.mshrs-2), UInt(1 << (params.mshrs-2)),
    lowerMatches))

  // If this goes to the scheduled MSHR, it may need to be bypassed
  // Alternatively, the MSHR may be refilled from a request queued in the ListBuffer
  val selected_requests = Cat(mshr_selectOH, mshr_selectOH, mshr_selectOH) & requests.io.valid
  val a_pop = selected_requests((0 + 1) * params.mshrs - 1, 0 * params.mshrs).orR()
  val b_pop = selected_requests((1 + 1) * params.mshrs - 1, 1 * params.mshrs).orR()
  val c_pop = selected_requests((2 + 1) * params.mshrs - 1, 2 * params.mshrs).orR()
  val bypassMatches = (mshr_selectOH & lowerMatches1).orR() &&
                      Mux(c_pop || request.bits.prio(2), !c_pop, Mux(b_pop || request.bits.prio(1), !b_pop, !a_pop))
  val may_pop = a_pop || b_pop || c_pop
  val bypass = request.valid && queue && bypassMatches
  val will_reload = schedule.reload && (may_pop || bypass)
  val will_pop = schedule.reload && may_pop && !bypass

  params.ccover(mshr_selectOH.orR && bypass, "SCHEDULER_BYPASS", "Bypass new request directly to conflicting MSHR")
  params.ccover(mshr_selectOH.orR && will_reload, "SCHEDULER_RELOAD", "Back-to-back service of two requests")
  params.ccover(mshr_selectOH.orR && will_pop, "SCHEDULER_POP", "Service of a secondary miss")

  // Repeat the above logic, but without the fan-in
  mshrs.zipWithIndex.foreach { case (m, i) =>
    val sel = mshr_selectOH(i)
    m.io.schedule.ready := sel
    val a_pop = requests.io.valid(params.mshrs * 0 + i)
    val b_pop = requests.io.valid(params.mshrs * 1 + i)
    val c_pop = requests.io.valid(params.mshrs * 2 + i)
    val bypassMatches = lowerMatches1(i) &&
                        Mux(c_pop || request.bits.prio(2), !c_pop, Mux(b_pop || request.bits.prio(1), !b_pop, !a_pop))
    val may_pop = a_pop || b_pop || c_pop
    val bypass = request.valid && queue && bypassMatches
    val will_reload = m.io.schedule.bits.reload && (may_pop || bypass)
    m.io.allocate.bits := Mux(bypass, Wire(new QueuedRequest(params), init = request.bits), requests.io.data)
    m.io.allocate.bits.set := m.io.status.bits.set
    m.io.allocate.bits.repeat := m.io.allocate.bits.tag === m.io.status.bits.tag && !m.io.status.bits.nop
    m.io.allocate.bits.disamb := false.B
    m.io.allocate.bits.disamb := false.B
    m.io.allocate.valid := sel && will_reload
  }

  // Determine which of the queued requests to pop (supposing will_pop)
  val prio_requests = ~(~requests.io.valid | (requests.io.valid >> params.mshrs) | (requests.io.valid >> 2*params.mshrs))
  val pop_index = OHToUInt(Cat(mshr_selectOH, mshr_selectOH, mshr_selectOH) & prio_requests)
  requests.io.pop.valid := will_pop
  requests.io.pop.bits  := pop_index

  // Reload from the Directory if the next MSHR operation changes tags
  val force_tag_missmatch = scheduleNop
  val lb_tag_mismatch     = scheduleTag =/= requests.io.data.tag || force_tag_missmatch
  val mshr_uses_directory_assuming_no_bypass = schedule.reload && may_pop && lb_tag_mismatch
  val mshr_uses_directory_for_lb = will_pop && lb_tag_mismatch
  val mshr_uses_directory = will_reload && (scheduleTag =/= Mux(bypass, request.bits.tag, requests.io.data.tag) || force_tag_missmatch)

  // Is there an MSHR free for this request?
  val mshr_validOH = Cat(mshrs.map(_.io.status.valid).reverse)
  val mshr_free = (~mshr_validOH & prioFilter).orR()

  // Fanout the request to the appropriate handler (if any)
  val bypassQueue = schedule.reload && bypassMatches
  val request_alloc_cases =
     (alloc && !mshr_uses_directory_assuming_no_bypass && mshr_free) ||
     (nestB && !mshr_uses_directory_assuming_no_bypass && !bc_mshr.io.status.valid && !c_mshr.io.status.valid) ||
     (nestC && !mshr_uses_directory_assuming_no_bypass && !c_mshr.io.status.valid)
  request.ready := request_alloc_cases || (queue && (bypassQueue || requests.io.push.ready))
  val alloc_uses_directory = request.valid && request_alloc_cases

  // When a request goes through, it will need to hit the Directory
  directory.io.read.valid := mshr_uses_directory || alloc_uses_directory
  directory.io.read.bits.set := Mux(will_pop && mshr_uses_directory, scheduleSet,          request.bits.set)
  directory.io.read.bits.tag := Mux(will_pop && mshr_uses_directory, requests.io.data.tag, request.bits.tag)
  //read swap zone will trigger swap
  directory.io.read.bits.swz := !(will_pop && mshr_uses_directory) && request.bits.control && request.bits.opcode === XOPCODE.SWAP
  when(will_pop && mshr_uses_directory) { assert(!(requests.io.data.control && requests.io.data.opcode === XOPCODE.SWAP)) }

  // Enqueue the request if not bypassed directly into an MSHR
  requests.io.push.valid := request.valid && queue && !bypassQueue
  requests.io.push.bits.data  := request.bits
  requests.io.push.bits.index := Mux1H(
    request.bits.prio, Seq(
      OHToUInt(lowerMatches1 << params.mshrs*0),
      OHToUInt(lowerMatches1 << params.mshrs*1),
      OHToUInt(lowerMatches1 << params.mshrs*2)))

  val mshr_insertOH = ~(leftOR(~mshr_validOH) << 1) & ~mshr_validOH & prioFilter
  (mshr_insertOH.asBools zip mshrs) map { case (s, m) =>
    when (request.valid && alloc && s && !mshr_uses_directory_assuming_no_bypass) {
      m.io.allocate.valid := Bool(true)
      m.io.allocate.bits := request.bits
      m.io.allocate.bits.repeat := Bool(false)
      m.io.allocate.bits.disamb := disamb
    }
  }

  when (request.valid && nestB && !bc_mshr.io.status.valid && !c_mshr.io.status.valid && !mshr_uses_directory_assuming_no_bypass) {
    bc_mshr.io.allocate.valid := Bool(true)
    bc_mshr.io.allocate.bits := request.bits
    bc_mshr.io.allocate.bits.repeat := Bool(false)
    bc_mshr.io.allocate.bits.disamb := Bool(false)
    assert (!request.bits.prio(0))
  }
  bc_mshr.io.allocate.bits.prio(0) := Bool(false)

  when (request.valid && nestC && !c_mshr.io.status.valid && !mshr_uses_directory_assuming_no_bypass) {
    c_mshr.io.allocate.valid := Bool(true)
    c_mshr.io.allocate.bits := request.bits
    c_mshr.io.allocate.bits.repeat := Bool(false)
    c_mshr.io.allocate.bits.disamb := Bool(false)
    assert (!request.bits.prio(0))
    assert (!request.bits.prio(1))
  }
  c_mshr.io.allocate.bits.prio(0) := Bool(false)
  c_mshr.io.allocate.bits.prio(1) := Bool(false)

  // Fanout the result of the Directory lookup
  val dirTarget = Mux(alloc, mshr_insertOH, Mux(nestB, UInt(1 << (params.mshrs-2)), UInt(1 << (params.mshrs-1))))
  val directoryFanout = params.dirReg(RegNext(Mux(mshr_uses_directory, mshr_selectOH, Mux(alloc_uses_directory, dirTarget, UInt(0)))))
  mshrs.zipWithIndex.foreach { case (m, i) =>
    m.io.directory.valid := directoryFanout(i)
    m.io.directory.bits := directory.io.result.bits
  }

  // MSHR response meta-data fetch
  sinkC.io.set := Vec(mshrs.map(_.io.status.bits.set))(sinkC.io.sink)
  sinkC.io.way := Vec(mshrs.map(_.io.status.bits.way))(sinkC.io.sink)
  sinkC.io.swz := Vec(mshrs.map(_.io.status.bits.swz))(sinkC.io.sink)
  sinkD.io.set := Vec(mshrs.map(_.io.status.bits.set))(sinkD.io.source)
  sinkD.io.way := Vec(mshrs.map(_.io.status.bits.way))(sinkD.io.source)
  sinkD.io.swz := Vec(mshrs.map(_.io.status.bits.swz))(sinkD.io.source)

  // Beat buffer connections between components
  sinkA.io.pb_pop <> sourceD.io.pb_pop
  sourceD.io.pb_beat := sinkA.io.pb_beat
  sinkC.io.rel_pop <> sourceD.io.rel_pop
  sourceD.io.rel_beat := sinkC.io.rel_beat

  // BankedStore ports
  bankedStore.io.sinkC_adr <> sinkC.io.bs_adr
  bankedStore.io.sinkC_dat := sinkC.io.bs_dat
  bankedStore.io.sinkD_adr <> sinkD.io.bs_adr
  bankedStore.io.sinkD_dat := sinkD.io.bs_dat
  bankedStore.io.sourceC_adr <> sourceC.io.bs_adr
  bankedStore.io.sourceD_radr <> sourceD.io.bs_radr
  bankedStore.io.sourceD_wadr <> sourceD.io.bs_wadr
  bankedStore.io.sourceD_wdat := sourceD.io.bs_wdat
  sourceC.io.bs_dat := bankedStore.io.sourceC_dat
  sourceD.io.bs_rdat := bankedStore.io.sourceD_rdat

  // SourceD data hazard interlock
  sourceD.io.evict_req := sourceC.io.evict_req
  sourceD.io.grant_req := sinkD  .io.grant_req
  sourceC.io.evict_safe := sourceD.io.evict_safe
  sinkD  .io.grant_safe := sourceD.io.grant_safe

  // Remaper data hazard interlock
  sourceD.io.swap_req   := remaper.io.swap_req
  remaper.io.swap_safe  := sourceD.io.swap_safe

  //must handle CD before AD
  sinkC.io.set_sinkAD_safe.valid := sourceD.io.req.fire() && sourceD.io.req.bits.prio(2)
  sinkC.io.set_sinkAD_safe.bits  := sourceD.io.req.bits.source

  if(params.remap.en) {
    val fsinkX                = Module(new FSink(io.req.bits.cloneType,  params))
    val fsinkA                = Module(new FSink(io.in.a.bits.cloneType, params))

    //req from inner
    fsinkX.io.front    <> io.req       ;     sinkX.io.x      <> fsinkX.io.back    //io.req     -->   fsinkX    --> sinkX
    fsinkA.io.front    <> io.in.a      ;     sinkA.io.a      <> fsinkA.io.back    //io.in.a    -->   fsinkA    --> sinkA

    //remaper status
    sinkC.io.rstatus    := remaper.io.status
    fsinkX.io.rstatus   := remaper.io.status
    fsinkA.io.rstatus   := remaper.io.status
    mshrs.map { case m => m.io.rstatus := remaper.io.status }

    //randomtable
    import chisel3.util.random.FibonacciLFSR
    val lfsr  = FibonacciLFSR.maxPeriod(64, true.B, seed = Some(11))
    val mix_c = Reg(io.in.c.bits.data.cloneType)
    val mix_d = Reg(io.in.d.bits.data.cloneType)
    mix_c := Mux(io.in.c.valid || io.out.c.valid, mix_c + (io.in.c.bits.data ^ io.out.c.bits.data), Cat(mix_d(0), mix_c(63, 1))) ^ lfsr
    mix_d := Mux(io.in.d.valid || io.out.d.valid, mix_d + (io.in.d.bits.data ^ io.out.d.bits.data), Cat(mix_c(0), mix_d(63, 1))) ^ lfsr
    when(reset || !directory.io.ready) {
      mix_c := 0.U
      mix_d := 0.U
    }
    remaper.io.rtreadys       := randomtable.io.readys
    randomtable.io.cmd        := remaper.io.rtcmd
    randomtable.io.mix.bits   := Cat(mix_c, mix_d)
    randomtable.io.mix.valid  := io.in.c.valid || io.out.c.valid || io.in.d.valid || io.out.d.valid || !directory.io.ready
    randomtable.io.req(0) <>  sinkC.io.rtab.req    ;  sinkC.io.rtab.resp   := randomtable.io.resp(0) ;                                      //sinkC   -->  randomtable  --> sinkC
    randomtable.io.req(1) <> fsinkA.io.rtab.req    ; fsinkA.io.rtab.resp   := randomtable.io.resp(1) ; sinkA.io.hset(0) <> fsinkA.io.hset(0) ; sinkA.io.hset(1) <> fsinkA.io.hset(1) //fsinkA  -->  randomtable  --> sinkA
    randomtable.io.req(2) <> fsinkX.io.rtab.req    ; fsinkX.io.rtab.resp   := randomtable.io.resp(2) ; sinkX.io.hset(0) <> fsinkX.io.hset(0) ; sinkX.io.hset(1) <> fsinkX.io.hset(1) //fsinkX  -->  randomtable  --> sinkX
    randomtable.io.req(3) <> remaper.io.rtreq      ; remaper.io.rtresp     := randomtable.io.resp(3) ;

    //rereq
    val rereq_arb = Module(new Arbiter(mshrs(0).io.schedule.bits.rereq.bits.cloneType, params.mshrs))
    ((0 until mshrs.length) zip mshrs).map { case(i, m) =>
      rereq_arb.io.in(i).valid := m.io.schedule.bits.rereq.valid
      rereq_arb.io.in(i).bits  := m.io.schedule.bits.rereq.bits
    }
    sinkC.io.rereq.valid  := rereq_arb.io.out.valid && rereq_arb.io.out.bits.prio(2)
    sinkC.io.rereq.bits   := rereq_arb.io.out.bits
    sinkC.io.rrfree       := PopCount(mshrs.map( _.io.schedule.bits.rrfreeC))
    assert(sinkC.io.rrfree <= 2.U)
    sinkX.io.rereq.valid  := rereq_arb.io.out.valid && rereq_arb.io.out.bits.prio(0) &&  rereq_arb.io.out.bits.control
    sinkX.io.rereq.bits   := rereq_arb.io.out.bits
    sinkA.io.rereq.valid  := rereq_arb.io.out.valid && rereq_arb.io.out.bits.prio(0) && !rereq_arb.io.out.bits.control
    sinkA.io.rereq.bits   := rereq_arb.io.out.bits
    sinkA.io.rrfree       := PopCount(abc_mshrs.map(_.io.schedule.bits.rrfreeA))
    assert(PopCount(rereq_arb.io.in.map(_.valid)) <= 1.U)
    assert(PopCount(mshrs.map( _.io.schedule.bits.rrfreeC)) <= 2.U)
    assert(PopCount(mshrs.map( _.io.schedule.bits.rrfreeA)) <= 2.U)
    when(rereq_arb.io.out.valid) { assert(sinkC.io.rereq.valid || sinkA.io.rereq.valid) }


    //remap cache
    //remaper.xreq: flush or swap
    sinkX.io.rx         <> remaper.io.xreq     ; remaper.io.evresp   <> sourceX.io.rx        //remaper  ---> sinkX    --> .... --> sourceX --> remaper
    /*sinkX.io.blocksr    :=  RegNext(io.in.a.valid || io.in.c.valid) || !requests.io.empty        || //lowest prioirty
                            directory.io.write.valid || !directory.io.write.ready || //can not inspect write fifo!! so wait write fifo drained
                            Cat(mshrs.map { m => m.io.status.valid && ( m.io.status.bits.set === sinkX.io.sset) }).orR || remaper.io.mshrbusy || //can not inspect write fifo!! so swap req can not be pushed into requests
                            RegNext((request.valid && request.bits.newset.valid) || (requests.io.pop.valid && requests.io.data.newset.valid)) //low prio than rereq(lowest prioirty)
    */
    val dir_wque = Reg(Valid(new DirectoryWrite(params)))
    when(directory.io.write.ready) {
      dir_wque.valid := directory.io.write.valid
      dir_wque.bits  := directory.io.write.bits
    }
    sinkX.io.blocksr    :=  (dir_wque.valid && dir_wque.bits.set === sinkX.io.sset)                   || //can not inspect write fifo!! so wait write fifo drained
                            (fsinkA.io.back.valid || sinkA.io.req.valid || !sinkC.io.sinkA_safe)      || //change prio below than a (usuallly c > x > a)
                            (Cat(mshrs.map { m => m.io.status.valid && (m.io.status.bits.set === sinkX.io.sset || m.io.status.bits.swz || !m.io.status.bits.bssafe || m.io.schedule.bits.rmp.valid) }).orR)
                            //(Cat(mshrs.map { m => m.io.status.valid && (!m.io.status.bits.bssafe || m.io.schedule.bits.rmp.valid  || (m.io.status.bits.set === sinkX.io.sset)) }).orR)
                            //(RegNext((request.valid           && request.bits.newset.valid     && request.bits.newset.bits     === sinkX.io.sset) ||
                            //         (requests.io.pop.valid   && requests.io.data.newset.valid && requests.io.data.newset.bits === sinkX.io.sset) )) //low prio than rereq(lowest prioirty)

    //swap directoryentry
    directory.io.rreq     <>  remaper.io.dereq
    remaper.io.deresp     :=  directory.io.rresp
    directory.io.rfinish  := remaper.io.definish
    //swap datablock
    bankedStore.io.rreq   <>  remaper.io.dbreq

    //remaper scheduler status
    remaper.io.schreq    :=  randomtable.io.resp.map(_.valid).orR            ||
                             fsinkA.io.back.valid  || fsinkX.io.back.valid   ||
                              sinkA.io.busy        ||  sinkX.io.busy         ||  sinkC.io.busy  ||  !requests.io.empty
    remaper.io.mshrbusy  := Cat(mshrs.map { m => m.io.status.valid }).orR()

    //attack detector
    remaper.io.req.bits               := attackdetector.io.remap.bits
    remaper.io.req.valid              := attackdetector.io.remap.valid && randomtable.io.readys
    attackdetector.io.remap.ready     := remaper.io.req.ready          && randomtable.io.readys
    attackdetector.io.config0         := io.config.atdetconf0
    attackdetector.io.config1         := io.config.atdetconf1
    attackdetector.io.access.valid    := io.pfcupdate.itlink.a_Done    && remaper.io.req.ready
    attackdetector.io.evict.valid     := sourceC.io.req.fire()         && remaper.io.req.ready
    attackdetector.io.evict.bits.set  := sourceC.io.req.bits.set

    //ASSERT
    when(sinkX.io.req.fire() && sinkX.io.req.bits.control && sinkX.io.req.bits.opcode === XOPCODE.SWAP) { 
      assert(alloc_uses_directory)
      assert(!mshr_uses_directory) 
    }
    when(will_pop) { assert(!(requests.io.data.control && requests.io.data.opcode === XOPCODE.SWAP)) }
    assert(!(directory.io.write.fire() && directory.io.write.bits.data.state  =/= MetaData.INVALID && remaper.io.status.oneloc && directory.io.write.bits.data.loc  =/= remaper.io.status.cloc))
    assert(!(directory.io.result.valid && directory.io.result.bits.state      =/= MetaData.INVALID && remaper.io.status.oneloc && directory.io.result.bits.loc      =/= remaper.io.status.cloc))
    mshrs.zipWithIndex.foreach { case (m0, i0) =>
      assert(RegNext(!m0.io.status.valid || !m0.io.status.bits.deadlock), "mshr_"+i0+"deadlock?") //deadlock?
      mshrs.zipWithIndex.foreach { case (m1, i1) => { if(i0 < i1) {
        val collide = RegNext(m0.io.allocate.valid || m1.io.allocate.valid)   &&
                      m0.io.status.valid        &&   m1.io.status.valid       &&
                      !m0.io.status.bits.disamb &&  !m1.io.status.bits.disamb &&
                      m0.io.status.bits.set     ===  m1.io.status.bits.set
        val legal_collide = (i0.U >= abc_mshrs.length.U || i1.U >= abc_mshrs.length.U) &&
                            (RegNext((m0.io.allocate.valid && Mux(i0.U === abc_mshrs.length.U, nestB, nestC))  ||
                                     (m1.io.allocate.valid && Mux(i1.U === abc_mshrs.length.U, nestB, nestC))  || //nestB or nestC
                                     (m0.io.allocate.valid && m0.io.status.valid && m0.io.allocate.bits.set === m0.io.status.bits.set) ||
                                     (m1.io.allocate.valid && m1.io.status.valid && m1.io.allocate.bits.set === m1.io.status.bits.set) )) //pop from requests(include bypass requests)
        when(collide && !legal_collide) {
          printf("illegal collide at set_%x: mshr_%x tag_%x swz_%x and mshr_%x tag_%x swz_%x\n",m0.io.status.bits.set, i0.U, m0.io.status.bits.tag, m0.io.status.bits.swz, i1.U, m1.io.status.bits.tag, m1.io.status.bits.swz)
          assert(legal_collide)
        }
      }}}
    }
  } else {

  }

  connectPFC(params)

  private def afmt(x: AddressSet) = s"""{"base":${x.base},"mask":${x.mask}}"""
  private def addresses = params.inner.manager.managers.flatMap(_.address).map(afmt _).mkString(",")
  private def setBits = params.addressMapping.drop(params.offsetBits).take(params.setBits).mkString(",")
  private def tagBits = params.addressMapping.drop(params.offsetBits + params.setBits).take(params.tagBits).mkString(",")
  private def simple = s""""reset":"${reset.pathName}","tagBits":[${tagBits}],"setBits":[${setBits}],"blockBytes":${params.cache.blockBytes},"ways":${params.cache.ways}"""
  def json: String = s"""{"addresses":[${addresses}],${simple},"directory":${directory.json},"subbanks":${bankedStore.json}}"""
}
