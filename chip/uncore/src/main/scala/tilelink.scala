// See LICENSE for license details.

package uncore
import Chisel._
import junctions._
import scala.math.max

/** Parameters exposed to the top-level design, set based on 
  * external requirements or design space exploration
  */
/** Unique name per TileLink network*/
case object TLId extends Field[String]
/** Coherency policy used to define custom mesage types */
case object TLCoherencePolicy extends Field[CoherencePolicy]
/** Number of manager agents */
case object TLNManagers extends Field[Int] 
/** Number of client agents */
case object TLNClients extends Field[Int]
/** Number of client agents that cache data and use custom [[uncore.Acquire]] types */
case object TLNCachingClients extends Field[Int]
/** Number of client agents that do not cache data and use built-in [[uncore.Acquire]] types */
case object TLNCachelessClients extends Field[Int]
/** Maximum number of unique outstanding transactions per client */
case object TLMaxClientXacts extends Field[Int]
/** Maximum number of clients multiplexed onto a single port */
case object TLMaxClientsPerPort extends Field[Int]
/** Maximum number of unique outstanding transactions per manager */
case object TLMaxManagerXacts extends Field[Int]
/** Width of cache block addresses */
case object TLBlockAddrBits extends Field[Int]
/** Width of data beats */
case object TLDataBits extends Field[Int]
/** Number of data beats per cache block */
case object TLDataBeats extends Field[Int]
/** Whether the underlying physical network preserved point-to-point ordering of messages */
case object TLNetworkIsOrderedP2P extends Field[Boolean]
/** Number of bits in write mask (usually one per byte in beat) */
case object TLWriteMaskBits extends Field[Int]

/** Utility trait for building Modules and Bundles that use TileLink parameters */
trait TileLinkParameters extends UsesParameters {
  val tlCoh = params(TLCoherencePolicy)
  val tlNManagers = params(TLNManagers)
  val tlNClients = params(TLNClients)
  val tlNCachingClients = params(TLNCachingClients)
  val tlNCachelessClients = params(TLNCachelessClients)
  val tlClientIdBits =  log2Up(tlNClients)
  val tlManagerIdBits =  log2Up(tlNManagers)
  val tlMaxClientXacts = params(TLMaxClientXacts)
  val tlMaxClientsPerPort = params(TLMaxClientsPerPort)
  val tlMaxManagerXacts = params(TLMaxManagerXacts)
  val tlClientXactIdBits = log2Up(tlMaxClientXacts*tlMaxClientsPerPort)
  val tlManagerXactIdBits = log2Up(tlMaxManagerXacts)
  val tlBlockAddrBits = params(TLBlockAddrBits)
  val tlDataBits = params(TLDataBits)
  val tlDataBytes = tlDataBits/8
  val tlDataBeats = params(TLDataBeats)
  val tlWriteMaskBits = params(TLWriteMaskBits)
  val tlBeatAddrBits = log2Up(tlDataBeats)
  val tlByteAddrBits = log2Up(tlWriteMaskBits)
  val tlMemoryOpcodeBits = M_SZ
  val tlMemoryOperandSizeBits = MT_SZ
  val tlAcquireTypeBits = max(log2Up(Acquire.nBuiltInTypes), 
                              tlCoh.acquireTypeWidth)
  val tlAcquireUnionBits = max(tlWriteMaskBits,
                                 (tlByteAddrBits +
                                   tlMemoryOperandSizeBits +
                                   tlMemoryOpcodeBits)) + 1
  val tlGrantTypeBits = max(log2Up(Grant.nBuiltInTypes), 
                              tlCoh.grantTypeWidth) + 1
  val tlNetworkPreservesPointToPointOrdering = params(TLNetworkIsOrderedP2P)
  val tlNetworkDoesNotInterleaveBeats = true
  val amoAluOperandBits = params(AmoAluOperandBits)
}

abstract class TLBundle extends Bundle with TileLinkParameters
abstract class TLModule extends Module with TileLinkParameters

/** Base trait for all TileLink channels */
trait TileLinkChannel extends TLBundle {
  def hasData(dummy: Int = 0): Bool
  def hasMultibeatData(dummy: Int = 0): Bool
}
/** Directionality of message channel. Used to hook up logical network ports to physical network ports */
trait ClientToManagerChannel extends TileLinkChannel
/** Directionality of message channel. Used to hook up logical network ports to physical network ports */
trait ManagerToClientChannel extends TileLinkChannel
/** Directionality of message channel. Used to hook up logical network ports to physical network ports */
trait ClientToClientChannel extends TileLinkChannel // Unused for now

/** Common signals that are used in multiple channels.
  * These traits are useful for type parameterizing bundle wiring functions.
  */

/** Address of a cache block. */
trait HasCacheBlockAddress extends TLBundle {
  val addr_block = UInt(width = tlBlockAddrBits)

  def conflicts(that: HasCacheBlockAddress) = this.addr_block === that.addr_block
  def conflicts(addr: UInt) = this.addr_block === addr
}

/** Sub-block address or beat id of multi-beat data */
trait HasTileLinkBeatId extends TLBundle {
  val addr_beat = UInt(width = tlBeatAddrBits)
}

/* Client-side transaction id. Usually Miss Status Handling Register File index */
trait HasClientTransactionId extends TLBundle {
  val client_xact_id = Bits(width = tlClientXactIdBits)
}

/** Manager-side transaction id. Usually Transaction Status Handling Register File index. */
trait HasManagerTransactionId extends TLBundle {
  val manager_xact_id = Bits(width = tlManagerXactIdBits)
}

/** A single beat of cache block data */
trait HasTileLinkData extends HasTileLinkBeatId {
  val data = UInt(width = tlDataBits + 2)

  def hasData(dummy: Int = 0): Bool
  def hasMultibeatData(dummy: Int = 0): Bool
}

/** The id of a client source or destination. Used in managers. */
trait HasClientId extends TLBundle {
  val client_id = UInt(width = tlClientIdBits)
}

/** TileLink channel bundle definitions */

/** The Acquire channel is used to intiate coherence protocol transactions in
  * order to gain access to a cache block's data with certain permissions
  * enabled. Messages sent over this channel may be custom types defined by
  * a [[uncore.CoherencePolicy]] for cached data accesse or may be built-in types
  * used for uncached data accesses. Acquires may contain data for Put or
  * PutAtomic built-in types. After sending an Acquire, clients must
  * wait for a manager to send them a [[uncore.Grant]] message in response.
  */
class Acquire extends ClientToManagerChannel 
    with HasCacheBlockAddress 
    with HasClientTransactionId 
    with HasTileLinkData {
  // Actual bundle fields:
  val is_builtin_type = Bool()
  val a_type = UInt(width = tlAcquireTypeBits)
  val union = Bits(width = tlAcquireUnionBits)

  // Utility funcs for accessing subblock union:
  val opCodeOff = 1
  val opSizeOff = tlMemoryOpcodeBits + opCodeOff
  val addrByteOff = tlMemoryOperandSizeBits + opSizeOff
  val addrByteMSB = tlByteAddrBits + addrByteOff
  /** Hint whether to allocate the block in any interveneing caches */
  def allocate(dummy: Int = 0) = union(0)
  /** Op code for [[uncore.PutAtomic]] operations */
  def op_code(dummy: Int = 0) = Mux(
    isBuiltInType(Acquire.putType) || isBuiltInType(Acquire.putBlockType),
    M_XWR, union(opSizeOff-1, opCodeOff))
  /** Operand size for [[uncore.PutAtomic]] */
  def op_size(dummy: Int = 0) = union(addrByteOff-1, opSizeOff)
  /** Byte address for [[uncore.PutAtomic]] operand */
  def addr_byte(dummy: Int = 0) = union(addrByteMSB-1, addrByteOff)
  private def amo_offset(dummy: Int = 0) = addr_byte()(tlByteAddrBits-1, log2Up(amoAluOperandBits/8))
  /** Bit offset of [[uncore.PutAtomic]] operand */
  def amo_shift_bits(dummy: Int = 0) = UInt(amoAluOperandBits)*amo_offset()
  /** Write mask for [[uncore.Put]], [[uncore.PutBlock]], [[uncore.PutAtomic]] */
  def wmask(dummy: Int = 0) = 
    Mux(isBuiltInType(Acquire.putAtomicType), 
      FillInterleaved(amoAluOperandBits/8, UIntToOH(amo_offset())),
      Mux(isBuiltInType(Acquire.putBlockType) || isBuiltInType(Acquire.putType),
        union(tlWriteMaskBits, 1),
        UInt(0, width = tlWriteMaskBits)))
  /** Full, beat-sized writemask */
  def full_wmask(dummy: Int = 0) = FillInterleaved(8, wmask())
  /** Complete physical address for block, beat or operand */
  def full_addr(dummy: Int = 0) = Cat(this.addr_block, this.addr_beat, this.addr_byte())

  // Other helper functions:
  /** Message type equality */
  def is(t: UInt) = a_type === t //TODO: make this more opaque; def ===?

  /** Is this message a built-in or custom type */
  def isBuiltInType(dummy: Int = 0): Bool = is_builtin_type
  /** Is this message a particular built-in type */
  def isBuiltInType(t: UInt): Bool = is_builtin_type && a_type === t 

  /** Does this message refer to subblock operands using info in the Acquire.union subbundle */ 
  def isSubBlockType(dummy: Int = 0): Bool = isBuiltInType() && Acquire.typesOnSubBlocks.contains(a_type) 

  /** Is this message a built-in prefetch message */
  def isPrefetch(dummy: Int = 0): Bool = isBuiltInType() && is(Acquire.prefetchType) 

  /** Does this message contain data? Assumes that no custom message types have data. */
  def hasData(dummy: Int = 0): Bool = isBuiltInType() && Acquire.typesWithData.contains(a_type)

  /** Does this message contain multiple beats of data? Assumes that no custom message types have data. */
  def hasMultibeatData(dummy: Int = 0): Bool = Bool(tlDataBeats > 1) && isBuiltInType() &&
                                           Acquire.typesWithMultibeatData.contains(a_type)

  /** Does this message require the manager to probe the client the very client that sent it?
    * Needed if multiple caches are attached to the same port.
    */
  def requiresSelfProbe(dummy: Int = 0) = Bool(false)

  /** Mapping between each built-in Acquire type (defined in companion object)
    * and a built-in Grant type.
    */
  def getBuiltInGrantType(dummy: Int = 0): UInt = {
    MuxLookup(this.a_type, Grant.putAckType, Array(
      Acquire.getType       -> Grant.getDataBeatType,
      Acquire.getBlockType  -> Grant.getDataBlockType,
      Acquire.putType       -> Grant.putAckType,
      Acquire.putBlockType  -> Grant.putAckType,
      Acquire.putAtomicType -> Grant.getDataBeatType,
      Acquire.prefetchType  -> Grant.prefetchAckType))
  }
}

