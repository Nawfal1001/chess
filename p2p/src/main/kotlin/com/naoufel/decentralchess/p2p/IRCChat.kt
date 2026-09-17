package com.naoufel.decentralchess.p2p

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

enum class ChatCommand {
    PRIVMSG,
    NOTICE,
    JOIN,
    PART,
    NICK,
    PING,
    PONG
}

data class ChatMessage(
    val command: ChatCommand,
    val target: String,
    val text: String = "",
    val messageId: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis()
) {
    init {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        require(target.isNotBlank() && target.length <= 128) { "Invalid chat target" }
        require(text.length <= 4096) { "Chat text exceeds 4096 characters" }
        require(timestampMs >= 0) { "Invalid chat timestamp" }
    }
}

data class ChatEvent(
    val senderPeerId: PeerId,
    val message: ChatMessage
)

object ChatCodec {
    private const val VERSION = "chat-v1"

    fun encode(message: ChatMessage): ByteArray {
        val fields = listOf(
            VERSION,
            message.command.name,
            message.messageId,
            message.timestampMs.toString(),
            Base64.getEncoder().encodeToString(message.target.toByteArray(StandardCharsets.UTF_8)),
            Base64.getEncoder().encodeToString(message.text.toByteArray(StandardCharsets.UTF_8))
        )
        return fields.joinToString("|").toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(payload: ByteArray): ChatMessage {
        if (payload.size > 16 * 1024) throw P2PMatchException.InvalidMessage("Chat payload too large")
        val parts = payload.toString(StandardCharsets.UTF_8).split("|")
        if (parts.size != 6 || parts[0] != VERSION) {
            throw P2PMatchException.InvalidMessage("Malformed CHAT payload")
        }
        val command = runCatching { ChatCommand.valueOf(parts[1]) }
            .getOrElse { throw P2PMatchException.InvalidMessage("Invalid chat command") }
        val id = parts[2]
        val timestamp = parts[3].toLongOrNull()
            ?: throw P2PMatchException.InvalidMessage("Invalid chat timestamp")
        val target = decodeText(parts[4], "target")
        val text = decodeText(parts[5], "text")
        return runCatching { ChatMessage(command, target, text, id, timestamp) }
            .getOrElse { throw P2PMatchException.InvalidMessage("Invalid chat message") }
    }

    private fun decodeText(value: String, field: String): String =
        runCatching {
            Base64.getDecoder().decode(value).toString(StandardCharsets.UTF_8)
        }.getOrElse {
            throw P2PMatchException.InvalidMessage("Invalid chat $field encoding")
        }
}

class ChatHistory(
    private val maxMessages: Int = 500
) {
    private val events = ArrayDeque<ChatEvent>()

    init {
        require(maxMessages in 1..10_000)
    }

    fun add(event: ChatEvent) {
        events.addLast(event)
        while (events.size > maxMessages) events.removeFirst()
    }

    fun messages(): List<ChatEvent> = events.toList()

    fun clear() = events.clear()
}

class IRCChatSession(
    private val localPeerId: PeerId,
    private val remotePeerId: PeerId,
    private val send: suspend (ProtocolEnvelope) -> Unit,
    private val nextSequence: () -> Long = { 0L }
) {
    private val seenMessageIds = mutableSetOf<String>()
    private val joinedChannels = mutableSetOf<String>()

    fun channels(): Set<String> = joinedChannels.toSet()

    suspend fun join(channel: String) {
        validateChannel(channel)
        joinedChannels.add(channel)
        sendChat(ChatMessage(ChatCommand.JOIN, channel))
    }

    suspend fun part(channel: String) {
        validateChannel(channel)
        joinedChannels.remove(channel)
        sendChat(ChatMessage(ChatCommand.PART, channel))
    }

    suspend fun message(target: String, text: String) {
        validateTarget(target)
        require(text.isNotBlank()) { "Chat message must not be blank" }
        sendChat(ChatMessage(ChatCommand.PRIVMSG, target, text))
    }

    suspend fun notice(target: String, text: String) {
        validateTarget(target)
        require(text.isNotBlank()) { "Notice must not be blank" }
        sendChat(ChatMessage(ChatCommand.NOTICE, target, text))
    }

    suspend fun nick(name: String) {
        require(name.matches(Regex("[A-Za-z0-9_]{1,32}"))) { "Invalid nickname" }
        sendChat(ChatMessage(ChatCommand.NICK, name))
    }

    suspend fun ping() = sendChat(ChatMessage(ChatCommand.PING, remotePeerId.value))

    suspend fun receive(envelope: ProtocolEnvelope): ChatEvent? {
        if (envelope.type != MessageType.CHAT) {
            throw P2PMatchException.InvalidMessage("Expected CHAT envelope")
        }
        if (envelope.senderPeerId != remotePeerId.value) {
            throw P2PMatchException.InvalidSender(remotePeerId.value, envelope.senderPeerId)
        }
        val message = ChatCodec.decode(envelope.payload)
        if (!seenMessageIds.add(message.messageId)) return null
        return ChatEvent(remotePeerId, message)
    }

    private suspend fun sendChat(message: ChatMessage) {
        val payload = ChatCodec.encode(message)
        val envelope = ProtocolEnvelope(
            P2PProtocol.VERSION,
            UUID.randomUUID().toString(),
            "",
            "",
            localPeerId.value,
            MessageType.CHAT,
            nextSequence(),
            payload
        )
        send(envelope)
    }

    private fun validateChannel(channel: String) {
        require(channel.matches(Regex("#[A-Za-z0-9_\\-]{1,63}"))) { "Invalid IRC channel" }
    }

    private fun validateTarget(target: String) {
        require(target.startsWith("#") || target == remotePeerId.value) { "Target must be a channel or remote peer" }
    }
}

object IRCCommandParser {
    fun parse(line: String): ChatMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("/join ") -> ChatMessage(ChatCommand.JOIN, trimmed.removePrefix("/join ").trim())
            trimmed.startsWith("/part ") -> ChatMessage(ChatCommand.PART, trimmed.removePrefix("/part ").trim())
            trimmed.startsWith("/msg ") -> {
                val body = trimmed.removePrefix("/msg ").trim()
                val split = body.indexOf(' ')
                require(split > 0) { "Usage: /msg <target> <message>" }
                ChatMessage(ChatCommand.PRIVMSG, body.substring(0, split), body.substring(split + 1))
            }
            trimmed.startsWith("/notice ") -> {
                val body = trimmed.removePrefix("/notice ").trim()
                val split = body.indexOf(' ')
                require(split > 0) { "Usage: /notice <target> <message>" }
                ChatMessage(ChatCommand.NOTICE, body.substring(0, split), body.substring(split + 1))
            }
            trimmed.startsWith("/nick ") -> ChatMessage(ChatCommand.NICK, trimmed.removePrefix("/nick ").trim())
            trimmed == "/ping" -> ChatMessage(ChatCommand.PING, "")
            else -> ChatMessage(ChatCommand.PRIVMSG, "#lobby", trimmed)
        }
    }
}
