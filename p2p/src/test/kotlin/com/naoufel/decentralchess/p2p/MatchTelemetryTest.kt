package com.naoufel.decentralchess.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MatchTelemetryTest {
    @Test
    fun telemetryHashIsDeterministicAndBoundToAuthenticatedEnvelope() {
        val envelope = ProtocolEnvelope(
            version = P2PProtocol.VERSION,
            messageId = "message-1",
            sessionId = "session-1",
            matchId = "match-1",
            senderPeerId = "peer-a",
            type = MessageType.MOVE,
            sequence = 4L,
            payload = "e2e4".toByteArray(),
            senderPublicKeyBase64 = "public-key"
        )

        val first = authenticatedMoveHash(envelope)
        val second = authenticatedMoveHash(envelope)
        assertEquals(64, first.length)
        assertEquals(first, second)

        val changed = envelope.copy(payload = "d2d4".toByteArray())
        assertNotEquals(first, authenticatedMoveHash(changed))
    }

    @Test
    fun inMemorySinkPreservesMoveOrder() {
        val sink = InMemoryMatchTelemetrySink()
        sink.record(MoveTelemetry("game-1", 1, "peer-a", 1000, null, "a".repeat(64), false))
        sink.record(MoveTelemetry("game-1", 2, "peer-b", 1200, null, "b".repeat(64), true))

        val events = sink.events()
        assertEquals(2, events.size)
        assertEquals(1L, events[0].plySequence)
        assertEquals(2L, events[1].plySequence)
        assertTrue(events[1].remoteObservation)
    }
}