/** [[uncore.Acquire]] with an extra field stating its source id */
class AcquireFromSrc extends Acquire with HasClientId

/** Contains definitions of the the built-in Acquire types and a factory
  * for [[uncore.Acquire]]
  *
  * In general you should avoid using this factory directly and use
  * [[uncore.ClientMetadata.makeAcquire]] for custom cached Acquires and
  * [[uncore.Get]], [[uncore.Put]], etc. for built-in uncached Acquires.
  *
  * @param is_builtin_type built-in or custom type message?
  * @param a_type built-in type enum or custom type enum
  * @param client_xact_id client's transaction id
  * @param addr_block address of the cache block
  * @param addr_beat sub-block address (which beat)
  * @param data data being put outwards
  * @param union additional fields used for uncached types
  */
object Acquire {
  val nBuiltInTypes = 5
  //TODO: Use Enum
  def getType       = UInt("b000") // Get a single beat of data
  def getBlockType  = UInt("b001") // Get a whole block of data
  def putType       = UInt("b010") // Put a single beat of data
  def putBlockType  = UInt("b011") // Put a whole block of data
  def putAtomicType = UInt("b100") // Perform an atomic memory op
  def prefetchType  = UInt("b101") // Prefetch a whole block of data
  def typesWithData = Vec(putType, putBlockType, putAtomicType)
  def typesWithMultibeatData = Vec(putBlockType)
  def typesOnSubBlocks = Vec(putType, getType, putAtomicType)

  def fullWriteMask = SInt(-1, width = new Acquire().tlWriteMaskBits).toUInt

  // Most generic constructor
  def apply(
      is_builtin_type: Bool,
      a_type: Bits,
      client_xact_id: UInt,
      addr_block: UInt,
      addr_beat: UInt = UInt(0),
      data: UInt = UInt(0),
      union: UInt = UInt(0)): Acquire = {
    val acq = Wire(new Acquire)
    acq.is_builtin_type := is_builtin_type
    acq.a_type := a_type
    acq.client_xact_id := client_xact_id
    acq.addr_block := addr_block
    acq.addr_beat := addr_beat
    acq.data := data
    acq.union := union
    acq
  }
  // Copy constructor
  def apply(a: Acquire): Acquire = {
    val acq = Wire(new Acquire)
    acq := a
    acq
  }
}

/** Get a single beat of data from the outer memory hierarchy
  *
  * The client can hint whether he block containing this beat should be 
  * allocated in the intervening levels of the hierarchy.
  *
  * @param client_xact_id client's transaction id
  * @param addr_block address of the cache block
  * @param addr_beat sub-block address (which beat)
  * @param addr_byte sub-block address (which byte)
  * @param operand_size {byte, half, word, double} from [[uncore.MemoryOpConstants]]
  * @param alloc hint whether the block should be allocated in intervening caches
  */
object Get {
  def apply(
      client_xact_id: UInt,
      addr_block: UInt,
      addr_beat: UInt,
      alloc: Bool = Bool(true)): Acquire = {
    Acquire(
      is_builtin_type = Bool(true),
      a_type = Acquire.getType,
      client_xact_id = client_xact_id,
      addr_block = addr_block,
      addr_beat = addr_beat,
      union = Cat(MT_Q, M_XRD, alloc))
  }
  def apply(
      client_xact_id: UInt,
      addr_block: UInt,
      addr_beat: UInt,
      addr_byte: UInt,
      operand_size: UInt,
      alloc: Bool): Acquire = {
    Acquire(
      is_builtin_type = Bool(true),
      a_type = Acquire.getType,
      client_xact_id = client_xact_id,
      addr_block = addr_block,
      addr_beat = addr_beat,
      union = Cat(addr_byte, operand_size, M_XRD, alloc))
  }
}

/** Get a whole cache block of data from the outer memory hierarchy
  *
  * The client can hint whether the block should be allocated in the 
  * intervening levels of the hierarchy.
  *
  * @param client_xact_id client's transaction id
  * @param addr_block address of the cache block
  * @param alloc hint whether the block should be allocated in intervening caches
  */
/*
object GetBlock {
  def apply(
      client_xact_id: UInt = UInt(0),
      addr_block: UInt,
      alloc: Bool = Bool(true)): Acquire = {
    Acquire(
      is_builtin_type = Bool(true),
      a_type = Acquire.getBlockType,
      client_xact_id = client_xact_id, 
      addr_block = addr_block,
      union = Cat(MT_Q, M_XRD, alloc))
  }
}
*/
object GetBlock {
  def apply(
      client_xact_id: UInt = UInt(0),
      addr_block: UInt,
      m_op: UInt = M_XRD,
      alloc: Bool = Bool(true)): Acquire = {
    Acquire(
      is_builtin_type = Bool(true),
      a_type = Acquire.getBlockType,
      client_xact_id = client_xact_id, 
      addr_block = addr_block,
      union = Cat(MT_Q, m_op, alloc))
  }
}


/** Prefetch a cache block into the next-outermost level of the memory hierarchy
  * with read permissions.
  *
  * @param client_xact_id client's transaction id
  * @param addr_block address of the cache block
  */
object GetPrefetch {
  def apply(
      client_xact_id: UInt,
      addr_block: UInt): Acquire = {
    Acquire(
      is_builtin_type = Bool(true),
      a_type = Acquire.prefetchType,
      client_xact_id = client_xact_id,
      addr_block = addr_block,
      addr_beat = UInt(0),
      union = Cat(MT_Q, M_XRD, Bool(true)))
  }
}

/** Put a single beat of data into the outer memory hierarchy
  *
  * The block will be allocated in the next-outermost level of the hierarchy.
  *
  * @param client_xact_id client's transaction id
  * @param addr_block address of the cache block
  * @param addr_beat sub-block address (which beat)
  * @param data data being refilled to the original requestor
  * @param wmask per-byte write mask for this beat
  */
object Put {
  def apply(
      client_xact_id: UInt,
      addr_block: UInt,
      addr_beat: UInt,
      data: UInt,
      wmask: UInt = Acquire.fullWriteMask): Acquire = {
    Acquire(
      is_builtin_type = Bool(true),
      a_type = Acquire.putType,
      addr_block = addr_block,
      addr_beat = addr_beat,
      client_xact_id = client_xact_id,
      data = data,
      union = Cat(wmask, Bool(true)))
  }
}

