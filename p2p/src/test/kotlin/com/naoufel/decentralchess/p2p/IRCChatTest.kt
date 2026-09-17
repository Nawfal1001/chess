package com.naoufel.decentralchess.p2p

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IRCChatTest {
    private val alice = PeerId("alice")
    private val bob = PeerId("bob")

    @Test
    fun chatCodecRoundTripsPrivateMessage() {
        val original = ChatMessage(ChatCommand.PRIVMSG, bob.value, "hello ♟")
        val decoded = ChatCodec.decode(ChatCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun parserSupportsIrcCommandsAndLobbyFallback() {
        assertEquals(ChatCommand.JOIN, IRCCommandParser.parse("/join #lobby")!!.command)
        assertEquals("#lobby", IRCCommandParser.parse("/join #lobby")!!.target)
        assertEquals(ChatCommand.PRIVMSG, IRCCommandParser.parse("/msg bob hi")!!.command)
        assertEquals("hi", IRCCommandParser.parse("/msg bob hi")!!.text)
        assertEquals("#lobby", IRCCommandParser.parse("hello everyone")!!.target)
    }

    @Test
    fun duplicateChatMessageIsIgnored() {
        val message = ChatMessage(ChatCommand.PRIVMSG, "#lobby", "hello")
        val envelope = ProtocolEnvelope(
            P2PProtocol.VERSION, "envelope", "session", "match", bob.value,
            MessageType.CHAT, 0L, ChatCodec.encode(message)
        )
        val session = IRCChatSession(alice, bob, {})
        assertNotNull(session.receive(envelope))
        assertNull(session.receive(envelope))
    }

    @Test
    fun invalidChatPayloadIsRejected() {
        assertThrows(P2PMatchException.InvalidMessage::class.java) {
            ChatCodec.decode("not-chat".toByteArray())
        }
    }
}
