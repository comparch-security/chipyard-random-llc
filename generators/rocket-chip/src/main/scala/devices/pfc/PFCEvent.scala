package freechips.rocketchip.pfc

import chisel3._
import chisel3.util._


trait PFCBundle extends Bundle {
  val name  : String
  val page  :  Int
  val dummy : Boolean
  val couws  : Seq[Int] //countes width
}

trait PFCRamBundle extends PFCBundle {
  val raml: Int 
  val ramw: Int
  lazy val couws = Seq(ramw)
  lazy val valid = Input(Bool())
  lazy val addr  = Input(UInt(log2Up(raml).W))
}

trait PFCRegBundle extends PFCBundle {
}

class SetEventPFCReg extends PFCRegBundle { //each set access/miss/eviction .....
  val dummy  = false
  val page   = 0
  val name = "CacheSetEvent"
  val couws  = (0 until 64).map(i => 64) //default all counters 60.W

  val set0   = Input(Bool())
  val set1   = Input(Bool())
  val set2   = Input(Bool())
  val set3   = Input(Bool())
  val set4   = Input(Bool())
  val set5   = Input(Bool())
  val set6   = Input(Bool())
  val set7   = Input(Bool())
  val set8   = Input(Bool())
  val set9   = Input(Bool())
  val set10  = Input(Bool())
  val set11  = Input(Bool())
  val set12  = Input(Bool())
  val set13  = Input(Bool())
  val set14  = Input(Bool())
  val set15  = Input(Bool())
  val set16  = Input(Bool())
  val set17  = Input(Bool())
  val set18  = Input(Bool())
  val set19  = Input(Bool())
  val set20  = Input(Bool())
  val set21  = Input(Bool())
  val set22  = Input(Bool())
  val set23  = Input(Bool())
  val set24  = Input(Bool())
  val set25  = Input(Bool())
  val set26  = Input(Bool())
  val set27  = Input(Bool())
  val set28  = Input(Bool())
  val set29  = Input(Bool())
  val set30  = Input(Bool())
  val set31  = Input(Bool())
  val set32  = Input(Bool())
  val set33  = Input(Bool())
  val set34  = Input(Bool())
  val set35  = Input(Bool())
  val set36  = Input(Bool())
  val set37  = Input(Bool())
  val set38  = Input(Bool())
  val set39  = Input(Bool())
  val set40  = Input(Bool())
  val set41  = Input(Bool())
  val set42  = Input(Bool())
  val set43  = Input(Bool())
  val set44  = Input(Bool())
  val set45  = Input(Bool())
  val set46  = Input(Bool())
  val set47  = Input(Bool())
  val set48  = Input(Bool())
  val set49  = Input(Bool())
  val set50  = Input(Bool())
  val set51  = Input(Bool())
  val set52  = Input(Bool())
  val set53  = Input(Bool())
  val set54  = Input(Bool())
  val set55  = Input(Bool())
  val set56  = Input(Bool())
  val set57  = Input(Bool())
  val set58  = Input(Bool())
  val set59  = Input(Bool())
  val set60  = Input(Bool())
  val set61  = Input(Bool())
  val set62  = Input(Bool())
  val set63  = Input(Bool())
}

class dummyPFCReg extends PFCRegBundle {
  val dummy  = true
  val page   = 0
  val name   = "PageX_Dummy"
  val couws  = Seq(1)

  val event  = Input(Bool())
}

class P0RocketCorePFCReg extends PFCRegBundle {
  val dummy  = false
  val page   = 0
  val name = "page0_CoreEvents"
  val couws  = (0 to 36).map(i => 60).updated(36, 50) //default all counters 60.W
  /*val word = Seq(0, 1, 31, 32, 33)
  (0 to 11).map(i  => {
    if(word.contains(i))       { couws.updated(i, 64) }
    else                       { couws.updated(i, 32) }
  })*/

