package com.naoufel.decentralchess.p2p

import com.naoufel.decentralchess.chess.GameHistory
import com.naoufel.decentralchess.chess.Move
import com.naoufel.decentralchess.chess.PieceType
import com.naoufel.decentralchess.chess.Side
import com.naoufel.decentralchess.chess.Square
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class PeerId(val value: String)

interface PeerTransport {
    suspend fun connect(peer: PeerId)
    suspend fun send(peer: PeerId, payload: ByteArray)
    suspend fun close(peer: PeerId)
}

enum class MessageType { HELLO, MATCH_OFFER, MATCH_ACCEPT, MOVE, ACK, HASH_CHECKPOINT, MATCH_FINAL, STATE_REQUEST, STATE_RESPONSE }

data class ProtocolEnvelope(
    val version: Int,
    val messageId: String,
    val sessionId: String,
    val senderPeerId: String,
    val type: MessageType,
    val sequence: Long,
    val payload: ByteArray,
    val signatureBase64: String? = null
) {
    init {
        require(version == 1) { "Unsupported P2P protocol version" }
        require(messageId.isNotBlank() && sessionId.isNotBlank()) { "IDs must not be blank" }
        require(senderPeerId.isNotBlank()) { "senderPeerId must not be blank" }
        require(sequence >= 0) { "sequence must be non-negative" }
    }

    fun canonicalHeader(): ByteArray =
        listOf(version.toString(), messageId, sessionId, senderPeerId, type.name, sequence.toString(), payload.size.toString())
            .joinToString("\n") { it.length.toString() + ":" + it }
            .toByteArray(StandardCharsets.UTF_8)
}

data class HashCheckpoint(
    val sessionId: String,
    val sequence: Long,
    val moveCount: Int,
    val gameHash: String
)

sealed class P2PMatchException(message: String) : IllegalStateException(message) {
    class InvalidSession(expected: String, actual: String) : P2PMatchException("Invalid session: expected $expected, received $actual")
    class InvalidSender(expected: String, actual: String) : P2PMatchException("Invalid sender: expected $expected, received $actual")
    class InvalidSequence(expected: Long, actual: Long) : P2PMatchException("Invalid sequence: expected $expected, received $actual")
    class WrongTurn(expected: Side, actual: Side) : P2PMatchException("Wrong turn: expected $expected, current side is $actual")
    class IllegalMove(move: Move) : P2PMatchException("Illegal network move: $move")
    class PreviousHashMismatch(expected: String, actual: String) : P2PMatchException("Previous game hash mismatch: expected $expected, received $actual")
    class ResultHashMismatch(expected: String, actual: String) : P2PMatchException("Resulting game hash mismatch: expected $expected, received $actual")
    class MoveCountMismatch(expected: Int, actual: Int) : P2PMatchException("Move count mismatch: expected $expected, received $actual")
    class InvalidMessage(message: String) : P2PMatchException(message)
}

data class MoveMessage(
    val move: Move,
    val previousGameHash: String,
    val resultingGameHash: String,
    val moveCount: Int
) {
    init {
        require(previousGameHash.length == 64) { "previousGameHash must be SHA-256 hex" }
        require(resultingGameHash.length == 64) { "resultingGameHash must be SHA-256 hex" }
        require(moveCount >= 1) { "moveCount must be positive" }
    }
}

object MoveMessageCodec {
    private const val VERSION = "move-v1"

