package com.naoufel.decentralchess

import com.naoufel.decentralchess.identity.PublicIdentity
import com.naoufel.decentralchess.p2p.*
import com.naoufel.decentralchess.storage.CommunityRepository

class CommunityRuntime(
    val repository: CommunityRepository,
    val identity: PublicIdentity,
    challengeMatchFactory: ChallengeMatchSessionFactory? = null
) {
    val sessionManager = CommunitySessionManager(PeerId(identity.id))

    val matchCoordinator: ChallengeMatchCoordinator? = challengeMatchFactory?.let {
        ChallengeMatchCoordinator(PeerId(identity.id), sessionManager, it)
    }

    private val membershipListener = object : CommunityMembershipListener {
        override suspend fun onChannelJoin(peer: PeerId, channelId: String) {
            sessionManager.subscribe(peer, channelId)
        }

        override suspend fun onChannelLeave(peer: PeerId, channelId: String) {
            sessionManager.unsubscribe(peer, channelId)
        }
    }

    private val challengeAcceptanceListener = object : CommunityChallengeAcceptanceListener {
        override suspend fun onChallengeAccepted(challenge: AcceptedChallenge) {
            matchCoordinator?.accept(challenge)
        }
    }

    val handler: CommunityEnvelopeHandler =
        CommunityRepositoryHandler(
            repository = repository,
            localPeerId = identity.id,
            challengeAcceptanceListener = challengeAcceptanceListener,
            membershipListener = membershipListener
        )

    fun isConnected(peerId: String): Boolean =
        sessionManager.isConnected(PeerId(peerId))

    fun connectedPeerIds(): Set<String> =
        sessionManager.connectedPeers().map { it.value }.toSet()

    fun pendingCount(): Int = sessionManager.pendingDeliveries().size

    fun registerAuthenticatedSession(peerId: String, controller: AuthenticatedP2PSessionController) {
        sessionManager.register(PeerId(peerId), controller)
    }

    fun unregisterAuthenticatedSession(peerId: String) {
        sessionManager.unregister(PeerId(peerId))
    }

    suspend fun sendDirectMessage(peerId: String, text: String): CommunityDelivery =
        sessionManager.sendDirectMessage(PeerId(peerId), text)

    suspend fun sendChannelMessage(peerId: String, channelId: String, text: String): CommunityDelivery =
        sessionManager.sendChannelMessage(PeerId(peerId), channelId, text)

    suspend fun broadcastChannelMessage(channelId: String, text: String): List<CommunityDelivery> =
        sessionManager.broadcast(channelId, MessageType.CHAT) { peer ->
            CommunityPacket(channelId, identity.id, text)
        }

    suspend fun joinChannel(peerId: String, channelId: String): CommunityDelivery =
        sessionManager.joinChannel(PeerId(peerId), channelId)

    suspend fun leaveChannel(peerId: String, channelId: String): CommunityDelivery =
        sessionManager.leaveChannel(PeerId(peerId), channelId)

    suspend fun sendChallenge(
        peerId: String,
        challengeId: String,
        timeControl: String,
        initialFen: String
    ): CommunityDelivery =
        sessionManager.sendChallenge(PeerId(peerId), challengeId, timeControl, initialFen)

    suspend fun acceptChallenge(
        peerId: String,
        challengeId: String,
        timeControl: String,
        initialFen: String
    ): CommunityDelivery =
        sessionManager.acceptChallenge(PeerId(peerId), challengeId, timeControl, initialFen)

    suspend fun retryPending(): List<CommunityDelivery> =
        sessionManager.retryPending()

    fun activeMatchIds(): Set<String> = matchCoordinator?.activeMatchIds().orEmpty()

    fun matchController(matchId: String): AuthenticatedP2PSessionController? =
        matchCoordinator?.controller(matchId)
}
