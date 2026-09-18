package com.naoufel.decentralchess.p2p

import com.naoufel.decentralchess.chess.Move
import com.naoufel.decentralchess.chess.Side
import com.naoufel.decentralchess.chess.Square
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class AuthenticatedChallengeMatchSessionFactoryTest {
    private data class TestIdentity(val handshake: HandshakeIdentity, val publicKey: String)

    private fun identity(): TestIdentity {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val peerId = HandshakeCrypto.peerIdForPublicKey(publicKey)
        val signer: (ByteArray) -> ByteArray = { payload ->
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(pair.private)
                update(payload)
            }.sign()
        }
        return TestIdentity(HandshakeIdentity(peerId, publicKey, signer), publicKey)
    }

    @Test
    fun acceptedChallenge_createsAuthenticatedSessionWithChallengePosition() = runTest {
        val white = identity()
        val black = identity()
        val whiteTransport = InMemoryPeerTransport(white.handshake.peerId)
        val blackTransport = InMemoryPeerTransport(black.handshake.peerId)
        whiteTransport.pairWith(blackTransport)

        val challenge = AcceptedChallenge(
            challengeId = "challenge-factory",
            fromPeerId = white.handshake.peerId,
            toPeerId = black.handshake.peerId,
            timeControl = "10+0",
            initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        )

        val blackFactory = AuthenticatedChallengeMatchSessionFactory(
            PeerTransportProvider { blackTransport },
            black.handshake,
            { white.handshake.peerId.takeIf { it == white.handshake.peerId }?.let { white.publicKey } }
        )
        val blackController = blackFactory.create(
            challenge, black.handshake.peerId, white.handshake.peerId, Side.BLACK
        )

        val whiteFactory = AuthenticatedChallengeMatchSessionFactory(
            PeerTransportProvider { whiteTransport },
            white.handshake,
            { peer -> if (peer == black.handshake.peerId) black.publicKey else null }
        )
        val whiteManager = CommunitySessionManager(white.handshake.peerId)
        val coordinator = ChallengeMatchCoordinator(
            white.handshake.peerId, whiteManager, whiteFactory
        )

        val created = coordinator.accept(challenge)

        assertTrue(created === coordinator.controller(challenge.matchId))
        assertEquals(SessionPhase.READY, created.phase())
        assertEquals(SessionPhase.READY, blackController.phase())
        assertEquals(challenge.initialFen, created.history().initialPosition().toFen())
        assertEquals(1, coordinator.activeMatchIds().size)

        created.sendMove(Move(Square(4, 1), Square(4, 3)))
        assertEquals(1, created.snapshot().moveCount)
        assertEquals(created.snapshot().gameHash(), blackController.snapshot().gameHash)
        assertTrue(whiteManager.pendingDeliveries().isEmpty())
    }
}
