package freechips.rocketchip.pfc

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._

object PFCManagerIds {
  val L2Bank0pfcIds   = Seq((8,  8)) 
  val L2Bank1pfcIds   = Seq((9,  9)) 
  val L2Bank2pfcIds   = Seq((10, 10))
  val L2Bank3pfcIds   = Seq((11, 11)) 
  val TCpfcIds        = Seq((12, 12))
  val maxIds          = 13
}

/*trait HasPFCNetImp extends LazyModuleImp  {
  implicit val p: Parameters
  val outer:   freechips.rocketchip.subsystem.BareSubsystem

  val nClients = p(freechips.rocketchip.subsystem.RocketTilesKey).length
  
  val ClientIds  = (0 until nClients).map(i => i).toList.toSeq
  val TilepfcIds = (0 until nClients).map(i => (i,i)).toList.toSeq
  val ManagerIds = TilepfcIds ++ PFCManagerIds.L2Bank0pfcIds

  //val tilespfc = tiles.map(_.pfcnode.makeSink()(p))  // in HasTiles
  val pfcnetwork = Module(new PFCNetwork(ClientIds, TilepfcIds))
  import freechips.rocketchip.subsystem.HasTiles
  if(outer.isInstanceOf[HasTiles]) {
    val tilespfc =  outer.asInstanceOf[HasTiles].tilespfc.map(_.bundle)
    (0 until tilespfc.size).foreach( i => {
      tilespfc(i).client  <> pfcnetwork.io.clients(i)
      tilespfc(i).manager <> pfcnetwork.io.managers(i)
    })                            
  }
}*/
object connect {
  import freechips.rocketchip.tilelink.{TLMessages, TLPermissions, TLAtomics, TLHints}
  import freechips.rocketchip.tilelink.{TLChannel,  TLBundle, TLBundleA,  TLBundleB, TLBundleC, TLBundleD, TLBundleE}
  import freechips.rocketchip.tilelink.TLEdge
  def connectTileLinkPFC(bundle: TLBundle, edge: TLEdge, pfc: TileLinkPFCReg) {

    var (en, i, j)  = (WireInit(false.B), 0, 0)
    val errors =  (0 until 5).map(_ => Wire(Vec(128, Bool()))) //a b c d e
    def monAssert(cond: Bool, message: String) { errors(j)(i) := RegNext(en && !cond); i=i+1 }
    def assume(cond: Bool,    message: String) { errors(j)(i) := RegNext(en && !cond); i=i+1 }
    def finalerr { require(i < 128); (i until 128).map( errors(j)(_) := false.B ) }

    def extra          = { }
    def diplomacyInfo  = { }

    def visible(address: UInt, source: UInt, edge: TLEdge) =
    edge.client.clients.map { c =>
      !c.sourceId.contains(source) ||
      c.visibility.map(_.contains(address)).reduce(_ || _)
    }.reduce(_ && _)

    // Acquire channel Error
    def get_a_Error(bundle: TLBundleA, edge: TLEdge, valid: Bool, ready: Bool) = {
      i=0; j=0;
      val source_ok  = edge.client.contains(bundle.source)
      val is_aligned = edge.isAligned(bundle.address, bundle.size)
      val mask       = edge.full_mask(bundle)
      val first      = edge.first(bundle, valid & ready)
      val opcode     = Reg(UInt())
      val param      = Reg(UInt())
      val size       = Reg(UInt())
      val source     = Reg(UInt())
      val address    = Reg(UInt())
      when (valid & ready & first) {
        opcode      := bundle.opcode
        param       := bundle.param
        size        := bundle.size
        source      := bundle.source
        address     := bundle.address
      }

      en = valid
      monAssert (TLMessages.isA(bundle.opcode), "'A' channel has invalid opcode" + extra)
      monAssert (visible(edge.address(bundle), bundle.source, edge), "'A' channel carries an address illegal for the specified bank visibility")
      en = valid && bundle.opcode === TLMessages.AcquireBlock //2 --
      monAssert (edge.master.emitsAcquireB(bundle.source, bundle.size) && edge.slave.supportsAcquireBSafe(edge.address(bundle), bundle.size), "'A' channel carries AcquireBlock type which is unexpected using diplomatic parameters" + diplomacyInfo + extra)
      monAssert (edge.master.supportsProbe(edge.source(bundle), bundle.size) && edge.slave.emitsProbeSafe(edge.address(bundle), bundle.size), "'A' channel carries AcquireBlock from a client which does not support Probe" + diplomacyInfo + extra)
      monAssert (source_ok, "'A' channel AcquireBlock carries invalid source ID" + diplomacyInfo + extra)
      monAssert (bundle.size >= log2Ceil(edge.manager.beatBytes).U, "'A' channel AcquireBlock smaller than a beat" + extra)
      monAssert (is_aligned, "'A' channel AcquireBlock address not aligned to size" + extra)
      monAssert (TLPermissions.isGrow(bundle.param), "'A' channel AcquireBlock carries invalid grow param" + extra)
      monAssert (~bundle.mask === 0.U, "'A' channel AcquireBlock contains invalid mask" + extra)
      monAssert (!bundle.corrupt, "'A' channel AcquireBlock is corrupt" + extra)
      en =  valid && bundle.opcode === TLMessages.AcquirePerm //10 --
      monAssert (edge.master.emitsAcquireB(bundle.source, bundle.size) && edge.slave.supportsAcquireBSafe(edge.address(bundle), bundle.size), "'A' channel carries AcquirePerm type which is unexpected using diplomatic parameters" + diplomacyInfo + extra)
      monAssert (edge.master.supportsProbe(edge.source(bundle), bundle.size) && edge.slave.emitsProbeSafe(edge.address(bundle), bundle.size), "'A' channel carries AcquirePerm from a client which does not support Probe" + diplomacyInfo + extra)
      monAssert (source_ok, "'A' channel AcquirePerm carries invalid source ID" + diplomacyInfo + extra)
      monAssert (bundle.size >= log2Ceil(edge.manager.beatBytes).U, "'A' channel AcquirePerm smaller than a beat" + extra)
      monAssert (is_aligned, "'A' channel AcquirePerm address not aligned to size" + extra)
      monAssert (TLPermissions.isGrow(bundle.param), "'A' channel AcquirePerm carries invalid grow param" + extra)
      monAssert (bundle.param =/= TLPermissions.NtoB, "'A' channel AcquirePerm requests NtoB" + extra)
      monAssert (~bundle.mask === 0.U, "'A' channel AcquirePerm contains invalid mask" + extra)
      monAssert (!bundle.corrupt, "'A' channel AcquirePerm is corrupt" + extra)
      en =  valid && bundle.opcode === TLMessages.Get //19 --
      monAssert (edge.master.emitsGet(bundle.source, bundle.size), "'A' channel carries Get type which master claims it can't emit" + diplomacyInfo + extra)
      monAssert (edge.slave.supportsGetSafe(edge.address(bundle), bundle.size, None), "'A' channel carries Get type which slave claims it can't support" + diplomacyInfo + extra)
      monAssert (source_ok, "'A' channel Get carries invalid source ID" + diplomacyInfo + extra)
      monAssert (is_aligned, "'A' channel Get address not aligned to size" + extra)
      monAssert (bundle.param === 0.U, "'A' channel Get carries invalid param" + extra)
      monAssert (bundle.mask === mask, "'A' channel Get contains invalid mask" + extra)
      monAssert (!bundle.corrupt, "'A' channel Get is corrupt" + extra)
      en =  valid && bundle.opcode === TLMessages.PutFullData //26 --
      monAssert (edge.master.emitsPutFull(bundle.source, bundle.size) && edge.slave.supportsPutFullSafe(edge.address(bundle), bundle.size), "'A' channel carries PutFull type which is unexpected using diplomatic parameters" + diplomacyInfo + extra)
      monAssert (source_ok, "'A' channel PutFull carries invalid source ID" + diplomacyInfo + extra)
      monAssert (is_aligned, "'A' channel PutFull address not aligned to size" + extra)
      monAssert (bundle.param === 0.U, "'A' channel PutFull carries invalid param" + extra)
      monAssert (bundle.mask === mask, "'A' channel PutFull contains invalid mask" + extra)
      en =  valid && bundle.opcode === TLMessages.PutPartialData //31 --
      monAssert (edge.master.emitsPutPartial(bundle.source, bundle.size) && edge.slave.supportsPutPartialSafe(edge.address(bundle), bundle.size), "'A' channel carries PutPartial type which is unexpected using diplomatic parameters" + extra)
      monAssert (source_ok, "'A' channel PutPartial carries invalid source ID" + diplomacyInfo + extra)
      monAssert (is_aligned, "'A' channel PutPartial address not aligned to size" + extra)
      monAssert (bundle.param === 0.U, "'A' channel PutPartial carries invalid param" + extra)
      monAssert ((bundle.mask & ~mask) === 0.U, "'A' channel PutPartial contains invalid mask" + extra)
      en =  valid && bundle.opcode === TLMessages.ArithmeticData //36 --
      monAssert (edge.master.emitsArithmetic(bundle.source, bundle.size) && edge.slave.supportsArithmeticSafe(edge.address(bundle), bundle.size), "'A' channel carries Arithmetic type which is unexpected using diplomatic parameters" + extra)
      monAssert (source_ok, "'A' channel Arithmetic carries invalid source ID" + diplomacyInfo + extra)
      monAssert (is_aligned, "'A' channel Arithmetic address not aligned to size" + extra)
      monAssert (TLAtomics.isArithmetic(bundle.param), "'A' channel Arithmetic carries invalid opcode param" + extra)
      monAssert (bundle.mask === mask, "'A' channel Arithmetic contains invalid mask" + extra)
      en =  valid && bundle.opcode === TLMessages.LogicalData //41 --
      monAssert (edge.master.emitsLogical(bundle.source, bundle.size) && edge.slave.supportsLogicalSafe(edge.address(bundle), bundle.size), "'A' channel carries Logical type which is unexpected using diplomatic parameters" + extra)
      monAssert (source_ok, "'A' channel Logical carries invalid source ID" + diplomacyInfo + extra)
      monAssert (is_aligned, "'A' channel Logical address not aligned to size" + extra)
      monAssert (TLAtomics.isLogical(bundle.param), "'A' channel Logical carries invalid opcode param" + extra)
      monAssert (bundle.mask === mask, "'A' channel Logical contains invalid mask" + extra)
      en =  valid && bundle.opcode === TLMessages.Hint //45 --
      monAssert (edge.master.emitsHint(bundle.source, bundle.size) && edge.slave.supportsHintSafe(edge.address(bundle), bundle.size), "'A' channel carries Hint type which is unexpected using diplomatic parameters" + extra)
      monAssert (source_ok, "'A' channel Hint carries invalid source ID" + diplomacyInfo + extra)
      monAssert (is_aligned, "'A' channel Hint address not aligned to size" + extra)
      monAssert (TLHints.isHints(bundle.param), "'A' channel Hint carries invalid opcode param" + extra)
      monAssert (bundle.mask === mask, "'A' channel Hint contains invalid mask" + extra)
      monAssert (!bundle.corrupt, "'A' channel Hint is corrupt" + extra)
      en =  valid && !first                  //51 --
      monAssert (bundle.opcode === opcode, "'A' channel opcode changed within multibeat operation" + extra)
      monAssert (bundle.param  === param,  "'A' channel param changed within multibeat operation" + extra)
      monAssert (bundle.size   === size,   "'A' channel size changed within multibeat operation" + extra)
      monAssert (bundle.source === source, "'A' channel source changed within multibeat operation" + extra)
      monAssert (bundle.address=== address,"'A' channel address changed with multibeat operation" + extra)
      //56 --

      finalerr
      errors(j).asUInt
    }

    def get_b_Error(bundle: TLBundleB, edge: TLEdge, valid: Bool, ready: Bool) = {
      i=0; j=1;
      val address_ok   = edge.manager.containsSafe(edge.address(bundle))
      val is_aligned   = edge.isAligned(bundle.address, bundle.size)
      val mask         = edge.full_mask(bundle)
      val legal_source = Mux1H(edge.client.find(bundle.source), edge.client.clients.map(c => c.sourceId.start.U)) === bundle.source
      val first        = edge.first(bundle, valid & ready)
      val opcode       = Reg(UInt())
      val param        = Reg(UInt())
      val size         = Reg(UInt())
      val source       = Reg(UInt())
      val address      = Reg(UInt())
      when (valid & ready & first) {
        opcode        := bundle.opcode
        param         := bundle.param
        size          := bundle.size
        source        := bundle.source
        address       := bundle.address
      }

      en = valid
      monAssert (TLMessages.isB(bundle.opcode), "'B' channel has invalid opcode" + extra)
      monAssert (visible(edge.address(bundle), bundle.source, edge), "'B' channel carries an address illegal for the specified bank visibility")
      en =  valid && bundle.opcode === TLMessages.Probe //2 --
      monAssert (edge.master.supportsProbe(edge.source(bundle), bundle.size) && edge.slave.emitsProbeSafe(edge.address(bundle), bundle.size), "'B' channel carries Probe type which is unexpected using diplomatic parameters" + extra)
      monAssert (address_ok, "'B' channel Probe carries unmanaged address" + extra)
      monAssert (legal_source, "'B' channel Probe carries source that is not first source" + extra)
      monAssert (is_aligned, "'B' channel Probe address not aligned to size" + extra)
      monAssert (TLPermissions.isCap(bundle.param), "'B' channel Probe carries invalid cap param" + extra)
      monAssert (bundle.mask === mask, "'B' channel Probe contains invalid mask" + extra)
      monAssert (!bundle.corrupt, "'B' channel Probe is corrupt" + extra)
      en =  valid && bundle.opcode === TLMessages.Get //9 --
      monAssert (edge.master.supportsPutFull(edge.source(bundle), bundle.size) && edge.slave.emitsPutFullSafe(edge.address(bundle), bundle.size), "'B' channel carries Get type which is unexpected using diplomatic parameters" + extra)
      monAssert (address_ok, "'B' channel Get carries unmanaged address" + extra)
      monAssert (legal_source, "'B' channel Get carries source that is not first source" + extra)
      monAssert (is_aligned, "'B' channel Get address not aligned to size" + extra)
      monAssert (bundle.param === 0.U, "'B' channel Get carries invalid param" + extra)
      monAssert (bundle.mask === mask, "'B' channel Get contains invalid mask" + extra)
      monAssert (!bundle.corrupt, "'B' channel Get is corrupt" + extra)
      en =  valid &&bundle.opcode === TLMessages.PutFullData //16 --
      monAssert (edge.master.supportsPutFull(edge.source(bundle), bundle.size) && edge.slave.emitsPutFullSafe(edge.address(bundle), bundle.size), "'B' channel carries PutFull type which is unexpected using diplomatic parameters" + extra)
      monAssert (address_ok, "'B' channel PutFull carries unmanaged address" + extra)
      monAssert (legal_source, "'B' channel PutFull carries source that is not first source" + extra)
      monAssert (is_aligned, "'B' channel PutFull address not aligned to size" + extra)
      monAssert (bundle.param === 0.U, "'B' channel PutFull carries invalid param" + extra)
      monAssert (bundle.mask === mask, "'B' channel PutFull contains invalid mask" + extra)
      en =  valid &&bundle.opcode === TLMessages.PutPartialData //22 --
      monAssert (edge.master.supportsPutPartial(edge.source(bundle), bundle.size) && edge.slave.emitsPutPartialSafe(edge.address(bundle), bundle.size), "'B' channel carries PutPartial type which is unexpected using diplomatic parameters" + extra)
      monAssert (address_ok, "'B' channel PutPartial carries unmanaged address" + extra)
      monAssert (legal_source, "'B' channel PutPartial carries source that is not first source" + extra)
      monAssert (is_aligned, "'B' channel PutPartial address not aligned to size" + extra)
      monAssert (bundle.param === 0.U, "'B' channel PutPartial carries invalid param" + extra)
      monAssert ((bundle.mask & ~mask) === 0.U, "'B' channel PutPartial contains invalid mask" + extra)
      en =  valid && bundle.opcode === TLMessages.ArithmeticData //28 --
      monAssert (edge.master.supportsArithmetic(edge.source(bundle), bundle.size) && edge.slave.emitsArithmeticSafe(edge.address(bundle), bundle.size), "'B' channel carries Arithmetic type unsupported by master" + extra)
      monAssert (address_ok, "'B' channel Arithmetic carries unmanaged address" + extra)
      monAssert (legal_source, "'B' channel Arithmetic carries source that is not first source" + extra)
      monAssert (is_aligned, "'B' channel Arithmetic address not aligned to size" + extra)
      monAssert (TLAtomics.isArithmetic(bundle.param), "'B' channel Arithmetic carries invalid opcode param" + extra)
      monAssert (bundle.mask === mask, "'B' channel Arithmetic contains invalid mask" + extra)
      en =  valid && bundle.opcode === TLMessages.LogicalData //34 --
      monAssert (edge.master.supportsLogical(edge.source(bundle), bundle.size) && edge.slave.emitsLogicalSafe(edge.address(bundle), bundle.size), "'B' channel carries Logical type unsupported by client" + extra)
      monAssert (address_ok, "'B' channel Logical carries unmanaged address" + extra)
      monAssert (legal_source, "'B' channel Logical carries source that is not first source" + extra)
      monAssert (is_aligned, "'B' channel Logical address not aligned to size" + extra)
      monAssert (TLAtomics.isLogical(bundle.param), "'B' channel Logical carries invalid opcode param" + extra)
      monAssert (bundle.mask === mask, "'B' channel Logical contains invalid mask" + extra)
      en =  valid && bundle.opcode === TLMessages.Hint //40 --
      monAssert (edge.master.supportsHint(edge.source(bundle), bundle.size) && edge.slave.emitsHintSafe(edge.address(bundle), bundle.size), "'B' channel carries Hint type unsupported by client" + extra)
      monAssert (address_ok, "'B' channel Hint carries unmanaged address" + extra)
      monAssert (legal_source, "'B' channel Hint carries source that is not first source" + extra)
      monAssert (is_aligned, "'B' channel Hint address not aligned to size" + extra)
      monAssert (bundle.mask === mask, "'B' channel Hint contains invalid mask" + extra)
      monAssert (!bundle.corrupt, "'B' channel Hint is corrupt" + extra)
      en =  valid && !first //46 --
      monAssert (bundle.opcode === opcode, "'B' channel opcode changed within multibeat operation" + extra)
      monAssert (bundle.param  === param,  "'B' channel param changed within multibeat operation" + extra)
      monAssert (bundle.size   === size,   "'B' channel size changed within multibeat operation" + extra)
      monAssert (bundle.source === source, "'B' channel source changed within multibeat operation" + extra)
      monAssert (bundle.address=== address,"'B' channel addresss changed with multibeat operation" + extra)
      //51 --

      finalerr
      errors(j).asUInt
    }

    def get_c_Error(bundle: TLBundleC, edge: TLEdge, valid: Bool, ready: Bool) = {
      i=0; j=2;
      val address_ok   = edge.manager.containsSafe(edge.address(bundle))
      val is_aligned   = edge.isAligned(bundle.address, bundle.size)
      val source_ok    = edge.client.contains(bundle.source)
      val legal_source = Mux1H(edge.client.find(bundle.source), edge.client.clients.map(c => c.sourceId.start.U)) === bundle.source
      val first        = edge.first(bundle, valid & ready)
      val opcode       = Reg(UInt())
      val param        = Reg(UInt())
      val size         = Reg(UInt())
      val source       = Reg(UInt())
      val address      = Reg(UInt())
      when (valid & ready & first) {
        opcode        := bundle.opcode
        param         := bundle.param
        size          := bundle.size
        source        := bundle.source
        address       := bundle.address
      }

      en = valid
      monAssert (TLMessages.isC(bundle.opcode), "'C' channel has invalid opcode" + extra)
      en =  valid && bundle.opcode === TLMessages.ProbeAck //1 --
      monAssert (address_ok, "'C' channel ProbeAck carries unmanaged address" + extra)
      monAssert (source_ok, "'C' channel ProbeAck carries invalid source ID" + extra)
      monAssert (bundle.size >= log2Ceil(edge.manager.beatBytes).U, "'C' channel ProbeAck smaller than a beat" + extra)
      monAssert (is_aligned, "'C' channel ProbeAck address not aligned to size" + extra)
      monAssert (TLPermissions.isReport(bundle.param), "'C' channel ProbeAck carries invalid report param" + extra)
      monAssert (!bundle.corrupt, "'C' channel ProbeAck is corrupt" + extra)
      en =  valid && bundle.opcode === TLMessages.ProbeAckData  //7 --
      monAssert (address_ok, "'C' channel ProbeAckData carries unmanaged address" + extra)
      monAssert (source_ok, "'C' channel ProbeAckData carries invalid source ID" + extra)
      monAssert (bundle.size >= log2Ceil(edge.manager.beatBytes).U, "'C' channel ProbeAckData smaller than a beat" + extra)
      monAssert (is_aligned, "'C' channel ProbeAckData address not aligned to size" + extra)
      monAssert (TLPermissions.isReport(bundle.param), "'C' channel ProbeAckData carries invalid report param" + extra)
      en =  valid && bundle.opcode === TLMessages.Release  //12 --
      monAssert (edge.master.emitsAcquireB(edge.source(bundle), bundle.size) && edge.slave.supportsAcquireBSafe(edge.address(bundle), bundle.size), "'C' channel carries Release type unsupported by manager" + extra)
      monAssert (edge.master.supportsProbe(edge.source(bundle), bundle.size) && edge.slave.emitsProbeSafe(edge.address(bundle), bundle.size), "'C' channel carries Release from a client which does not support Probe" + extra)
      monAssert (source_ok, "'C' channel Release carries invalid source ID" + extra)
      monAssert (bundle.size >= log2Ceil(edge.manager.beatBytes).U, "'C' channel Release smaller than a beat" + extra)
      monAssert (is_aligned, "'C' channel Release address not aligned to size" + extra)
      monAssert (TLPermissions.isReport(bundle.param), "'C' channel Release carries invalid report param" + extra)
      monAssert (!bundle.corrupt, "'C' channel Release is corrupt" + extra)
      en =  valid && bundle.opcode === TLMessages.ReleaseData  //19 --
      monAssert (edge.master.emitsAcquireB(edge.source(bundle), bundle.size) && edge.slave.supportsAcquireBSafe(edge.address(bundle), bundle.size), "'C' channel carries ReleaseData type unsupported by manager" + extra)
      monAssert (edge.master.supportsProbe(edge.source(bundle), bundle.size) && edge.slave.emitsProbeSafe(edge.address(bundle), bundle.size), "'C' channel carries Release from a client which does not support Probe" + extra)
      monAssert (source_ok, "'C' channel ReleaseData carries invalid source ID" + extra)
      monAssert (bundle.size >= log2Ceil(edge.manager.beatBytes).U, "'C' channel ReleaseData smaller than a beat" + extra)
      monAssert (is_aligned, "'C' channel ReleaseData address not aligned to size" + extra)
      monAssert (TLPermissions.isReport(bundle.param), "'C' channel ReleaseData carries invalid report param" + extra)
      en =  valid && bundle.opcode === TLMessages.AccessAck  //25 --
      monAssert (address_ok, "'C' channel AccessAck carries unmanaged address" + extra)
      monAssert (source_ok, "'C' channel AccessAck carries invalid source ID" + extra)
      monAssert (is_aligned, "'C' channel AccessAck address not aligned to size" + extra)
      monAssert (bundle.param === 0.U, "'C' channel AccessAck carries invalid param" + extra)
      monAssert (!bundle.corrupt, "'C' channel AccessAck is corrupt" + extra)
      en =  valid && bundle.opcode === TLMessages.AccessAckData  //30 --
      monAssert (address_ok, "'C' channel AccessAckData carries unmanaged address" + extra)
      monAssert (source_ok, "'C' channel AccessAckData carries invalid source ID" + extra)
      monAssert (is_aligned, "'C' channel AccessAckData address not aligned to size" + extra)
      monAssert (bundle.param === 0.U, "'C' channel AccessAckData carries invalid param" + extra)
      en =  valid && bundle.opcode === TLMessages.HintAck  //34 --
      monAssert (address_ok, "'C' channel HintAck carries unmanaged address" + extra)
      monAssert (source_ok, "'C' channel HintAck carries invalid source ID" + extra)
      monAssert (is_aligned, "'C' channel HintAck address not aligned to size" + extra)
      monAssert (bundle.param === 0.U, "'C' channel HintAck carries invalid param" + extra)
      monAssert (!bundle.corrupt, "'C' channel HintAck is corrupt" + extra)
      en =   valid && !first  //39 --
      monAssert (bundle.opcode === opcode, "'C' channel opcode changed within multibeat operation" + extra)
      monAssert (bundle.param  === param,  "'C' channel param changed within multibeat operation" + extra)
      monAssert (bundle.size   === size,   "'C' channel size changed within multibeat operation" + extra)
      monAssert (bundle.source === source, "'C' channel source changed within multibeat operation" + extra)
      monAssert (bundle.address=== address,"'C' channel address changed with multibeat operation" + extra)
      //44 --

      finalerr
      errors(j).asUInt
    }


    def get_d_Error(bundle: TLBundleD, edge: TLEdge, valid: Bool, ready: Bool) = {
      i=0; j=3;
      val source_ok    = edge.client.contains(bundle.source)
      val sink_ok      = bundle.sink < edge.manager.endSinkId.U
      val deny_put_ok  = edge.manager.mayDenyPut.B
      val deny_get_ok  = edge.manager.mayDenyGet.B
      val first        = edge.first(bundle, valid & ready)
      val opcode  = Reg(UInt())
      val param   = Reg(UInt())
      val size    = Reg(UInt())
      val source  = Reg(UInt())
      val sink    = Reg(UInt())
      val denied  = Reg(Bool())
      when (valid & ready & first) {
        opcode    := bundle.opcode
        param     := bundle.param
        size      := bundle.size
        source    := bundle.source
        sink      := bundle.sink
        denied    := bundle.denied
      }

      en = valid
      assume (TLMessages.isD(bundle.opcode), "'D' channel has invalid opcode" + extra)
      en =  valid && bundle.opcode === TLMessages.ReleaseAck //1 --
      assume (source_ok, "'D' channel ReleaseAck carries invalid source ID" + extra)
      assume (bundle.size >= log2Ceil(edge.manager.beatBytes).U, "'D' channel ReleaseAck smaller than a beat" + extra)
      assume (bundle.param === 0.U, "'D' channel ReleaseeAck carries invalid param" + extra)
      assume (!bundle.corrupt, "'D' channel ReleaseAck is corrupt" + extra)
      assume (!bundle.denied, "'D' channel ReleaseAck is denied" + extra)
      en =  valid && bundle.opcode === TLMessages.Grant //6 --
      assume (source_ok, "'D' channel Grant carries invalid source ID" + extra)
      assume (sink_ok, "'D' channel Grant carries invalid sink ID" + extra)
      assume (bundle.size >= log2Ceil(edge.manager.beatBytes).U, "'D' channel Grant smaller than a beat" + extra)
      assume (TLPermissions.isCap(bundle.param), "'D' channel Grant carries invalid cap param" + extra)
      assume (bundle.param =/= TLPermissions.toN, "'D' channel Grant carries toN param" + extra)
      assume (!bundle.corrupt, "'D' channel Grant is corrupt" + extra)
      assume (deny_put_ok || !bundle.denied, "'D' channel Grant is denied" + extra)
      en =  valid && bundle.opcode === TLMessages.GrantData //13 --
      assume (source_ok, "'D' channel GrantData carries invalid source ID" + extra)
      assume (sink_ok, "'D' channel GrantData carries invalid sink ID" + extra)
      assume (bundle.size >= log2Ceil(edge.manager.beatBytes).U, "'D' channel GrantData smaller than a beat" + extra)
      assume (TLPermissions.isCap(bundle.param), "'D' channel GrantData carries invalid cap param" + extra)
      assume (bundle.param =/= TLPermissions.toN, "'D' channel GrantData carries toN param" + extra)
      assume (!bundle.denied || bundle.corrupt, "'D' channel GrantData is denied but not corrupt" + extra)
      assume (deny_get_ok || !bundle.denied, "'D' channel GrantData is denied" + extra)
      en =  valid && bundle.opcode === TLMessages.AccessAck //20 --
      assume (source_ok, "'D' channel AccessAck carries invalid source ID" + extra)
      // size is ignored
      assume (bundle.param === 0.U, "'D' channel AccessAck carries invalid param" + extra)
      assume (!bundle.corrupt, "'D' channel AccessAck is corrupt" + extra)
      assume (deny_put_ok || !bundle.denied, "'D' channel AccessAck is denied" + extra)
      en =  valid && bundle.opcode === TLMessages.AccessAckData //24 --
      assume (source_ok, "'D' channel AccessAckData carries invalid source ID" + extra)
      // size is ignored
      assume (bundle.param === 0.U, "'D' channel AccessAckData carries invalid param" + extra)
      assume (!bundle.denied || bundle.corrupt, "'D' channel AccessAckData is denied but not corrupt" + extra)
      assume (deny_get_ok || !bundle.denied, "'D' channel AccessAckData is denied" + extra)
      en =  valid && bundle.opcode === TLMessages.HintAck //28 --
      assume (source_ok, "'D' channel HintAck carries invalid source ID" + extra)
      // size is ignored
      assume (bundle.param === 0.U, "'D' channel HintAck carries invalid param" + extra)
      assume (!bundle.corrupt, "'D' channel HintAck is corrupt" + extra)
      assume (deny_put_ok || !bundle.denied, "'D' channel HintAck is denied" + extra)
      en =   valid && !first //32 --
      assume (bundle.opcode === opcode, "'D' channel opcode changed within multibeat operation" + extra)
      assume (bundle.param  === param,  "'D' channel param changed within multibeat operation" + extra)
      assume (bundle.size   === size,   "'D' channel size changed within multibeat operation" + extra)
      assume (bundle.source === source, "'D' channel source changed within multibeat operation" + extra)
      assume (bundle.sink   === sink,   "'D' channel sink changed with multibeat operation" + extra)
      assume (bundle.denied === denied, "'D' channel denied changed with multibeat operation" + extra)
     //38 --

      finalerr
      errors(j).asUInt
    }

    def get_e_Error(bundle: TLBundleE, edge: TLEdge, valid: Bool, ready: Bool) = {
      i=0; j=4;
      val sink_ok = bundle.sink < edge.manager.endSinkId.U
      en =  valid
      monAssert (sink_ok, "'E' channels carries invalid sink ID" + extra)

      finalerr
      errors(j).asUInt
    }

    def get_Error(bundle: TLChannel, edge: TLEdge, valid: Bool, ready: Bool) = {
      bundle match {
        case _:TLBundleA => get_a_Error(RegNext(bundle.asInstanceOf[TLBundleA]), edge, RegNext(valid), RegNext(ready))
        case _:TLBundleB => get_b_Error(RegNext(bundle.asInstanceOf[TLBundleB]), edge, RegNext(valid), RegNext(ready))
        case _:TLBundleC => get_c_Error(RegNext(bundle.asInstanceOf[TLBundleC]), edge, RegNext(valid), RegNext(ready))
        case _:TLBundleD => get_d_Error(RegNext(bundle.asInstanceOf[TLBundleD]), edge, RegNext(valid), RegNext(ready))
        case _:TLBundleE => get_e_Error(RegNext(bundle.asInstanceOf[TLBundleE]), edge, RegNext(valid), RegNext(ready))
      }
    }

    val a_done = RegNext(edge.done(bundle.a))
    val a_op   = RegNext(bundle.a.bits.opcode)
    val a_err  = get_Error(bundle.a.bits, edge, bundle.a.valid, bundle.a.ready)
    val d_done = RegNext(edge.done(bundle.d))
    val d_op   = RegNext(bundle.d.bits.opcode)
    val d_err  = get_Error(bundle.d.bits, edge, bundle.d.valid, bundle.d.ready)
    pfc.a_Done                :=  a_done                                                   //event0: a_Done
    pfc.a_PutFullData         :=  a_done          && (a_op === TLMessages.PutFullData)     //event1
    pfc.a_PutPartialData      :=  a_done          && (a_op === TLMessages.PutPartialData)  //event2
    pfc.a_ArithmeticData      :=  a_done          && (a_op === TLMessages.ArithmeticData)  //event3
    pfc.a_LogicalData         :=  a_done          && (a_op === TLMessages.LogicalData)     //event4
    pfc.a_Get                 :=  a_done          && (a_op === TLMessages.Get)             //event5
    pfc.a_Hint                :=  a_done          && (a_op === TLMessages.Hint)            //event6
    pfc.a_AcquireBlock        :=  a_done          && (a_op === TLMessages.AcquireBlock)    //event7
    pfc.a_AcquirePerm         :=  a_done          && (a_op === TLMessages.AcquirePerm)     //event8
    pfc.a_Blocked             :=  bundle.a.valid  && !bundle.a.ready                       //event9
    pfc.a_Err0                :=  a_err(63,  0)                                            //event10
    pfc.a_Err1                :=  a_err(127,64)                                            //event11
    pfc.d_Done                :=  d_done                                                   //event34: d_Done
    pfc.d_AccessAck           :=  d_done          && (d_op === TLMessages.AccessAck)       //event35
    pfc.d_AccessAckData       :=  d_done          && (d_op === TLMessages.AccessAckData)   //event36
    pfc.d_HintAck             :=  d_done          && (d_op === TLMessages.HintAck)         //event37
    pfc.d_Grant               :=  d_done          && (d_op === TLMessages.Grant)           //event38
    pfc.d_GrantData           :=  d_done          && (d_op === TLMessages.GrantData)       //event39
    pfc.d_ReleaseAck          :=  d_done          && (d_op === TLMessages.ReleaseAck)      //event40
    pfc.d_Blocked             :=  bundle.d.valid  && !bundle.d.ready                       //event41
    pfc.d_Err0                :=  d_err(63,  0)                                            //event10
    pfc.d_Err1                :=  d_err(127,64)                                            //event11
    if(bundle.params.hasBCE) {
      val b_done = RegNext(edge.done(bundle.b))
      val b_op   = RegNext(bundle.b.bits.opcode)
      val b_err  = get_Error(bundle.b.bits, edge, bundle.b.valid, bundle.b.ready)
      val c_done = RegNext(edge.done(bundle.c))
      val c_op   = RegNext(bundle.c.bits.opcode)
      val c_err  = get_Error(bundle.c.bits, edge, bundle.c.valid, bundle.c.ready)
      val e_err  = get_Error(bundle.e.bits, edge, bundle.e.valid, bundle.e.ready)
      pfc.b_Done              := b_done                                                    //event12: b_done
      pfc.b_PutFullData       := b_done         && (b_op === TLMessages.PutFullData)       //event13
      pfc.b_PutPartialData    := b_done         && (b_op === TLMessages.PutPartialData)    //event14
      pfc.b_ArithmeticData    := b_done         && (b_op === TLMessages.ArithmeticData)    //event15
      pfc.b_LogicalData       := b_done         && (b_op === TLMessages.LogicalData)       //event16
      pfc.b_Get               := b_done         && (b_op === TLMessages.Get)               //event17
      pfc.b_Hint              := b_done         && (b_op === TLMessages.Hint)              //event18
      pfc.b_Probe             := b_done         && (b_op === TLMessages.Probe)             //event19
      pfc.b_Blocked           := bundle.b.valid && !bundle.b.ready                         //event20
      pfc.b_Err0              := b_err(63,  0)                                             //event21
      pfc.b_Err1              := b_err(127,64)                                             //event22
      pfc.c_Done              := c_done                                                    //event23: c_Done
      pfc.c_AccessAck         := c_done         && (c_op === TLMessages.AccessAck)         //event24
      pfc.c_AccessAckData     := c_done         && (c_op === TLMessages.AccessAckData)     //event25
      pfc.c_HintAck           := c_done         && (c_op === TLMessages.HintAck)           //event26
      pfc.c_ProbeAck          := c_done         && (c_op === TLMessages.ProbeAck)          //event27
      pfc.c_ProbeAckData      := c_done         && (c_op === TLMessages.ProbeAckData)      //event28
      pfc.c_Release           := c_done         && (c_op === TLMessages.Release)           //event29
      pfc.c_ReleaseData       := c_done         && (c_op === TLMessages.ReleaseData)       //event30
      pfc.c_Blocked           := bundle.c.valid && !bundle.c.ready                         //event31
      pfc.c_Err0              := c_err(63,  0)                                             //event32
      pfc.c_Err1              := c_err(127,64)                                             //event33
      pfc.e_Done              := edge.done(bundle.e)                                       //event44: e_Done
      pfc.e_GrantAck          := edge.done(bundle.e)                                       //event45
      pfc.e_Blocked           := bundle.e.valid && !bundle.e.ready                         //event46
      pfc.e_Err0              := e_err(63,  0)                                             //event47
      pfc.e_Err1              := e_err(127,64)                                             //event48
    } else {
      pfc.b_Done              := false.B  //event12
      pfc.b_PutFullData       := false.B  //event13
      pfc.b_PutPartialData    := false.B  //event14
      pfc.b_ArithmeticData    := false.B  //event15
      pfc.b_LogicalData       := false.B  //event16
      pfc.b_Get               := false.B  //event17
      pfc.b_Hint              := false.B  //event18
      pfc.b_Probe             := false.B  //event19
      pfc.b_Blocked           := false.B  //event20
      pfc.b_Err0              := 0.U      //event21
      pfc.b_Err1              := 0.U      //event22
      pfc.c_Done              := false.B  //event23
      pfc.c_AccessAck         := false.B  //event24
      pfc.c_AccessAckData     := false.B  //event25
      pfc.c_HintAck           := false.B  //event26
      pfc.c_ProbeAck          := false.B  //event27
      pfc.c_ProbeAckData      := false.B  //event28
      pfc.c_Release           := false.B  //event29
      pfc.c_ReleaseData       := false.B  //event30
      pfc.c_Blocked           := false.B  //event31
      pfc.c_Err0              := 0.U      //event32
      pfc.c_Err1              := 0.U      //event33
      pfc.e_Done              := false.B  //event44
      pfc.e_GrantAck          := false.B  //event45
      pfc.e_Blocked           := false.B  //event46
      pfc.e_Err0              := 0.U      //event47
      pfc.e_Err1              := 0.U      //event48
    }
  }

}


