import Foundation
import CryptoKit

/// Per-session X25519 identity — fresh on every launch (rotating-identity pillar).
public final class SessionIdentity {
    let privateKey: Curve25519.KeyAgreement.PrivateKey
    public var publicKey: [UInt8] { [UInt8](privateKey.publicKey.rawRepresentation) }
    public init() { privateKey = Curve25519.KeyAgreement.PrivateKey() }
    /// Deterministic identity — tests only (known-answer vectors against the Kotlin core).
    init(privateKeyRaw: [UInt8]) throws { privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(privateKeyRaw)) }
}

public struct SessionKeys {
    public let sendKey: [UInt8]
    public let recvKey: [UInt8]
    /// Short authentication string both humans can compare.
    public let sas: String
    /// Raw X25519 shared secret — input to extra derivations (vault PIN auth).
    public let shared: [UInt8]
}

public enum SessionCrypto {
    public static func deriveSession(identity: SessionIdentity, peerPublicKey: [UInt8], isInitiator: Bool) throws -> SessionKeys {
        let peer = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicKey)
        let secret = try identity.privateKey.sharedSecretFromKeyAgreement(with: peer)
        let shared = secret.withUnsafeBytes { [UInt8]($0) }
        // A low-order / identity point makes the shared secret all-zero (anyone could derive the keys).
        // BouncyCastle refuses these; so do we, whatever CryptoKit does.
        guard shared.reduce(0, { $0 | $1 }) != 0 else { throw ProtocolError("rejected weak peer key") }

        let local = identity.publicKey
        let salt = isInitiator ? local + peerPublicKey : peerPublicKey + local

        let i2r = hkdf(shared, salt, "crossdrop/v1/session/i2r", 32)
        let r2i = hkdf(shared, salt, "crossdrop/v1/session/r2i", 32)
        let sasBytes = hkdf(shared, salt, "crossdrop/v1/sas", 4)
        let n = (UInt32(sasBytes[0]) << 24) | (UInt32(sasBytes[1]) << 16) | (UInt32(sasBytes[2]) << 8) | UInt32(sasBytes[3])
        let sas = String(format: "%06d", n % 1_000_000)

        return isInitiator
            ? SessionKeys(sendKey: i2r, recvKey: r2i, sas: sas, shared: shared)
            : SessionKeys(sendKey: r2i, recvKey: i2r, sas: sas, shared: shared)
    }

    public static func vaultAuthToken(shared: [UInt8], pin: String) -> [UInt8] {
        hkdf(shared, Array(pin.utf8), "crossdrop/v1/vault-auth", 32)
    }

    static func hkdf(_ ikm: [UInt8], _ salt: [UInt8], _ info: String, _ outLen: Int) -> [UInt8] {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm), salt: salt,
            info: Data(info.utf8), outputByteCount: outLen)
        return key.withUnsafeBytes { [UInt8]($0) }
    }
}

/// TLS-record-style AEAD: ChaCha20-Poly1305 with nonce = 4 zero bytes || u64 BE counter.
public final class RecordCipher {
    private let key: SymmetricKey
    private var counter: UInt64 = 0

    public init(key: [UInt8]) { self.key = SymmetricKey(data: key) }

    private func nextNonce() throws -> ChaChaPoly.Nonce {
        // 2^64 records is unreachable, but a wrapped counter would reuse a nonce — fail closed.
        guard counter != UInt64.max else { throw ProtocolError("record counter exhausted") }
        var n = [UInt8](repeating: 0, count: 12)
        for i in 0..<8 { n[4 + i] = UInt8((counter >> UInt64(56 - 8 * i)) & 0xFF) }
        counter += 1
        return try ChaChaPoly.Nonce(data: n)
    }

    /// Returns ciphertext || 16-byte tag (BouncyCastle-compatible layout).
    public func seal(_ plaintext: [UInt8]) throws -> [UInt8] {
        let box = try ChaChaPoly.seal(plaintext, using: key, nonce: try nextNonce())
        return [UInt8](box.ciphertext) + [UInt8](box.tag)
    }

    public func open(_ sealed: [UInt8]) throws -> [UInt8] {
        guard sealed.count >= 16 else { throw ProtocolError("short sealed record") }
        let ct = Data(sealed[0..<sealed.count - 16])
        let tag = Data(sealed[(sealed.count - 16)...])
        let box = try ChaChaPoly.SealedBox(nonce: try nextNonce(), ciphertext: ct, tag: tag)
        return [UInt8](try ChaChaPoly.open(box, using: key))
    }
}
