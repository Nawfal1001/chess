package com.naoufel.decentralchess.p2p

import com.naoufel.decentralchess.chess.Side
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID

class CommunitySessionE2ETest {
    private data class TestIdentity(val handshake: HandshakeIdentity, val envelope: EnvelopeIdentity, val publicKey: String)

    private fun identity(): TestIdentity {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val peerId = HandshakeCrypto.peerIdForPublicKey(publicKey)
        val sign: (ByteArray) -> ByteArray = { payload ->
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(pair.private)
                update(payload)
            }.sign()
        }
        return TestIdentity(
            HandshakeIdentity(peerId, publicKey, sign),
            EnvelopeIdentity(peerId, publicKey, sign),
            publicKey
        )
    }

    @Test
    fun authenticatedSessionsRouteCommunityMessagesEndToEnd() = runTest {
        val a = identity()
        val b = identity()
        val sessionId = UUID.randomUUID().toString()
        val matchId = "community-test-match"

        val stateA = MatchStateMachine(sessionId, a.handshake.peerId, b.handshake.peerId, Side.WHITE, matchId, a.envelope, b.publicKey)
        val stateB = MatchStateMachine(sessionId, b.handshake.peerId, a.handshake.peerId, Side.BLACK, matchId, b.envelope, a.publicKey)

        val transportA = InMemoryPeerTransport(a.handshake.peerId)
        val transportB = InMemoryPeerTransport(b.handshake.peerId)
        transportA.pairWith(transportB)

        val received = mutableListOf<Pair<PeerId, CommunityPacket>>()
        val handlerB = object : CommunityEnvelopeHandler {
            override suspend fun onCommunityEnvelope(peer: PeerId, envelope: ProtocolEnvelope, packet: CommunityPacket) {
                received += peer to packet
            }
            override suspend fun onCommunityDisconnected(peer: PeerId) = Unit
        }

        val controllerA = AuthenticatedP2PSessionController(
            stateA, transportA, a.handshake, HandshakeCrypto.verifierFor(b.publicKey)
        )
        val controllerB = AuthenticatedP2PSessionController(
            stateB, transportB, b.handshake, HandshakeCrypto.verifierFor(a.publicKey), handlerB
        )
        controllerA.attach()
        controllerB.attach()
        controllerA.connect()

        assertEquals(SessionPhase.READY, controllerA.phase())
        assertEquals(SessionPhase.READY, controllerB.phase())

        val managerA = CommunitySessionManager(a.handshake.peerId)
        managerA.register(b.handshake.peerId, controllerA)
        managerA.sendDirectMessage(b.handshake.peerId.value, "hello from authenticated peer")

        assertEquals(1, received.size)
        assertEquals(a.handshake.peerId, received.single().first)
        assertEquals("hello from authenticated peer", received.single().second.text)
        assertTrue(managerA.pendingDeliveries().isEmpty())
    }
}