trait HasPFCClient { this: freechips.rocketchip.rocket.CSRFile =>
  val pfcclient = Module(new CSRPFCClient(hartId))
  def connectPFC = {
    io.pfcclient <> pfcclient.io.client
    pfcclient.io.access.addr       := io.rw.addr
    pfcclient.io.access.cmd        := io.rw.cmd
    pfcclient.io.access.wdata      := io.rw.wdata
    pfcclient.io.access.retire     := io.retire.asBool()
    pfcclient.io.access.interrupt  := false.B
  }
}

trait HasL1IPFC { this: freechips.rocketchip.rocket.ICacheModule =>
  def connectPFC = {
    val missSetIdx = (refill_paddr >> blockOffBits)(log2Up(nSets)-1, 0)
    io.pfcupdate.setmiss.valid := refill_fire
    io.pfcupdate.setmiss.addr  := missSetIdx
 }
}

trait HasL1DPFC { this: freechips.rocketchip.rocket.NonBlockingDCacheModule =>
  def connectPFC = {
    val missSetIdx  = WireInit((tl_out.a.bits.address >> blockOffBits)(log2Up(nSets)-1, 0))
    val wbSetaddr   = WireInit((tl_out.c.bits.address >> blockOffBits)(log2Up(nSets)-1, 0))
    io.pfcupdate.setmiss.valid := edge.done(tl_out.a)
    io.pfcupdate.setmiss.addr  := missSetIdx
    io.pfcupdate.setev.valid := edge.done(tl_out.c)
    io.pfcupdate.setev.addr  := wbSetaddr
 }
}

