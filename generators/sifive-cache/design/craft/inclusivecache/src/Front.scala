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

class FSourceX_no_used_anymore(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val front  = Decoupled(new SourceXRequest(params)).flip
    val back   = Decoupled(new SourceXRequest(params))      //to Source X
    //for use by rempaer
    val rx     = Valid(new SourceXRequest(params))          //Remaper SourceXResp
  }

  val twice  = RegInit(false.B) //when remap flush twice use both hset
  val back   = Module(new Queue(io.back.bits, 2))

  //io.front => back
  back.io.enq.valid   := io.front.valid && (io.front.bits.source === 1.U || (twice && io.front.bits.source === 2.U))
  back.io.enq.bits    := io.front.bits
  io.front.ready      := back.io.enq.ready
  when(io.front.fire() && io.front.bits.source === 2.U)  { twice := true.B  }
  //back => io.back
  io.back.valid       := back.io.deq.valid && back.io.deq.bits.source =/= 0.U
  io.back.bits        := back.io.deq.bits
  back.io.deq.ready   := io.back.ready
  when(io.back.fire())  { twice := false.B }

  //io.front => io.rx
  io.rx.valid     := io.front.fire() && io.front.bits.source === 0.U
  io.rx.bits      := io.front.bits

  params.ccover(io.front.valid && !io.front.ready, "SOURCEX_STALL", "Backpressure when sending a control message")
}

class FSinkX_no_used_anymore(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val front    = Decoupled(new SinkXRequest(params)).flip
    val back     = Decoupled(new SinkXRequest(params))      //BackendX
    val diradr   = Decoupled(new DirectoryRead(params))
    val fldone   = Bool().asInput                           //flush done
    //for use by rempaer
    val rx       = Decoupled(new DirectoryRead(params)).flip //remaper x
    val rstatus  = new RemaperStatusIO(params.setBits).asInput  //Remaper Status
    val rtab     = new SourceSinkRandomTableIO(params.blkadrBits, params.setBits)
    val idle     = Bool() //only when idle we can remap safely
  }

  io.diradr.valid  := io.back.valid
  val (tag, set, offset) = params.parseAddress(io.front.bits.address)
  val blkadr  = Cat(tag, set)
  val s_idle  = RegInit(true.B)
  val twice   = RegInit(false.B) //when remap flush twice with both hset

  val blkadrR = Reg(UInt(width = params.blkadrBits))
  val lhset   = Module(new Queue(UInt(width = params.setBits), 1))
  val rhset   = Module(new Queue(UInt(width = params.setBits), 1))

  //io.front          --->  rtab
  io.rtab.req.valid         := s_idle & !io.rstatus.blockSinkX & io.front.valid
  io.rtab.req.bits.blkadr   := blkadr
  io.front.ready            := s_idle & !io.rstatus.blockSinkX & io.rtab.req.ready
  when(io.front.fire())     { blkadrR := blkadr }

  params.ccover(io.front.valid && !s_idle, "SINKX_STALL", "Backpressure when accepting a control message")

  //rtab      --->  hset
  lhset.io.enq.valid        := io.rtab.resp.valid && Mux(io.rstatus.oneloc, io.rstatus.cloc === RTAL.LEFT, !(io.rstatus.cloc === RTAL.LEFT && io.rtab.resp.bits.lhset < io.rstatus.head))
  rhset.io.enq.valid        := io.rtab.resp.valid && Mux(io.rstatus.oneloc, io.rstatus.cloc === RTAL.RIGH, !(io.rstatus.cloc === RTAL.RIGH && io.rtab.resp.bits.rhset < io.rstatus.head))
  lhset.io.enq.bits         := io.rtab.resp.bits.lhset
  rhset.io.enq.bits         := io.rtab.resp.bits.rhset
  when(lhset.io.enq.valid || rhset.io.enq.valid) { twice := false.B  }
  when(lhset.io.enq.valid && rhset.io.enq.valid) { twice := true.B   }

  //to BackendSinkX
  io.back.valid         := io.rx.valid || lhset.io.deq.valid || rhset.io.deq.valid
  io.back.bits.source   := Mux(io.rx.valid, 0.U           , Mux(twice, 2.U,               1.U))
  io.diradr.bits.set    := Mux(io.rx.valid, io.rx.bits.set, Mux(lhset.io.deq.valid,       lhset.io.deq.bits, rhset.io.deq.bits))
  io.diradr.bits.tag    := Mux(io.rx.valid, io.rx.bits.tag, blkadrR)
  lhset.io.deq.ready    := !io.rx.valid && io.back.ready
  rhset.io.deq.ready    := !io.rx.valid && io.back.ready && !lhset.io.deq.valid
  io.rx.ready           := io.back.ready

  //when frontendX or backendX is busy we can not remap safely
  io.idle  := s_idle
  when(io.front.fire())      {  s_idle := false.B  }
  when(io.fldone)            {  s_idle := true.B   }

}

