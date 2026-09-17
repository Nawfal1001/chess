package com.naoufel.decentralchess.p2p

import com.naoufel.decentralchess.chess.Move
import com.naoufel.decentralchess.chess.Side
import com.naoufel.decentralchess.chess.Square
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class SessionHandshakeTest {
    private val session = "handshake-session"
    private val match = "handshake-match"
    private val whiteKey = Base64.getEncoder().encodeToString("key-white".encodeToByteArray())
    private val blackKey = Base64.getEncoder().encodeToString("key-black".encodeToByteArray())
    private val white = HandshakeCrypto.peerIdForPublicKey(whiteKey)
    private val black = HandshakeCrypto.peerIdForPublicKey(blackKey)

    private fun identity(peer: PeerId) = when (peer) {
        white -> HandshakeIdentity(peer, whiteKey, { payload -> payload })
        black -> HandshakeIdentity(peer, blackKey, { payload -> payload })
        else -> error("Unknown test peer")
    }

    private fun verifier(expectedPeer: PeerId): (ProtocolEnvelope) -> Boolean = { envelope ->
        envelope.senderPeerId == expectedPeer.value &&
            envelope.senderPublicKeyBase64 == identity(expectedPeer).publicKeyBase64 &&
            envelope.signatureBase64 == Base64.getEncoder().encodeToString(envelope.canonicalBytes())
    }

    @Test
    fun handshakeReachesReadyAndBindsSessionMatchPeersAndNonce() {
        val wi = identity(white)
        val bi = identity(black)
        val w = SessionHandshake(white, black, session, match, Side.WHITE, com.naoufel.decentralchess.chess.GameHistory().initialPosition().toFen(), wi, verifier(black)) { byteArrayOf(1, 2, 3) }
        val b = SessionHandshake(black, white, session, match, Side.BLACK, com.naoufel.decentralchess.chess.GameHistory().initialPosition().toFen(), bi, verifier(white)) { byteArrayOf(9, 8, 7) }

        val helloW = w.startHello()
        assertEquals(SessionPhase.HELLO_SENT, w.phase())
        val helloB = b.startHello()
        assertEquals(SessionPhase.HELLO_SENT, b.phase())

        b.onEnvelope(helloW)
        assertEquals(SessionPhase.HELLO_RECEIVED, b.phase())

        val offer = w.onEnvelope(helloB).single()
        assertEquals(MessageType.MATCH_OFFER, offer.type)
        val accepted = b.onEnvelope(offer).single()
        assertEquals(MessageType.MATCH_ACCEPT, accepted.type)
        assertEquals(SessionPhase.NEGOTIATING, b.phase())

        val finishedW = w.onEnvelope(accepted).single()
        assertEquals(MessageType.HANDSHAKE_FINISHED, finishedW.type)
        assertEquals(SessionPhase.NEGOTIATING, w.phase())

        val finishedB = b.onEnvelope(finishedW).single()
        assertEquals(MessageType.HANDSHAKE_FINISHED, finishedB.type)
        assertEquals(SessionPhase.READY, b.phase())

        w.onEnvelope(finishedB)
        assertEquals(SessionPhase.READY, w.phase())
    }

    @Test
    fun replayedHandshakeMessageIsRejected() {
        val wi = identity(white)
        val bi = identity(black)
        val w = SessionHandshake(white, black, session, match, Side.WHITE, com.naoufel.decentralchess.chess.GameHistory().initialPosition().toFen(), wi, verifier(black)) { byteArrayOf(1) }
        val b = SessionHandshake(black, white, session, match, Side.BLACK, com.naoufel.decentralchess.chess.GameHistory().initialPosition().toFen(), bi, verifier(white)) { byteArrayOf(2) }
        val hello = w.startHello()
        b.startHello()
        b.onEnvelope(hello)
        assertThrows(P2PMatchException.InvalidMessage::class.java) { b.onEnvelope(hello) }
    }

    @Test
    fun publicKeyPeerIdBindingIsEnforced() {
        val wi = identity(white)
        val bi = identity(black)
        val w = SessionHandshake(white, black, session, match, Side.WHITE, com.naoufel.decentralchess.chess.GameHistory().initialPosition().toFen(), wi, verifier(black)) { byteArrayOf(1) }
        val b = SessionHandshake(black, white, session, match, Side.BLACK, com.naoufel.decentralchess.chess.GameHistory().initialPosition().toFen(), bi, verifier(white)) { byteArrayOf(2) }
        val hello = w.startHello().copy(senderPeerId = black.value, senderPublicKeyBase64 = wi.publicKeyBase64)
        assertThrows(P2PMatchException.InvalidSender::class.java) { b.onEnvelope(hello) }
    }

    @Test
    fun tamperedHandshakeSignatureIsRejected() {
        val wi = identity(white)
        val bi = identity(black)
        val w = SessionHandshake(white, black, session, match, Side.WHITE, com.naoufel.decentralchess.chess.GameHistory().initialPosition().toFen(), wi, verifier(black)) { byteArrayOf(1) }
        val b = SessionHandshake(black, white, session, match, Side.BLACK, com.naoufel.decentralchess.chess.GameHistory().initialPosition().toFen(), bi, verifier(white)) { byteArrayOf(2) }
        val hello = w.startHello()
        b.startHello()
        val tampered = hello.copy(signatureBase64 = Base64.getEncoder().encodeToString("forged".encodeToByteArray()))
        assertThrows(P2PMatchException.InvalidMessage::class.java) { b.onEnvelope(tampered) }
    }

    @Test
    fun authenticatedControllerRejectsMoveBeforeReadyAndThenMovesAfterHandshake() = runTest {
        val whiteTransport = InMemoryPeerTransport(white)
        val blackTransport = InMemoryPeerTransport(black)
        whiteTransport.pairWith(blackTransport)

        val whiteState = MatchStateMachine(session, white, black, Side.WHITE, match)
        val blackState = MatchStateMachine(session, black, white, Side.BLACK, match)
        val wc = AuthenticatedP2PSessionController(whiteState, whiteTransport, identity(white), verifier(black))
        val bc = AuthenticatedP2PSessionController(blackState, blackTransport, identity(black), verifier(white))
        wc.attach()
        bc.attach()

        assertThrows(P2PMatchException.InvalidMessage::class.java) {
            kotlinx.coroutines.runBlocking {
                wc.sendMove(Move(Square(4, 1), Square(4, 3)))
            }
        }

        wc.connect()

        assertEquals(SessionPhase.READY, wc.phase())
        assertEquals(SessionPhase.READY, bc.phase())

        wc.sendMove(Move(Square(4, 1), Square(4, 3)))
        bc.sendMove(Move(Square(4, 6), Square(4, 4)))
        assertEquals(2, whiteState.history().moveHistory.size)
        assertEquals(whiteState.history().gameHash(), blackState.history().gameHash())
    }

    @Test
    fun readySessionRejectsUnexpectedHandshakeMessages() = runTest {
        val whiteTransport = InMemoryPeerTransport(white)
        val blackTransport = InMemoryPeerTransport(black)
        whiteTransport.pairWith(blackTransport)
        val whiteState = MatchStateMachine(session, white, black, Side.WHITE, match)
        val blackState = MatchStateMachine(session, black, white, Side.BLACK, match)
        val wc = AuthenticatedP2PSessionController(whiteState, whiteTransport, identity(white), verifier(black))
        val bc = AuthenticatedP2PSessionController(blackState, blackTransport, identity(black), verifier(white))
        wc.attach(); bc.attach(); wc.connect()
        val lateHello = SessionHandshake(black, white, session, match, Side.BLACK,
            blackState.history().initialPosition().toFen(), identity(black), verifier(white)) { byteArrayOf(7) }.startHello()
        assertThrows(P2PMatchException.InvalidMessage::class.java) {
            runBlocking { wc.onPayload(black, ProtocolEnvelopeCodec.encode(lateHello)) }
        }
    }
}
