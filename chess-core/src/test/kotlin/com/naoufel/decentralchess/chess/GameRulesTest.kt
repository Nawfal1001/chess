package com.naoufel.decentralchess.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRulesTest {
    @Test fun checkmateAndStalemateAreDistinguished() {
        val mate = Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        val stale = Position.fromFen("7k/5Q2/7K/8/8/8/8/8 b - - 0 1")
        assertEquals(GameStatus.CHECKMATE, mate.gameStatus())
        assertEquals(GameStatus.STALEMATE, stale.gameStatus())
    }

    @Test fun insufficientMaterialIsDetected() {
        val kingOnly = Position.fromFen("7k/8/8/8/8/8/8/K7 w - - 0 1")
        val bishopOnly = Position.fromFen("7k/8/8/8/8/8/1B6/K7 w - - 0 1")
        assertTrue(kingOnly.isInsufficientMaterial())
        assertTrue(bishopOnly.isInsufficientMaterial())
    }

    @Test fun hashChainChangesWhenMoveChanges() {
        val a = GameHistory()
        val b = GameHistory()
        a.play(Move(Square(4,1), Square(4,3)))
        b.play(Move(Square(3,1), Square(3,3)))
        assertNotEquals(a.gameHash(), b.gameHash())
    }

    @Test fun historySupportsUndo() {
        val history = GameHistory()
        val initialHash = history.current.stableHash()
        history.play(Move(Square(4,1), Square(4,3)))
        assertNotEquals(initialHash, history.current.stableHash())
        history.undo()
        assertEquals(initialHash, history.current.stableHash())
        assertEquals(0, history.moveHistory.size)
    }
}
