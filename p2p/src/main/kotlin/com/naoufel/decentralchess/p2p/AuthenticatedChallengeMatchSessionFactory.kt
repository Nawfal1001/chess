package com.naoufel.decentralchess.p2p

import com.naoufel.decentralchess.chess.GameHistory
import com.naoufel.decentralchess.chess.Position
import com.naoufel.decentralchess.chess.Side

/**
 * Supplies the transport used to reach a peer.
 * The networking layer stays outside the chess protocol so the same factory works
 * with in-memory, LAN, WebSocket, or relay transports.
 */
fun interface PeerTransportProvider {
    fun transportFor(peerId: PeerId): PeerTransport
}

/**
 * Concrete bridge from an accepted community challenge to an authenticated chess session.
 */
class AuthenticatedChallengeMatchSessionFactory(
    private val transportProvider: PeerTransportProvider,
    private val localIdentity: HandshakeIdentity,
    private val remotePublicKeyProvider: (PeerId) -> String?,
    private val communityHandler: CommunityEnvelopeHandler? = null,
    private val telemetrySink: MatchTelemetrySink? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) : ChallengeMatchSessionFactory {

    override suspend fun create(
        challenge: AcceptedChallenge,
        localPeerId: PeerId,
        remotePeerId: PeerId,
        localSide: Side
    ): AuthenticatedP2PSessionController {
        require(localPeerId == localIdentity.peerId) {
            "Factory local identity does not match requested local peer"
        }

        val remotePublicKey = remotePublicKeyProvider(remotePeerId)
            ?: throw P2PMatchException.InvalidMessage(
                "No authenticated public key is known for ${remotePeerId.value}"
            )

        val initialPosition = runCatching { Position.fromFen(challenge.initialFen) }
            .getOrElse { throw P2PMatchException.InvalidMessage("Invalid challenge initial FEN") }

        val state = MatchStateMachine(
            sessionId = challenge.sessionId,
            localPeerId = localPeerId,
            remotePeerId = remotePeerId,
            localSide = localSide,
            matchId = challenge.matchId,
            localIdentity = EnvelopeIdentity(
                localIdentity.peerId,
                localIdentity.publicKeyBase64,
                localIdentity.sign
            ),
            remotePublicKeyBase64 = remotePublicKey,
            initialHistory = GameHistory(initialPosition)
        )

        val controller = AuthenticatedP2PSessionController(
            state = state,
            transport = transportProvider.transportFor(remotePeerId),
            identity = localIdentity,
            verifySignature = { envelope ->\n                envelope.senderPublicKeyBase64 == remotePublicKey && EnvelopeVerifier.verify(envelope)\n            },
            communityHandler = communityHandler,
            telemetrySink = telemetrySink,
            clockMs = clockMs
        )
        controller.attach()
        return controller
    }
}
