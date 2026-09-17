package com.naoufel.decentralchess.security

/** Security boundary for signatures, anti-tamper and secure storage. */
interface SecureSigner { fun sign(data: ByteArray): ByteArray; fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean }
data class SecurityEvent(val type: String, val timestampMs: Long, val details: String)
