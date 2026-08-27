package org.aerialpod.core.lan

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM/Android primitives. Shared by both targets because Android's crypto is
 * the JCA — there is nothing platform-specific to say here beyond the provider
 * doing the work.
 *
 * `AES/GCM/NoPadding` returns ciphertext||tag with a 128-bit tag, which is the
 * same layout Python's `AESGCM.encrypt` produces, so frames cross unchanged.
 */

private const val GCM_TAG_BITS = 128

internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    // An all-zero salt is legal HKDF input but an illegal JCA key, and RFC 5869
    // explicitly allows a zero-length salt — so pad rather than reject.
    val material = if (key.isEmpty()) ByteArray(32) else key
    mac.init(SecretKeySpec(material, "HmacSHA256"))
    return mac.doFinal(data)
}

internal actual fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    return cipher.doFinal(plaintext)
}

internal actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    return try {
        cipher.doFinal(ciphertext)
    } catch (exc: AEADBadTagException) {
        throw InvalidTagException(exc.message ?: "AES-GCM authentication failed")
    } catch (exc: javax.crypto.IllegalBlockSizeException) {
        // A frame shorter than the tag never reaches the tag check.
        throw InvalidTagException(exc.message ?: "AES-GCM frame is malformed")
    }
}

private val random = SecureRandom()

actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)
