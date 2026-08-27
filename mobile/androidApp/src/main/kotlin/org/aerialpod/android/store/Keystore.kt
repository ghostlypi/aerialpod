package org.aerialpod.android.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM with a key that never leaves the hardware keystore.
 *
 * What this buys over app-private preferences: the sandbox already stops other
 * apps, but it does not stop root, an unlocked bootloader or a physical
 * extraction — all of which can read `/data/data`. A keystore-wrapped secret is
 * ciphertext to anyone holding the file, because the key lives in the TEE and
 * cannot be exported, only used.
 *
 * **No user authentication requirement.** The peer link has to work while the
 * screen is off during playback, and `setUserAuthenticationRequired(true)`
 * would make every read fail on a locked device. The threat model here is a
 * stolen file, not a stolen unlocked phone.
 */
/**
 * Wraps a secret so the file it lives in is useless on its own.
 *
 * An interface so the stores can be tested without a device. The migration
 * paths below are exactly the kind that fail silently — a user who signed in
 * once and finds themselves signed out has no way to report what happened —
 * and they are unreachable in a unit test if the only implementation talks to
 * hardware.
 */
interface Sealer {
    fun seal(plain: ByteArray): String
    fun open(encoded: String): ByteArray?
}

object Keystore : Sealer {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "aerialpod-secrets-v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // A reused GCM nonce is catastrophic, so let the platform pick.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** `base64(iv || ciphertext)`. */
    override fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain)
        return Base64.getEncoder().encodeToString(cipher.iv + sealed)
    }

    /**
     * Null if the value cannot be opened.
     *
     * That happens when the keystore key is gone — a factory reset, or an OS
     * that dropped it. Nothing can recover the plaintext at that point, so the
     * caller's job is to start over: for the pairing secret that means
     * generating a fresh one, which unpairs this device until it is paired
     * again. Losing the pairing is the correct outcome; pretending to still
     * have it is not.
     */
    override fun open(encoded: String): ByteArray? = runCatching {
        val raw = Base64.getDecoder().decode(encoded)
        if (raw.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        }
        cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
    }.getOrNull()
}
