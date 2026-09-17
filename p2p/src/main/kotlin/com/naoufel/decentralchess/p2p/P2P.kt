package com.naoufel.decentralchess.p2p

import java.nio.charset.StandardCharsets

data class PeerId(val value: String)
interface PeerTransport {
    suspend fun connect(peer: PeerId)
    suspend fun send(peer: PeerId, payload: ByteArray)
    suspend fun close(peer: PeerId)
}

/** Versioned application envelope carried by any future P2P transport (WebRTC/QUIC/relay). */
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
        require(sequence >= 0) { "sequence must be non-negative" }
    }

    fun canonicalHeader(): ByteArray =
        listOf(version.toString(), messageId, sessionId, senderPeerId, type.name, sequence.toString(), payload.size.toString())
            .joinToString("
") { it.length.toString() + ":" + it }
            .toByteArray(StandardCharsets.UTF_8)
}

data class HashCheckpoint(
    val sessionId: String,
    val sequence: Long,
    val moveCount: Int,
    val gameHash: String
)

object P2PProtocol {
    const val VERSION = 1
    fun nextSequence(previous: Long): Long = previous + 1
}
