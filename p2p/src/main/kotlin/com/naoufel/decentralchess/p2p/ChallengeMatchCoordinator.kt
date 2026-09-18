package com.naoufel.decentralchess.p2p

import com.naoufel.decentralchess.chess.Side
import java.security.MessageDigest

data class AcceptedChallenge(
    val challengeId: String,
    val fromPeerId: PeerId,
    val toPeerId: PeerId,
    val timeControl: String,
    val initialFen: String
) {
    val sessionId: String = deterministicId("session", challengeId, fromPeerId.value, toPeerId.value)
    val matchId: String = deterministicId("match", challengeId, fromPeerId.value, toPeerId.value)

    fun localSide(localPeerId: PeerId): Side = when (localPeerId) {
        fromPeerId -> Side.WHITE
        toPeerId -> Side.BLACK
        else -> throw P2PMatchException.InvalidSender(fromPeerId.value, localPeerId.value)
    }

    companion object {
        private fun deterministicId(prefix: String, challengeId: String, from: String, to: String): String {
            val canonical = listOf(prefix, challengeId, from, to).joinToString("\n")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return prefix + "-" + digest.take(32)
        }
    }
}

interface CommunityChallengeAcceptanceListener {
    suspend fun onChallengeAccepted(challenge: AcceptedChallenge)
}

interface ChallengeMatchSessionFactory {
    suspend fun create(
        challenge: AcceptedChallenge,
        localPeerId: PeerId,
        remotePeerId: PeerId,
        localSide: Side
    ): AuthenticatedP2PSessionController
}

class ChallengeMatchCoordinator(
    private val localPeerId: PeerId,
    private val sessions: CommunitySessionManager,
    private val factory: ChallengeMatchSessionFactory
) {
    private val controllers = linkedMapOf<String, AuthenticatedP2PSessionController>()

    suspend fun accept(challenge: AcceptedChallenge): AuthenticatedP2PSessionController {
        val localSide = challenge.localSide(localPeerId)
        val remotePeerId = if (localSide == Side.WHITE) challenge.toPeerId else challenge.fromPeerId
        val existing = controllers[challenge.matchId]
        if (existing != null) {
            sessions.register(remotePeerId, existing)
            return existing
        }

        val controller = factory.create(challenge, localPeerId, remotePeerId, localSide)
        controllers[challenge.matchId] = controller
        sessions.register(remotePeerId, controller)
        controller.connect()
        return controller
    }

    fun controller(matchId: String): AuthenticatedP2PSessionController? = controllers[matchId]
    fun activeMatchIds(): Set<String> = controllers.keys.toSet()

    fun remove(matchId: String, remotePeerId: PeerId) {
        controllers.remove(matchId)
        sessions.unregister(remotePeerId)
    }

    fun clear() {
        controllers.clear()
        sessions.clear()
    }
}
