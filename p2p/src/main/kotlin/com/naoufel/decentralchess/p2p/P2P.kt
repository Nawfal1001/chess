package com.naoufel.decentralchess.p2p

import com.naoufel.decentralchess.chess.ChessNotation
import com.naoufel.decentralchess.chess.GameHistory
import com.naoufel.decentralchess.chess.Move
import com.naoufel.decentralchess.chess.PieceType
import com.naoufel.decentralchess.chess.Side
import com.naoufel.decentralchess.chess.Square
import com.naoufel.decentralchess.identity.AndroidKeystoreIdentityProvider
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
    val matchId: String,
    val senderPeerId: String,
    val type: MessageType,
    val sequence: Long,
    val payload: ByteArray,
    val senderPublicKeyBase64: String? = null,
    val signatureBase64: String? = null
) {
    init {
        require(version == 1) { "Unsupported P2P protocol version" }
        require(messageId.isNotBlank() && sessionId.isNotBlank() && matchId.isNotBlank()) { "IDs must not be blank" }
        require(senderPeerId.isNotBlank()) { "senderPeerId must not be blank" }
        require(sequence >= 0) { "sequence must be non-negative" }
    }

    fun canonicalBytes(): ByteArray {
        val payloadHash = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        return listOf(
            "p2p-envelope-v1", version.toString(), messageId, sessionId, matchId, senderPeerId,
            type.name, sequence.toString(), senderPublicKeyBase64 ?: "-", payloadHash
        ).joinToString("\n") { it.length.toString() + ":" + it }
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun canonicalHeader(): ByteArray = canonicalBytes()
}

data class EnvelopeIdentity(
    val peerId: PeerId,
    val publicKeyBase64: String,
    val signer: (ByteArray) -> ByteArray
) {
    fun sign(envelope: ProtocolEnvelope): String =
        java.util.Base64.getEncoder().encodeToString(signer(envelope.canonicalBytes()))
}

object EnvelopeVerifier {
    fun verify(envelope: ProtocolEnvelope): Boolean = runCatching {
        val key = envelope.senderPublicKeyBase64 ?: return false
        val signature = envelope.signatureBase64 ?: return false
        val keyBytes = java.util.Base64.getDecoder().decode(key)
        val expectedPeerId = MessageDigest.getInstance("SHA-256").digest(keyBytes)
            .joinToString("") { "%02x".format(it) }
        if (expectedPeerId != envelope.senderPeerId) return false
        AndroidKeystoreIdentityProvider.verify(
            envelope.canonicalBytes(),
            java.util.Base64.getDecoder().decode(signature),
            key
        )
    }.getOrDefault(false)
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

data class StateRequest(
    val sessionId: String,
    val matchId: String,
    val expectedIncomingSequence: Long,
    val currentGameHash: String
) {
    init {
        require(sessionId.isNotBlank() && matchId.isNotBlank()) { "State request IDs must not be blank" }
        require(expectedIncomingSequence >= 0) { "expectedIncomingSequence must be non-negative" }
        require(currentGameHash.length == 64) { "currentGameHash must be SHA-256 hex" }
    }
}

object StateRequestCodec {
    private const val VERSION = "state-request-v1"

    fun encode(request: StateRequest): ByteArray =
        listOf(VERSION, request.sessionId, request.matchId, request.expectedIncomingSequence, request.currentGameHash)
            .joinToString("|").toByteArray(StandardCharsets.UTF_8)

    fun decode(payload: ByteArray): StateRequest {
        val parts = payload.toString(StandardCharsets.UTF_8).split("|")
        if (parts.size != 5 || parts[0] != VERSION) throw P2PMatchException.InvalidMessage("Malformed STATE_REQUEST payload")
        val sequence = parts[3].toLongOrNull() ?: throw P2PMatchException.InvalidMessage("Invalid requested sequence")
        return runCatching { StateRequest(parts[1], parts[2], sequence, parts[4]) }.getOrElse {
            throw P2PMatchException.InvalidMessage("Invalid STATE_REQUEST fields")
        }
    }
}

data class StateResponse(
    val sessionId: String,
    val matchId: String,
    val requestMessageId: String,
    val baseSequence: Long,
    val moveCount: Int,
    val initialFen: String,
    val finalFen: String,
    val gameHash: String,
    val pgn: String
) {
    init {
        require(sessionId.isNotBlank() && matchId.isNotBlank() && requestMessageId.isNotBlank()) { "State response IDs must not be blank" }
        require(baseSequence >= 0) { "baseSequence must be non-negative" }
        require(moveCount >= 0) { "moveCount must be non-negative" }
        require(gameHash.length == 64) { "gameHash must be SHA-256 hex" }
        require(pgn.isNotBlank()) { "pgn must not be blank" }
    }
}

object StateResponseCodec {
    private const val VERSION = "state-response-v1"

    fun encode(response: StateResponse): ByteArray {
        val pgn = java.util.Base64.getEncoder().encodeToString(response.pgn.toByteArray(StandardCharsets.UTF_8))
        return listOf(VERSION, response.sessionId, response.matchId, response.requestMessageId, response.baseSequence, response.moveCount,
            response.initialFen, response.finalFen, response.gameHash, pgn).joinToString("|")
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(payload: ByteArray): StateResponse {
        val parts = payload.toString(StandardCharsets.UTF_8).split("|")
        if (parts.size != 10 || parts[0] != VERSION) throw P2PMatchException.InvalidMessage("Malformed STATE_RESPONSE payload")
        val baseSequence = parts[4].toLongOrNull() ?: throw P2PMatchException.InvalidMessage("Invalid base sequence")
        val moveCount = parts[5].toIntOrNull() ?: throw P2PMatchException.InvalidMessage("Invalid move count")
        val pgn = runCatching { String(java.util.Base64.getDecoder().decode(parts[9]), StandardCharsets.UTF_8) }.getOrElse {
            throw P2PMatchException.InvalidMessage("Invalid PGN encoding")
        }
        return runCatching { StateResponse(parts[1], parts[2], parts[3], baseSequence, moveCount, parts[6], parts[7], parts[8], pgn) }.getOrElse {
            throw P2PMatchException.InvalidMessage("Invalid STATE_RESPONSE fields")
        }
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
    val matchId: String,
    private val localIdentity: EnvelopeIdentity? = null,
    private val remotePublicKeyBase64: String? = null,
    initialHistory: GameHistory = GameHistory()
) {
    private val history = initialHistory
    private var expectedIncomingSequence = 0L
    private var nextOutgoingSequence = 0L
    private var pendingStateRequestMessageId: String? = null

    fun history(): GameHistory = history

    fun snapshot(): MatchStateSnapshot = MatchStateSnapshot(
        sessionId, localPeerId, remotePeerId, localSide, expectedIncomingSequence,
        nextOutgoingSequence, history.moveHistory.size, history.gameHash()
    )

    fun createLocalMove(move: Move): ProtocolEnvelope {
        if (history.current.sideToMove != localSide) {
            throw P2PMatchException.WrongTurn(localSide, history.current.sideToMove)
        }
        if (localIdentity != null && localIdentity.peerId != localPeerId) {
            throw P2PMatchException.InvalidSender(localPeerId.value, localIdentity.peerId.value)
        }
        val previousHash = history.gameHash()
        runCatching { history.play(move) }.getOrElse { throw P2PMatchException.IllegalMove(move) }
        val resultingHash = history.gameHash()
        val message = MoveMessage(move, previousHash, resultingHash, history.moveHistory.size)
        val sequence = nextOutgoingSequence
        val unsigned = ProtocolEnvelope(
            P2PProtocol.VERSION, UUID.randomUUID().toString(), sessionId, matchId, localPeerId.value,
            MessageType.MOVE, sequence, MoveMessageCodec.encode(message),
            localIdentity?.publicKeyBase64
        )
        val signed = if (localIdentity == null) unsigned else
            unsigned.copy(signatureBase64 = localIdentity.sign(unsigned))
        nextOutgoingSequence++
        return signed
    }

    fun createStateRequest(): ProtocolEnvelope {
        val request = StateRequest(sessionId, matchId, expectedIncomingSequence, history.gameHash())
        val envelope = buildEnvelope(MessageType.STATE_REQUEST, StateRequestCodec.encode(request))
        pendingStateRequestMessageId = envelope.messageId
        return envelope
    }

    fun createStateResponse(requestEnvelope: ProtocolEnvelope): ProtocolEnvelope {
        validateControlEnvelope(requestEnvelope, MessageType.STATE_REQUEST)
        val request = StateRequestCodec.decode(requestEnvelope.payload)
        if (request.sessionId != sessionId || request.matchId != matchId) {
            throw P2PMatchException.InvalidMessage("STATE_REQUEST does not match this match")
        }
        val response = StateResponse(sessionId, matchId, requestEnvelope.messageId, 0, history.moveHistory.size,
            history.initialPosition().toFen(), history.current.toFen(), history.gameHash(), ChessNotation.exportPgn(history))
        return buildEnvelope(MessageType.STATE_RESPONSE, StateResponseCodec.encode(response))
    }

    fun receive(envelope: ProtocolEnvelope): MoveMessage {
        if (envelope.sessionId != sessionId) throw P2PMatchException.InvalidSession(sessionId, envelope.sessionId)
        if (envelope.matchId != matchId) throw P2PMatchException.InvalidMessage("Invalid match ID")
        if (envelope.senderPeerId != remotePeerId.value) throw P2PMatchException.InvalidSender(remotePeerId.value, envelope.senderPeerId)
        if (remotePublicKeyBase64 != null && envelope.senderPublicKeyBase64 != remotePublicKeyBase64) {
            throw P2PMatchException.InvalidMessage("Unexpected sender public key")
        }
        if (remotePublicKeyBase64 != null && !EnvelopeVerifier.verify(envelope)) {
            throw P2PMatchException.InvalidMessage("Invalid MOVE signature")
        }
        if (envelope.type != MessageType.MOVE) throw P2PMatchException.InvalidMessage("Expected MOVE, received ${envelope.type}")
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

    fun receiveStateResponse(envelope: ProtocolEnvelope): StateResponse {
        validateControlEnvelope(envelope, MessageType.STATE_RESPONSE)
        val response = StateResponseCodec.decode(envelope.payload)
        if (response.sessionId != sessionId || response.matchId != matchId) {
            throw P2PMatchException.InvalidMessage("STATE_RESPONSE does not match this match")
        }
        if (response.requestMessageId != pendingStateRequestMessageId) {
            throw P2PMatchException.InvalidMessage("STATE_RESPONSE does not match an outstanding STATE_REQUEST")
        }
        if (response.baseSequence != 0L) {
            throw P2PMatchException.InvalidMessage("Unsupported state response base sequence")
        }

        val recovered = runCatching { ChessNotation.importPgn(response.pgn) }.getOrElse {
            throw P2PMatchException.InvalidMessage("STATE_RESPONSE PGN replay failed")
        }
        if (recovered.initialPosition().toFen() != response.initialFen) {
            throw P2PMatchException.InvalidMessage("STATE_RESPONSE initial FEN mismatch")
        }
        if (recovered.current.toFen() != response.finalFen) {
            throw P2PMatchException.InvalidMessage("STATE_RESPONSE final FEN mismatch")
        }
        if (recovered.gameHash() != response.gameHash) {
            throw P2PMatchException.InvalidMessage("STATE_RESPONSE game hash mismatch")
        }
        if (recovered.moveHistory.size != response.moveCount) {
            throw P2PMatchException.MoveCountMismatch(recovered.moveHistory.size, response.moveCount)
        }
        if (recovered.initialPosition().toFen() != history.initialPosition().toFen()) {
            throw P2PMatchException.InvalidMessage("Cannot replace match with a different initial position")
        }

        while (history.moveHistory.isNotEmpty()) history.undo()
        recovered.moveHistory.forEach { history.play(it.move) }
        if (history.gameHash() != response.gameHash || history.current.toFen() != response.finalFen) {
            throw P2PMatchException.InvalidMessage("Recovered local state failed final verification")
        }
        expectedIncomingSequence = response.moveCount.toLong()
        pendingStateRequestMessageId = null
        return response
    }

    private fun buildEnvelope(type: MessageType, payload: ByteArray): ProtocolEnvelope {
        // Envelope sequence is the deterministic MOVE stream sequence. Control messages
        // use message IDs/request bindings for replay correlation and do not consume move sequence.
        val unsigned = ProtocolEnvelope(
            P2PProtocol.VERSION, UUID.randomUUID().toString(), sessionId, matchId, localPeerId.value,
            type, 0L, payload, localIdentity?.publicKeyBase64
        )
        return if (localIdentity == null) unsigned else unsigned.copy(signatureBase64 = localIdentity.sign(unsigned))
    }

    private fun validateControlEnvelope(envelope: ProtocolEnvelope, expectedType: MessageType) {
        if (envelope.sessionId != sessionId) throw P2PMatchException.InvalidSession(sessionId, envelope.sessionId)
        if (envelope.matchId != matchId) throw P2PMatchException.InvalidMessage("Invalid match ID")
        if (envelope.senderPeerId != remotePeerId.value) throw P2PMatchException.InvalidSender(remotePeerId.value, envelope.senderPeerId)
        if (remotePublicKeyBase64 != null && envelope.senderPublicKeyBase64 != remotePublicKeyBase64) {
            throw P2PMatchException.InvalidMessage("Unexpected sender public key")
        }
        if (remotePublicKeyBase64 != null && !EnvelopeVerifier.verify(envelope)) {
            throw P2PMatchException.InvalidMessage("Invalid $expectedType signature")
        }
        if (envelope.type != expectedType) {
            throw P2PMatchException.InvalidMessage("Expected $expectedType, received ${envelope.type}")
        }
    }
}

object P2PProtocol {
    const val VERSION = 1

    fun nextSequence(previous: Long): Long = previous + 1

    fun newSession(local: PeerId, remote: PeerId, matchId: String, localSide: Side = Side.WHITE, localIdentity: EnvelopeIdentity? = null, remotePublicKeyBase64: String? = null): MatchStateMachine {
        require(local.value.isNotBlank() && remote.value.isNotBlank()) { "Peer IDs must not be blank" }
        require(local.value != remote.value) { "A peer cannot match against itself" }
        require(matchId.isNotBlank()) { "matchId must not be blank" }
        require(localIdentity == null || localIdentity.peerId == local) { "localIdentity does not match local peer" }
        return MatchStateMachine(UUID.randomUUID().toString(), local, remote, localSide, matchId, localIdentity, remotePublicKeyBase64)
    }

    fun payloadHash(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
}
