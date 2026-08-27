package org.aerialpod.core.gpodder

/**
 * gpodder.net account details.
 *
 * The password is held so it can be sent as basic auth on every request — the
 * protocol offers no token to exchange it for, which is exactly why the peer
 * channel's key is 160 random bits instead of being derived from this (see
 * `lan/Crypto.kt`): a captured handshake must not be worth grinding for the
 * account password.
 */
data class GpodderCredentials(
    val username: String,
    val password: String,
    val server: String = GPODDER_DEFAULT_SERVER,
)

/**
 * Where the account lives. Android backs this with the keystore, iOS will use
 * the keychain — the same role the GNOME keyring plays on the desktop.
 */
interface GpodderCredentialStore {
    fun load(): GpodderCredentials?
    fun save(credentials: GpodderCredentials)
    fun clear()
}
