package com.naoufel.decentralchess.p2p

import java.util.UUID

/**
 * Owns authenticated community sessions and provides one routing surface for UI/network code.
 *
 * The manager deliberately does not invent a transport: callers register an already configured
 * AuthenticatedP2PSessionController. This keeps authentication and match state in the existing
 * session controller while giving community features a stable multi-peer API.
 */
data class CommunityDelivery(
    val id: String,
    val peerId: PeerId,
    val type: MessageType,
    val packet: CommunityPacket,
    val createdAtMs: Long = System.currentTimeMillis(),
    var attempts: Int = 0,
    var delivered: Boolean = false
)

class CommunitySessionManager(
    private val localPeerId: PeerId
) {
    private val sessions = linkedMapOf<PeerId, AuthenticatedP2PSessionController>()
    private val subscriptions = linkedMapOf<PeerId, MutableSet<String>>()
    private val pending = linkedMapOf<String, CommunityDelivery>()

    fun register(peer: PeerId, controller: AuthenticatedP2PSessionController) {
        require(peer != localPeerId) { "Cannot register local peer as remote session" }
        sessions[peer] = controller
    }

    fun unregister(peer: PeerId) {
        sessions.remove(peer)
        subscriptions.remove(peer)
    }

    fun isConnected(peer: PeerId): Boolean =
        sessions[peer]?.phase() == SessionPhase.READY

    fun connectedPeers(): List<PeerId> =
        sessions.filterValues { it.phase() == SessionPhase.READY }.keys.toList()

    fun subscribe(peer: PeerId, channelId: String) {
        require(channelId.isNotBlank())
        subscriptions.getOrPut(peer) { linkedSetOf() }.add(channelId)
    }

    fun unsubscribe(peer: PeerId, channelId: String) {
        subscriptions[peer]?.remove(channelId)
    }

    fun subscribedChannels(peer: PeerId): Set<String> =
        subscriptions[peer]?.toSet() ?: emptySet()

    suspend fun sendToPeer(
        peer: PeerId,
        type: MessageType,
        packet: CommunityPacket
    ): CommunityDelivery {
        require(peer != localPeerId)
        require(packet.senderPeerId == localPeerId.value) {
            "Community packet sender must be the local peer"
        }
        val delivery = CommunityDelivery(UUID.randomUUID().toString(), peer, type, packet)
        pending[delivery.id] = delivery
        val controller = sessions[peer]
        if (controller == null || controller.phase() != SessionPhase.READY) {
            throw P2PMatchException.InvalidSender(peer.value, "not-connected")
        }
        try {
            delivery.attempts++
            controller.sendCommunity(type, packet)
            delivery.delivered = true
            pending.remove(delivery.id)
            return delivery
        } catch (t: Throwable) {
            // Keep the item for an explicit retry once the authenticated session is READY.
            throw t
        }
    }

    suspend fun sendChannelMessage(peer: PeerId, channelId: String, text: String): CommunityDelivery =
        sendToPeer(peer, MessageType.CHAT, CommunityPacket(channelId, localPeerId.value, text))

    suspend fun sendDirectMessage(peer: PeerId, text: String): CommunityDelivery {
        val conversationId = listOf(localPeerId.value, peer.value).sorted().joinToString(":")
        return sendToPeer(peer, MessageType.DM, CommunityPacket(conversationId, localPeerId.value, text))
    }

    suspend fun sendProfile(peer: PeerId, displayName: String, rating: Int): CommunityDelivery =
        sendToPeer(peer, MessageType.PROFILE, CommunityPacket(
            conversationId = "profile:${localPeerId.value}",
            senderPeerId = localPeerId.value,
            displayName = displayName,
            rating = rating
        ))

    suspend fun sendChallenge(
        peer: PeerId,
        challengeId: String,
        timeControl: String,
        initialFen: String
    ): CommunityDelivery = sendToPeer(
        peer,
        MessageType.CHALLENGE,
        CommunityPacket(
            conversationId = "challenge:$challengeId",
            senderPeerId = localPeerId.value,
            referenceId = peer.value,
            timeControl = timeControl,
            initialFen = initialFen
        )
    )

    suspend fun acceptChallenge(
        peer: PeerId,
        challengeId: String,
        timeControl: String,
        initialFen: String
    ): CommunityDelivery = sendToPeer(
        peer,
        MessageType.CHALLENGE_ACCEPT,
        CommunityPacket(
            conversationId = "challenge:$challengeId",
            senderPeerId = localPeerId.value,
            referenceId = challengeId,
            timeControl = timeControl,
            initialFen = initialFen
        )
    )

    suspend fun broadcast(
        channelId: String,
        type: MessageType,
        packetFactory: (PeerId) -> CommunityPacket
    ): List<CommunityDelivery> {
        val recipients = connectedPeers().filter { channelId in subscribedChannels(it) }
        return recipients.map { peer ->
            sendToPeer(peer, type, packetFactory(peer))
        }
    }

    suspend fun retryPending(): List<CommunityDelivery> {
        val completed = mutableListOf<CommunityDelivery>()
        val queued = pending.values.toList()
        for (delivery in queued) {
            if (!isConnected(delivery.peerId)) continue
            try {
                delivery.attempts++
                sessions.getValue(delivery.peerId).sendCommunity(delivery.type, delivery.packet)
                delivery.delivered = true
                pending.remove(delivery.id)
                completed += delivery
            } catch (_: Throwable) {
                // Leave queued; the next reconnect/retry can attempt delivery again.
            }
        }
        return completed
    }

    fun pendingDeliveries(): List<CommunityDelivery> = pending.values.toList()

    fun clearPending(id: String) {
        pending.remove(id)
    }

    fun clear() {
        sessions.clear()
        subscriptions.clear()
        pending.clear()
    }
}