//FrontEnd of SinkA and SinkC
class FSink_no_used_anymore[T <: TLAddrChannel](val gen: T, params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val front      = Decoupled(gen).flip
    val back       = Decoupled(gen)                               //Backend
    val diradr     = Decoupled(new DirectoryRead(params))         //must synchronize with bc!!
    //for use by rempaer
    val rstatus    = new RemaperStatusIO(params.setBits).asInput  //Remaper Status
    val rtab       = new SourceSinkRandomTableIO(params.blkadrBits, params.setBits)
    val idle       = Bool() //can remap safely
    val dir_read   = Decoupled(new DirectoryRead(params))
    val dir_result = Valid(new DirectoryResult(params)).asInput
  }

  val back           =  Module(new Queue(io.front.bits.cloneType,     1, pipe = false, flow = true ))
  val diradr         =  Module(new Queue(io.diradr.bits.cloneType,    1, pipe = false, flow = true ))
  val dir_read       =  Module(new Queue(io.dir_read.bits.cloneType,  1, pipe = false, flow = true ))
  val diradr_arb     =  Module(new Arbiter(io.diradr.bits.cloneType,  3))

  val block          = Wire(Bool())    //block front req
  val blkadr         = Wire(UInt())
  val stall          = RegInit(false.B)

  val dir_tag        = Reg(UInt(width = params.blkadrBits))
  val oldset         = Reg(UInt(width = params.setBits))
  val newset         = Reg(UInt(width = params.setBits))
  val swapped        = Wire(init = ((io.rstatus.cloc === RTAL.LEFT && io.rtab.resp.bits.lhset < io.rstatus.head) || (io.rstatus.cloc === RTAL.RIGH && io.rtab.resp.bits.rhset < io.rstatus.head)))
  val needSet        = Wire(Bool())

  io.front.bits match {
    case a: TLBundleA => {
      val (tag, set, _)       = params.parseAddress(a.address)
      needSet                := true.B
      block                  := io.rstatus.blockSinkA
      blkadr                 := Cat(tag, set)
    }
    case c: TLBundleC => {
      val (tag, set, _)       = params.parseAddress(c.address)
      val (first, _, _, _)    = params.inner.count(c, io.front.fire())
      needSet                := first && !(c.opcode === TLMessages.ProbeAck || c.opcode === TLMessages.ProbeAckData)
      block                  := io.rstatus.blockSinkC      
      blkadr                 := Cat(tag, set)
    }
    case _  => require(false)
  }

  //rtab
  io.rtab.req.valid            := io.front.valid && needSet && !block && !stall
  io.rtab.req.bits.blkadr      := blkadr
  when(io.rtab.resp.valid) {
    oldset                     := Mux(io.rstatus.cloc === RTAL.LEFT, io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)
    newset                     := Mux(io.rstatus.nloc === RTAL.LEFT, io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)
  }

  //dir_read
  dir_read.io.enq.valid        := io.rtab.resp.valid && !io.rstatus.oneloc && !swapped
  dir_read.io.enq.bits.tag     := blkadr
  dir_read.io.enq.bits.set     := Mux(io.rstatus.cloc === RTAL.LEFT, io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)
  io.dir_read.valid            := dir_read.io.deq.valid
  io.dir_read.bits             := dir_read.io.deq.bits
  dir_read.io.deq.ready        := io.dir_read.ready

  when( io.rtab.req.fire()  )  { stall   := true.B    }
  when( io.back.fire()      )  { stall   := false.B   }

  diradr_arb.io.in(0).valid    := RegNext(io.dir_result.valid)
  diradr_arb.io.in(0).bits.set := RegEnable(Mux(io.dir_result.bits.hit, oldset, newset), io.dir_result.valid)
  diradr_arb.io.in(1).valid    := io.rtab.resp.valid && (io.rstatus.oneloc || swapped)
  diradr_arb.io.in(1).bits.set := Mux(Mux(io.rstatus.oneloc, io.rstatus.cloc === RTAL.LEFT, io.rstatus.nloc === RTAL.LEFT), io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)
  diradr_arb.io.in(2).valid    := io.front.valid && !block && !needSet
  diradr_arb.io.in(2).bits.set := diradr_arb.io.in(1).bits.set

  //io.front      --->  back
  back.io.enq.valid            :=  io.front.valid    && diradr_arb.io.out.valid
  back.io.enq.bits             :=  io.front.bits
  io.front.ready               :=  back.io.enq.ready && diradr_arb.io.out.valid
  //diradr_arb    --->  diradr
  diradr.io.enq.valid          :=  diradr_arb.io.out.valid
  diradr.io.enq.bits           :=  diradr_arb.io.out.bits
  //back          ---> io.back
  io.back <> back.io.deq
  io.diradr.valid              := back.io.deq.valid
  io.diradr.bits.set           := diradr.io.deq.bits.set
  diradr.io.deq.ready          := back.io.deq.ready

  io.idle  := !stall

  //ASSERT  
  when(io.back.valid) {
    assert(!back.io.enq.valid || back.io.enq.ready)
  }
}

