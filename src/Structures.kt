import java.security.PublicKey
import java.security.Timestamp
import java.util.*

data class Amount(val pennies: Int, val currency: Currency) {
    init {
        // Negative amounts are of course a vital part of any ledger, but negative values are only valid in certain
        // contexts: you cannot send a negative amount of cash, but you can (sometimes) have a negative balance.
        // TODO: Think about how positive-only vs positive-or-negative amounts can be represented in the type system.
        require(pennies >= 0) { "Negative amounts are not allowed: $pennies" }
    }

    operator fun plus(other: Amount): Amount {
        require(other.currency == currency)
        return Amount(pennies + other.pennies, currency)
    }

    operator fun minus(other: Amount): Amount {
        require(other.currency == currency)
        return Amount(pennies - other.pennies, currency)
    }
}

/**
 * A contract state (or just "state") contains opaque data used by a contract program. It can be thought of as a disk
 * file that the program can use to persist data across transactions. States are immutable: once created they are never
 * updated, instead, any changes must generate a new successor state.
 */
interface ContractState {
    /**
     * Refers to a bytecode program that has previously been published to the network. This contract program
     * will be executed any time this state is used in an input. It must accept in order for the
     * transaction to proceed.
     */
    val programRef: SecureHash
}

/**
 * A stateref is a pointer to a state, normally a hash but this version is opaque.
 */
class ContractStateRef(private val hash: SecureHash.SHA256)

/**
 * A transaction wraps the data needed to calculate one or more successor states from a set of input states.
 * The data here is provided in lightly processed form to the verify method of each input states contract program.
 * Specifically, the input state refs are dereferenced into real [ContractState]s and the args are signature checked
 * and institutions are looked up (if known).
 */
class Transaction(
    /** Arbitrary data passed to the program of each input state. */
    val args: List<SignedCommand>,
    /** The input states which will be consumed/invalidated by the execution of this transaction. */
    val inputStates: List<ContractStateRef>,
    /** The states that will be generated by the execution of this transaction. */
    val outputStates: List<ContractState>
)

/**
 * A transition groups one or more transactions together, and combines them with a signed timestamp. A transaction
 * may not stand independent of a transition and all transactions are applied or reverted together as a unit.
 *
 * Consider the following attack: a malicious actor extracts a single transaction like "pay $X to me" from a transition
 * and then broadcasts it with a fresh timestamp. This cannot work because the original transition will always take
 * priority over the later attempt as it has an earlier timestamp. As long as both are visible, the first transition
 * will always win.
 */
data class Transition(
    val tx: Transaction,

    /** Timestamp of the serialised transaction as fetched from a timestamping authority (RFC 3161) */
    val signedTimestamp: Timestamp
)

class Institution(
    val name: String,
    val owningKey: PublicKey
) {
    override fun toString() = name
}

interface Command

/** Provided as an input to a contract; converted to a [VerifiedSignedCommand] by the platform before execution. */
data class SignedCommand(
    /** Signature over this object to prove who it came from */
    val commandDataSignature: DigitalSignature.WithKey,

    /** Command data, deserialized to an implementation of [Command] */
    val serialized: ByteArray,
    /** Identifies what command the serialized data contains (should maybe be a hash too) */
    val classID: String,
    /** Hash of a derivative of the transaction data, so this command can only ever apply to one transaction */
    val txBindingHash: SecureHash.SHA256
)

/** Obtained from a [SignedCommand], deserialised and signature checked */
data class VerifiedSignedCommand(
    val signer: PublicKey,
    /** If the public key was recognised, the looked up institution is available here, otherwise it's null */
    val signingInstitution: Institution?,
    val command: Command
)

/**
 * Implemented by a program that implements business logic on the shared ledger. All participants run this code for
 * every [Transaction] they see on the network, for every input state. All input states must accept the transaction
 * for it to be accepted: failure of any aborts the entire thing.
 */
interface Contract {
    /** Must throw an exception if there's a problem that should prevent state transition. */
    fun verify(inStates: List<ContractState>, outStates: List<ContractState>, args: List<VerifiedSignedCommand>)
}

