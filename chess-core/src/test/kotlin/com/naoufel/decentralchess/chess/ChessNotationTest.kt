package com.naoufel.decentralchess.chess

import org.junit.Assert.assertEquals
import org.junit.Test

class ChessNotationTest {
    @Test fun sanCoversOpeningMoves() {
        var p = Position.initial()
        val e4 = Move(Square(4, 1), Square(4, 3))
        assertEquals("e4", ChessNotation.toSan(p, e4))
        assertEquals(e4, ChessNotation.parseSan(p, "e4"))
        p = p.apply(e4)
        val e5 = Move(Square(4, 6), Square(4, 4))
        assertEquals("e5", ChessNotation.toSan(p, e5))
        p = p.apply(e5)
        val nf3 = Move(Square(6, 0), Square(5, 2))
        assertEquals("Nf3", ChessNotation.toSan(p, nf3))
        assertEquals(nf3, ChessNotation.parseSan(p, "Nf3"))
    }

    @Test fun sanMarksCheckmate() {
        var p = Position.initial()
        val moves = listOf("f3", "e5", "g4", "Qh4#")
        moves.forEach { san -> p = p.apply(ChessNotation.parseSan(p, san)) }
        assertEquals(GameStatus.CHECKMATE, p.gameStatus())
        assertEquals("Qh4#", ChessNotation.toSan(Position.initial().apply(Move(Square(5,1),Square(5,2))).apply(Move(Square(4,6),Square(4,4))).apply(Move(Square(6,1),Square(6,3))), Move(Square(3,7),Square(7,3))))
    }

    @Test fun sanHandlesCastlingAndEnPassant() {
        val castle = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val kingSide = Move(Square(4, 0), Square(6, 0))
        assertEquals("O-O", ChessNotation.toSan(castle, kingSide))
        assertEquals(kingSide, ChessNotation.parseSan(castle, "0-0"))

        val ep = Position.fromFen("8/8/8/3pP3/8/8/8/4K2k w - d6 0 1")
        val epMove = Move(Square(4, 4), Square(3, 5))
        assertEquals("exd6", ChessNotation.toSan(ep, epMove))
        assertEquals(epMove, ChessNotation.parseSan(ep, "exd6"))
    }

    @Test fun sanHandlesPromotion() {
        val p = Position.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val move = Move(Square(0, 6), Square(0, 7), PieceType.QUEEN)
        assertEquals("a8=Q+", ChessNotation.toSan(p, move))
        assertEquals(move, ChessNotation.parseSan(p, "a8=Q+"))
    }

    @Test fun pgnRoundTripPreservesGameHash() {
        val history = GameHistory()
        listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6").forEach { history.play(ChessNotation.parseSan(history.current, it)) }
        val pgn = ChessNotation.exportPgn(history, mapOf("Event" to "Decentral Chess Test"))
        val imported = ChessNotation.importPgn(pgn)
        assertEquals(history.gameHash(), imported.gameHash())
        assertEquals(history.current.toFen(), imported.current.toFen())
    }
}
