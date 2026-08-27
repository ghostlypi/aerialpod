package org.aerialpod.core.lan

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the Kotlin peer to the desktop's wire format.
 *
 * Every expected value here came out of `lan/crypto.py` and `lan/protocol.py`
 * (see mobile/tools/gen_lan_vectors.py), so these tests fail if a label, a salt,
 * a nonce prefix or the frame header ever drifts — which a Kotlin-only test
 * suite could never notice, since it would happily agree with itself.
 */
class LanInteropTest {

    private fun hex(s: String): ByteArray = s.hexToBytesOrNull() ?: error("bad hex: $s")

    @Test
    fun channelKeyMatchesTheDesktop() {
        for (v in LanVectors.channelKeys) {
            assertContentEquals(hex(v.key), channelKey(hex(v.secret)), "secret ${v.secret}")
        }
    }

    @Test
    fun proofsMatchTheDesktop() {
        val cn = hex(LanVectors.CN)
        val sn = hex(LanVectors.SN)
        for (v in LanVectors.proofs) {
            val key = channelKey(hex(v.secret))
            assertContentEquals(hex(v.client), clientProof(key, cn, sn), "client ${v.secret}")
            assertContentEquals(hex(v.server), serverProof(key, cn, sn), "server ${v.secret}")
        }
    }

    @Test
    fun sessionKeysMatchTheDesktop() {
        val key = channelKey(hex(LanVectors.channelKeys[0].secret))
        val (c2s, s2c) = sessionKeys(key, hex(LanVectors.CN), hex(LanVectors.SN))
        assertContentEquals(hex(LanVectors.SESSION_C2S), c2s)
        assertContentEquals(hex(LanVectors.SESSION_S2C), s2c)
    }

    @Test
    fun sealedFramesMatchTheDesktopForBothRoles() {
        val c2s = hex(LanVectors.SESSION_C2S)
        val s2c = hex(LanVectors.SESSION_S2C)
        for ((role, frames) in listOf(
            Role.CLIENT to LanVectors.sealedClient,
            Role.SERVER to LanVectors.sealedServer,
        )) {
            // One sealer across the whole list: the counter must advance exactly
            // as Python's did, so frame N only matches if frames 0..N-1 did too.
            val sealer = Sealer(role, c2s, s2c)
            for ((i, frame) in frames.withIndex()) {
                assertContentEquals(
                    hex(frame.ciphertext),
                    sealer.seal(hex(frame.plaintext)),
                    "$role frame $i",
                )
            }
        }
    }

    @Test
    fun opensWhatTheDesktopSealed() {
        // The desktop's client-sealed frames are what our server end must read.
        val sealer = Sealer(Role.SERVER, hex(LanVectors.SESSION_C2S), hex(LanVectors.SESSION_S2C))
        for (frame in LanVectors.sealedClient) {
            assertContentEquals(hex(frame.plaintext), sealer.open(hex(frame.ciphertext)))
        }
    }

    @Test
    fun aReorderedFrameIsFatal() {
        val sealer = Sealer(Role.SERVER, hex(LanVectors.SESSION_C2S), hex(LanVectors.SESSION_S2C))
        // Skipping frame 0 puts the counter out of step, which is exactly the
        // condition the desktop treats as fatal rather than recoverable.
        assertFailsWith<InvalidTagException> {
            sealer.open(hex(LanVectors.sealedClient[1].ciphertext))
        }
    }

    @Test
    fun pairingCodesMatchTheDesktop() {
        for (v in LanVectors.codes) {
            assertEquals(v.code, Pairing.formatCode(hex(v.secret)), "secret ${v.secret}")
        }
    }

    @Test
    fun parsesEveryCodeTheDesktopAccepts() {
        for (v in LanVectors.codeParseOk) {
            assertContentEquals(hex(v.secret), Pairing.parseCode(v.code), "input ${v.code}")
        }
    }

    @Test
    fun rejectsEveryCodeTheDesktopRejects() {
        for (bad in LanVectors.codeParseFail) {
            assertFailsWith<PairingCodeException>("expected rejection of '$bad'") {
                Pairing.parseCode(bad)
            }
        }
    }

    /**
     * The whole handshake, replayed against the bytes a real desktop produced.
     *
     * Our client is fed the desktop server's exact lines and must emit the
     * desktop client's exact lines back — which only works if the nonce, the
     * proof, the JSON encoding and the newline framing all agree.
     */
    @Test
    fun clientHandshakeReproducesTheDesktopTranscript() {
        val key = channelKey(hex(LanVectors.channelKeys[0].secret))
        val channel = PeerChannel(Role.CLIENT, key) { hex(LanVectors.CN) }
        val recorded = LanVectors.transcript
        channel.start()
        assertContentEquals(hex(recorded[0].bytes), channel.takeOutput(), "client hello")

        channel.feed(hex(recorded[1].bytes))
        assertContentEquals(hex(recorded[2].bytes), channel.takeOutput(), "client proof")

        channel.feed(hex(recorded[3].bytes))
        assertTrue(channel.established, "channel should be established")
    }

    @Test
    fun serverHandshakeReproducesTheDesktopTranscript() {
        val key = channelKey(hex(LanVectors.channelKeys[0].secret))
        val channel = PeerChannel(Role.SERVER, key) { hex(LanVectors.SN) }
        val recorded = LanVectors.transcript

        channel.feed(hex(recorded[0].bytes))
        assertContentEquals(hex(recorded[1].bytes), channel.takeOutput(), "server nonce")

        channel.feed(hex(recorded[2].bytes))
        assertContentEquals(hex(recorded[3].bytes), channel.takeOutput(), "server proof")
        assertTrue(channel.established, "channel should be established")
    }

    /** Application frames the desktop produced, read back through a live channel. */
    @Test
    fun readsTheDesktopsApplicationFrames() {
        val key = channelKey(hex(LanVectors.channelKeys[0].secret))
        val client = PeerChannel(Role.CLIENT, key) { hex(LanVectors.CN) }
        val server = PeerChannel(Role.SERVER, key) { hex(LanVectors.SN) }
        client.start()
        server.feed(client.takeOutput())
        client.feed(server.takeOutput())
        server.feed(client.takeOutput())
        client.feed(server.takeOutput())
        assertTrue(client.established && server.established)

        for (exchange in LanVectors.exchanges) {
            val receiver = if (exchange.sender == "client") server else client
            val messages = receiver.feed(hex(exchange.frame))
            assertEquals(1, messages.size, "one message per frame")
            assertEquals("ping", messages[0]["type"]?.toString()?.trim('"'))
            assertEquals(exchange.seq.toString(), messages[0]["seq"]?.toString())
        }
    }
}
