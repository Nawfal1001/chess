package com.naoufel.decentralchess.security

import com.naoufel.decentralchess.chess.ChessNotation
import com.naoufel.decentralchess.chess.GameHistory
import com.naoufel.decentralchess.identity.AndroidKeystoreIdentityProvider
import com.naoufel.decentralchess.identity.IdentityProvider
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun canonical(vararg fields: String): ByteArray =
    fields.joinToString("
") { it.length.toString() + ":" + it }.toByteArray(StandardCharsets.UTF_8)

/** Versioned, deterministic envelope for one player's signed game record. */
data class SignedGameRecord(
    val playerId: String,
    val publicKeyBase64: String,
    val initialFen: String,
    val finalFen: String,
    val gameHash: String,
    val pgn: String,
    val signatureBase64: String
) {
    fun canonicalPayload(): ByteArray = canonical(
        "signed-game-record-v1", playerId, publicKeyBase64, initialFen, finalFen, gameHash, pgn
    )

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
            val record = SignedGameRecord(
                identity.id, identity.publicKeyBase64,
                history.initialPosition().toFen(), history.current.toFen(),
                history.gameHash(), pgn, ""
            )
            return record.copy(signatureBase64 = Base64.getEncoder().encodeToString(identityProvider.sign(record.canonicalPayload())))
        }
    }
}

/**
 * Final match envelope. Both players independently sign the same immutable game commitment.
 * The signatures do not assert identity alone: they bind identities to the exact replayable game.
 */
data class SignedMatchRecord(
    val protocolVersion: Int,
    val matchId: String,
    val nonceBase64: String,
    val initialFen: String,
    val finalFen: String,
    val gameHash: String,
    val pgn: String,
    val whitePlayerId: String,
    val whitePublicKeyBase64: String,
    val whiteSignatureBase64: String,
    val blackPlayerId: String,
    val blackPublicKeyBase64: String,
    val blackSignatureBase64: String
) {
    init {
        require(protocolVersion == 1) { "Unsupported match protocol version" }
        require(matchId.isNotBlank()) { "matchId must not be blank" }
        require(whitePlayerId != blackPlayerId) { "Players must have different identities" }
    }

    fun canonicalPayload(): ByteArray = canonical(
        "signed-match-v1", protocolVersion.toString(), matchId, nonceBase64,
        initialFen, finalFen, gameHash, pgn,
        whitePlayerId, whitePublicKeyBase64, blackPlayerId, blackPublicKeyBase64
    )

    fun commitment(): String = sha256Hex(canonicalPayload())

    fun verify(): Boolean = runCatching {
        val payload = canonicalPayload()
        val whiteSig = Base64.getDecoder().decode(whiteSignatureBase64)
        val blackSig = Base64.getDecoder().decode(blackSignatureBase64)
        if (!AndroidKeystoreIdentityProvider.verify(payload, whiteSig, whitePublicKeyBase64)) return false
        if (!AndroidKeystoreIdentityProvider.verify(payload, blackSig, blackPublicKeyBase64)) return false
        val replayed = ChessNotation.importPgn(pgn)
        replayed.initialPosition().toFen() == initialFen &&
            replayed.current.toFen() == finalFen &&
            replayed.gameHash() == gameHash
    }.getOrDefault(false)

    companion object {
        fun unsigned(
            history: GameHistory,
            matchId: String,
            nonce: ByteArray,
            white: PublicPlayer,
            black: PublicPlayer
        ): SignedMatchRecord {
            val pgn = ChessNotation.exportPgn(history)
            return SignedMatchRecord(
                1, matchId, Base64.getEncoder().encodeToString(nonce),
                history.initialPosition().toFen(), history.current.toFen(), history.gameHash(), pgn,
                white.id, white.publicKeyBase64, "", black.id, black.publicKeyBase64, ""
            )
        }

        fun signWhite(record: SignedMatchRecord, signer: IdentityProvider): SignedMatchRecord =
            record.copy(whiteSignatureBase64 = Base64.getEncoder().encodeToString(signer.sign(record.canonicalPayload())))

        fun signBlack(record: SignedMatchRecord, signer: IdentityProvider): SignedMatchRecord =
            record.copy(blackSignatureBase64 = Base64.getEncoder().encodeToString(signer.sign(record.canonicalPayload())))
    }
}

data class PublicPlayer(val id: String, val publicKeyBase64: String)

object MatchProtocol {
    fun player(identityProvider: IdentityProvider): PublicPlayer {
        val i = identityProvider.identity()
        return PublicPlayer(i.id, i.publicKeyBase64)
    }

    fun randomNonce(): ByteArray {
        val nonce = ByteArray(32)
        java.security.SecureRandom().nextBytes(nonce)
        return nonce
    }
}
