package com.naoufel.decentralchess.security

import com.naoufel.decentralchess.chess.GameHistory
import com.naoufel.decentralchess.identity.IdentityProvider
import com.naoufel.decentralchess.identity.PublicIdentity
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

private class TestSigner(private val id: String) : IdentityProvider {
    private val key = id.toByteArray()
    override fun identity() = PublicIdentity(id, Base64.getEncoder().encodeToString(key))
    override fun sign(payload: ByteArray): ByteArray = payload
}

class SignedMatchRecordTest {
    @Test fun canonicalEnvelopeIsDeterministic() {
        val w = TestSigner("white"); val b = TestSigner("black")
        val h = GameHistory()
        val r = SignedMatchRecord.unsigned(h, "match-1", ByteArray(32) { 7 }, MatchProtocol.player(w), MatchProtocol.player(b))
        assertTrue(r.commitment().isNotBlank())
        assertTrue(r.canonicalPayload().contentEquals(r.canonicalPayload()))
    }

    @Test fun rejectsSamePlayerIdentity() {
        val w = TestSigner("same")
        val p = MatchProtocol.player(w)
        try {
            SignedMatchRecord.unsigned(GameHistory(), "m", ByteArray(32), p, p)
            assertFalse("expected constructor rejection", true)
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
