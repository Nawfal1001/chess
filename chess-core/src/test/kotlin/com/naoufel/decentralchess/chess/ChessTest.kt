package com.naoufel.decentralchess.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessTest {
    @Test fun initialPositionHasTwentyLegalMoves() {
        val p = Position.initial()
        assertEquals(20, p.legalMoves().size)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", p.toFen())
    }

    @Test fun fenRoundTripIsDeterministic() {
        val fen = "r3k2r/ppp2ppp/2n1b3/8/8/2N1B3/PPP2PPP/R3K2R w KQkq - 4 12"
        assertEquals(fen, Position.fromFen(fen).toFen())
        assertEquals(Position.fromFen(fen).stableHash(), Position.fromFen(fen).stableHash())
    }

    @Test fun foolsMateEndsInCheckmate() {
        var p = Position.initial()
        fun play(from:String,to:String) { p=p.apply(Move(Square(from[0]-'a',from[1]-'1'),Square(to[0]-'a',to[1]-'1'))) }
        play("f2","f3"); play("e7","e5"); play("g2","g4"); play("d8","h4")
        assertTrue(p.inCheck(Side.WHITE))
        assertEquals(0, p.legalMoves().size)
    }

    @Test fun castlingMovesRook() {
        val p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val next = p.apply(Move(Square(4,0), Square(6,0)))
        assertEquals(Piece(Side.WHITE, PieceType.KING), next.pieceAt(Square(6,0)))
        assertEquals(Piece(Side.WHITE, PieceType.ROOK), next.pieceAt(Square(5,0)))
        assertEquals("r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1", next.toFen())
    }

    @Test fun enPassantCapturesPawn() {
        val p = Position.fromFen("8/8/8/3pP3/8/8/8/8 w - d6 0 1")
        val next = p.apply(Move(Square(4,4), Square(3,5)))
        assertEquals(Piece(Side.WHITE, PieceType.PAWN), next.pieceAt(Square(3,5)))
        assertEquals(null, next.pieceAt(Square(3,4)))
        assertEquals(0, next.halfmoveClock)
    }

    @Test fun promotionCreatesRequestedPiece() {
        val p = Position.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val next = p.apply(Move(Square(0,6), Square(0,7), PieceType.KNIGHT))
        assertEquals(Piece(Side.WHITE, PieceType.KNIGHT), next.pieceAt(Square(0,7)))
    }

    @Test fun kingCannotMoveIntoCheck() {
        val p = Position.fromFen("4r1k1/8/8/8/8/8/8/4K3 w - - 0 1")
        assertTrue(p.legalMoves(Square(4,0)).none { it.to == Square(4,1) })
    }
}