/** Put a whole cache block of data into the outer memory hierarchy
  *
  * If the write mask is not full, the block will be allocated in the
  * next-outermost level of the hierarchy. If the write mask is full, the
  * client can hint whether the block should be allocated or not.
  *
  * @param client_xact_id client's transaction id
  * @param addr_block address of the cache block
  * @param addr_beat sub-block address (which beat of several)
  * @param data data being refilled to the original requestor
  * @param wmask per-byte write mask for this beat
  * @param alloc hint whether the block should be allocated in intervening caches
  */
object PutBlock {
  def apply(
      client_xact_id: UInt,
      addr_block: UInt,
      addr_beat: UInt,
      data: UInt,
      wmask: UInt): Acquire = {
    Acquire(
      is_builtin_type = Bool(true),
      a_type = Acquire.putBlockType,
      client_xact_id = client_xact_id,
      addr_block = addr_block,
      addr_beat = addr_beat,
      data = data,
      union = Cat(wmask, (wmask != Acquire.fullWriteMask)))
  }
  def apply(
      client_xact_id: UInt,
      addr_block: UInt,
      addr_beat: UInt,
      data: UInt,
      alloc: Bool = Bool(true)): Acquire = {
    Acquire(
      is_builtin_type = Bool(true),
      a_type = Acquire.putBlockType,
      client_xact_id = client_xact_id,
      addr_block = addr_block,
      addr_beat = addr_beat,
      data = data,
      union = Cat(Acquire.fullWriteMask, alloc))
  }
}

/** Prefetch a cache block into the next-outermost level of the memory hierarchy
  * with write permissions.
  *
  * @param client_xact_id client's transaction id
  * @param addr_block address of the cache block
  */
object PutPrefetch {
  def apply(
      client_xact_id: UInt,
      addr_block: UInt): Acquire = {
    Acquire(
      is_builtin_type = Bool(true),
      a_type = Acquire.prefetchType,
      client_xact_id = client_xact_id,
      addr_block = addr_block,
      addr_beat = UInt(0),
      union = Cat(M_XWR, Bool(true)))
  }
}

/** Perform an atomic memory operation in the next-outermost level of the memory hierarchy
  *
  * @param client_xact_id client's transaction id
  * @param addr_block address of the cache block
  * @param addr_beat sub-block address (within which beat)
  * @param addr_byte sub-block address (which byte)
  * @param atomic_opcode {swap, add, xor, and, min, max, minu, maxu} from [[uncore.MemoryOpConstants]]
  * @param operand_size {byte, half, word, double} from [[uncore.MemoryOpConstants]]
  * @param data source operand data
  */
object PutAtomic {
  def apply(
      client_xact_id: UInt,
      addr_block: UInt,
      addr_beat: UInt,
      addr_byte: UInt,
      atomic_opcode: UInt,
      operand_size: UInt,
      data: UInt): Acquire = {
    Acquire(
      is_builtin_type = Bool(true),
      a_type = Acquire.putAtomicType,
      client_xact_id = client_xact_id, 
      addr_block = addr_block, 
      addr_beat = addr_beat, 
      data = data,
      union = Cat(addr_byte, operand_size, atomic_opcode, Bool(true)))
  }
}

/** The Probe channel is used to force clients to release data or cede permissions
  * on a cache block. Clients respond to Probes with [[uncore.Release]] messages.
  * The available types of Probes are customized by a particular
  * [[uncore.CoherencePolicy]].
  */
class Probe extends ManagerToClientChannel 
    with HasCacheBlockAddress {
  val p_type = UInt(width = tlCoh.probeTypeWidth)

  def is(t: UInt) = p_type === t
  def hasData(dummy: Int = 0) = Bool(false)
  def hasMultibeatData(dummy: Int = 0) = Bool(false)
}

/** [[uncore.Probe]] with an extra field stating its destination id */
class ProbeToDst extends Probe with HasClientId

/** Contains factories for [[uncore.Probe]] and [[uncore.ProbeToDst]]
  *
  * In general you should avoid using these factories directly and use
  * [[uncore.ManagerMetadata.makeProbe(UInt,Acquire)* makeProbe]] instead.
  *
  * @param dst id of client to which probe should be sent
  * @param p_type custom probe type
  * @param addr_block address of the cache block
  */
object Probe {
  def apply(p_type: UInt, addr_block: UInt): Probe = {
    val prb = Wire(new Probe)
    prb.p_type := p_type
    prb.addr_block := addr_block
    prb
  }
  def apply(dst: UInt, p_type: UInt, addr_block: UInt): ProbeToDst = {
    val prb = Wire(new ProbeToDst)
    prb.client_id := dst
    prb.p_type := p_type
    prb.addr_block := addr_block
    prb
  }
}

/** The Release channel is used to release data or permission back to the manager
  * in response to [[uncore.Probe]] messages. It can also be used to voluntarily
  * write back data, for example in the event that dirty data must be evicted on
  * a cache miss. The available types of Release messages are always customized by
  * a particular [[uncore.CoherencePolicy]]. Releases may contain data or may be
  * simple acknowledgements. Voluntary Releases are acknowledged with [[uncore.Grant Grants]].
  */
class Release extends ClientToManagerChannel 
    with HasCacheBlockAddress 
    with HasClientTransactionId 
    with HasTileLinkData {
  val r_type = UInt(width = tlCoh.releaseTypeWidth)
  val voluntary = Bool()

  // Helper funcs
  def is(t: UInt) = r_type === t
  def hasData(dummy: Int = 0) = tlCoh.releaseTypesWithData.contains(r_type)
  //TODO: Assumes all releases write back full cache blocks:
  def hasMultibeatData(dummy: Int = 0) = Bool(tlDataBeats > 1) && tlCoh.releaseTypesWithData.contains(r_type)
  def isVoluntary(dummy: Int = 0) = voluntary
  def requiresAck(dummy: Int = 0) = !Bool(tlNetworkPreservesPointToPointOrdering)
  def full_addr(dummy: Int = 0) = Cat(this.addr_block, this.addr_beat, UInt(0, width = tlByteAddrBits))
}

/** [[uncore.Release]] with an extra field stating its source id */
class ReleaseFromSrc extends Release with HasClientId

/** Contains a [[uncore.Release]] factory
  *
  * In general you should avoid using this factory directly and use
  * [[uncore.ClientMetadata.makeRelease]] instead.
  *
  * @param voluntary is this a voluntary writeback
  * @param r_type type enum defined by coherence protocol
  * @param client_xact_id client's transaction id
  * @param addr_block address of the cache block
  * @param addr_beat beat id of the data
  * @param data data being written back
  */
object Release {
  def apply(
      voluntary: Bool,
      r_type: UInt,
      client_xact_id: UInt,
      addr_block: UInt,
      addr_beat: UInt = UInt(0),
      data: UInt = UInt(0)): Release = {
    val rel = Wire(new Release)
    rel.r_type := r_type
    rel.client_xact_id := client_xact_id
    rel.addr_block := addr_block
    rel.addr_beat := addr_beat
    rel.data := data
    rel.voluntary := voluntary
    rel
  }
}

/** The Grant channel is used to refill data or grant permissions requested of the 
  * manager agent via an [[uncore.Acquire]] message. It is also used to acknowledge
  * the receipt of voluntary writeback from clients in the form of [[uncore.Release]]
  * messages. There are built-in Grant messages used for Gets and Puts, and
  * coherence policies may also define custom Grant types. Grants may contain data
  * or may be simple acknowledgements. Grants are responded to with [[uncore.Finish]].
  */
class Grant extends ManagerToClientChannel 
    with HasTileLinkData 
    with HasClientTransactionId 
    with HasManagerTransactionId {
  val is_builtin_type = Bool()
  val g_type = UInt(width = tlGrantTypeBits)

  // Helper funcs
  def isBuiltInType(dummy: Int = 0): Bool = is_builtin_type
  def isBuiltInType(t: UInt): Bool = is_builtin_type && g_type === t 
  def is(t: UInt):Bool = g_type === t
  def hasData(dummy: Int = 0): Bool = Mux(isBuiltInType(),
                                        Grant.typesWithData.contains(g_type),
                                        tlCoh.grantTypesWithData.contains(g_type))
  def hasMultibeatData(dummy: Int = 0): Bool = 
    Bool(tlDataBeats > 1) && Mux(isBuiltInType(),
                               Grant.typesWithMultibeatData.contains(g_type),
                               tlCoh.grantTypesWithData.contains(g_type))
  def isVoluntary(dummy: Int = 0): Bool = isBuiltInType() && (g_type === Grant.voluntaryAckType)
  def requiresAck(dummy: Int = 0): Bool = !Bool(tlNetworkPreservesPointToPointOrdering) && !isVoluntary()
  def makeFinish(dummy: Int = 0): Finish = {
    val f = Wire(Bundle(new Finish, { case TLMaxManagerXacts => tlMaxManagerXacts }))
    f.manager_xact_id := this.manager_xact_id
    f
  }
}

