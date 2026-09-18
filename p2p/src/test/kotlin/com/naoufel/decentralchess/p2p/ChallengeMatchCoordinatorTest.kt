package com.naoufel.decentralchess.p2p

import com.naoufel.decentralchess.chess.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChallengeMatchCoordinatorTest {
    @Test
    fun acceptedChallenge_derivesStableSessionAndMatchIds() {
        val a = PeerId("alice")
        val b = PeerId("bob")
        val first = AcceptedChallenge("challenge-123", a, b, "10+0", "start")
        val second = AcceptedChallenge("challenge-123", a, b, "10+0", "start")

        assertEquals(first.sessionId, second.sessionId)
        assertEquals(first.matchId, second.matchId)
        assertNotEquals(first.sessionId, first.matchId)
        assertEquals(Side.WHITE, first.localSide(a))
        assertEquals(Side.BLACK, first.localSide(b))
    }

    @Test
    fun acceptedChallenge_rejectsUnknownPeer() {
        val challenge = AcceptedChallenge(
            "challenge-123",
            PeerId("alice"),
            PeerId("bob"),
            "5+0",
            "start"
        )

        runCatching { challenge.localSide(PeerId("mallory")) }
            .onSuccess { error("unknown peer must not receive a chess side") }
    }
}