trait HasRocketCorePFC { this: freechips.rocketchip.rocket.Rocket =>
  def connectPFC = {
    import freechips.rocketchip.rocket.{ ALU, CSR }
    //import freechips.rocketchip.util  // no effect ??? can't use isOneOf
    //import freechips.rocketchip.rocket.constants.MemoryOpConstants  //no effect ??  can't  use M_XLR  M_XSC

   //copy from MemoryOpConstants
   //trait MemoryOpConstants {
    def M_XRD        = ("b00000").U // int load
    def M_XWR        = ("b00001").U // int store
    def M_PFR        = ("b00010").U // prefetch with intent to read
    def M_PFW        = ("b00011").U // prefetch with intent to write
    def M_XA_SWAP    = ("b00100").U
    def M_FLUSH_ALL  = ("b00101").U // flush all lines
    def M_XLR        = ("b00110").U
    def M_XSC        = ("b00111").U
    def M_XA_ADD     = ("b01000").U
    def M_XA_XOR     = ("b01001").U
    def M_XA_OR      = ("b01010").U
    def M_XA_AND     = ("b01011").U
    def M_XA_MIN     = ("b01100").U
    def M_XA_MAX     = ("b01101").U
    def M_XA_MINU    = ("b01110").U
    def M_XA_MAXU    = ("b01111").U
    def M_FLUSH      = ("b10000").U // write back dirty data and cede R/W permissions
    def M_PWR        = ("b10001").U // partial (masked) store
    def M_PRODUCE    = ("b10010").U // write back dirty data and cede W permissions
    def M_CLEAN      = ("b10011").U // write back dirty data and retain R/W permissions
    def M_SFENCE     = ("b10100").U // flush TLB
    def M_WOK        = ("b10111").U // check write permissions but don't perform a write

    def isAMOLogical(cmd: UInt) =  Seq(M_XA_SWAP, M_XA_XOR, M_XA_OR, M_XA_AND).map(_ === cmd).reduce(_||_)
    def isAMOArithmetic(cmd: UInt) = Seq(M_XA_ADD, M_XA_MIN, M_XA_MAX, M_XA_MINU, M_XA_MAXU).map(_ === cmd).reduce(_||_)
    def isAMO(cmd: UInt) = isAMOLogical(cmd) || isAMOArithmetic(cmd)
    //}

     val csr = rocketImpl.csr
     val id_ctrl  = rocketImpl.id_ctrl
     val ex_ctrl  = rocketImpl.ex_ctrl
     val mem_ctrl = rocketImpl.mem_ctrl
     val wb_ctrl  = rocketImpl.wb_ctrl
     val wb_valid = rocketImpl.wb_valid
     val wb_xcpt  = rocketImpl.wb_xcpt

     val id_ex_hazard                   = rocketImpl.id_ex_hazard
     val id_mem_hazard                  = rocketImpl.id_mem_hazard
     val id_wb_hazard                   = rocketImpl.wb_xcpt
     val id_sboard_hazard               = rocketImpl.id_sboard_hazard
     val icache_blocked                 = rocketImpl.icache_blocked
     val dcache_blocked                 = rocketImpl.dcache_blocked
     val mem_direction_misprediction    = rocketImpl.mem_direction_misprediction
     val mem_misprediction              = rocketImpl.mem_misprediction
     val mem_cfi                        = rocketImpl.mem_cfi
     val id_stall_fpu                   = rocketImpl.id_stall_fpu
     val pipelinedMul                   = rocketImpl.pipelinedMul
     val take_pc_mem                    = rocketImpl.take_pc_mem
     val wb_reg_flush_pipe              = rocketImpl.wb_reg_flush_pipe     
     val replay_wb                      = rocketImpl.replay_wb          

     def pipelineIDToWB[T <: Data](x: T): T = rocketImpl.pipelineIDToWB(x)  

    io.pfcclient <> csr.io.pfcclient

    io.pfcupdate.eventG0.cycle                  := true.B              //event0
    io.pfcupdate.eventG0.instruction            := csr.io.retire       //event1
    io.pfcupdate.eventG0.exception              := csr.io.exception    //event2 different with perfEvents.exception!
    io.pfcupdate.eventG0.load                   := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.mem && id_ctrl.mem_cmd === M_XRD && !id_ctrl.fp)   //event3
    io.pfcupdate.eventG0.store                  := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.mem && id_ctrl.mem_cmd === M_XWR && !id_ctrl.fp)    //event4
    //io.pfcupdate.eventG0.amo                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.mem && (isAMO(id_ctrl.mem_cmd) || id_ctrl.mem_cmd.isOneOf(M_XLR, M_XSC))) && usingAtomics.B   //event5
    io.pfcupdate.eventG0.amo                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.mem && (isAMO(id_ctrl.mem_cmd) || Seq(M_XLR, M_XSC).map(_ === id_ctrl.mem_cmd).reduce(_||_))) && usingAtomics.B   //event5
    io.pfcupdate.eventG0.system                 := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.csr =/= CSR.N)  //event6
    io.pfcupdate.eventG0.arith                  := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.wxd && !(id_ctrl.jal || id_ctrl.jalr || id_ctrl.mem || id_ctrl.fp || id_ctrl.mul || id_ctrl.div || id_ctrl.csr =/= CSR.N))  //event7
    io.pfcupdate.eventG0.branch                 := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.branch)  //event8
    io.pfcupdate.eventG0.jal                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.jal)     //event9
    io.pfcupdate.eventG0.jalr                   := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.jalr)    //event10
    if(usingMulDiv &&  pipelinedMul) {
    io.pfcupdate.eventG0.mul                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.mul)
    io.pfcupdate.eventG0.div                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.div)
    }
    if(usingMulDiv && !pipelinedMul) {
    io.pfcupdate.eventG0.mul                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.div && (id_ctrl.alu_fn & ALU.FN_DIV) =/= ALU.FN_DIV)
    io.pfcupdate.eventG0.div                    := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.div && (id_ctrl.alu_fn & ALU.FN_DIV) === ALU.FN_DIV)
    }
    if(usingFPU) {
    io.pfcupdate.eventG0.fp_load                := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && io.fpu.dec.ldst && io.fpu.dec.wen)
    io.pfcupdate.eventG0.fp_store               := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && io.fpu.dec.ldst && !io.fpu.dec.wen)
    io.pfcupdate.eventG0.fp_add                 := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && io.fpu.dec.fma && io.fpu.dec.swap23)
    io.pfcupdate.eventG0.fp_mul                 := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && io.fpu.dec.fma && !io.fpu.dec.swap23 && !io.fpu.dec.ren3)
    io.pfcupdate.eventG0.fp_muladd              := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && io.fpu.dec.fma && io.fpu.dec.ren3)
    io.pfcupdate.eventG0.fp_divsqrt             := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp &&  (io.fpu.dec.div || io.fpu.dec.sqrt))
    io.pfcupdate.eventG0.fp_other               := !wb_xcpt && wb_valid && pipelineIDToWB(id_ctrl.fp && !(io.fpu.dec.ldst || io.fpu.dec.fma || io.fpu.dec.div || io.fpu.dec.sqrt))
    }
    io.pfcupdate.eventG0.load_use_interlock     := id_ex_hazard && ex_ctrl.mem || id_mem_hazard && mem_ctrl.mem || id_wb_hazard && wb_ctrl.mem
    io.pfcupdate.eventG0.long_latency_interlock := id_sboard_hazard
    io.pfcupdate.eventG0.csr_interlock          := id_ex_hazard && ex_ctrl.csr =/= CSR.N || id_mem_hazard && mem_ctrl.csr =/= CSR.N || id_wb_hazard && wb_ctrl.csr =/= CSR.N
    io.pfcupdate.eventG0.Iblocked               := icache_blocked
    io.pfcupdate.eventG0.Dblocked               := id_ctrl.mem && dcache_blocked
    io.pfcupdate.eventG0.branch_misprediction   := take_pc_mem && mem_direction_misprediction
    io.pfcupdate.eventG0.cft_misprediction      := take_pc_mem && mem_misprediction && mem_cfi && !mem_direction_misprediction && !icache_blocked //controlflow_target_misprediction
    io.pfcupdate.eventG0.flush                  := wb_reg_flush_pipe
    io.pfcupdate.eventG0.replay                 := replay_wb
    if(usingMulDiv) {
    io.pfcupdate.eventG0.muldiv_interlock       := id_ex_hazard && (ex_ctrl.mul || ex_ctrl.div) || id_mem_hazard && (mem_ctrl.mul || mem_ctrl.div) || id_wb_hazard && wb_ctrl.div
    }
    if(usingFPU) {  
    io.pfcupdate.eventG0.fp_interlock           := id_ex_hazard && ex_ctrl.fp || id_mem_hazard && mem_ctrl.fp || id_wb_hazard && wb_ctrl.fp || id_ctrl.fp && id_stall_fpu
    }
    io.pfcupdate.eventG0.Imiss                  := io.imem.perf.acquire
    io.pfcupdate.eventG0.Dmiss                  := io.dmem.perf.acquire
    io.pfcupdate.eventG0.Drelease               := io.dmem.perf.release
    io.pfcupdate.eventG0.ITLBmiss               := io.imem.perf.tlbMiss
    io.pfcupdate.eventG0.DTLBmiss               := io.dmem.perf.tlbMiss
    io.pfcupdate.eventG0.L2TLBmiss              := io.ptw.perf.l2miss    //event35
  } 
}