/** [[uncore.Grant]] with an extra field stating its destination */
class GrantToDst extends Grant with HasClientId

/** Contains definitions of the the built-in grant types and factories 
  * for [[uncore.Grant]] and [[uncore.GrantToDst]]
  *
  * In general you should avoid using these factories directly and use
  * [[uncore.ManagerMetadata.makeGrant(uncore.AcquireFromSrc* makeGrant]] instead.
  *
  * @param dst id of client to which grant should be sent
  * @param is_builtin_type built-in or custom type message?
  * @param g_type built-in type enum or custom type enum
  * @param client_xact_id client's transaction id
  * @param manager_xact_id manager's transaction id
  * @param addr_beat beat id of the data
  * @param data data being refilled to the original requestor
  */
object Grant {
  val nBuiltInTypes = 5
  def voluntaryAckType = UInt("b000") // For acking Releases
  def prefetchAckType  = UInt("b001") // For acking any kind of Prefetch
  def putAckType       = UInt("b011") // For acking any kind of non-prfetch Put
  def getDataBeatType  = UInt("b100") // Supplying a single beat of Get
  def getDataBlockType = UInt("b101") // Supplying all beats of a GetBlock
  def typesWithData = Vec(getDataBlockType, getDataBeatType)
  def typesWithMultibeatData= Vec(getDataBlockType)

  def apply(
      is_builtin_type: Bool,
      g_type: UInt,
      client_xact_id: UInt, 
      manager_xact_id: UInt,
      addr_beat: UInt,
      data: UInt): Grant = {
    val gnt = Wire(new Grant)
    gnt.is_builtin_type := is_builtin_type
    gnt.g_type := g_type
    gnt.client_xact_id := client_xact_id
    gnt.manager_xact_id := manager_xact_id
    gnt.addr_beat := addr_beat
    gnt.data := data
    gnt
  }

  def apply(
      dst: UInt,
      is_builtin_type: Bool,
      g_type: UInt,
      client_xact_id: UInt,
      manager_xact_id: UInt,
      addr_beat: UInt = UInt(0),
      data: UInt = UInt(0)): GrantToDst = {
    val gnt = Wire(new GrantToDst)
    gnt.client_id := dst
    gnt.is_builtin_type := is_builtin_type
    gnt.g_type := g_type
    gnt.client_xact_id := client_xact_id
    gnt.manager_xact_id := manager_xact_id
    gnt.addr_beat := addr_beat
    gnt.data := data
    gnt
  }
}

/** The Finish channel is used to provide a global ordering of transactions
  * in networks that do not guarantee point-to-point ordering of messages.
  * A Finsish message is sent as acknowledgement of receipt of a [[uncore.Grant]].
  * When a Finish message is received, a manager knows it is safe to begin
  * processing other transactions that touch the same cache block.
  */
class Finish extends ClientToManagerChannel with HasManagerTransactionId {
  def hasData(dummy: Int = 0) = Bool(false)
  def hasMultibeatData(dummy: Int = 0) = Bool(false)
}

/** Complete IO definition for incoherent TileLink, including networking headers */
class UncachedTileLinkIO extends TLBundle {
  val acquire   = new DecoupledIO(new LogicalNetworkIO(new Acquire))
  val grant     = new DecoupledIO(new LogicalNetworkIO(new Grant)).flip
  val finish = new DecoupledIO(new LogicalNetworkIO(new Finish))
}

/** Complete IO definition for coherent TileLink, including networking headers */
class TileLinkIO extends UncachedTileLinkIO {
  val probe     = new DecoupledIO(new LogicalNetworkIO(new Probe)).flip
  val release   = new DecoupledIO(new LogicalNetworkIO(new Release))
}

/** This version of UncachedTileLinkIO does not contain network headers. 
  * It is intended for use within client agents.
  *
  * Headers are provided in the top-level that instantiates the clients and network,
  * probably using a [[uncore.ClientTileLinkNetworkPort]] module.
  * By eliding the header subbundles within the clients we can enable 
  * hierarchical P-and-R while minimizing unconnected port errors in GDS.
  *
  * Secondly, this version of the interface elides [[uncore.Finish]] messages, with the
  * assumption that a [[uncore.FinishUnit]] has been coupled to the TileLinkIO port
  * to deal with acking received [[uncore.Grant Grants]].
  */
class ClientUncachedTileLinkIO extends TLBundle {
  val acquire   = new DecoupledIO(new Acquire)
  val grant     = new DecoupledIO(new Grant).flip
}

/** This version of TileLinkIO does not contain network headers. 
  * It is intended for use within client agents.
  */
class ClientTileLinkIO extends ClientUncachedTileLinkIO {
  val probe     = new DecoupledIO(new Probe).flip
  val release   = new DecoupledIO(new Release)
}

/** This version of TileLinkIO does not contain network headers, but
  * every channel does include an extra client_id subbundle.
  * It is intended for use within Management agents.
  *
  * Managers need to track where [[uncore.Acquire]] and [[uncore.Release]] messages
  * originated so that they can send a [[uncore.Grant]] to the right place. 
  * Similarly they must be able to issues Probes to particular clients.
  * However, we'd still prefer to have [[uncore.ManagerTileLinkNetworkPort]] fill in
  * the header.src to enable hierarchical p-and-r of the managers. Additionally, 
  * coherent clients might be mapped to random network port ids, and we'll leave it to the
  * [[uncore.ManagerTileLinkNetworkPort]] to apply the correct mapping. Managers do need to
  * see Finished so they know when to allow new transactions on a cache
  * block to proceed.
  */
class ManagerTileLinkIO extends TLBundle {
  val acquire   = new DecoupledIO(new AcquireFromSrc).flip
  val grant     = new DecoupledIO(new GrantToDst)
  val finish    = new DecoupledIO(new Finish).flip
  val probe     = new DecoupledIO(new ProbeToDst)
  val release   = new DecoupledIO(new ReleaseFromSrc).flip
}

/** Utilities for safely wrapping a *UncachedTileLink by pinning probe.ready and release.valid low */
object TileLinkIOWrapper {
  def apply(utl: ClientUncachedTileLinkIO, p: Parameters): ClientTileLinkIO = {
    val conv = Module(new ClientTileLinkIOWrapper)(p)
    conv.io.in <> utl
    conv.io.out
  }
  def apply(utl: ClientUncachedTileLinkIO): ClientTileLinkIO = {
    val conv = Module(new ClientTileLinkIOWrapper)
    conv.io.in <> utl
    conv.io.out
  }
  def apply(tl: ClientTileLinkIO): ClientTileLinkIO = tl
  def apply(utl: UncachedTileLinkIO, p: Parameters): TileLinkIO = {
    val conv = Module(new TileLinkIOWrapper)(p)
    conv.io.in <> utl
    conv.io.out
  }
  def apply(utl: UncachedTileLinkIO): TileLinkIO = {
    val conv = Module(new TileLinkIOWrapper)
    conv.io.in <> utl
    conv.io.out
  }
  def apply(tl: TileLinkIO): TileLinkIO = tl
}

class TileLinkIOWrapper extends TLModule {
  val io = new Bundle {
    val in = new UncachedTileLinkIO().flip
    val out = new TileLinkIO
  }
  io.out.acquire <> io.in.acquire
  io.in.grant <> io.out.grant
  io.out.finish <> io.in.finish
  io.out.probe.ready := Bool(true)
  io.out.release.valid := Bool(false)
}

class ClientTileLinkIOWrapper extends TLModule {
  val io = new Bundle {
    val in = new ClientUncachedTileLinkIO().flip
    val out = new ClientTileLinkIO
  }
  io.out.acquire <> io.in.acquire
  io.in.grant <> io.out.grant
  io.out.probe.ready := Bool(true)
  io.out.release.valid := Bool(false)
}

/** Used to track metadata for transactions where multiple secondary misses have been merged
  * and handled by a single transaction tracker.
  */
class SecondaryMissInfo extends TLBundle // TODO: add a_type to merge e.g. Get+GetBlocks, and/or HasClientId
    with HasTileLinkBeatId
    with HasClientTransactionId

/** A helper module that automatically issues [[uncore.Finish]] messages in repsonse
  * to [[uncore.Grant]] that it receives from a manager and forwards to a client
  */
class FinishUnit(srcId: Int = 0, outstanding: Int = 2) extends TLModule with HasDataBeatCounters {
  val io = new Bundle {
    val grant = Decoupled(new LogicalNetworkIO(new Grant)).flip
    val refill = Decoupled(new Grant)
    val finish = Decoupled(new LogicalNetworkIO(new Finish))
    val ready = Bool(OUTPUT)
  }

