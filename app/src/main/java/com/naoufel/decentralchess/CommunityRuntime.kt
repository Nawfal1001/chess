package com.naoufel.decentralchess

import com.naoufel.decentralchess.identity.PublicIdentity
import com.naoufel.decentralchess.p2p.*
import com.naoufel.decentralchess.storage.CommunityRepository

/**
 * App-level composition root for community networking.
 *
 * It owns the routing manager and authenticated inbound handler, but deliberately does not
 * pretend that a peer is online until a READY AuthenticatedP2PSessionController is registered.
 */
class CommunityRuntime(
    val repository: CommunityRepository,
    val identity: PublicIdentity
) {
    val sessionManager = CommunitySessionManager(PeerId(identity.id))
    val handler: CommunityEnvelopeHandler =
        CommunityRepositoryHandler(repository, identity.id)

    fun isConnected(peerId: String): Boolean =
        sessionManager.isConnected(PeerId(peerId))

    fun connectedPeerIds(): Set<String> =
        sessionManager.connectedPeers().map { it.value }.toSet()

    fun pendingCount(): Int = sessionManager.pendingDeliveries().size

    suspend fun sendDirectMessage(peerId: String, text: String): CommunityDelivery =
        sessionManager.sendDirectMessage(PeerId(peerId), text)

    suspend fun sendChannelMessage(peerId: String, channelId: String, text: String): CommunityDelivery =
        sessionManager.sendChannelMessage(PeerId(peerId), channelId, text)

    suspend fun sendChallenge(
        peerId: String,
        challengeId: String,
        timeControl: String,
        initialFen: String
    ): CommunityDelivery =
        sessionManager.sendChallenge(PeerId(peerId), challengeId, timeControl, initialFen)

    suspend fun retryPending(): List<CommunityDelivery> =
        sessionManager.retryPending()
}