//FrontEnd of SinkA and SinkX
class FSink[T <: Data](val gen: T, params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val front      = Decoupled(gen).flip
    val back       = Decoupled(gen)
    val hset       = Vec(2, Decoupled(UInt(width = params.setBits)))
    //for use by rempaer
    val rstatus    = new RemaperStatusIO(params.setBits).asInput  //Remaper Status
    val rtab       = new SourceSinkRandomTableIO(params.blkadrBits, params.setBits)
  }

  val back           =                Module(new Queue(io.front.bits.cloneType,       1, pipe = false, flow = true ))
  val hset           =  Seq.fill(2) { Module(new Queue(UInt(width = params.setBits),  1, pipe = false, flow = true )) }
  val address        =  Wire(UInt())
  io.front.bits match {
    case a: TLBundleA    => { address := a.address }
    case x: SinkXRequest => { address := x.address }
    case _               =>     require(false)  
  }
  val (tag, set, _)  = params.parseAddress(address)
  val blkadr         = Cat(tag, set)
  val stall          = RegInit(false.B)

  val swapped        = Wire(init = ((io.rstatus.cloc === RTAL.LEFT && io.rtab.resp.bits.lhset < io.rstatus.head) || (io.rstatus.cloc === RTAL.RIGH && io.rtab.resp.bits.rhset < io.rstatus.head)))

  //io.front      --->  io.rtab.req
  io.rtab.req.valid             := io.front.valid && !stall
  io.rtab.req.bits.blkadr       := blkadr

  //io.rtab.resp  --->  hset
  hset(0).io.enq.valid          := io.rtab.resp.valid
  hset(0).io.enq.bits           := Mux(Mux(!io.rstatus.oneloc && swapped, io.rstatus.nloc === RTAL.LEFT, io.rstatus.cloc === RTAL.LEFT), io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)
  hset(1).io.enq.valid          := io.rtab.resp.valid && !io.rstatus.oneloc && !swapped
  hset(1).io.enq.bits           := Mux(io.rstatus.nloc === RTAL.LEFT, io.rtab.resp.bits.lhset, io.rtab.resp.bits.rhset)

  //io.front      --->  back
  back.io.enq.valid             :=  io.rtab.resp.valid
  back.io.enq.bits              :=  io.front.bits
  io.front.ready                :=  io.rtab.resp.valid

  //back          ---> io.back
  io.back <> back.io.deq
  (0 to 1).map( i => {
    io.hset(i).valid            := hset(i).io.deq.valid
    io.hset(i).bits             := hset(i).io.deq.bits
    hset(i).io.deq.ready        := io.back.ready
  })


  when( io.rtab.req.fire()  )  { stall   := true.B    }
  when( io.back.fire()      )  { stall   := false.B   }

  //ASSERT
  when(io.rtab.resp.valid) {
    assert(back.io.enq.ready && hset(0).io.enq.ready && hset(1).io.enq.ready)
  }
}