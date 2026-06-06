package sh.haven.core.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [WireguardTunnel.ensurePeerKeepalive]. */
class WireguardKeepaliveTest {

    private fun ensure(cfg: String, seconds: Int = WireguardTunnel.DEFAULT_KEEPALIVE_SECONDS) =
        WireguardTunnel.ensurePeerKeepalive(cfg, seconds)

    @Test
    fun `injects keepalive into a peer that lacks one`() {
        val cfg = """
            [Interface]
            PrivateKey = abc
            Address = 192.168.0.242/32

            [Peer]
            PublicKey = def
            Endpoint = 203.0.113.5:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()
        val out = ensure(cfg)
        assertTrue("expected injected keepalive", out.contains("PersistentKeepalive = 25"))
        // Original keys preserved.
        assertTrue(out.contains("PrivateKey = abc"))
        assertTrue(out.contains("Endpoint = 203.0.113.5:51820"))
        assertTrue(out.contains("AllowedIPs = 0.0.0.0/0"))
        // Exactly one keepalive line.
        assertEquals(1, Regex("PersistentKeepalive", RegexOption.IGNORE_CASE).findAll(out).count())
    }

    @Test
    fun `leaves an existing keepalive untouched`() {
        val cfg = """
            [Interface]
            PrivateKey = abc

            [Peer]
            PublicKey = def
            Endpoint = 203.0.113.5:51820
            PersistentKeepalive = 15
        """.trimIndent()
        val out = ensure(cfg)
        assertTrue("user interval kept", out.contains("PersistentKeepalive = 15"))
        assertFalse("did not append the default", out.contains("PersistentKeepalive = 25"))
        assertEquals(1, Regex("PersistentKeepalive", RegexOption.IGNORE_CASE).findAll(out).count())
    }

    @Test
    fun `does not add keepalive to the interface section`() {
        val cfg = "[Interface]\nPrivateKey = abc\nAddress = 10.0.0.2/32\n"
        val out = ensure(cfg)
        assertFalse(out.contains("PersistentKeepalive"))
    }

    @Test
    fun `injects into each peer missing one across multiple peers`() {
        val cfg = """
            [Interface]
            PrivateKey = abc

            [Peer]
            PublicKey = peer1
            Endpoint = 1.1.1.1:51820

            [Peer]
            PublicKey = peer2
            Endpoint = 2.2.2.2:51820
            PersistentKeepalive = 10
        """.trimIndent()
        val out = ensure(cfg)
        // peer1 gets the default, peer2 keeps its 10 — two keepalive lines total.
        assertEquals(2, Regex("PersistentKeepalive", RegexOption.IGNORE_CASE).findAll(out).count())
        assertTrue(out.contains("PersistentKeepalive = 25"))
        assertTrue(out.contains("PersistentKeepalive = 10"))
        // The default lands in peer1's block (before peer2's header).
        val idx25 = out.indexOf("PersistentKeepalive = 25")
        val idxPeer2 = out.indexOf("PublicKey = peer2")
        assertTrue("default keepalive belongs to peer1", idx25 in 0 until idxPeer2)
    }

    @Test
    fun `case-insensitive detection avoids a duplicate`() {
        val cfg = "[Peer]\nPublicKey = def\npersistentkeepalive=20\n"
        val out = ensure(cfg)
        assertEquals(1, Regex("PersistentKeepalive", RegexOption.IGNORE_CASE).findAll(out).count())
        assertTrue(out.contains("persistentkeepalive=20"))
    }

    @Test
    fun `zero or negative interval is a no-op`() {
        val cfg = "[Peer]\nPublicKey = def\nEndpoint = 1.1.1.1:51820\n"
        assertEquals(cfg, ensure(cfg, seconds = 0))
        assertEquals(cfg, ensure(cfg, seconds = -5))
    }
}
