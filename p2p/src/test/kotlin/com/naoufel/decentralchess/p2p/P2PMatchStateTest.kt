package com.naoufel.decentralchess.p2p

import com.naoufel.decentralchess.chess.Move
import com.naoufel.decentralchess.chess.PieceType
import com.naoufel.decentralchess.chess.Side
import com.naoufel.decentralchess.chess.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class P2PMatchStateTest {
    private val white = PeerId("white-peer")
    private val black = PeerId("black-peer")
    private val session = "session-1"

    private fun whiteMachine() = MatchStateMachine(session, white, black, Side.WHITE, "match-1")
    private fun blackMachine() = MatchStateMachine(session, black, white, Side.BLACK, "match-1")

    private fun move(fromFile: Int, fromRank: Int, toFile: Int, toRank: Int, promotion: PieceType? = null) =
        Move(Square(fromFile, fromRank), Square(toFile, toRank), promotion)

    @Test
    fun legalMovesKeepBothPeersOnIdenticalDeterministicState() {
        val w = whiteMachine()
        val b = blackMachine()

        b.receive(w.createLocalMove(move(4, 1, 4, 3)))
        assertEquals(w.history().gameHash(), b.history().gameHash())

        w.receive(b.createLocalMove(move(4, 6, 4, 4)))
        assertEquals(w.history().gameHash(), b.history().gameHash())

        b.receive(w.createLocalMove(move(6, 0, 5, 2)))
        w.receive(b.createLocalMove(move(1, 7, 2, 5)))

        assertEquals(4, w.history().moveHistory.size)
        assertEquals(4, b.history().moveHistory.size)
        assertEquals(w.history().current.toFen(), b.history().current.toFen())
        assertEquals(w.history().gameHash(), b.history().gameHash())
    }

    @Test
    fun illegalLocalMoveIsRejectedWithoutMutatingState() {
        val w = whiteMachine()
        val before = w.snapshot()

        assertThrows(P2PMatchException.IllegalMove::class.java) {
            w.createLocalMove(move(4, 1, 4, 4))
        }

        assertEquals(before.gameHash, w.snapshot().gameHash)
        assertEquals(0, w.snapshot().moveCount)
        assertEquals(0L, w.snapshot().nextOutgoingSequence)
    }

    @Test
    fun stalePreviousHashIsRejectedWithoutAdvancingRemoteSequence() {
        val w = whiteMachine()
        val b = blackMachine()
        val valid = w.createLocalMove(move(4, 1, 4, 3))
        val decoded = MoveMessageCodec.decode(valid.payload)
        val tampered = decoded.copy(previousGameHash = "0".repeat(64))
        val envelope = valid.copy(payload = MoveMessageCodec.encode(tampered))

        assertThrows(P2PMatchException.PreviousHashMismatch::class.java) {
            b.receive(envelope)
        }

        assertEquals(0L, b.snapshot().expectedIncomingSequence)
        assertEquals(0, b.snapshot().moveCount)
    }

    @Test
    fun tamperedResultingHashIsRejectedAndStateIsRolledBack() {
        val w = whiteMachine()
        val b = blackMachine()
        val valid = w.createLocalMove(move(4, 1, 4, 3))
        val decoded = MoveMessageCodec.decode(valid.payload)
        val tampered = decoded.copy(resultingGameHash = "f".repeat(64))
        val envelope = valid.copy(payload = MoveMessageCodec.encode(tampered))

        assertThrows(P2PMatchException.ResultHashMismatch::class.java) {
            b.receive(envelope)
        }

        assertEquals(0L, b.snapshot().expectedIncomingSequence)
        assertEquals(0, b.snapshot().moveCount)
    }

    @Test
    fun replayedSequenceIsRejected() {
        val w = whiteMachine()
        val b = blackMachine()
        val first = w.createLocalMove(move(4, 1, 4, 3))
        b.receive(first)

        val second = b.createLocalMove(move(4, 6, 4, 4))
        w.receive(second)

        assertThrows(P2PMatchException.InvalidSequence::class.java) {
            w.receive(second)
        }
        assertEquals(2, w.snapshot().moveCount)
        assertEquals(1L, w.snapshot().expectedIncomingSequence)
    }

    @Test
    fun wrongSenderAndWrongSessionAreRejected() {
        val w = whiteMachine()
        val valid = w.createLocalMove(move(4, 1, 4, 3))

        assertThrows(P2PMatchException.InvalidSender::class.java) {
            blackMachine().receive(valid.copy(senderPeerId = "attacker"))
        }

        assertThrows(P2PMatchException.InvalidSession::class.java) {
            blackMachine().receive(valid.copy(sessionId = "other-session"))
        }
    }

    @Test
    fun reconnectTransitionsThroughRecoveryAndBackToConnected() {
        val w = whiteMachine()
        val b = blackMachine()
        w.createLocalMove(move(4, 1, 4, 3))
        b.receive(w.createLocalMove(move(6, 0, 5, 2)))
        w.markDisconnected()
        assertEquals(MatchConnectionState.DISCONNECTED, w.connectionState())

        val request = w.createReconnectRequest()
        assertEquals(MatchConnectionState.RECOVERING, w.connectionState())
        val response = b.createStateResponse(request)
        w.receiveStateResponse(response)

        assertEquals(MatchConnectionState.CONNECTED, w.connectionState())
        assertEquals(b.history().gameHash(), w.history().gameHash())
    }

    @Test
    fun acknowledgementsTrackOutstandingMovesAndVerifyHash() {
        val w = whiteMachine()
        val b = blackMachine()

        val moveEnvelope = w.createLocalMove(move(4, 1, 4, 3))
        assertEquals(1, w.pendingAcknowledgementCount())

        b.receive(moveEnvelope)
        val ack = b.createAck(moveEnvelope)
        val decoded = AckMessageCodec.decode(ack.payload)

        assertEquals(moveEnvelope.messageId, decoded.acknowledgedMessageId)
        assertEquals(moveEnvelope.sequence, decoded.acknowledgedSequence)
        assertEquals(b.history().gameHash(), decoded.gameHash)

        w.receiveAck(ack)
        assertEquals(0, w.pendingAcknowledgementCount())
    }

    @Test
    fun forgedAcknowledgementCannotClearOutstandingMove() {
        val w = whiteMachine()
        val b = blackMachine()
        val moveEnvelope = w.createLocalMove(move(4, 1, 4, 3))
        b.receive(moveEnvelope)

        val ack = b.createAck(moveEnvelope)
        val decoded = AckMessageCodec.decode(ack.payload)
        val forged = ack.copy(payload = AckMessageCodec.encode(decoded.copy(acknowledgedMessageId = "attacker")))
        assertThrows(P2PMatchException.InvalidMessage::class.java) {
            w.receiveAck(forged)
        }
        assertEquals(1, w.pendingAcknowledgementCount())
    }

    @Test
    fun stateRecoveryReplaysDeterministicallyAndResetsIncomingSequence() {
        val w = whiteMachine()
        val b = blackMachine()
        b.receive(w.createLocalMove(move(4, 1, 4, 3)))
        w.receive(b.createLocalMove(move(4, 6, 4, 4)))

        val stale = MatchStateMachine(session, black, white, Side.BLACK, "match-1")
        val request = stale.createStateRequest()
        val requestPayload = StateRequestCodec.decode(request.payload)
        assertEquals(0L, requestPayload.expectedIncomingSequence)
        assertEquals(stale.history().gameHash(), requestPayload.currentGameHash)
        assertEquals(0L, stale.snapshot().nextOutgoingSequence)

        val response = w.createStateResponse(request)
        val decoded = StateResponseCodec.decode(response.payload)
        assertEquals(request.messageId, decoded.requestMessageId)
        assertEquals(w.history().gameHash(), decoded.gameHash)
        assertEquals(w.history().current.toFen(), decoded.finalFen)
        assertEquals(1L, w.snapshot().nextOutgoingSequence)

        stale.receiveStateResponse(response)
        assertEquals(w.history().gameHash(), stale.history().gameHash())
        assertEquals(w.history().current.toFen(), stale.history().current.toFen())
        assertEquals(2, stale.snapshot().moveCount)
        assertEquals(2L, stale.snapshot().expectedIncomingSequence)
    }

    @Test
    fun tamperedStateResponseIsRejectedWithoutReplacingLocalState() {
        val w = whiteMachine()
        w.createLocalMove(move(4, 1, 4, 3))
        val stale = MatchStateMachine(session, black, white, Side.BLACK, "match-1")
        val request = stale.createStateRequest()
        val response = w.createStateResponse(request)
        val originalHash = stale.history().gameHash()
        val decoded = StateResponseCodec.decode(response.payload)
        val tampered = decoded.copy(gameHash = "f".repeat(64))
        val envelope = response.copy(payload = StateResponseCodec.encode(tampered))

        assertThrows(P2PMatchException.InvalidMessage::class.java) {
            stale.receiveStateResponse(envelope)
        }
        assertEquals(originalHash, stale.history().gameHash())
        assertEquals(0, stale.snapshot().moveCount)
        assertEquals(0L, stale.snapshot().expectedIncomingSequence)
    }

    @Test
    fun moveCodecRoundTripsPromotionAndCoordinates() {
        val message = MoveMessage(
            move(0, 6, 0, 7, PieceType.QUEEN),
            "a".repeat(64),
            "b".repeat(64),
            12
        )
        assertEquals(message, MoveMessageCodec.decode(MoveMessageCodec.encode(message)))
    }
}
