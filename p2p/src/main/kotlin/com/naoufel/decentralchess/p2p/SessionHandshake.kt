package com.naoufel.decentralchess.p2p

import com.naoufel.decentralchess.chess.Side
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

enum class SessionPhase {
    DISCONNECTED,
    HELLO_SENT,
    HELLO_RECEIVED,
    NEGOTIATING,
    READY,
    RECOVERING,
    CLOSED
}

data class HandshakeIdentity(
    val peerId: PeerId,
    val publicKeyBase64: String,
    val sign: (ByteArray) -> ByteArray
)

data class HelloMessage(
    val protocolVersion: Int,
    val sessionId: String,
    val matchId: String,
    val peerId: PeerId,
    val nonceBase64: String,
    val requestedSide: Side
)

data class MatchOfferMessage(
    val sessionId: String,
    val matchId: String,
    val initiatorPeerId: PeerId,
    val responderPeerId: PeerId,
    val challengeNonceBase64: String,
    val initialFen: String
)

data class MatchAcceptMessage(
    val sessionId: String,
    val matchId: String,
    val initiatorPeerId: PeerId,
    val responderPeerId: PeerId,
    val challengeNonceBase64: String,
    val initialFen: String
)

data class HandshakeFinishedMessage(
    val transcriptHash: String
) {
    init {
        require(transcriptHash.length == 64) { "transcriptHash must be SHA-256 hex" }
    }
}

object SessionHandshakeCodec {
    private const val HELLO = "hello-v1"
    private const val OFFER = "offer-v1"
    private const val ACCEPT = "accept-v1"

    fun encode(message: HelloMessage): ByteArray =
        listOf(HELLO, message.protocolVersion, message.sessionId, message.matchId,
            message.peerId.value, message.nonceBase64, message.requestedSide.name)
            .joinToString("|").toByteArray(StandardCharsets.UTF_8)

    fun decodeHello(payload: ByteArray): HelloMessage {
        val p = payload.toString(StandardCharsets.UTF_8).split("|")
        if (p.size != 7 || p[0] != HELLO) throw P2PMatchException.InvalidMessage("Malformed HELLO payload")
        val version = p[1].toIntOrNull() ?: throw P2PMatchException.InvalidMessage("Invalid HELLO protocol version")
        val side = runCatching { Side.valueOf(p[6]) }.getOrElse {
            throw P2PMatchException.InvalidMessage("Invalid HELLO side")
        }
        return runCatching { HelloMessage(version, p[2], p[3], PeerId(p[4]), p[5], side) }.getOrElse {
            throw P2PMatchException.InvalidMessage("Invalid HELLO fields")
        }
    }

    fun encode(message: MatchOfferMessage): ByteArray =
        listOf(OFFER, message.sessionId, message.matchId, message.initiatorPeerId.value,
            message.responderPeerId.value, message.challengeNonceBase64, message.initialFen)
            .joinToString("|").toByteArray(StandardCharsets.UTF_8)

    fun decodeOffer(payload: ByteArray): MatchOfferMessage {
        val p = payload.toString(StandardCharsets.UTF_8).split("|")
        if (p.size != 7 || p[0] != OFFER) throw P2PMatchException.InvalidMessage("Malformed MATCH_OFFER payload")
        return runCatching { MatchOfferMessage(p[1], p[2], PeerId(p[3]), PeerId(p[4]), p[5], p[6]) }.getOrElse {
            throw P2PMatchException.InvalidMessage("Invalid MATCH_OFFER fields")
        }
    }

    fun encode(message: MatchAcceptMessage): ByteArray =
        listOf(ACCEPT, message.sessionId, message.matchId, message.initiatorPeerId.value,
            message.responderPeerId.value, message.challengeNonceBase64, message.initialFen)
            .joinToString("|").toByteArray(StandardCharsets.UTF_8)

    fun decodeAccept(payload: ByteArray): MatchAcceptMessage {
        val p = payload.toString(StandardCharsets.UTF_8).split("|")
        if (p.size != 7 || p[0] != ACCEPT) throw P2PMatchException.InvalidMessage("Malformed MATCH_ACCEPT payload")
        return runCatching { MatchAcceptMessage(p[1], p[2], PeerId(p[3]), PeerId(p[4]), p[5], p[6]) }.getOrElse {
            throw P2PMatchException.InvalidMessage("Invalid MATCH_ACCEPT fields")
        }
    }
}