  val g = io.grant.bits.payload

  if(tlNetworkPreservesPointToPointOrdering) {
    io.finish.valid := Bool(false)
    io.refill.valid := io.grant.valid
    io.refill.bits := g
    io.grant.ready := io.refill.ready
    io.ready := Bool(true)
  } else {
    // We only want to send Finishes after we have collected all beats of
    // a multibeat Grant. But Grants from multiple managers or transactions may
    // get interleaved, so we could need a counter for each.
    val done = if(tlNetworkDoesNotInterleaveBeats) {
      connectIncomingDataBeatCounterWithHeader(io.grant)
    } else {
      val entries = 1 << tlClientXactIdBits
      def getId(g: LogicalNetworkIO[Grant]) = g.payload.client_xact_id
      assert(getId(io.grant.bits) <= UInt(entries), "Not enough grant beat counters, only " + entries + " entries.")
      connectIncomingDataBeatCountersWithHeader(io.grant, entries, getId).reduce(_||_)
    }
    val q = Module(new FinishQueue(outstanding))
    q.io.enq.valid := io.grant.fire() && g.requiresAck() && (!g.hasMultibeatData() || done)
    q.io.enq.bits.fin := g.makeFinish()
    q.io.enq.bits.dst := io.grant.bits.header.src

    io.finish.bits.header.src := UInt(srcId)
    io.finish.bits.header.dst := q.io.deq.bits.dst
    io.finish.bits.payload := q.io.deq.bits.fin
    io.finish.valid := q.io.deq.valid
    q.io.deq.ready := io.finish.ready

    io.refill.valid := io.grant.valid
    io.refill.bits := g
    io.grant.ready := (q.io.enq.ready || !g.requiresAck()) && io.refill.ready
    io.ready := q.io.enq.ready
  }
}

class FinishQueueEntry extends TLBundle {
    val fin = new Finish
    val dst = UInt(width = log2Up(params(LNEndpoints)))
}

class FinishQueue(entries: Int) extends Queue(new FinishQueueEntry, entries)

/** A port to convert [[uncore.ClientTileLinkIO]].flip into [[uncore.TileLinkIO]]
  *
  * Creates network headers for [[uncore.Acquire]] and [[uncore.Release]] messages,
  * calculating header.dst and filling in header.src.
  * Strips headers from [[uncore.Probe Probes]].
  * Responds to [[uncore.Grant]] by automatically issuing [[uncore.Finish]] to the granting managers.
  *
  * @param clientId network port id of this agent
  * @param addrConvert how a physical address maps to a destination manager port id
  */
class ClientTileLinkNetworkPort(clientId: Int, addrConvert: UInt => UInt) extends TLModule {
  val io = new Bundle {
    val client = new ClientTileLinkIO().flip
    val network = new TileLinkIO
  }

  val finisher = Module(new FinishUnit(clientId))
  finisher.io.grant <> io.network.grant
  io.network.finish <> finisher.io.finish

  val acq_with_header = ClientTileLinkHeaderCreator(io.client.acquire, clientId, addrConvert)
  val rel_with_header = ClientTileLinkHeaderCreator(io.client.release, clientId, addrConvert)
  val prb_without_header = DecoupledLogicalNetworkIOUnwrapper(io.network.probe)
  val gnt_without_header = finisher.io.refill

  io.network.acquire.bits := acq_with_header.bits
  io.network.acquire.valid := acq_with_header.valid && finisher.io.ready
  acq_with_header.ready := io.network.acquire.ready && finisher.io.ready
  io.network.release <> rel_with_header
  io.client.probe <> prb_without_header
  io.client.grant <> gnt_without_header
}

object ClientTileLinkHeaderCreator {
  def apply[T <: ClientToManagerChannel with HasCacheBlockAddress](
      in: DecoupledIO[T],
      clientId: Int,
      addrConvert: UInt => UInt): DecoupledIO[LogicalNetworkIO[T]] = {
    val out = Wire(new DecoupledIO(new LogicalNetworkIO(in.bits)))
    out.bits.payload := in.bits
    out.bits.header.src := UInt(clientId)
    out.bits.header.dst := addrConvert(in.bits.addr_block)
    out.valid := in.valid
    in.ready := out.ready
    out
  }
}

/** A port to convert [[uncore.ManagerTileLinkIO]].flip into [[uncore.TileLinkIO]].flip
  *
  * Creates network headers for [[uncore.Probe]] and [[uncore.Grant]] messagess,
  * calculating header.dst and filling in header.src.
  * Strips headers from [[uncore.Acquire]], [[uncore.Release]] and [[uncore.Finish]],
  * but supplies client_id instead.
  *
  * @param managerId the network port id of this agent
  * @param idConvert how a sharer id maps to a destination client port id
  */
class ManagerTileLinkNetworkPort(managerId: Int, idConvert: UInt => UInt) extends TLModule {
  val io = new Bundle {
    val manager = new ManagerTileLinkIO().flip
    val network = new TileLinkIO().flip
  }
  io.network.grant <> ManagerTileLinkHeaderCreator(io.manager.grant, managerId, (u: UInt) => u)
  io.network.probe <> ManagerTileLinkHeaderCreator(io.manager.probe, managerId, idConvert)
  io.manager.acquire.bits.client_id := io.network.acquire.bits.header.src
  io.manager.acquire <> DecoupledLogicalNetworkIOUnwrapper(io.network.acquire)
  io.manager.release.bits.client_id := io.network.release.bits.header.src
  io.manager.release <> DecoupledLogicalNetworkIOUnwrapper(io.network.release)
  io.manager.finish <> DecoupledLogicalNetworkIOUnwrapper(io.network.finish)
}

object ManagerTileLinkHeaderCreator {
  def apply[T <: ManagerToClientChannel with HasClientId](
      in: DecoupledIO[T],
      managerId: Int,
      idConvert: UInt => UInt): DecoupledIO[LogicalNetworkIO[T]] = {
    val out = Wire(new DecoupledIO(new LogicalNetworkIO(in.bits)))
    out.bits.payload := in.bits
    out.bits.header.src := UInt(managerId)
    out.bits.header.dst := idConvert(in.bits.client_id)
    out.valid := in.valid
    in.ready := out.ready
    out
  }
}

/** Struct for describing per-channel queue depths */
case class TileLinkDepths(acq: Int, prb: Int, rel: Int, gnt: Int, fin: Int)

/** Optionally enqueues each [[uncore.TileLinkChannel]] individually */
class TileLinkEnqueuer(depths: TileLinkDepths) extends Module {
  val io = new Bundle {
    val client = new TileLinkIO().flip
    val manager = new TileLinkIO
  }
  io.manager.acquire <> (if(depths.acq > 0) Queue(io.client.acquire, depths.acq) else io.client.acquire)
  io.client.probe    <> (if(depths.prb > 0) Queue(io.manager.probe,  depths.prb) else io.manager.probe)
  io.manager.release <> (if(depths.rel > 0) Queue(io.client.release, depths.rel) else io.client.release)
  io.client.grant    <> (if(depths.gnt > 0) Queue(io.manager.grant,  depths.gnt) else io.manager.grant)
  io.manager.finish  <> (if(depths.fin > 0) Queue(io.client.finish,  depths.fin) else io.client.finish)
}

object TileLinkEnqueuer {
  def apply(in: TileLinkIO, depths: TileLinkDepths)(p: Parameters): TileLinkIO = {
    val t = Module(new TileLinkEnqueuer(depths))(p)
    t.io.client <> in
    t.io.manager
  }
  def apply(in: TileLinkIO, depth: Int)(p: Parameters): TileLinkIO = {
    apply(in, TileLinkDepths(depth, depth, depth, depth, depth))(p)
  }
}

/** Utility functions for constructing TileLinkIO arbiters */
trait TileLinkArbiterLike extends TileLinkParameters {
  // Some shorthand type variables
  type ManagerSourcedWithId = ManagerToClientChannel with HasClientTransactionId
  type ClientSourcedWithId = ClientToManagerChannel with HasClientTransactionId
  type ClientSourcedWithIdAndData = ClientToManagerChannel with HasClientTransactionId with HasTileLinkData

  val arbN: Int // The number of ports on the client side

  // These abstract funcs are filled in depending on whether the arbiter mucks with the 
  // outgoing client ids to track sourcing and then needs to revert them on the way back
  def clientSourcedClientXactId(in: ClientSourcedWithId, id: Int): Bits
  def managerSourcedClientXactId(in: ManagerSourcedWithId): Bits
  def arbIdx(in: ManagerSourcedWithId): UInt

  // The following functions are all wiring helpers for each of the different types of TileLink channels

