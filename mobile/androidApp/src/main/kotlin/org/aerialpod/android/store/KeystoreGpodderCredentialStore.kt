package org.aerialpod.android.store

import android.content.SharedPreferences
import androidx.core.content.edit
import org.aerialpod.core.gpodder.GPODDER_DEFAULT_SERVER
import org.aerialpod.core.gpodder.GpodderCredentials
import org.aerialpod.core.gpodder.GpodderCredentialStore

/**
 * The gpodder.net account, with the password wrapped by the keystore.
 *
 * Only the password is sealed. The username and server are not secrets, and
 * leaving them readable means the Settings screen can say who is signed in
 * without touching the keystore on every composition.
 *
 * The password has to be kept rather than exchanged for a token because the v2
 * API offers nothing to exchange it for — which is also why the peer channel
 * uses its own 160-bit secret instead of deriving one from this.
 */
class KeystoreGpodderCredentialStore(
    private val prefs: SharedPreferences,
    private val sealer: Sealer = Keystore,
) : GpodderCredentialStore {

    override fun load(): GpodderCredentials? {
        val username = prefs.getString(USERNAME, null)?.takeIf { it.isNotEmpty() } ?: return null
        val password = readPassword() ?: return null
        return GpodderCredentials(
            username = username,
            password = password,
            server = prefs.getString(SERVER, null) ?: GPODDER_DEFAULT_SERVER,
        )
    }

    override fun save(credentials: GpodderCredentials) {
        prefs.edit {
            putString(USERNAME, credentials.username)
            putString(PASSWORD_SEALED, sealer.seal(credentials.password.toByteArray()))
            putString(SERVER, credentials.server)
            remove(LEGACY_PASSWORD)
        }
    }

    override fun clear() {
        prefs.edit {
            remove(USERNAME)
            remove(PASSWORD_SEALED)
            remove(LEGACY_PASSWORD)
            remove(SERVER)
        }
    }

    private fun readPassword(): String? {
        prefs.getString(PASSWORD_SEALED, null)?.let { sealed ->
            val opened = sealer.open(sealed)
            if (opened != null) return String(opened)
            // The key is gone. Clear the account rather than leaving a sign-in
            // that fails on every sync with no explanation.
            clear()
            return null
        }
        // Written before the keystore existed: re-seal it and drop the plaintext.
        val legacy = prefs.getString(LEGACY_PASSWORD, null) ?: return null
        prefs.edit {
            putString(PASSWORD_SEALED, sealer.seal(legacy.toByteArray()))
            remove(LEGACY_PASSWORD)
        }
        return legacy
    }

    private companion object {
        const val USERNAME = "gpodder_username"
        const val PASSWORD_SEALED = "gpodder_password_sealed"
        const val LEGACY_PASSWORD = "gpodder_password"
        const val SERVER = "gpodder_server"
    }
}
