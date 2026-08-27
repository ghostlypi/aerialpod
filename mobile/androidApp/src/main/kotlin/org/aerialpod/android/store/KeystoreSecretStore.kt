package org.aerialpod.android.store

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Base64
import org.aerialpod.core.lan.SecretStore

/**
 * The pairing secret, wrapped by the hardware keystore.
 *
 * This is the peer channel's only credential — anyone holding these 20 bytes
 * can complete the handshake as a paired device and read or rewrite the
 * library. That is why it is worth more than the app sandbox.
 *
 * Migrates the plaintext value written before the keystore existed, then
 * deletes it, so an install that ran an earlier build does not keep a readable
 * copy alongside the wrapped one.
 */
class KeystoreSecretStore(
    private val prefs: SharedPreferences,
    private val sealer: Sealer = Keystore,
) : SecretStore {

    override fun loadSecret(): ByteArray? {
        prefs.getString(KEY, null)?.let { sealed ->
            val opened = sealer.open(sealed)
            if (opened != null) return opened
            // Unopenable: the key is gone and the bytes are unrecoverable.
            // Drop them so every read does not retry a dead value.
            prefs.edit { remove(KEY) }
            return null
        }
        return migrateLegacy()
    }

    override fun storeSecret(value: ByteArray) {
        prefs.edit {
            putString(KEY, sealer.seal(value))
            remove(LEGACY_KEY)
        }
    }

    private fun migrateLegacy(): ByteArray? {
        val legacy = prefs.getString(LEGACY_KEY, null) ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(legacy) }.getOrNull()
        if (bytes == null) {
            prefs.edit { remove(LEGACY_KEY) }
            return null
        }
        storeSecret(bytes)
        return bytes
    }

    private companion object {
        const val KEY = "lan_pairing_secret_sealed"
        const val LEGACY_KEY = "lan_pairing_secret"
    }
}
