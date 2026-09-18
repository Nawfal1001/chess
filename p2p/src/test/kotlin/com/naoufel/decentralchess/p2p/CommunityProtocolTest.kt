package com.naoufel.decentralchess.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityProtocolTest {
    @Test
    fun packet_roundTrips() {
        val original = CommunityPacket(
            conversationId = "#global-chess",
            senderPeerId = "peer-a",
            text = "hello | chess!",
            referenceId = "challenge-1",
            timeControl = "10+0",
            initialFen = "startpos",
            displayName = "Alice",
            rating = 1842
        )
        val decoded = CommunityPacketCodec.decode(CommunityPacketCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun optional_fields_roundTrip() {
        val original = CommunityPacket("#lobby", "peer-a", "hi")
        val decoded = CommunityPacketCodec.decode(CommunityPacketCodec.encode(original))
        assertEquals(original, decoded)
        assertNull(decoded.referenceId)
        assertNull(decoded.rating)
    }
}