    fun encode(message: MoveMessage): ByteArray {
        val promotion = message.move.promotion?.name ?: "-"
        val value = listOf(
            VERSION, message.move.from.file, message.move.from.rank, message.move.to.file, message.move.to.rank,
            promotion, message.previousGameHash, message.resultingGameHash, message.moveCount
        ).joinToString("|")
        return value.toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(payload: ByteArray): MoveMessage {
        val parts = payload.toString(StandardCharsets.UTF_8).split("|")
        if (parts.size != 9 || parts[0] != VERSION) throw P2PMatchException.InvalidMessage("Malformed MOVE payload")
        fun coordinate(index: Int): Int = parts[index].toIntOrNull() ?: throw P2PMatchException.InvalidMessage("Invalid coordinate")
        val from = Square(coordinate(1), coordinate(2))
        val to = Square(coordinate(3), coordinate(4))
        if (from.file !in 0..7 || from.rank !in 0..7 || to.file !in 0..7 || to.rank !in 0..7) {
            throw P2PMatchException.InvalidMessage("Coordinate outside chess board")
        }
        val promotion = when (parts[5]) {
            "-" -> null
            else -> runCatching { PieceType.valueOf(parts[5]) }.getOrElse {
                throw P2PMatchException.InvalidMessage("Invalid promotion piece")
            }
        }
        val moveCount = parts[8].toIntOrNull() ?: throw P2PMatchException.InvalidMessage("Invalid move count")
        return MoveMessage(Move(from, to, promotion), parts[6], parts[7], moveCount)
    }
}

data class MatchStateSnapshot(
    val sessionId: String,
    val localPeerId: PeerId,
    val remotePeerId: PeerId,
    val localSide: Side,
    val expectedIncomingSequence: Long,
    val nextOutgoingSequence: Long,
    val moveCount: Int,
    val gameHash: String
)

class MatchStateMachine(
    val sessionId: String,
    val localPeerId: PeerId,
    val remotePeerId: PeerId,
    val localSide: Side,
    initialHistory: GameHistory = GameHistory()
) {
    private val history = initialHistory
    private var expectedIncomingSequence = 0L
    private var nextOutgoingSequence = 0L

    fun history(): GameHistory = history

    fun snapshot(): MatchStateSnapshot = MatchStateSnapshot(
        sessionId, localPeerId, remotePeerId, localSide, expectedIncomingSequence,
        nextOutgoingSequence, history.moveHistory.size, history.gameHash()
    )

    fun createLocalMove(move: Move): ProtocolEnvelope {
        if (history.current.sideToMove != localSide) {
            throw P2PMatchException.WrongTurn(localSide, history.current.sideToMove)
        }
        val previousHash = history.gameHash()
        runCatching { history.play(move) }.getOrElse { throw P2PMatchException.IllegalMove(move) }
        val resultingHash = history.gameHash()
        val message = MoveMessage(move, previousHash, resultingHash, history.moveHistory.size)
        val sequence = nextOutgoingSequence++
        return ProtocolEnvelope(
            P2PProtocol.VERSION, UUID.randomUUID().toString(), sessionId, localPeerId.value,
            MessageType.MOVE, sequence, MoveMessageCodec.encode(message)
        )
    }

    fun receive(envelope: ProtocolEnvelope): MoveMessage {
        if (envelope.sessionId != sessionId) throw P2PMatchException.InvalidSession(sessionId, envelope.sessionId)
        if (envelope.senderPeerId != remotePeerId.value) throw P2PMatchException.InvalidSender(remotePeerId.value, envelope.senderPeerId)
        if (envelope.type != MessageType.MOVE) throw P2PMatchException.InvalidMessage("Expected MOVE, received \${envelope.type}")
        if (envelope.sequence != expectedIncomingSequence) {
            throw P2PMatchException.InvalidSequence(expectedIncomingSequence, envelope.sequence)
        }

        val message = MoveMessageCodec.decode(envelope.payload)
        val localHash = history.gameHash()
        if (message.previousGameHash != localHash) throw P2PMatchException.PreviousHashMismatch(localHash, message.previousGameHash)

        val remoteSide = if (localSide == Side.WHITE) Side.BLACK else Side.WHITE
        if (history.current.sideToMove != remoteSide) throw P2PMatchException.WrongTurn(remoteSide, history.current.sideToMove)
        if (message.moveCount != history.moveHistory.size + 1) {
            throw P2PMatchException.MoveCountMismatch(history.moveHistory.size + 1, message.moveCount)
        }

        runCatching { history.play(message.move) }.getOrElse { throw P2PMatchException.IllegalMove(message.move) }
        val actualResultHash = history.gameHash()
        if (message.resultingGameHash != actualResultHash) {
            history.undo()
            throw P2PMatchException.ResultHashMismatch(actualResultHash, message.resultingGameHash)
        }
        if (message.moveCount != history.moveHistory.size) {
            history.undo()
            throw P2PMatchException.MoveCountMismatch(history.moveHistory.size, message.moveCount)
        }
        expectedIncomingSequence++
        return message
    }
}

object P2PProtocol {
    const val VERSION = 1

    fun nextSequence(previous: Long): Long = previous + 1

    fun newSession(local: PeerId, remote: PeerId, matchId: String): MatchStateMachine {
        require(local.value.isNotBlank() && remote.value.isNotBlank()) { "Peer IDs must not be blank" }
        require(local.value != remote.value) { "A peer cannot match against itself" }
        require(matchId.isNotBlank()) { "matchId must not be blank" }
        return MatchStateMachine(UUID.randomUUID().toString(), local, remote, Side.WHITE)
    }

    fun payloadHash(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
}