  def hookupClientSource[M <: ClientSourcedWithIdAndData](
      clts: Seq[DecoupledIO[LogicalNetworkIO[M]]],
      mngr: DecoupledIO[LogicalNetworkIO[M]]) {
    def hasData(m: LogicalNetworkIO[M]) = m.payload.hasMultibeatData()
    val arb = Module(new LockingRRArbiter(mngr.bits, arbN, tlDataBeats, Some(hasData _)))
    clts.zipWithIndex.zip(arb.io.in).map{ case ((req, id), arb) => {
      arb.valid := req.valid
      arb.bits := req.bits
      arb.bits.payload.client_xact_id := clientSourcedClientXactId(req.bits.payload, id)
      req.ready := arb.ready
    }}
    mngr <> arb.io.out
  }

  def hookupClientSourceHeaderless[M <: ClientSourcedWithIdAndData](
      clts: Seq[DecoupledIO[M]],
      mngr: DecoupledIO[M]) {
    def hasData(m: M) = m.hasMultibeatData()
    val arb = Module(new LockingRRArbiter(mngr.bits, arbN, tlDataBeats, Some(hasData _)))
    clts.zipWithIndex.zip(arb.io.in).map{ case ((req, id), arb) => {
      arb.valid := req.valid
      arb.bits := req.bits
      arb.bits.client_xact_id := clientSourcedClientXactId(req.bits, id)
      req.ready := arb.ready
    }}
    mngr <> arb.io.out
  }

  def hookupManagerSourceWithHeader[M <: ManagerToClientChannel](
      clts: Seq[DecoupledIO[LogicalNetworkIO[M]]], 
      mngr: DecoupledIO[LogicalNetworkIO[M]]) {
    mngr.ready := Bool(false)
    for (i <- 0 until arbN) {
      clts(i).valid := Bool(false)
      when (mngr.bits.header.dst === UInt(i)) {
        clts(i).valid := mngr.valid
        mngr.ready := clts(i).ready
      }
      clts(i).bits := mngr.bits
    }
  }

  def hookupManagerSourceWithId[M <: ManagerSourcedWithId](
      clts: Seq[DecoupledIO[LogicalNetworkIO[M]]], 
      mngr: DecoupledIO[LogicalNetworkIO[M]]) {
    mngr.ready := Bool(false)
    for (i <- 0 until arbN) {
      clts(i).valid := Bool(false)
      when (arbIdx(mngr.bits.payload) === UInt(i)) {
        clts(i).valid := mngr.valid
        mngr.ready := clts(i).ready
      }
      clts(i).bits := mngr.bits
      clts(i).bits.payload.client_xact_id := managerSourcedClientXactId(mngr.bits.payload)
    }
  }

  def hookupManagerSourceHeaderlessWithId[M <: ManagerSourcedWithId](
      clts: Seq[DecoupledIO[M]], 
      mngr: DecoupledIO[M]) {
    mngr.ready := Bool(false)
    for (i <- 0 until arbN) {
      clts(i).valid := Bool(false)
      when (arbIdx(mngr.bits) === UInt(i)) {
        clts(i).valid := mngr.valid
        mngr.ready := clts(i).ready
      }
      clts(i).bits := mngr.bits
      clts(i).bits.client_xact_id := managerSourcedClientXactId(mngr.bits)
    }
  }

  def hookupManagerSourceBroadcast[M <: Data](clts: Seq[DecoupledIO[M]], mngr: DecoupledIO[M]) {
    clts.map{ _.valid := mngr.valid }
    clts.map{ _.bits := mngr.bits }
    mngr.ready := clts.map(_.ready).reduce(_&&_)
  }

  def hookupFinish[M <: LogicalNetworkIO[Finish]]( clts: Seq[DecoupledIO[M]], mngr: DecoupledIO[M]) {
    val arb = Module(new RRArbiter(mngr.bits, arbN))
    arb.io.in <> clts
    mngr <> arb.io.out
  }
}

/** Abstract base case for any Arbiters that have UncachedTileLinkIOs */
abstract class UncachedTileLinkIOArbiter(val arbN: Int) extends Module with TileLinkArbiterLike {
  val io = new Bundle {
    val in = Vec(new UncachedTileLinkIO, arbN).flip
    val out = new UncachedTileLinkIO
  }
  hookupClientSource(io.in.map(_.acquire), io.out.acquire)
  hookupFinish(io.in.map(_.finish), io.out.finish)
  hookupManagerSourceWithId(io.in.map(_.grant), io.out.grant)
}

/** Abstract base case for any Arbiters that have cached TileLinkIOs */
abstract class TileLinkIOArbiter(val arbN: Int) extends Module with TileLinkArbiterLike {
  val io = new Bundle {
    val in = Vec(new TileLinkIO, arbN).flip
    val out = new TileLinkIO
  }
  hookupClientSource(io.in.map(_.acquire), io.out.acquire)
  hookupClientSource(io.in.map(_.release), io.out.release)
  hookupFinish(io.in.map(_.finish), io.out.finish)
  hookupManagerSourceBroadcast(io.in.map(_.probe), io.out.probe)
  hookupManagerSourceWithId(io.in.map(_.grant), io.out.grant)
}

/** Appends the port index of the arbiter to the client_xact_id */
trait AppendsArbiterId extends TileLinkArbiterLike {
  def clientSourcedClientXactId(in: ClientSourcedWithId, id: Int) =
    Cat(in.client_xact_id, UInt(id, log2Up(arbN)))
  def managerSourcedClientXactId(in: ManagerSourcedWithId) = 
    in.client_xact_id >> log2Up(arbN)
  def arbIdx(in: ManagerSourcedWithId) = in.client_xact_id(log2Up(arbN)-1,0).toUInt
}

/** Uses the client_xact_id as is (assumes it has been set to port index) */
trait PassesId extends TileLinkArbiterLike {
  def clientSourcedClientXactId(in: ClientSourcedWithId, id: Int) = in.client_xact_id
  def managerSourcedClientXactId(in: ManagerSourcedWithId) = in.client_xact_id
  def arbIdx(in: ManagerSourcedWithId) = in.client_xact_id
}

/** Overwrites some default client_xact_id with the port idx */
trait UsesNewId extends TileLinkArbiterLike {
  def clientSourcedClientXactId(in: ClientSourcedWithId, id: Int) = UInt(id, log2Up(arbN))
  def managerSourcedClientXactId(in: ManagerSourcedWithId) = UInt(0)
  def arbIdx(in: ManagerSourcedWithId) = in.client_xact_id
}

// Now we can mix-in thevarious id-generation traits to make concrete arbiter classes
class UncachedTileLinkIOArbiterThatAppendsArbiterId(val n: Int) extends UncachedTileLinkIOArbiter(n) with AppendsArbiterId
class UncachedTileLinkIOArbiterThatPassesId(val n: Int) extends UncachedTileLinkIOArbiter(n) with PassesId
class UncachedTileLinkIOArbiterThatUsesNewId(val n: Int) extends UncachedTileLinkIOArbiter(n) with UsesNewId
class TileLinkIOArbiterThatAppendsArbiterId(val n: Int) extends TileLinkIOArbiter(n) with AppendsArbiterId
class TileLinkIOArbiterThatPassesId(val n: Int) extends TileLinkIOArbiter(n) with PassesId
class TileLinkIOArbiterThatUsesNewId(val n: Int) extends TileLinkIOArbiter(n) with UsesNewId

/** Concrete uncached client-side arbiter that appends the arbiter's port id to client_xact_id */
class ClientUncachedTileLinkIOArbiter(val arbN: Int) extends Module with TileLinkArbiterLike with AppendsArbiterId {
  val io = new Bundle {
    val in = Vec(new ClientUncachedTileLinkIO, arbN).flip
    val out = new ClientUncachedTileLinkIO
  }
  hookupClientSourceHeaderless(io.in.map(_.acquire), io.out.acquire)
  hookupManagerSourceHeaderlessWithId(io.in.map(_.grant), io.out.grant)
}

/** Concrete client-side arbiter that appends the arbiter's port id to client_xact_id */
class ClientTileLinkIOArbiter(val arbN: Int) extends Module with TileLinkArbiterLike with AppendsArbiterId {
  val io = new Bundle {
    val in = Vec(new ClientTileLinkIO, arbN).flip
    val out = new ClientTileLinkIO
  }
  hookupClientSourceHeaderless(io.in.map(_.acquire), io.out.acquire)
  hookupClientSourceHeaderless(io.in.map(_.release), io.out.release)
  hookupManagerSourceBroadcast(io.in.map(_.probe), io.out.probe)
  hookupManagerSourceHeaderlessWithId(io.in.map(_.grant), io.out.grant)
}

