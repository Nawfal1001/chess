package com.naoufel.decentralchess.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Cryptographic identity boundary. Keys are generated and retained by Android Keystore. */
data class PublicIdentity(val id: String, val publicKeyBase64: String)
interface IdentityProvider { fun identity(): PublicIdentity; fun sign(payload: ByteArray): ByteArray }

class AndroidKeystoreIdentityProvider(
    private val alias: String = "decentral-chess-identity-v1"
) : IdentityProvider {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        if (!keyStore.containsAlias(alias)) generateKeyPair()
    }

    override fun identity(): PublicIdentity {
        val publicKey = publicKey()
        val encoded = Base64.getEncoder().encodeToString(publicKey.encoded)
        return PublicIdentity(sha256(publicKey.encoded), encoded)
    }

    override fun sign(payload: ByteArray): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey())
        signature.update(payload)
        return signature.sign()
    }

    private fun generateKeyPair() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        generator.generateKeyPair()
    }

    private fun privateKey(): PrivateKey = (keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey

    private fun publicKey(): PublicKey = keyStore.getCertificate(alias).publicKey

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun verify(payload: ByteArray, signatureBytes: ByteArray, publicKeyBase64: String): Boolean = runCatching {
            val encoded = Base64.getDecoder().decode(publicKeyBase64)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
            Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initVerify(key)
                update(payload)
            }.verify(signatureBytes)
        }.getOrDefault(false)

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
