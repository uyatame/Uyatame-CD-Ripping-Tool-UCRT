package com.uyatame.ucrt.airplay

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 受信機の identity (永続 Ed25519 鍵ペア)。GoAirPlay の loadIdentity の移植。
 * 種 (32 バイト) をファイルに保存し、初回は生成する。
 * 暗号プリミティブは BouncyCastle (依存に既存) を使う。minSdk 23 でも
 * Ed25519 / X25519 が使えるため。
 */
internal class AirPlayIdentity private constructor(
    private val edPrivate: Ed25519PrivateKeyParameters,
) {
    val edPublicBytes: ByteArray = edPrivate.generatePublicKey().encoded

    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, edPrivate)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    companion object {
        /** 送信側セッション用の一時 identity (保存しない)。 */
        fun ephemeral(): AirPlayIdentity =
            AirPlayIdentity(Ed25519PrivateKeyParameters(SecureRandom()))

        fun loadOrCreate(seedFile: File): AirPlayIdentity {
            val seed = try {
                if (seedFile.isFile) seedFile.readBytes() else null
            } catch (e: Exception) {
                null
            }
            if (seed != null && seed.size == 32) {
                return AirPlayIdentity(Ed25519PrivateKeyParameters(seed, 0))
            }
            val priv = Ed25519PrivateKeyParameters(SecureRandom())
            try {
                seedFile.parentFile?.mkdirs()
                seedFile.writeBytes(priv.encoded)
            } catch (e: Exception) {
                // 保存できなくても今回の起動中は使える (次回は別の identity になる)。
            }
            return AirPlayIdentity(priv)
        }

        /** Ed25519 の署名検証 (pair-verify のステップ 2 で使う)。 */
        fun verify(publicBytes: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            if (publicBytes.size != 32 || signature.size != 64) return false
            return try {
                val verifier = Ed25519Signer()
                verifier.init(false, Ed25519PublicKeyParameters(publicBytes, 0))
                verifier.update(message, 0, message.size)
                verifier.verifySignature(signature)
            } catch (e: Exception) {
                false
            }
        }
    }
}

/** pair-verify で使う暗号プリミティブ (GoAirPlay の main.go の移植)。 */
internal object PairCrypto {

    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    /** Pair-Verify-AES-Key / -IV を ECDH 秘密から導く (塩が先、SHA-512 の先頭 16 バイト)。 */
    fun deriveKeyIV(secret: ByteArray): Pair<ByteArray, ByteArray> {
        val key = sha512("Pair-Verify-AES-Key".toByteArray(Charsets.US_ASCII) + secret).copyOfRange(0, 16)
        val iv = sha512("Pair-Verify-AES-IV".toByteArray(Charsets.US_ASCII) + secret).copyOfRange(0, 16)
        return key to iv
    }

    /** AES-128-CTR (Go の cipher.NewCTR と同じ、IV はビッグエンディアンのカウンタ)。 */
    fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** pair-verify 用の署名暗号化: 鍵と IV は ECDH 秘密から導く。 */
    fun aesCtrWithSecret(secret: ByteArray, data: ByteArray): ByteArray {
        val (key, iv) = deriveKeyIV(secret)
        return aesCtr(key, iv, data)
    }

    class X25519Keys(val priv: X25519PrivateKeyParameters, val pubBytes: ByteArray)

    fun generateX25519(): X25519Keys {
        val gen = X25519KeyPairGenerator()
        gen.init(KeyGenerationParameters(SecureRandom(), 255))
        val pair: AsymmetricCipherKeyPair = gen.generateKeyPair()
        val priv = pair.private as X25519PrivateKeyParameters
        val pub = pair.public as X25519PublicKeyParameters
        return X25519Keys(priv, pub.encoded)
    }

    fun x25519Secret(priv: X25519PrivateKeyParameters, peerPubBytes: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(priv)
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPubBytes, 0), out, 0)
        return out
    }
}
