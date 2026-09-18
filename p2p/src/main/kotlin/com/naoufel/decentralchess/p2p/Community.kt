package com.naoufel.decentralchess.p2p

/** Community domain foundation. */
enum class CommunityScope { GLOBAL, LOCAL }
enum class ConversationKind { CHANNEL, DIRECT, GAME, TOURNAMENT }

data class CommunityChannel(val id: String, val name: String, val scope: CommunityScope, val description: String = "", val memberCount: Int = 0)
data class PeerProfile(val peerId: String, val displayName: String, val publicKeyBase64: String, val rating: Int = 1200, val gamesPlayed: Int = 0, val bot: Boolean = false)
data class CommunityMessage(val id: String, val conversationId: String, val senderPeerId: String, val text: String, val createdAtMs: Long)
data class CommunityChallenge(val id: String, val fromPeerId: String, val toPeerId: String, val timeControl: String, val initialFen: String, val createdAtMs: Long)
data class TournamentRoom(val id: String, val name: String, val ownerPeerId: String, val maxPlayers: Int = 64, val rated: Boolean = true)
data class ModerationState(val blockedPeers: Set<String> = emptySet(), val mutedPeers: Set<String> = emptySet())
data class CommunityReport(val id: String, val reporterPeerId: String, val reportedPeerId: String, val messageId: String?, val reason: String, val createdAtMs: Long)

interface CommunityHistory {
    fun append(message: CommunityMessage)
    fun messages(conversationId: String, limit: Int = 200): List<CommunityMessage>
    fun clear(conversationId: String)
}

class InMemoryCommunityHistory : CommunityHistory {
    private val items = mutableListOf<CommunityMessage>()
    override fun append(message: CommunityMessage) { items.removeAll { it.id == message.id }; items += message }
    override fun messages(conversationId: String, limit: Int): List<CommunityMessage> = items.filter { it.conversationId == conversationId }.takeLast(limit)
    override fun clear(conversationId: String) { items.removeAll { it.conversationId == conversationId } }
}