  val cycle                  = Input(Bool())   //event0
  val instruction            = Input(Bool())   //event1
  val exception              = Input(Bool())   //event2
  val load                   = Input(Bool())   //event3
  val store                  = Input(Bool())   //event4
  val amo                    = Input(Bool())   //event5
  val system                 = Input(Bool())   //event6
  val arith                  = Input(Bool())   //event7
  val branch                 = Input(Bool())   //event8
  val jal                    = Input(Bool())   //event9
  val jalr                   = Input(Bool())   //event10
  //(usingMulDiv)
  val mul                    = Input(Bool())   //event11
  val div                    = Input(Bool())   //event12
  //(usingFPU)
  val fp_load                = Input(Bool())   //event13
  val fp_store               = Input(Bool())   //event14
  val fp_add                 = Input(Bool())   //event15
  val fp_mul                 = Input(Bool())   //event16
  val fp_muladd              = Input(Bool())   //event17
  val fp_divsqrt             = Input(Bool())   //event18
  val fp_other               = Input(Bool())   //event19

  val load_use_interlock     = Input(Bool())   //event20
  val long_latency_interlock = Input(Bool())   //event21
  val csr_interlock          = Input(Bool())   //event22
  val Iblocked               = Input(Bool())   //event23
  val Dblocked               = Input(Bool())   //event24
  val branch_misprediction   = Input(Bool())   //event25
  val cft_misprediction      = Input(Bool())   //event26: controlflow_target_misprediction
  val flush                  = Input(Bool())   //event27
  val replay                 = Input(Bool())   //event28
  //(usingMulDiv)
  val muldiv_interlock       = Input(Bool())   //event29
  //(usingFPU)  
  val fp_interlock           = Input(Bool())   //event30

  val Imiss                  = Input(Bool())   //event31
  val Dmiss                  = Input(Bool())   //event32
  val Drelease               = Input(Bool())   //event33
  val ITLBmiss               = Input(Bool())   //event34
  val DTLBmiss               = Input(Bool())   //event35
  val L2TLBmiss              = Input(Bool())   //event36
}

class P1RocketCorePFCReg extends PFCRegBundle {
  val name = "page1_CoreEvents"
  val dummy  = true
  val page   = 1
  val couws  = (0 to 36).map(i => 60) //default all counters 60.W

  val event0              = Input(Bool())   //event0
}


class P2FrontendPFCReg extends PFCRegBundle {
  val name = "page2_FrontendEvents"
  val dummy  = true
  val page   = 2
  val couws  = (0 to 36).map(i => 60) //default all counters 60.W

  val event0              = Input(Bool())   //event0
}

class P3L1IPFCReg extends PFCRegBundle {
  val name = "page3_L1IEvents"
  val dummy  = true
  val page   = 3
  val couws  = (0 to 36).map(i => 60) //default all counters 60.W

  val event0              = Input(Bool())   //event0
}

class P4L1DPFCReg extends PFCRegBundle {
  val name = "page4_L1DEvents"
  val dummy  = true
  val page   = 4
  val couws  = (0 to 36).map(i => 60) //default all counters 60.W

  val event0              = Input(Bool())   //event0
}

class P5MSHRPFCReg extends PFCRegBundle {
  val name = "page5_MSHREvents"
  val dummy  = true
  val page   = 4
  val couws  = (0 to 36).map(i => 60) //default all counters 60.W

  val event0              = Input(Bool())   //event0
}

// Ram page
class SetEventPFCRam(val nsets : Int = 64, val name : String = "CacheSetEvent") extends PFCRamBundle {
  val page = 0
  val dummy  = true
  val raml   = nsets
  val ramw   = 64
}


class P0L2PFCReg extends PFCRegBundle {
  val name = "page0_L2Events"
  val dummy  = true
  val page   = 1
  val couws  = (0 to 36).map(i => 60) //default all counters 60.W

  val event0              = Input(Bool())   //event0
}