class SessionHandshake(
    private val localPeerId: PeerId,
    private val remotePeerId: PeerId,
    private val sessionId: String,
    private val matchId: String,
    private val localSide: Side,
    private val initialFen: String,
    private val identity: HandshakeIdentity,
    private val verifySignature: (ProtocolEnvelope) -> Boolean,
    private val nonceSource: () -> ByteArray = { ByteArray(32).also(SecureRandom()::nextBytes) }
) {
    private var phase = SessionPhase.DISCONNECTED
    private var localNonceBase64: String? = null
    private var remoteNonceBase64: String? = null
    private var offerSent = false
    private var acceptSent = false
    private var localHelloPayload: ByteArray? = null
    private var remoteHelloPayload: ByteArray? = null
    private var offerPayload: ByteArray? = null
    private var acceptPayload: ByteArray? = null
    private var finishedSent = false
    private val seenMessageIds = mutableSetOf<String>()

    init {
        require(localPeerId != remotePeerId) { "Peers must differ" }
        require(identity.peerId == localPeerId) { "Handshake identity does not match local peer" }
        require(HandshakeCrypto.peerIdForPublicKey(identity.publicKeyBase64) == localPeerId) {
            "Handshake identity peer ID does not match its public key"
        }
    }

    fun phase(): SessionPhase = phase

    fun startHello(): ProtocolEnvelope {
        check(phase == SessionPhase.DISCONNECTED) { "HELLO can only start from DISCONNECTED" }
        val nonce = Base64.getEncoder().encodeToString(nonceSource())
        localNonceBase64 = nonce
        phase = SessionPhase.HELLO_SENT
        return envelope(MessageType.HELLO, SessionHandshakeCodec.encode(
            HelloMessage(P2PProtocol.VERSION, sessionId, matchId, localPeerId, nonce, localSide)
        ).also { localHelloPayload = it }
    }

    fun onEnvelope(envelope: ProtocolEnvelope): List<ProtocolEnvelope> {
        validateEnvelope(envelope)
        if (!seenMessageIds.add(envelope.messageId)) {
            throw P2PMatchException.InvalidMessage("Handshake message replayed")
        }
        return when (envelope.type) {
            MessageType.HELLO -> onHello(envelope)
            MessageType.MATCH_OFFER -> onOffer(envelope)
            MessageType.MATCH_ACCEPT -> onAccept(envelope)
            MessageType.HANDSHAKE_FINISHED -> onFinished(envelope)
            else -> throw P2PMatchException.InvalidMessage("Unexpected handshake message: ${envelope.type}")
        }
    }

    fun requireReady() {
        if (phase != SessionPhase.READY) {
            throw P2PMatchException.InvalidMessage("Session is not READY: $phase")
        }
    }

    private fun onHello(envelope: ProtocolEnvelope): List<ProtocolEnvelope> {
        val hello = SessionHandshakeCodec.decodeHello(envelope.payload)
        if (hello.protocolVersion != P2PProtocol.VERSION) throw P2PMatchException.InvalidMessage("Unsupported handshake protocol")
        validateBinding(hello.sessionId, hello.matchId, hello.peerId)
        remoteNonceBase64 = hello.nonceBase64
        remoteHelloPayload = envelope.payload.copy()
        phase = SessionPhase.HELLO_RECEIVED

        val localIsInitiator = localPeerId.value < remotePeerId.value
        return if (localIsInitiator && !offerSent) {
            offerSent = true
            phase = SessionPhase.NEGOTIATING
            listOf(envelope(MessageType.MATCH_OFFER, SessionHandshakeCodec.encode(
                MatchOfferMessage(sessionId, matchId, localPeerId, remotePeerId, hello.nonceBase64, initialFen)
            ).also { offerPayload = it }))
        } else emptyList()
    }

    private fun onOffer(envelope: ProtocolEnvelope): List<ProtocolEnvelope> {
        val offer = SessionHandshakeCodec.decodeOffer(envelope.payload)
        if (offer.sessionId != sessionId || offer.matchId != matchId) throw P2PMatchException.InvalidMessage("MATCH_OFFER binding mismatch")
        if (offer.initiatorPeerId != remotePeerId || offer.responderPeerId != localPeerId) {
            throw P2PMatchException.InvalidMessage("MATCH_OFFER peer binding mismatch")
        }
        if (offer.challengeNonceBase64 != remoteNonceBase64) {
            throw P2PMatchException.InvalidMessage("MATCH_OFFER challenge mismatch")
        }
        if (offer.initialFen != initialFen) throw P2PMatchException.InvalidMessage("MATCH_OFFER initial position mismatch")
        if (localPeerId.value < remotePeerId.value) {
            throw P2PMatchException.InvalidMessage("Unexpected MATCH_OFFER from initiator")
        }
        phase = SessionPhase.NEGOTIATING
        if (acceptSent) throw P2PMatchException.InvalidMessage("MATCH_ACCEPT already sent")
        acceptSent = true
        val accept = SessionHandshakeCodec.encode(MatchAcceptMessage(sessionId, matchId, remotePeerId, localPeerId, offer.challengeNonceBase64, initialFen)).also { acceptPayload = it }
        phase = SessionPhase.NEGOTIATING
        acceptSent = true
        return listOf(envelope(MessageType.MATCH_ACCEPT, accept))
    }

    private fun onAccept(envelope: ProtocolEnvelope): List<ProtocolEnvelope> {
        val accept = SessionHandshakeCodec.decodeAccept(envelope.payload)
        if (accept.sessionId != sessionId || accept.matchId != matchId) throw P2PMatchException.InvalidMessage("MATCH_ACCEPT binding mismatch")
        if (accept.initiatorPeerId != localPeerId || accept.responderPeerId != remotePeerId) {
            throw P2PMatchException.InvalidMessage("MATCH_ACCEPT peer binding mismatch")
        }
        if (accept.challengeNonceBase64 != localNonceBase64) {
            throw P2PMatchException.InvalidMessage("MATCH_ACCEPT challenge mismatch")
        }
        if (accept.initialFen != initialFen) throw P2PMatchException.InvalidMessage("MATCH_ACCEPT initial position mismatch")
        if (!offerSent) throw P2PMatchException.InvalidMessage("MATCH_ACCEPT received before MATCH_OFFER")
        val acceptPayloadBytes = envelope.payload.copy()
        acceptPayload = acceptPayloadBytes
        phase = SessionPhase.NEGOTIATING
        if (finishedSent) throw P2PMatchException.InvalidMessage("HANDSHAKE_FINISHED already sent")
        finishedSent = true
        return listOf(envelope(MessageType.HANDSHAKE_FINISHED, encodeFinished()))
    }

    private fun onFinished(envelope: ProtocolEnvelope): List<ProtocolEnvelope> {
        if (phase != SessionPhase.NEGOTIATING) throw P2PMatchException.InvalidMessage("HANDSHAKE_FINISHED received before negotiation")
        val finished = decodeFinished(envelope.payload)
        val expected = transcriptHash()
        if (finished.transcriptHash != expected) throw P2PMatchException.InvalidMessage("Handshake transcript mismatch")
        if (!finishedSent) {
            finishedSent = true
            phase = SessionPhase.READY
            return listOf(envelope(MessageType.HANDSHAKE_FINISHED, encodeFinished()))
        }
        phase = SessionPhase.READY
        return emptyList()
    }

    private fun encodeFinished(): ByteArray = listOf("finished-v1", transcriptHash()).joinToString("|").toByteArray(StandardCharsets.UTF_8)

    private fun decodeFinished(payload: ByteArray): HandshakeFinishedMessage {
        val parts = payload.toString(StandardCharsets.UTF_8).split("|")
        if (parts.size != 2 || parts[0] != "finished-v1") throw P2PMatchException.InvalidMessage("Malformed HANDSHAKE_FINISHED payload")
        return runCatching { HandshakeFinishedMessage(parts[1]) }.getOrElse { throw P2PMatchException.InvalidMessage("Invalid HANDSHAKE_FINISHED payload") }
    }

    private fun transcriptHash(): String {
        val localHello = localHelloPayload ?: throw P2PMatchException.InvalidMessage("Local HELLO missing")
        val remoteHello = remoteHelloPayload ?: throw P2PMatchException.InvalidMessage("Remote HELLO missing")
        val offer = offerPayload ?: throw P2PMatchException.InvalidMessage("MATCH_OFFER missing")
        val accept = acceptPayload ?: throw P2PMatchException.InvalidMessage("MATCH_ACCEPT missing")
        val helloPair = if (localPeerId.value < remotePeerId.value) listOf(localHello, remoteHello) else listOf(remoteHello, localHello)
        val parts = helloPair + listOf(offer, accept)
        val framed = parts.fold(ByteArray(0)) { acc, bytes -> acc + java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes }
        return MessageDigest.getInstance("SHA-256").digest(framed).joinToString("") { "%02x".format(it) }
    }

    private fun validateEnvelope(envelope: ProtocolEnvelope) {
        if (envelope.version != P2PProtocol.VERSION) throw P2PMatchException.InvalidMessage("Unsupported envelope version")
        if (envelope.sessionId != sessionId) throw P2PMatchException.InvalidSession(sessionId, envelope.sessionId)
        if (envelope.matchId != matchId) throw P2PMatchException.InvalidMessage("Invalid match ID")
        if (envelope.senderPeerId != remotePeerId.value) throw P2PMatchException.InvalidSender(remotePeerId.value, envelope.senderPeerId)
        val publicKey = envelope.senderPublicKeyBase64
            ?: throw P2PMatchException.InvalidMessage("Authenticated handshake requires public key and signature")
        if (envelope.signatureBase64 == null) {
            throw P2PMatchException.InvalidMessage("Authenticated handshake requires public key and signature")
        }
        val derivedPeerId = runCatching { HandshakeCrypto.peerIdForPublicKey(publicKey) }.getOrElse {
            throw P2PMatchException.InvalidMessage("Invalid handshake public key encoding")
        }
        if (derivedPeerId != remotePeerId) {
            throw P2PMatchException.InvalidSender(remotePeerId.value, derivedPeerId.value)
        }
        if (!verifySignature(envelope)) throw P2PMatchException.InvalidMessage("Invalid handshake signature")
    }

    private fun validateBinding(session: String, match: String, peer: PeerId) {
        if (session != sessionId || match != matchId || peer != remotePeerId) {
            throw P2PMatchException.InvalidMessage("Handshake identity binding mismatch")
        }
    }

    private fun envelope(type: MessageType, payload: ByteArray): ProtocolEnvelope {
        val unsigned = ProtocolEnvelope(
            P2PProtocol.VERSION, UUID.randomUUID().toString(), sessionId, matchId,
            localPeerId.value, type, 0L, payload, identity.publicKeyBase64
        )
        return unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(identity.sign(unsigned.canonicalBytes())))
    }
}