/** Utility trait containing wiring functions to keep track of how many data beats have 
  * been sent or recieved over a particular [[uncore.TileLinkChannel]] or pair of channels. 
  *
  * Won't count message types that don't have data. 
  * Used in [[uncore.XactTracker]] and [[uncore.FinishUnit]].
  */
trait HasDataBeatCounters {
  type HasBeat = TileLinkChannel with HasTileLinkBeatId

  /** Returns the current count on this channel and when a message is done
    * @param inc increment the counter (usually .valid or .fire())
    * @param data the actual channel data
    * @param beat count to return for single-beat messages
    */
  def connectDataBeatCounter[S <: TileLinkChannel](inc: Bool, data: S, beat: UInt) = {
    val multi = data.hasMultibeatData()
    val (multi_cnt, multi_done) = Counter(inc && multi, data.tlDataBeats)
    val cnt = Mux(multi, multi_cnt, beat)
    val done = Mux(multi, multi_done, inc)
    (cnt, done)
  }

  /** Counter for beats on outgoing [[chisel.DecoupledIO]] */
  def connectOutgoingDataBeatCounter[T <: TileLinkChannel](in: DecoupledIO[T], beat: UInt = UInt(0)): (UInt, Bool) =
    connectDataBeatCounter(in.fire(), in.bits, beat)

  /** Returns done but not cnt. Use the addr_beat subbundle instead of cnt for beats on 
    * incoming channels in case of network reordering.
    */
  def connectIncomingDataBeatCounter[T <: TileLinkChannel](in: DecoupledIO[T]): Bool =
    connectDataBeatCounter(in.fire(), in.bits, UInt(0))._2

  /** Counter for beats on incoming DecoupledIO[LogicalNetworkIO[]]s returns done */
  def connectIncomingDataBeatCounterWithHeader[T <: TileLinkChannel](in: DecoupledIO[LogicalNetworkIO[T]]): Bool =
    connectDataBeatCounter(in.fire(), in.bits.payload, UInt(0))._2

  /** If the network might interleave beats from different messages, we need a Vec of counters,
    * one for every outstanding message id that might be interleaved.
    *
    * @param getId mapping from Message to counter id
    */
  def connectIncomingDataBeatCountersWithHeader[T <: TileLinkChannel with HasClientTransactionId](
      in: DecoupledIO[LogicalNetworkIO[T]],
      entries: Int,
      getId: LogicalNetworkIO[T] => UInt): Vec[Bool] = {
    Vec((0 until entries).map { i =>
      connectDataBeatCounter(in.fire() && getId(in.bits) === UInt(i), in.bits.payload, UInt(0))._2 
    })
  }

  /** Provides counters on two channels, as well a meta-counter that tracks how many
    * messages have been sent over the up channel but not yet responded to over the down channel
    *
    * @param max max number of outstanding ups with no down
    * @param up outgoing channel
    * @param down incoming channel
    * @param beat overrides cnts on single-beat messages
    * @param track whether up's message should be tracked
    * @return a tuple containing whether their are outstanding messages, up's count,
    *         up's done, down's count, down's done
    */
  def connectTwoWayBeatCounter[T <: TileLinkChannel, S <: TileLinkChannel](
      max: Int,
      up: DecoupledIO[T],
      down: DecoupledIO[S],
      beat: UInt = UInt(0),
      track: T => Bool = (t: T) => Bool(true)): (Bool, UInt, Bool, UInt, Bool) = {
    val cnt = Reg(init = UInt(0, width = log2Up(max+1)))
    val (up_idx, up_done) = connectDataBeatCounter(up.fire(), up.bits, beat)
    val (down_idx, down_done) = connectDataBeatCounter(down.fire(), down.bits, beat)
    val do_inc = up_done && track(up.bits)
    val do_dec = down_done
    cnt := Mux(do_dec,
            Mux(do_inc, cnt, cnt - UInt(1)),
            Mux(do_inc, cnt + UInt(1), cnt))
    (cnt > UInt(0), up_idx, up_done, down_idx, down_done)
  }
}

class ReorderQueueWrite[T <: Data](dType: T, tagWidth: Int) extends Bundle {
  val data = dType.cloneType
  val tag = UInt(width = tagWidth)

  override def cloneType =
    new ReorderQueueWrite(dType, tagWidth).asInstanceOf[this.type]
}

class ReorderQueue[T <: Data](dType: T, tagWidth: Int, size: Int)
    extends Module {
  val io = new Bundle {
    val enq = Decoupled(new ReorderQueueWrite(dType, tagWidth)).flip
    val deq = new Bundle {
      val valid = Bool(INPUT)
      val tag = UInt(INPUT, tagWidth)
      val data = dType.cloneType.asOutput
    }
    val full = Bool(OUTPUT)
  }

  val roq_data = Reg(Vec(dType.cloneType, size))
  val roq_tags = Reg(Vec(UInt(width = tagWidth), size))
  val roq_free = Reg(init = Vec.fill(size)(Bool(true)))

  val roq_enq_addr = PriorityEncoder(roq_free)
  val roq_deq_addr = PriorityEncoder(roq_tags.map(_ === io.deq.tag))

  io.enq.ready := roq_free.reduce(_ || _)
  io.deq.data := roq_data(roq_deq_addr)

  when (io.enq.valid && io.enq.ready) {
    roq_data(roq_enq_addr) := io.enq.bits.data
    roq_tags(roq_enq_addr) := io.enq.bits.tag
    roq_free(roq_enq_addr) := Bool(false)
  }

  when (io.deq.valid) {
    roq_free(roq_deq_addr) := Bool(true)
  }
}

class ClientTileLinkIOUnwrapperInfo extends Bundle {
  val voluntary = Bool()
  val builtin = Bool()
}

class ClientTileLinkIOUnwrapper extends TLModule {
  val io = new Bundle {
    val in = new ClientTileLinkIO().flip
    val out = new ClientUncachedTileLinkIO
  }

  def needsRoqEnq(channel: HasTileLinkData): Bool =
    !channel.hasMultibeatData() || channel.addr_beat === UInt(0)

  def needsRoqDeq(channel: HasTileLinkData): Bool =
    !channel.hasMultibeatData() || channel.addr_beat === UInt(tlDataBeats - 1)

  val acqArb = Module(new LockingRRArbiter(new Acquire, 2, tlDataBeats,
    Some((acq: Acquire) => acq.hasMultibeatData())))
  val roqArb = Module(new RRArbiter(new ReorderQueueWrite(
    new ClientTileLinkIOUnwrapperInfo, tlClientXactIdBits), 2))

  val iacq = io.in.acquire.bits
  val irel = io.in.release.bits
  val ognt = io.out.grant.bits

  val iacq_wait = needsRoqEnq(iacq) && !roqArb.io.in(0).ready
  val irel_wait = needsRoqEnq(irel) && !roqArb.io.in(1).ready

  roqArb.io.in(0).valid := io.in.acquire.valid && needsRoqEnq(iacq)
  roqArb.io.in(0).bits.data.voluntary := Bool(false)
  roqArb.io.in(0).bits.data.builtin := iacq.isBuiltInType()
  roqArb.io.in(0).bits.tag := iacq.client_xact_id

  acqArb.io.in(0).valid := io.in.acquire.valid && !iacq_wait
  acqArb.io.in(0).bits := Acquire(
    is_builtin_type = Bool(true),
    a_type = Mux(iacq.isBuiltInType(),
      iacq.a_type, Acquire.getBlockType),
    client_xact_id = iacq.client_xact_id,
    addr_block = iacq.addr_block,
    addr_beat = iacq.addr_beat,
    data = iacq.data,
    union = Mux(iacq.isBuiltInType(),
      iacq.union, Cat(MT_Q, M_XRD, Bool(true))))
  io.in.acquire.ready := acqArb.io.in(0).ready && !iacq_wait

  roqArb.io.in(1).valid := io.in.release.valid && needsRoqEnq(iacq)
  roqArb.io.in(1).bits.data.voluntary := irel.isVoluntary()
  roqArb.io.in(1).bits.data.builtin := Bool(true)
  roqArb.io.in(1).bits.tag := irel.client_xact_id

  acqArb.io.in(1).valid := io.in.release.valid && !irel_wait
  acqArb.io.in(1).bits := PutBlock(
    client_xact_id = irel.client_xact_id,
    addr_block = irel.addr_block,
    addr_beat = irel.addr_beat,
    data = irel.data,
    wmask = Acquire.fullWriteMask)
  io.in.release.ready := acqArb.io.in(1).ready && !irel_wait

  io.out.acquire <> acqArb.io.out

