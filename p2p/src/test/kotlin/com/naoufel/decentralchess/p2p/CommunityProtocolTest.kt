package com.naoufel.decentralchess.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows

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
    fun clientBindsSenderAndSignsWhenIdentityProvided() = runTest {
        val sent = mutableListOf<ProtocolEnvelope>()
        val identity = EnvelopeIdentity(PeerId("local"), "public-key") { "signature".encodeToByteArray() }
        val client = CommunityP2PClient(PeerId("local"), { sent += it }, identity) { 7L }
        client.send(MessageType.CHAT, CommunityPacket("global", "local", "hello"), "session-1", "match-1")
        assertEquals(1, sent.size)
        assertEquals("local", sent.single().senderPeerId)
        assertEquals("public-key", sent.single().senderPublicKeyBase64)
        assertEquals("signature", java.util.Base64.getDecoder().decode(sent.single().signatureBase64!!).decodeToString())
    }

    @Test
    fun receiverRejectsPacketSenderMismatch() {
        val packet = CommunityPacket("global", "other", "hello")
        val envelope = ProtocolEnvelope(
            P2PProtocol.VERSION, "msg-1", "session-1", "match-1", "local",
            MessageType.CHAT, 0, CommunityPacketCodec.encode(packet)
        )
        assertThrows(P2PMatchException.InvalidSender::class.java) {
            CommunityP2PReceiver.decode(envelope, "local")
        }
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