trait HasTilePFCNode { this: freechips.rocketchip.tile.BaseTile =>
   //val pfcnode   = BundleBridgeSource(() => (new TilePFCCMIO(p(freechips.rocketchip.subsystem.RocketTilesKey).length).cloneType))
   //val pfc       = InModuleBody { pfcnode.bundle }
   //val pfclnode  = new BundleBridgeSink(Some(() => (new PFCClientIO(p(freechips.rocketchip.subsystem.RocketTilesKey).length).cloneType)))  //not work :PFCNetwork.scala: clnodes
   //val pfcmaode  = new BundleBridgeSink(Some(() => (new PFCManagerIO(p(freechips.rocketchip.subsystem.RocketTilesKey).length).cloneType))) //not work :PFCNetwork.scala: manodes
   val pfclnode  = BundleBridgeSource(() => (new PFCClientIO(p(freechips.rocketchip.subsystem.RocketTilesKey).length+1).cloneType))
   val pfcmaode  = BundleBridgeSource(() => (new PFCManagerIO(p(freechips.rocketchip.subsystem.RocketTilesKey).length+1).cloneType))
   val pfccl     = InModuleBody { pfclnode.bundle }
   val pfcma     = InModuleBody { pfcmaode.bundle }
}

/*trait HasTilePFCManager { this: freechips.rocketchip.tile.RocketTileModuleImp =>
  val pfcmanager = Module(new PFCManager(
    nClients = p(freechips.rocketchip.subsystem.RocketTilesKey).length, 
    rebt = Some(Seq(new P0RocketCorePFCReg(),  // order !!!!!
                    new P1RocketCorePFCReg(),
                    new P2FrontendPFCReg(),
                    new P3L1IPFCReg(),
                    new P4L1DPFCReg(),
                    new P5MSHRPFCReg()
                )),
    rabt = Some(Seq(new SetEventPFCRam(64),
                    new SetEventPFCRam(64),
                    new SetEventPFCRam(64)
                ))
   ))

  val CoreEventG0    = pfcmanager.io.update.reg.get(0) 
  val CoreEventG1    = pfcmanager.io.update.reg.get(1)
  val FrontendEvent  = pfcmanager.io.update.reg.get(2)
  val L1IEvent   = pfcmanager.io.update.reg.get(3)
  val L1DEvent   = pfcmanager.io.update.reg.get(4)
  val MSHREvent  = pfcmanager.io.update.reg.get(5)

  val L1ISetMiss =  pfcmanager.io.update.ram.get(0)
  val L1DSetMiss =  pfcmanager.io.update.ram.get(1)
  val L1DSetWB   =  pfcmanager.io.update.ram.get(2)

  //import freechips.rocketchip.tile._
  def connectPFC = {
    outer.pfc.client  <> core.io.pfcclient
    outer.pfc.manager <> pfcmanager.io.manager

  /*outer.pfccl.req.valid               := core.io.pfcclient.req.valid
   outer.pfccl.req.bits                := core.io.pfcclient.req.bits
   core.io.pfcclient.req.ready         := outer.pfccl.req.ready
   core.io.pfcclient.resp.valid        := outer.pfccl.resp.valid
   core.io.pfcclient.resp.bits         := outer.pfccl.resp.bits
   outer.pfccl.resp.ready              := core.io.pfcclient.resp.ready 
   pfcmanager.io.manager.req.valid     := outer.pfcma.req.valid
   pfcmanager.io.manager.req.bits      := outer.pfcma.req.bits
   outer.pfcma.req.ready               := pfcmanager.io.manager.req.ready
   outer.pfcma.resp.valid              := pfcmanager.io.manager.resp.valid
   outer.pfcma.resp.bits               := pfcmanager.io.manager.resp.bits
   pfcmanager.io.manager.resp.ready    := outer.pfcma.resp.ready*/ 
   
    CoreEventG1.elements.foreach(_._2 := false.B)  
    FrontendEvent.elements.foreach(_._2 := false.B)
    L1IEvent.elements.foreach(_._2 := false.B)
    L1DEvent.elements.foreach(_._2 := false.B)
    MSHREvent.elements.foreach(_._2 := false.B) 

    CoreEventG0  := core.io.pfcupdate.eventG0
    L1ISetMiss   := outer.frontend.module.io.pfcupdate.setmiss 
    L1DSetMiss   := outer.dcache.module.io.pfcupdate.setmiss
    L1DSetWB     := outer.dcache.module.io.pfcupdate.setwb
    
    /*
    if these above code is build in RocketTileModuleImp some connect order should change
    {
      outer.pfc.client  <> core.io.pfcclient
      outer.pfc.manager <> pfcmanager.io.manager
      //CoreEventG0 <> core.io.pfcupdate.eventG0    // wrong ????!!! // used as a SinkFlow but can only be used as a SourceFlow
      //CoreEventG0 := core.io.pfcupdate.eventG0    // wrong ????!!!
      //core.io.pfcupdate.eventG0    <> CoreEventG0 // ok  
      core.io.pfcupdate.eventG0   :=  CoreEventG0

      outer.frontend.module.io.pfcupdate.setmiss  :=  L1ISetMiss
      outer.dcache.module.io.pfcupdate.setmiss    :=  L1DSetMiss
      outer.dcache.module.io.pfcupdate.setwb      :=  L1DSetWB
    }*/

  }
}*/

