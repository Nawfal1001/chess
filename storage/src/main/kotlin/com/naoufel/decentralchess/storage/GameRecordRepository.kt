package com.naoufel.decentralchess.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.naoufel.decentralchess.chess.ChessNotation
import com.naoufel.decentralchess.chess.GameHistory
import java.util.UUID

data class StoredGame(
    val id: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val initialFen: String,
    val finalFen: String,
    val gameHash: String,
    val pgn: String,
    val status: String,
    val whitePlayerId: String?,
    val blackPlayerId: String?
) {
    fun replay(): GameHistory = ChessNotation.importPgn(pgn)
}

interface GameRecordRepository {
    fun save(
        history: GameHistory,
        id: String? = null,
        whitePlayerId: String? = null,
        blackPlayerId: String? = null
    ): StoredGame
    fun get(id: String): StoredGame?
    fun list(limit: Int = 50): List<StoredGame>
    fun delete(id: String): Boolean
}

class SqliteGameRecordRepository(context: Context) : GameRecordRepository {
    private val helper = GameDatabase(context.applicationContext)

    override fun save(
        history: GameHistory,
        id: String?,
        whitePlayerId: String?,
        blackPlayerId: String?
    ): StoredGame {
        val now = System.currentTimeMillis()
        val gameId = id ?: UUID.randomUUID().toString()
        val pgn = ChessNotation.exportPgn(history)
        val record = StoredGame(
            id = gameId,
            createdAtMs = findCreatedAt(gameId) ?: now,
            updatedAtMs = now,
            initialFen = history.initialPosition().toFen(),
            finalFen = history.current.toFen(),
            gameHash = history.gameHash(),
            pgn = pgn,
            status = history.status().name,
            whitePlayerId = whitePlayerId,
            blackPlayerId = blackPlayerId
        )

        val values = ContentValues().apply {
            put(C_ID, record.id)
            put(C_CREATED, record.createdAtMs)
            put(C_UPDATED, record.updatedAtMs)
            put(C_INITIAL_FEN, record.initialFen)
            put(C_FINAL_FEN, record.finalFen)
            put(C_HASH, record.gameHash)
            put(C_PGN, record.pgn)
            put(C_STATUS, record.status)
            put(C_WHITE_ID, record.whitePlayerId)
            put(C_BLACK_ID, record.blackPlayerId)
        }

        helper.writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return record
    }

    override fun get(id: String): StoredGame? {
        helper.readableDatabase.query(
            TABLE, COLUMNS, "$C_ID = ?", arrayOf(id), null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toStoredGame() else null
        }
    }

    override fun list(limit: Int): List<StoredGame> {
        require(limit in 1..500) { "limit must be between 1 and 500" }
        val result = mutableListOf<StoredGame>()
        helper.readableDatabase.query(
            TABLE, COLUMNS, null, null, null, null, "$C_UPDATED DESC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toStoredGame()
        }
        return result
    }

    override fun delete(id: String): Boolean =
        helper.writableDatabase.delete(TABLE, "$C_ID = ?", arrayOf(id)) > 0

    private fun findCreatedAt(id: String): Long? = get(id)?.createdAtMs

    private fun android.database.Cursor.toStoredGame(): StoredGame = StoredGame(
        id = getString(getColumnIndexOrThrow(C_ID)),
        createdAtMs = getLong(getColumnIndexOrThrow(C_CREATED)),
        updatedAtMs = getLong(getColumnIndexOrThrow(C_UPDATED)),
        initialFen = getString(getColumnIndexOrThrow(C_INITIAL_FEN)),
        finalFen = getString(getColumnIndexOrThrow(C_FINAL_FEN)),
        gameHash = getString(getColumnIndexOrThrow(C_HASH)),
        pgn = getString(getColumnIndexOrThrow(C_PGN)),
        status = getString(getColumnIndexOrThrow(C_STATUS)),
        whitePlayerId = getStringOrNull(C_WHITE_ID),
        blackPlayerId = getStringOrNull(C_BLACK_ID)
    )

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private companion object {
        const val TABLE = "games"
        const val C_ID = "id"
        const val C_CREATED = "created_at"
        const val C_UPDATED = "updated_at"
        const val C_INITIAL_FEN = "initial_fen"
        const val C_FINAL_FEN = "final_fen"
        const val C_HASH = "game_hash"
        const val C_PGN = "pgn"
        const val C_STATUS = "status"
        const val C_WHITE_ID = "white_player_id"
        const val C_BLACK_ID = "black_player_id"
        val COLUMNS = arrayOf(C_ID, C_CREATED, C_UPDATED, C_INITIAL_FEN, C_FINAL_FEN, C_HASH, C_PGN, C_STATUS, C_WHITE_ID, C_BLACK_ID)
    }
}

private class GameDatabase(context: Context) : SQLiteOpenHelper(context, "decentral_chess.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE games (
                id TEXT PRIMARY KEY NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                initial_fen TEXT NOT NULL,
                final_fen TEXT NOT NULL,
                game_hash TEXT NOT NULL,
                pgn TEXT NOT NULL,
                status TEXT NOT NULL,
                white_player_id TEXT,
                black_player_id TEXT
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_games_updated ON games(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema migrations will be added here before the version is incremented.
    }
}
