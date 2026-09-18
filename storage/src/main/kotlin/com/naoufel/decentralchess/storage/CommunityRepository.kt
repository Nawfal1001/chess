package com.naoufel.decentralchess.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.naoufel.decentralchess.p2p.*
import java.util.UUID

/** Persistent local store for the community layer. Network synchronization remains above this store. */
interface CommunityRepository : CommunityHistory {
    fun upsertChannel(channel: CommunityChannel)
    fun channels(): List<CommunityChannel>
    fun upsertPeer(peer: PeerProfile)
    fun peer(peerId: String): PeerProfile?
    fun peers(): List<PeerProfile>
    fun saveChallenge(challenge: CommunityChallenge)
    fun challenges(limit: Int = 50): List<CommunityChallenge>
    fun saveTournament(room: TournamentRoom)
    fun tournaments(): List<TournamentRoom>
    fun moderation(): ModerationState
    fun setBlocked(peerId: String, blocked: Boolean)
    fun setMuted(peerId: String, muted: Boolean)
    fun saveReport(report: CommunityReport)
}

class SqliteCommunityRepository(context: Context) : CommunityRepository {
    private val helper = ChessDatabase(context.applicationContext)

    override fun append(message: CommunityMessage) {
        val values = ContentValues().apply {
            put("id", message.id)
            put("conversation_id", message.conversationId)
            put("sender_peer_id", message.senderPeerId)
            put("text", message.text)
            put("created_at", message.createdAtMs)
        }
        helper.writableDatabase.insertWithOnConflict("community_messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun messages(conversationId: String, limit: Int): List<CommunityMessage> {
        require(limit in 1..500) { "limit must be between 1 and 500" }
        val result = mutableListOf<CommunityMessage>()
        helper.readableDatabase.query(
            "community_messages",
            arrayOf("id", "conversation_id", "sender_peer_id", "text", "created_at"),
            "conversation_id = ?", arrayOf(conversationId), null, null, "created_at ASC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                result += CommunityMessage(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))
            }
        }
        return result
    }

    override fun clear(conversationId: String) {
        helper.writableDatabase.delete("community_messages", "conversation_id = ?", arrayOf(conversationId))
    }