  val roq = Module(new ReorderQueue(
    new ClientTileLinkIOUnwrapperInfo, tlClientXactIdBits, tlMaxClientsPerPort))
  roq.io.enq <> roqArb.io.out
  roq.io.deq.valid := io.out.grant.valid && needsRoqDeq(ognt)
  roq.io.deq.tag := ognt.client_xact_id

  val gnt_builtin = roq.io.deq.data.builtin
  val gnt_voluntary = roq.io.deq.data.voluntary

  io.in.grant.valid := io.out.grant.valid
  io.in.grant.bits := Grant(
    is_builtin_type = gnt_builtin,
    g_type = MuxCase(ognt.g_type, Seq(
      (!gnt_builtin, tlCoh.getExclusiveGrantType),
      (gnt_voluntary, Grant.voluntaryAckType))),
    client_xact_id = ognt.client_xact_id,
    manager_xact_id = ognt.manager_xact_id,
    addr_beat = ognt.addr_beat,
    data = ognt.data)
  io.out.grant.ready := io.in.grant.ready

  io.in.probe.valid := Bool(false)
}

class NASTIIOTileLinkIOConverterInfo extends TLBundle {
  val byteOff = UInt(width = tlByteAddrBits)
  val subblock = Bool()
}

class NASTIIOTileLinkIOConverter extends TLModule with NASTIParameters {
  val io = new Bundle {
    val tl = new ClientUncachedTileLinkIO().flip
    val nasti = new NASTIIO
  }

  private def opSizeToXSize(ops: UInt) = MuxLookup(ops, UInt("b111"), Seq(
    MT_B  -> UInt(0),
    MT_BU -> UInt(0),
    MT_H  -> UInt(1),
    MT_HU -> UInt(1),
    MT_W  -> UInt(2),
    MT_D  -> UInt(3),
    MT_Q  -> UInt(log2Up(tlDataBytes))))

  val dataBits = tlDataBits*tlDataBeats 
  val dstIdBits = params(LNHeaderBits)
  require(tlDataBits == nastiXDataBits, "Data sizes between LLC and MC don't agree") // TODO: remove this restriction
  require(tlDataBeats < (1 << nastiXLenBits), "Can't have that many beats")
  require(dstIdBits + tlClientXactIdBits < nastiXIdBits, "NASTIIO converter is going truncate tags: " + dstIdBits + " + " + tlClientXactIdBits + " >= " + nastiXIdBits)

  io.tl.acquire.ready := Bool(false)

  io.nasti.b.ready := Bool(false)
  io.nasti.r.ready := Bool(false)
  io.nasti.ar.valid := Bool(false)
  io.nasti.aw.valid := Bool(false)
  io.nasti.w.valid := Bool(false)

  val dst_off = dstIdBits + tlClientXactIdBits
  val acq_has_data = io.tl.acquire.bits.hasData()
  val is_write = io.tl.acquire.valid && acq_has_data

  // Decompose outgoing TL Acquires into NASTI address and data channels
  val active_out = Reg(init=Bool(false))
  val cmd_sent_out = Reg(init=Bool(false))
  val tag_out = Reg(UInt(width = nastiXIdBits))
  val addr_out = Reg(UInt(width = nastiXAddrBits))
  val has_data = Reg(init=Bool(false))
  val is_subblock = io.tl.acquire.bits.isSubBlockType()
  val (tl_cnt_out, tl_wrap_out) = Counter(
    io.tl.acquire.fire() && io.tl.acquire.bits.hasMultibeatData(), tlDataBeats)
  val tl_done_out = Reg(init=Bool(false))

  val roq = Module(new ReorderQueue(
    new NASTIIOTileLinkIOConverterInfo,
    nastiRIdBits, tlMaxClientsPerPort))

  val (nasti_cnt_out, nasti_wrap_out) = Counter(
    io.nasti.r.fire() && !roq.io.deq.data.subblock, tlDataBeats)

  roq.io.enq.valid := io.tl.acquire.fire() && !acq_has_data
  roq.io.enq.bits.tag := io.nasti.ar.bits.id
  roq.io.enq.bits.data.byteOff := io.tl.acquire.bits.addr_byte()
  roq.io.enq.bits.data.subblock := is_subblock
  roq.io.deq.valid := io.nasti.r.fire() && (nasti_wrap_out || roq.io.deq.data.subblock)
  roq.io.deq.tag := io.nasti.r.bits.id

  io.nasti.ar.bits := NASTIReadAddressChannel(
    id = tag_out,
    addr = addr_out, 
    size = UInt(log2Ceil(tlDataBytes)),
    len = Mux(has_data, UInt(tlDataBeats - 1), UInt(0)))
  io.nasti.aw.bits := io.nasti.ar.bits
  io.nasti.w.bits := NASTIWriteDataChannel(
    data = io.tl.acquire.bits.data,
    strb = io.tl.acquire.bits.wmask(),
    last = tl_wrap_out || (io.tl.acquire.fire() && is_subblock))

  when(!active_out){
    io.tl.acquire.ready := io.nasti.w.ready
    io.nasti.w.valid := is_write
    when(io.tl.acquire.fire()) {
      active_out := (!is_write && !io.nasti.ar.ready) ||
                    (is_write && !(io.nasti.aw.ready && io.nasti.w.ready)) ||
                    (io.nasti.w.valid && Bool(tlDataBeats > 1))
      io.nasti.aw.valid := is_write
      io.nasti.ar.valid := !is_write
      cmd_sent_out := (!is_write && io.nasti.ar.ready) || (is_write && io.nasti.aw.ready)
      val tag = io.tl.acquire.bits.client_xact_id
      val addr = io.tl.acquire.bits.full_addr()
      when(is_write) {
        io.nasti.aw.bits.id := tag
        io.nasti.aw.bits.addr := addr
        io.nasti.aw.bits.len := Mux(!is_subblock, UInt(tlDataBeats-1), UInt(0))
      } .otherwise {
        io.nasti.ar.bits.id := tag
        io.nasti.ar.bits.addr := addr
        io.nasti.ar.bits.len := Mux(!is_subblock, UInt(tlDataBeats-1), UInt(0))
        
        io.nasti.ar.bits.size := opSizeToXSize(io.tl.acquire.bits.op_size())
      }
      tag_out := tag
      addr_out := addr
      has_data := acq_has_data
      tl_done_out := tl_wrap_out || is_subblock
    }
  }
  when(active_out) {
    io.nasti.ar.valid := !cmd_sent_out && !has_data
    io.nasti.aw.valid := !cmd_sent_out && has_data
    cmd_sent_out := cmd_sent_out || io.nasti.ar.fire() || io.nasti.aw.fire()
    when(has_data && !tl_done_out) {
      io.tl.acquire.ready := io.nasti.w.ready
      io.nasti.w.valid := io.tl.acquire.valid
    }
    when(tl_wrap_out) { tl_done_out := Bool(true) }
    when(cmd_sent_out && roq.io.enq.ready && (!has_data || tl_done_out)) {
      active_out := Bool(false)
    }
  }
  println(f"\tnastiRIdBits:\t${io.nasti.r.bits.nastiRIdBits}%x")
  println(f"\tnastiWIdBits:\t${io.nasti.r.bits.nastiWIdBits}%x")
  // Aggregate incoming NASTI responses into TL Grants
  val (tl_cnt_in, tl_wrap_in) = Counter(io.tl.grant.fire() && io.tl.grant.bits.hasMultibeatData(), tlDataBeats)
  val gnt_arb = Module(new Arbiter(new GrantToDst, 2))
  io.tl.grant <> gnt_arb.io.out

  val r_aligned_data = Mux(roq.io.deq.data.subblock,
    io.nasti.r.bits.data << Cat(roq.io.deq.data.byteOff, UInt(0, 3)),
    io.nasti.r.bits.data)

  gnt_arb.io.in(0).valid := io.nasti.r.valid
  io.nasti.r.ready := gnt_arb.io.in(0).ready
  gnt_arb.io.in(0).bits := Grant(
    is_builtin_type = Bool(true),
    g_type = Mux(roq.io.deq.data.subblock,
      Grant.getDataBeatType, Grant.getDataBlockType),
    client_xact_id = io.nasti.r.bits.id,
    manager_xact_id = UInt(0),
    addr_beat = tl_cnt_in,
    data = r_aligned_data)

  gnt_arb.io.in(1).valid := io.nasti.b.valid
  io.nasti.b.ready := gnt_arb.io.in(1).ready
  gnt_arb.io.in(1).bits := Grant(
    is_builtin_type = Bool(true),
    g_type = Grant.putAckType,
    client_xact_id = io.nasti.b.bits.id,
    manager_xact_id = UInt(0),
    addr_beat = UInt(0),
    data = Bits(0))
}