class AuthenticatedP2PSessionController(
    private val state: MatchStateMachine,
    private val transport: PeerTransport,
    private val identity: HandshakeIdentity,
    verifySignature: (ProtocolEnvelope) -> Boolean,
    private val communityHandler: CommunityEnvelopeHandler? = null
) : PeerTransportListener {
    private val handshake = SessionHandshake(
        state.localPeerId, state.remotePeerId, state.sessionId, state.matchId,
        state.localSide, state.history().initialPosition().toFen(), identity, verifySignature
    )
    private var nextCommunitySequence = 0L

    init {
        require(identity.peerId == state.localPeerId) { "Handshake identity does not match state" }
    }

    fun attach() {
        transport.setListener(this)
    }

    fun phase(): SessionPhase = handshake.phase()

    suspend fun connect() {
        transport.connect(state.remotePeerId)
    }

    suspend fun sendMove(move: com.naoufel.decentralchess.chess.Move): ProtocolEnvelope {
        handshake.requireReady()
        val envelope = state.createLocalMove(move)
        transport.send(state.remotePeerId, ProtocolEnvelopeCodec.encode(envelope))
        return envelope
    }

    suspend fun sendCommunity(type: MessageType, packet: CommunityPacket): ProtocolEnvelope {
        handshake.requireReady()
        require(CommunityP2PReceiver.isCommunityType(type))
        require(packet.senderPeerId == identity.peerId.value)
        val unsigned = ProtocolEnvelope(
            P2PProtocol.VERSION,
            UUID.randomUUID().toString(),
            state.sessionId,
            state.matchId,
            identity.peerId.value,
            type,
            nextCommunitySequence++,
            CommunityPacketCodec.encode(packet),
            identity.publicKeyBase64
        )
        val envelope = unsigned.copy(
            signatureBase64 = Base64.getEncoder().encodeToString(identity.sign(unsigned.canonicalBytes()))
        )
        transport.send(state.remotePeerId, ProtocolEnvelopeCodec.encode(envelope))
        return envelope
    }

    override suspend fun onConnected(peer: PeerId) {
        if (peer != state.remotePeerId) throw P2PMatchException.InvalidSender(state.remotePeerId.value, peer.value)
        if (handshake.phase() == SessionPhase.DISCONNECTED) {
            val hello = handshake.startHello()
            transport.send(peer, ProtocolEnvelopeCodec.encode(hello))
        }
    }

    override suspend fun onPayload(peer: PeerId, payload: ByteArray) {
        if (peer != state.remotePeerId) throw P2PMatchException.InvalidSender(state.remotePeerId.value, peer.value)
        val envelope = ProtocolEnvelopeCodec.decode(payload)
        when (handshake.phase()) {
            SessionPhase.READY, SessionPhase.RECOVERING -> onReadyPayload(envelope)
            else -> {
                val responses = handshake.onEnvelope(envelope)
                responses.forEach { transport.send(state.remotePeerId, ProtocolEnvelopeCodec.encode(it)) }
            }
        }
    }

    private suspend fun onReadyPayload(envelope: ProtocolEnvelope) {
        when (envelope.type) {
            MessageType.MOVE -> {
                state.receive(envelope)
                val ack = state.createAck(envelope)
                transport.send(state.remotePeerId, ProtocolEnvelopeCodec.encode(ack))
            }
            MessageType.ACK -> state.receiveAck(envelope)
            MessageType.STATE_REQUEST -> {
                val response = state.createStateResponse(envelope)
                transport.send(state.remotePeerId, ProtocolEnvelopeCodec.encode(response))
            }
            MessageType.STATE_RESPONSE -> state.receiveStateResponse(envelope)
            MessageType.CHANNEL_JOIN, MessageType.CHANNEL_LEAVE, MessageType.CHAT,
            MessageType.DM, MessageType.CHALLENGE, MessageType.CHALLENGE_ACCEPT,
            MessageType.PROFILE, MessageType.TOURNAMENT -> {
                val handler = communityHandler
                    ?: throw P2PMatchException.InvalidMessage("No community handler attached")
                val packet = CommunityP2PReceiver.decode(
                    envelope,
                    state.remotePeerId.value,
                    requireSignature = true
                )
                handler.onCommunityEnvelope(state.remotePeerId, envelope, packet)
            }
            else -> throw P2PMatchException.InvalidMessage("Unsupported READY message: ${envelope.type}")
        }
    }

    override suspend fun onDisconnected(peer: PeerId) {
        if (peer == state.remotePeerId) {
            state.markDisconnected()
            communityHandler?.onCommunityDisconnected(peer)
        }
    }
}

object HandshakeCrypto {
    fun peerIdForPublicKey(publicKeyBase64: String): PeerId {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        require(keyBytes.isNotEmpty()) { "Public key must not be empty" }
        return PeerId(MessageDigest.getInstance("SHA-256").digest(keyBytes).joinToString("") { "%02x".format(it) })
    }

    fun verifierFor(expectedPublicKeyBase64: String): (ProtocolEnvelope) -> Boolean = { envelope ->
        envelope.senderPublicKeyBase64 == expectedPublicKeyBase64 && EnvelopeVerifier.verify(envelope)
    }
}
