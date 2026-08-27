package org.aerialpod.core.lan

/**
 * The shared secret that decides which devices are yours — the codec half of
 * `lan/pairing.py`. Storage is platform-specific and lives behind [SecretStore].
 *
 * Peers used to be keyed off the gpodder.net password. That made the handshake
 * transcript — which is plaintext, by necessity, before any session key exists —
 * a verifier anyone could grind offline, and the prize was the account password
 * itself. A one-time pairing step removes the problem rather than pricing it up:
 * the secret here is 160 random bits, so a captured transcript is worth nothing
 * to guess against.
 *
 * Each install generates its own secret on first use and shows it as a pairing
 * code. Entering another device's code adopts that device's secret, so both ends
 * converge on one key; pair a third device against either of them and all three
 * share it.
 *
 * The code is base32 (RFC 4648: A-Z and 2-7, so no 0/O or 1/I ambiguity to begin
 * with) in groups of four — 160 bits lands on exactly 32 characters, which
 * divides evenly and reads back cleanly over the phone.
 */
object Pairing {
    const val SECRET_LEN = 20 // 160 bits -> exactly 32 base32 characters, no padding
    const val GROUP = 4

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Characters a pairing code can hold, ignoring grouping and case. */
    val CODE_LENGTH: Int get() = (SECRET_LEN * 8 + 4) / 5

    fun newSecret(): ByteArray = secureRandomBytes(SECRET_LEN)

    /** What to read out to the other device. */
    fun formatCode(value: ByteArray): String =
        base32Encode(value).chunked(GROUP).joinToString("-")

    /**
     * Decode a pairing code a human typed. Throws [PairingCodeException] if it isn't one.
     *
     * Deliberately forgiving about how it was transcribed — case, spacing and the
     * usual digit-for-letter misreads — because the alternative is a user staring
     * at 'invalid code' with no idea which character is wrong.
     */
    fun parseCode(code: String): ByteArray {
        // 0, 1, 8 and 9 are not in the base32 alphabet, so a digit that appears
        // where a letter belongs is unambiguously a misread of one.
        val cleaned = buildString {
            for (ch in code) {
                if (!ch.isLetterOrDigit()) continue
                append(
                    when (ch.uppercaseChar()) {
                        '0' -> 'O'
                        '1' -> 'I'
                        '8' -> 'B'
                        else -> ch.uppercaseChar()
                    }
                )
            }
        }
        if (cleaned.isEmpty()) {
            throw PairingCodeException("Enter the pairing code shown on your other device.")
        }
        if (cleaned.length != CODE_LENGTH) {
            throw PairingCodeException(
                "That code is ${cleaned.length} characters; a pairing code has $CODE_LENGTH."
            )
        }
        val value = base32Decode(cleaned)
            ?: throw PairingCodeException("That doesn't look like a pairing code — check for typos.")
        if (value.size != SECRET_LEN) {
            throw PairingCodeException("That doesn't look like a pairing code — check for typos.")
        }
        return value
    }

    // ------------------------------------------------------------ base32

    private fun base32Encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer ushr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    /** Null rather than an exception: the caller phrases the error. */
    private fun base32Decode(text: String): ByteArray? {
        val out = ArrayList<Byte>(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val index = ALPHABET.indexOf(ch)
            if (index < 0) return null
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                out.add(((buffer ushr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

class PairingCodeException(message: String) : Exception(message)

/**
 * Where this device's pairing secret lives.
 *
 * Android backs it with the keystore; iOS will use the keychain — the same role
 * the GNOME keyring plays on the desktop, with the same `0600`-file spirit of
 * fallback when there is no secure store to be had.
 */
interface SecretStore {
    fun loadSecret(): ByteArray?
    fun storeSecret(value: ByteArray)
}

/**
 * This device's pairing secret and the key derived from it.
 *
 * [reset] generates a fresh secret: existing peers stop matching until they are
 * paired again — which is the point of a 'New code' button.
 */
class PairingKeys(private val store: SecretStore) {

    fun secret(): ByteArray {
        val stored = store.loadSecret()
        if (stored != null && stored.size == Pairing.SECRET_LEN) return stored
        return reset()
    }

    fun reset(): ByteArray = Pairing.newSecret().also(store::storeSecret)

    /** The root key the peer handshake runs on. */
    fun channelKey(): ByteArray = channelKey(secret())

    fun pairingCode(): String = Pairing.formatCode(secret())

    /** Adopt another device's secret. Throws [PairingCodeException] on a bad code. */
    fun pairWithCode(code: String) {
        store.storeSecret(Pairing.parseCode(code))
    }
}