trait CanAttachTiletoPFC { this:  freechips.rocketchip.subsystem.CanAttachTile =>
  import freechips.rocketchip.tile.TilePRCIDomain
  def connectPFC (domain: TilePRCIDomain[TileType], context: TileContextType, hartID: Int): Unit = {
    implicit val p = context.p
    val pfbus = context.asInstanceOf[HasPFCnetwork].pfbus

    val TilePFCCLNode  = domain.tile.asInstanceOf[HasTilePFCNode].pfclnode
    val TilePFCMANode  = domain.tile.asInstanceOf[HasTilePFCNode].pfcmaode

    //TilePFCCLNode       := pfbu.clnodes(hartID)
    //TilePFCMANode       := pfbu.manodes(hartID)
    pfbus.clnodes(hartID) := TilePFCCLNode
    pfbus.manodes(hartID) := TilePFCMANode
  }
}

trait HasTilePFCManager { this: freechips.rocketchip.tile.RocketTileModuleImp =>

  val pfcmanager = Module(new PFCManager(
    nClients = p(freechips.rocketchip.subsystem.RocketTilesKey).length+1,
    rebt = Some(Seq(new P0RocketCorePFCReg(),  // order !!!!!
                    new P1RocketCorePFCReg(),
                    new P2FrontendPFCReg(),
                    new P3L1IPFCReg(),
                    new P4L1DPFCReg(),
                    new P5MSHRPFCReg()
                )),
    rabt = Some(Seq(new SetEventPFCRam(64),
                    new SetEventPFCRam(64),
                    new SetEventPFCRam(64)
                ))
   ))