class P1L2PFCReg extends PFCRegBundle {
  val name = "page1_L2Events"
  val dummy  = true
  val page   = 1
  val couws  = (0 to 36).map(i => 60) //default all counters 60.W

  val event0              = Input(Bool())   //event0
}

class TileLinkPFCReg extends PFCRegBundle {
  val name = "TileLinkEvents"
  val dummy  = false
  val page   = 1
  val couws  = (0 to 48).map(i => 60) //default all counters 60.W
  val word = Seq(0, 7, 10, 12, 19, 21, 23, 30, 32, 34, 36, 42, 44, 45, 47)
  val half = Seq(1, 2, 5)
  val bit = Seq(11, 22, 33, 43, 48)
  (0 to 11).map(i  => {
    if(word.contains(i))       { couws.updated(i, 64) }
    else if(half.contains(i))  { couws.updated(i, 32) }
    else if(bit.contains(i))   { couws.updated(i, 1)  }
    else                       { couws.updated(i, 8)  }
  })


  //a: Acquire channel
  val a_Done              = Input(Bool())     //event0
  val a_PutFullData       = Input(Bool())     //event1
  val a_PutPartialData    = Input(Bool())     //event2
  val a_ArithmeticData    = Input(Bool())     //event3
  val a_LogicalData       = Input(Bool())     //event4
  val a_Get               = Input(Bool())     //event5
  val a_Hint              = Input(Bool())     //event6
  val a_AcquireBlock      = Input(Bool())     //event7
  val a_AcquirePerm       = Input(Bool())     //event8
  val a_Blocked           = Input(Bool())     //event9
  val a_Err0              = Input(UInt(64.W)) //event10
  val a_Err1              = Input(UInt(1.W))  //event11
  //b: Probe channel
  val b_Done              = Input(Bool())     //event12
  val b_PutFullData       = Input(Bool())     //event13
  val b_PutPartialData    = Input(Bool())     //event14
  val b_ArithmeticData    = Input(Bool())     //event15
  val b_LogicalData       = Input(Bool())     //event16
  val b_Get               = Input(Bool())     //event17
  val b_Hint              = Input(Bool())     //event18
  val b_Probe             = Input(Bool())     //event19
  val b_Blocked           = Input(Bool())     //event20
  val b_Err0              = Input(UInt(64.W)) //event21
  val b_Err1              = Input(UInt(1.W))  //event22
  //c: Release channel
  val c_Done              = Input(Bool())     //event23
  val c_AccessAck         = Input(Bool())     //event24
  val c_AccessAckData     = Input(Bool())     //event25
  val c_HintAck           = Input(Bool())     //event26
  val c_ProbeAck          = Input(Bool())     //event27
  val c_ProbeAckData      = Input(Bool())     //event28
  val c_Release           = Input(Bool())     //event29
  val c_ReleaseData       = Input(Bool())     //event30
  val c_Blocked           = Input(Bool())     //event31
  val c_Err0              = Input(UInt(64.W)) //event32
  val c_Err1              = Input(UInt(1.W))  //event33
  //d: Grant channel
  val d_Done              = Input(Bool())     //event34
  val d_AccessAck         = Input(Bool())     //event35
  val d_AccessAckData     = Input(Bool())     //event36
  val d_HintAck           = Input(Bool())     //event37
  val d_Grant             = Input(Bool())     //event38
  val d_GrantData         = Input(Bool())     //event39
  val d_ReleaseAck        = Input(Bool())     //event40
  val d_Blocked           = Input(Bool())     //event41
  val d_Err0              = Input(UInt(64.W)) //event42
  val d_Err1              = Input(UInt(1.W))  //event43
  //e: Finish channel
  val e_Done              = Input(Bool())     //event44
  val e_GrantAck          = Input(Bool())     //event45
  val e_Blocked           = Input(Bool())     //event46
  val e_Err0              = Input(UInt(10.W)) //event47
  val e_Err1              = Input(UInt(1.W))  //event48
}

