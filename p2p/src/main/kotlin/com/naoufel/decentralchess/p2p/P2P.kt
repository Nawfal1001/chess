}

enum class MessageType { HELLO, MATCH_OFFER, MATCH_ACCEPT, HANDSHAKE_FINISHED, MOVE, ACK, HASH_CHECKPOINT, MATCH_FINAL, STATE_REQUEST, STATE_RESPONSE, CHAT, CHANNEL_JOIN, CHANNEL_LEAVE, DM, CHALLENGE, CHALLENGE_ACCEPT, PROFILE, TOURNAMENT }

private const val MAX_PAYLOAD_BYTES = 1024 * 1024
private const val MAX_TEXT_FIELD_LENGTH = 4096

data class ProtocolEnvelope(
    val version: Int,
    val messageId: String,
    val sessionId: String,
    val matchId: String,
    val senderPeerId: String,
    val type: MessageType,
    val sequence: Long,
    val payload: ByteArray,
    val senderPublicKeyBase64: String? = null,
    val signatureBase64: String? = null
) {
    init {
        require(version == 1) { "Unsupported P2P protocol version" }