    override fun upsertChannel(channel: CommunityChannel) {
        val v = ContentValues().apply {
            put("id", channel.id); put("name", channel.name); put("scope", channel.scope.name)
            put("description", channel.description); put("member_count", channel.memberCount)
        }
        helper.writableDatabase.insertWithOnConflict("community_channels", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun channels(): List<CommunityChannel> {
        val result = mutableListOf<CommunityChannel>()
        helper.readableDatabase.query("community_channels", null, null, null, null, null, "name ASC").use { c ->
            while (c.moveToNext()) {
                result += CommunityChannel(c.getString(c.getColumnIndexOrThrow("id")),
                    c.getString(c.getColumnIndexOrThrow("name")),
                    CommunityScope.valueOf(c.getString(c.getColumnIndexOrThrow("scope"))),
                    c.getString(c.getColumnIndexOrThrow("description")),
                    c.getInt(c.getColumnIndexOrThrow("member_count")))
            }
        }
        return result
    }

    override fun upsertPeer(peer: PeerProfile) {
        val v = ContentValues().apply {
            put("peer_id", peer.peerId); put("display_name", peer.displayName); put("public_key", peer.publicKeyBase64)
            put("rating", peer.rating); put("games_played", peer.gamesPlayed); put("is_bot", if (peer.bot) 1 else 0)
        }
        helper.writableDatabase.insertWithOnConflict("community_peers", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun peer(peerId: String): PeerProfile? {
        helper.readableDatabase.query("community_peers", null, "peer_id = ?", arrayOf(peerId), null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            return PeerProfile(c.getString(c.getColumnIndexOrThrow("peer_id")),
                c.getString(c.getColumnIndexOrThrow("display_name")),
                c.getString(c.getColumnIndexOrThrow("public_key")),
                c.getInt(c.getColumnIndexOrThrow("rating")),
                c.getInt(c.getColumnIndexOrThrow("games_played")),
                c.getInt(c.getColumnIndexOrThrow("is_bot")) != 0)
        }
    }

    override fun peers(): List<PeerProfile> {
        val result = mutableListOf<PeerProfile>()
        helper.readableDatabase.query("community_peers", null, null, null, null, null, "display_name ASC").use { c ->
            while (c.moveToNext()) result += PeerProfile(
                c.getString(c.getColumnIndexOrThrow("peer_id")),
                c.getString(c.getColumnIndexOrThrow("display_name")),
                c.getString(c.getColumnIndexOrThrow("public_key")),
                c.getInt(c.getColumnIndexOrThrow("rating")),
                c.getInt(c.getColumnIndexOrThrow("games_played")),
                c.getInt(c.getColumnIndexOrThrow("is_bot")) != 0
            )
        }
        return result
    }

    override fun saveChallenge(challenge: CommunityChallenge) {
        val v = ContentValues().apply {
            put("id", challenge.id); put("from_peer_id", challenge.fromPeerId); put("to_peer_id", challenge.toPeerId)
            put("time_control", challenge.timeControl); put("initial_fen", challenge.initialFen); put("created_at", challenge.createdAtMs)
        }
        helper.writableDatabase.insertWithOnConflict("community_challenges", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun challenges(limit: Int): List<CommunityChallenge> {
        require(limit in 1..500)
        val result = mutableListOf<CommunityChallenge>()
        helper.readableDatabase.query("community_challenges", null, null, null, null, null, "created_at DESC", limit.toString()).use { c ->
            while (c.moveToNext()) result += CommunityChallenge(
                c.getString(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("from_peer_id")),
                c.getString(c.getColumnIndexOrThrow("to_peer_id")),
                c.getString(c.getColumnIndexOrThrow("time_control")),
                c.getString(c.getColumnIndexOrThrow("initial_fen")),
                c.getLong(c.getColumnIndexOrThrow("created_at"))
            )
        }
        return result
    }

    override fun saveTournament(room: TournamentRoom) {
        val v = ContentValues().apply {
            put("id", room.id); put("name", room.name); put("owner_peer_id", room.ownerPeerId)
            put("max_players", room.maxPlayers); put("rated", if (room.rated) 1 else 0)
        }
        helper.writableDatabase.insertWithOnConflict("community_tournaments", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun tournaments(): List<TournamentRoom> {
        val result = mutableListOf<TournamentRoom>()
        helper.readableDatabase.query("community_tournaments", null, null, null, null, null, "name ASC").use { c ->
            while (c.moveToNext()) result += TournamentRoom(
                c.getString(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("owner_peer_id")),
                c.getInt(c.getColumnIndexOrThrow("max_players")),
                c.getInt(c.getColumnIndexOrThrow("rated")) != 0
            )
        }
        return result
    }

    override fun moderation(): ModerationState {
        val blocked = mutableSetOf<String>()
        val muted = mutableSetOf<String>()
        helper.readableDatabase.query("community_moderation", arrayOf("peer_id", "blocked", "muted"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) != 0) blocked += c.getString(0)
                if (c.getInt(2) != 0) muted += c.getString(0)
            }
        }
        return ModerationState(blocked, muted)
    }

    override fun setBlocked(peerId: String, blocked: Boolean) = setModeration(peerId, blocked = blocked, muted = null)
    override fun setMuted(peerId: String, muted: Boolean) = setModeration(peerId, blocked = null, muted = muted)

    private fun setModeration(peerId: String, blocked: Boolean?, muted: Boolean?) {
        val current = helper.readableDatabase.query("community_moderation", arrayOf("blocked", "muted"), "peer_id = ?", arrayOf(peerId), null, null, null).use {
            if (it.moveToFirst()) Pair(it.getInt(0) != 0, it.getInt(1) != 0) else Pair(false, false)
        }
        val v = ContentValues().apply {
            put("peer_id", peerId)
            put("blocked", if (blocked ?: current.first) 1 else 0)
            put("muted", if (muted ?: current.second) 1 else 0)
        }
        helper.writableDatabase.insertWithOnConflict("community_moderation", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun saveReport(report: CommunityReport) {
        val v = ContentValues().apply {
            put("id", report.id); put("reporter_peer_id", report.reporterPeerId); put("reported_peer_id", report.reportedPeerId)
            put("message_id", report.messageId); put("reason", report.reason); put("created_at", report.createdAtMs)
        }
        helper.writableDatabase.insertWithOnConflict("community_reports", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

internal class ChessDatabase(context: Context) : SQLiteOpenHelper(context, "decentral_chess.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        createGames(db)
        createCommunity(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createCommunity(db)
    }

    private fun createGames(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS games (
            id TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            initial_fen TEXT NOT NULL, final_fen TEXT NOT NULL, game_hash TEXT NOT NULL, pgn TEXT NOT NULL,
            status TEXT NOT NULL, white_player_id TEXT, black_player_id TEXT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_games_updated ON games(updated_at DESC)")
    }

    private fun createCommunity(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS community_messages (
            id TEXT PRIMARY KEY NOT NULL, conversation_id TEXT NOT NULL, sender_peer_id TEXT NOT NULL,
            text TEXT NOT NULL, created_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_community_messages_conversation ON community_messages(conversation_id, created_at)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS community_channels (
            id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, scope TEXT NOT NULL, description TEXT NOT NULL, member_count INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS community_peers (
            peer_id TEXT PRIMARY KEY NOT NULL, display_name TEXT NOT NULL, public_key TEXT NOT NULL,
            rating INTEGER NOT NULL, games_played INTEGER NOT NULL, is_bot INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS community_challenges (
            id TEXT PRIMARY KEY NOT NULL, from_peer_id TEXT NOT NULL, to_peer_id TEXT NOT NULL,
            time_control TEXT NOT NULL, initial_fen TEXT NOT NULL, created_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS community_tournaments (
            id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, owner_peer_id TEXT NOT NULL,
            max_players INTEGER NOT NULL, rated INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS community_moderation (
            peer_id TEXT PRIMARY KEY NOT NULL, blocked INTEGER NOT NULL, muted INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS community_reports (
            id TEXT PRIMARY KEY NOT NULL, reporter_peer_id TEXT NOT NULL, reported_peer_id TEXT NOT NULL,
            message_id TEXT, reason TEXT NOT NULL, created_at INTEGER NOT NULL)""")
    }
}