  val CoreEventG0    = pfcmanager.io.update.reg.get(0) 
  val CoreEventG1    = pfcmanager.io.update.reg.get(1)
  val FrontendEvent  = pfcmanager.io.update.reg.get(2)
  val L1IEvent   = pfcmanager.io.update.reg.get(3)
  val L1DEvent   = pfcmanager.io.update.reg.get(4)
  val MSHREvent  = pfcmanager.io.update.reg.get(5)

  val L1ISetMiss =  pfcmanager.io.update.ram.get(0)
  val L1DSetMiss =  pfcmanager.io.update.ram.get(1)
  val L1DSetEV   =  pfcmanager.io.update.ram.get(2)

  def connectPFC = {
    core.io.pfcclient <> outer.pfccl
    pfcmanager.io.manager <> outer.pfcma

    CoreEventG1.elements.foreach(_._2 := false.B)  
    FrontendEvent.elements.foreach(_._2 := false.B)
    L1IEvent.elements.foreach(_._2 := false.B)
    L1DEvent.elements.foreach(_._2 := false.B)
    MSHREvent.elements.foreach(_._2 := false.B) 

    CoreEventG0  := core.io.pfcupdate.eventG0
    L1ISetMiss   := outer.frontend.module.io.pfcupdate.setmiss 
    L1DSetMiss   := outer.dcache.module.io.pfcupdate.setmiss
    L1DSetEV     := outer.dcache.module.io.pfcupdate.setev

  }

}

trait HasPFCnetwork  { this: freechips.rocketchip.subsystem.BaseSubsystem =>
  val pfbus = {
    val nTiless  = p(freechips.rocketchip.subsystem.RocketTilesKey).length
    val nClients = nTiless+1  //1 more fore osd
    val ClientIds  = (0 until nClients).map(i => i).toList.toSeq
    val TilepfcIds = (0 until nTiless).map(i => (i,i)).toList.toSeq
    val ManagerIds = TilepfcIds ++ PFCManagerIds.L2Bank0pfcIds
    
    LazyModule(new PFCNetworkl(ClientIds, ManagerIds))
  }
}
