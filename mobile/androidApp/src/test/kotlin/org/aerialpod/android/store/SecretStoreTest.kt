package org.aerialpod.android.store

import org.aerialpod.core.gpodder.GpodderCredentials
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two stores that hold everything worth stealing.
 *
 * Step 6.1 wrote both in plaintext and recorded it as a carry-over; 6.4 wrapped
 * them with the hardware keystore and added a migration for the values the
 * earlier build had already written. The migration is the part worth testing:
 * it runs once, on exactly one launch, on a device that already had a working
 * pairing — and if it goes wrong the symptom is a device that quietly unpairs
 * or an account that quietly signs out, with nothing to report.
 */
class SecretStoreTest {

    private val secret = ByteArray(20) { it.toByte() }

    // ---------------------------------------------------------------- secret

    @Test
    fun storesTheSecretSealedAndReadsItBack() {
        val prefs = FakePrefs()
        val store = KeystoreSecretStore(prefs, FakeSealer())

        store.storeSecret(secret)

        assertTrue(secret.contentEquals(store.loadSecret()))
        assertTrue(
            prefs.values.keys.none { it == "lan_pairing_secret" },
            "the plaintext key must never be written",
        )
    }

    @Test
    fun migratesThePlaintextSecretAnEarlierBuildWrote() {
        val legacy = Base64.getEncoder().encodeToString(secret)
        val prefs = FakePrefs(mapOf("lan_pairing_secret" to legacy))
        val sealer = FakeSealer()
        val store = KeystoreSecretStore(prefs, sealer)

        val loaded = store.loadSecret()

        assertTrue(secret.contentEquals(loaded), "the existing pairing must survive")
        assertEquals(1, sealer.seals, "and be re-sealed once")
        assertFalse(
            prefs.values.containsKey("lan_pairing_secret"),
            "the readable copy must be deleted, not left alongside",
        )
        assertTrue(prefs.values.containsKey("lan_pairing_secret_sealed"))
    }

    @Test
    fun migrationRunsOnceAndNotOnEveryRead() {
        val prefs = FakePrefs(
            mapOf("lan_pairing_secret" to Base64.getEncoder().encodeToString(secret)),
        )
        val sealer = FakeSealer()
        val store = KeystoreSecretStore(prefs, sealer)

        store.loadSecret()
        store.loadSecret()
        store.loadSecret()

        assertEquals(1, sealer.seals)
    }

    @Test
    fun aLostKeyDropsTheSecretRatherThanRetryingForever() {
        // Nothing can recover the plaintext. Returning null makes PairingKeys
        // generate a fresh secret, which unpairs this device until it is paired
        // again — the correct outcome. Keeping the dead bytes would mean every
        // launch re-reads a value that can never open.
        val prefs = FakePrefs()
        KeystoreSecretStore(prefs, FakeSealer()).storeSecret(secret)

        val store = KeystoreSecretStore(prefs, FakeSealer(lost = true))

        assertNull(store.loadSecret())
        assertFalse(prefs.values.containsKey("lan_pairing_secret_sealed"))
    }

    @Test
    fun anUnreadableLegacyValueIsDiscardedNotCrashed() {
        val prefs = FakePrefs(mapOf("lan_pairing_secret" to "!!! not base64 !!!"))
        val store = KeystoreSecretStore(prefs, FakeSealer())

        assertNull(store.loadSecret())
        assertFalse(prefs.values.containsKey("lan_pairing_secret"))
    }

    @Test
    fun aFreshInstallHasNoSecret() {
        assertNull(KeystoreSecretStore(FakePrefs(), FakeSealer()).loadSecret())
    }

    // ---------------------------------------------------------------- account

    @Test
    fun storesTheAccountWithOnlyThePasswordSealed() {
        val prefs = FakePrefs()
        val store = KeystoreGpodderCredentialStore(prefs, FakeSealer())

        store.save(GpodderCredentials("ghost", "hunter2", "https://gpodder.net"))

        val loaded = store.load()!!
        assertEquals("ghost", loaded.username)
        assertEquals("hunter2", loaded.password)
        assertEquals("https://gpodder.net", loaded.server)
        // The username is not a secret, and leaving it readable is what lets
        // Settings say who is signed in without touching the keystore.
        assertEquals("ghost", prefs.values["gpodder_username"])
        assertFalse(prefs.values.containsKey("gpodder_password"))
        assertTrue(prefs.values.containsKey("gpodder_password_sealed"))
    }

    @Test
    fun migratesThePlaintextPasswordAnEarlierBuildWrote() {
        val prefs = FakePrefs(
            mapOf(
                "gpodder_username" to "ghost",
                "gpodder_password" to "hunter2",
                "gpodder_server" to "https://gpodder.net",
            ),
        )
        val store = KeystoreGpodderCredentialStore(prefs, FakeSealer())

        val loaded = store.load()!!

        assertEquals("hunter2", loaded.password, "the sign-in must survive the upgrade")
        assertFalse(prefs.values.containsKey("gpodder_password"), "and the readable copy must go")
        assertTrue(prefs.values.containsKey("gpodder_password_sealed"))
    }

    @Test
    fun aLostKeyClearsTheAccountRatherThanFailingEverySync() {
        val prefs = FakePrefs()
        KeystoreGpodderCredentialStore(prefs, FakeSealer())
            .save(GpodderCredentials("ghost", "hunter2"))

        val store = KeystoreGpodderCredentialStore(prefs, FakeSealer(lost = true))

        assertNull(store.load())
        assertTrue(
            prefs.values.keys.none { it.startsWith("gpodder_") },
            "a sign-in that can never work again must not be left in place",
        )
    }

    @Test
    fun clearRemovesEveryTraceIncludingTheLegacyKey() {
        val prefs = FakePrefs(mapOf("gpodder_password" to "hunter2"))
        val store = KeystoreGpodderCredentialStore(prefs, FakeSealer())
        store.save(GpodderCredentials("ghost", "hunter2"))

        store.clear()

        assertTrue(prefs.values.keys.none { it.startsWith("gpodder_") })
        assertNull(store.load())
    }

    @Test
    fun anAccountWithNoStoredPasswordIsNotAnAccount() {
        val prefs = FakePrefs(mapOf("gpodder_username" to "ghost"))
        assertNull(KeystoreGpodderCredentialStore(prefs, FakeSealer()).load())
    }
}
