package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for cage headless-output geometry (no Android/proot). */
class DesktopManagerResolutionTest {

    @Test
    fun `auto matches device aspect, short edge at budget, even dims`() {
        // Pixel 8 Pro 1344x2992: short=1344 → 720; long capped at 1600.
        assertEquals(720 to 1600, computeAutoResolution(1344, 2992, 720, 1600))
        // 1080x1920: long = 720*1920/1080 = 1280 (under cap).
        assertEquals(720 to 1280, computeAutoResolution(1080, 1920, 720, 1600))
    }

    @Test
    fun `auto preserves landscape orientation`() {
        // Landscape device → (long, short).
        assertEquals(1280 to 720, computeAutoResolution(1920, 1080, 720, 1600))
    }

    @Test
    fun `auto matches a corner-safe portrait area (short edges inset)`() {
        // 1344x2992 with ~60px top/bottom corner inset → safe 1344x2872.
        // long = round(720*2872/1344)=1539 → even 1540.
        assertEquals(720 to 1540, computeAutoResolution(1344, 2872, 720, 1600))
    }

    @Test
    fun `auto rounds both dimensions up to even`() {
        val (w, h) = computeAutoResolution(1000, 1777, 720, 4000)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test
    fun `auto falls back on zero or negative device size`() {
        val (w, h) = computeAutoResolution(0, 0, 720, 1600)
        assertTrue(w > 0 && h > 0)
        assertTrue("portrait fallback", h >= w)
    }

    @Test
    fun `parseWxH parses and clamps to even`() {
        assertEquals(1280 to 720, parseWxH("1280x720"))
        assertEquals(1282 to 722, parseWxH("1281x721")) // odd → even
        assertEquals(160 to 160, parseWxH("100x100")) // below min → clamp 160
        assertEquals(4096 to 4096, parseWxH("5000x5000")) // above max → clamp 4096
        assertNull(parseWxH("auto"))
        assertNull(parseWxH("nonsense"))
        assertNull(parseWxH("1280X720")) // capital X not accepted
    }

    @Test
    fun `resolveCageResolution prefers explicit WxH else auto`() {
        assertEquals(1280 to 720, resolveCageResolution("1280x720", 1344, 2992))
        assertEquals(720 to 1600, resolveCageResolution("auto", 1344, 2992, 720, 1600))
    }

    @Test
    fun `formatCageScale is clean and clamped`() {
        assertEquals("1", formatCageScale(1f))
        assertEquals("1.5", formatCageScale(1.5f))
        assertEquals("2", formatCageScale(2f))
        assertEquals("0.5", formatCageScale(0.1f)) // clamp low
        assertEquals("3", formatCageScale(9f)) // clamp high
    }
}
