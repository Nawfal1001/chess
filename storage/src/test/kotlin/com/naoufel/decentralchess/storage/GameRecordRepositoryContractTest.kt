package com.naoufel.decentralchess.storage

import com.naoufel.decentralchess.chess.GameHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecordRepositoryContractTest {
    @Test
    fun exportedRecordReplaysToSameState() {
        val history = GameHistory()
        val storedPgn = com.naoufel.decentralchess.chess.ChessNotation.exportPgn(history)
        val replayed = com.naoufel.decentralchess.chess.ChessNotation.importPgn(storedPgn)
        assertEquals(history.current.toFen(), replayed.current.toFen())
        assertEquals(history.gameHash(), replayed.gameHash())
        assertTrue(storedPgn.contains("[Result"))
    }
}
