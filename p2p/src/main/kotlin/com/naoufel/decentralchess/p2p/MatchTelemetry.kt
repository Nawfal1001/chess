package com.naoufel.decentralchess.p2p

import java.security.MessageDigest

/**
 * Observation emitted only after an authenticated MOVE envelope has been accepted/sent.
 *
 * observedAtMs is local wall-clock evidence. It is intentionally NOT presented as an
 * authoritative server timestamp. Rated/competitive infrastructure can later replace or
 * supplement it with a relay/server receive timestamp.
 */
data class MoveTelemetry(
    val gameId: String,
    val plySequence: Long,
    val peerId: String,
    val observedAtMs: Long,
    val clientThinkTimeMs: Long?,
    val moveHash: String,
    val remoteObservation: Boolean
) {
    init {
        require(gameId.isNotBlank())
        require(peerId.isNotBlank())
        require(plySequence >= 0)
        require(observedAtMs >= 0)
        require(clientThinkTimeMs == null || clientThinkTimeMs >= 0)
        require(moveHash.length == 64)
    }
}

fun interface MatchTelemetrySink {
    fun record(event: MoveTelemetry)
}

class InMemoryMatchTelemetrySink : MatchTelemetrySink {
    private val events = mutableListOf<MoveTelemetry>()

    @Synchronized
    override fun record(event: MoveTelemetry) {
        events += event
    }

    @Synchronized
    fun events(): List<MoveTelemetry> = events.toList()

    @Synchronized
    fun clear() {
        events.clear()
    }
}

internal fun authenticatedMoveHash(envelope: ProtocolEnvelope): String =
    MessageDigest.getInstance("SHA-256")
        .digest(envelope.canonicalBytes())
        .joinToString("") { "%02x".format(it) }
