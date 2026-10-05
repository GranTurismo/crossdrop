package com.crossdrop.core.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * Per-session X25519 identity. Generated fresh on every app start — nothing
 * long-lived to track or correlate across venues (rotating identity pillar).
 */
class SessionIdentity(private val random: SecureRandom = SecureRandom()) {
    val privateKey = X25519PrivateKeyParameters(random)
    val publicKey: ByteArray get() = privateKey.generatePublicKey().encoded
}

/**
 * Keys negotiated by one X25519 handshake. [sendKey]/[recvKey] are already
 * role-swapped per side; [sas] is a short authentication string the two humans
 * can compare to rule out MITM (acoustic OOB replaces this in a later phase).
 */
class SessionKeys(
    val sendKey: ByteArray,
    val recvKey: ByteArray,
    val sas: String,
    /** Raw X25519 shared secret — input to extra derivations (vault PIN auth). */
    val shared: ByteArray,
)

object SessionCrypto {

    /**
     * Derive session keys after exchanging ephemeral X25519 public keys.
     *
     * @param isInitiator true for the side that opened the TCP connection
     *        (sent HELLO). Key direction is bound to roles, not byte order.
     */
    fun deriveSession(
        identity: SessionIdentity,
        peerPublicKey: ByteArray,
        isInitiator: Boolean,
    ): SessionKeys {
        val shared = ByteArray(32)
        val agreement = X25519Agreement()
        agreement.init(identity.privateKey)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)

        // Salt binds keys to this exact pairing of ephemeral keys.
        val localPub = identity.publicKey
        val salt = if (isInitiator) localPub + peerPublicKey else peerPublicKey + localPub

        val i2r = hkdf(shared, salt, "crossdrop/v1/session/i2r", 32)
        val r2i = hkdf(shared, salt, "crossdrop/v1/session/r2i", 32)

        val sasBytes = hkdf(shared, salt, "crossdrop/v1/sas", 4)
        val sas = "%06d".format(
            (sasBytes.readUInt() % 1_000_000L)
        )

        return if (isInitiator) SessionKeys(i2r, r2i, sas, shared)
            else SessionKeys(r2i, i2r, sas, shared)
    }

    /** Vault PIN proof — HKDF(ECDH secret, salt = PIN utf8). A wrong PIN yields
     *  a wrong token; only compared, never transmitted in the clear (AEAD). */
    fun vaultAuthToken(shared: ByteArray, pin: String): ByteArray =
        hkdf(shared, pin.toByteArray(Charsets.UTF_8), "crossdrop/v1/vault-auth", 32)

    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, outLen: Int): ByteArray {
        val out = ByteArray(outLen)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.UTF_8)))
        hkdf.generateBytes(out, 0, outLen)
        return out
    }

    private fun ByteArray.readUInt(): Long =
        ((this[0].toLong() and 0xFF) shl 24) or
            ((this[1].toLong() and 0xFF) shl 16) or
            ((this[2].toLong() and 0xFF) shl 8) or
            (this[3].toLong() and 0xFF)
}
