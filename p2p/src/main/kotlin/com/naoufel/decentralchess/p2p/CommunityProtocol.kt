package com.naoufel.decentralchess.p2p

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** Application-level community packets carried inside the existing authenticated ProtocolEnvelope. */
data class CommunityPacket(
    val conversationId: String,
    val senderPeerId: String,
    val text: String = "",
    val referenceId: String? = null,
    val timeControl: String? = null,
    val initialFen: String? = null,
    val displayName: String? = null,
    val rating: Int? = null
) {
    init {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        require(senderPeerId.isNotBlank() && senderPeerId.length <= 128)
        require(text.length <= 4096)
        require(referenceId == null || referenceId.isNotBlank())
        require(timeControl == null || timeControl.isNotBlank())
        require(initialFen == null || initialFen.isNotBlank())
        require(referenceId == null || referenceId.length <= 128)
        require(timeControl == null || timeControl.length <= 32)
        require(initialFen == null || initialFen.length <= 256)
        require(displayName == null || displayName.length <= 64)
        require(rating == null || rating in 0..4000)
    }
}

object CommunityPacketCodec {
    private const val VERSION = "community-v1"
    private const val FIELD_COUNT = 9

    fun encode(packet: CommunityPacket): ByteArray {
        val fields = listOf(
            VERSION, packet.conversationId, packet.senderPeerId, packet.text,
            packet.referenceId.orEmpty(), packet.timeControl.orEmpty(), packet.initialFen.orEmpty(),
            packet.displayName.orEmpty(), packet.rating?.toString().orEmpty()
        ).map { Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) }
        return fields.joinToString("|").toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(payload: ByteArray): CommunityPacket {
        if (payload.size > 64 * 1024) throw P2PMatchException.InvalidMessage("Community payload too large")
        val parts = payload.toString(StandardCharsets.UTF_8).split("|")
        if (parts.size != FIELD_COUNT) throw P2PMatchException.InvalidMessage("Malformed community payload")
        val values = parts.map { encoded ->
            runCatching { Base64.getDecoder().decode(encoded).toString(StandardCharsets.UTF_8) }
                .getOrElse { throw P2PMatchException.InvalidMessage("Invalid community encoding") }
        }
        if (values[0] != VERSION) throw P2PMatchException.InvalidMessage("Unsupported community payload version")
        val rating = values[8].takeIf { it.isNotEmpty() }?.toIntOrNull()
            ?: values[8].takeIf { it.isNotEmpty() }?.let { throw P2PMatchException.InvalidMessage("Invalid rating") }
        return runCatching {
            CommunityPacket(
                conversationId = values[1],
                senderPeerId = values[2],
                text = values[3],
                referenceId = values[4].ifEmpty { null },
                timeControl = values[5].ifEmpty { null },
                initialFen = values[6].ifEmpty { null },
                displayName = values[7].ifEmpty { null },
                rating = rating
            )
        }.getOrElse { throw P2PMatchException.InvalidMessage("Invalid community packet") }
    }
}

private val COMMUNITY_TYPES = setOf(
    MessageType.CHANNEL_JOIN, MessageType.CHANNEL_LEAVE, MessageType.CHAT,
    MessageType.DM, MessageType.CHALLENGE, MessageType.CHALLENGE_ACCEPT,
    MessageType.PROFILE, MessageType.TOURNAMENT
)

interface CommunityEnvelopeHandler {
    suspend fun onCommunityEnvelope(peer: PeerId, envelope: ProtocolEnvelope, packet: CommunityPacket)
    suspend fun onCommunityDisconnected(peer: PeerId) {}
}

class CommunityP2PClient(
    private val localPeerId: PeerId,
    private val send: suspend (ProtocolEnvelope) -> Unit,
    private val identity: EnvelopeIdentity? = null,
    private val nextSequence: () -> Long = { 0L },
    private val sessionId: String? = null,
    private val matchId: String? = null
) {
    suspend fun send(
        type: MessageType,
        packet: CommunityPacket,
        sessionId: String? = this.sessionId,
        matchId: String? = this.matchId
    ) {
        require(type in COMMUNITY_TYPES)
        require(!sessionId.isNullOrBlank() && !matchId.isNullOrBlank())
        require(packet.senderPeerId == localPeerId.value)
        val unsigned = ProtocolEnvelope(
            P2PProtocol.VERSION, UUID.randomUUID().toString(), sessionId!!, matchId!!,
            localPeerId.value, type, nextSequence(), CommunityPacketCodec.encode(packet),
            identity?.publicKeyBase64
        )
        send(if (identity == null) unsigned else unsigned.copy(
            signatureBase64 = identity.sign(unsigned)
        ))
    }
}

object CommunityP2PReceiver {
    fun isCommunityType(type: MessageType): Boolean = type in COMMUNITY_TYPES

    fun decode(
        envelope: ProtocolEnvelope,
        expectedSenderPeerId: String? = null,
        expectedPublicKeyBase64: String? = null,
        requireSignature: Boolean = false
    ): CommunityPacket {
        require(envelope.type in COMMUNITY_TYPES)
        if (expectedSenderPeerId != null && envelope.senderPeerId != expectedSenderPeerId) {
            throw P2PMatchException.InvalidSender(expectedSenderPeerId, envelope.senderPeerId)
        }
        if (requireSignature || expectedPublicKeyBase64 != null) {
            if (envelope.senderPublicKeyBase64 == null || envelope.signatureBase64 == null) {
                throw P2PMatchException.InvalidMessage("Signed community envelope required")
            }
            if (expectedPublicKeyBase64 != null && envelope.senderPublicKeyBase64 != expectedPublicKeyBase64) {
                throw P2PMatchException.InvalidMessage("Unexpected community sender public key")
            }
            if (!EnvelopeVerifier.verify(envelope)) {
                throw P2PMatchException.InvalidMessage("Invalid community envelope signature")
            }
        }
        val packet = CommunityPacketCodec.decode(envelope.payload)
        if (packet.senderPeerId != envelope.senderPeerId) {
            throw P2PMatchException.InvalidSender(envelope.senderPeerId, packet.senderPeerId)
        }
        return packet
    }
}
