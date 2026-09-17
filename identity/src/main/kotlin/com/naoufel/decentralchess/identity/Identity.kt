package com.naoufel.decentralchess.identity

/** Cryptographic identity boundary; Android Keystore implementation plugs in here. */
data class PublicIdentity(val id: String, val publicKeyBase64: String)
interface IdentityProvider { fun identity(): PublicIdentity; fun sign(payload: ByteArray): ByteArray }
