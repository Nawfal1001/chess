package com.naoufel.decentralchess.security

import com.naoufel.decentralchess.chess.ChessNotation
import com.naoufel.decentralchess.chess.GameHistory
import com.naoufel.decentralchess.identity.AndroidKeystoreIdentityProvider
import com.naoufel.decentralchess.identity.IdentityProvider
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Security boundary for signatures, anti-tamper and secure storage. */
interface SecureSigner { fun sign(data: ByteArray): ByteArray; fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean }
data class SecurityEvent(val type: String, val timestampMs: Long, val details: String)

data class SignedGameRecord(
    val playerId: String,
    val publicKeyBase64: String,
    val initialFen: String,
    val finalFen: String,
    val gameHash: String,
    val pgn: String,
    val signatureBase64: String
) {
    fun canonicalPayload(): ByteArray = canonicalPayload(
        playerId, publicKeyBase64, initialFen, finalFen, gameHash, pgn
    ).toByteArray(StandardCharsets.UTF_8)

    fun verify(): Boolean = runCatching {
        val signature = Base64.getDecoder().decode(signatureBase64)
        if (!AndroidKeystoreIdentityProvider.verify(canonicalPayload(), signature, publicKeyBase64)) return false
        val replayed = ChessNotation.importPgn(pgn)
        replayed.initialPosition().toFen() == initialFen &&
            replayed.current.toFen() == finalFen &&
            replayed.gameHash() == gameHash
    }.getOrDefault(false)

    companion object {
        fun create(history: GameHistory, identityProvider: IdentityProvider): SignedGameRecord {
            val identity = identityProvider.identity()
            val pgn = ChessNotation.exportPgn(history)
            val payload = canonicalPayload(
                identity.id,
                identity.publicKeyBase64,
                history.initialPosition().toFen(),
                history.current.toFen(),
                history.gameHash(),
                pgn
            ).toByteArray(StandardCharsets.UTF_8)
            val signature = identityProvider.sign(payload)
            return SignedGameRecord(
                playerId = identity.id,
                publicKeyBase64 = identity.publicKeyBase64,
                initialFen = history.initialPosition().toFen(),
                finalFen = history.current.toFen(),
                gameHash = history.gameHash(),
                pgn = pgn,
                signatureBase64 = Base64.getEncoder().encodeToString(signature)
            )
        }

        private fun canonicalPayload(
            playerId: String,
            publicKeyBase64: String,
            initialFen: String,
            finalFen: String,
            gameHash: String,
            pgn: String
        ): String = listOf(playerId, publicKeyBase64, initialFen, finalFen, gameHash, pgn)
            .joinToString("\n")
    }
}
