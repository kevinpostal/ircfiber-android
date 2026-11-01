package com.kevinpostal.fiberirc.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NickColorTest {
    /**
     * Ground-truth pairs harvested from a live IRCCloud session (web `utils.test.ts`
     * "nickColorIndex (IRCCloud parity)"). Several (charlie, AShapiro, RAPEEPORN_SINGH, scroll, bombuzal)
     * only match when the accumulator is NOT wrapped to 32 bits — the shifts are int32, the sum is not.
     */
    @Test
    fun indexMatchesIrcCloud() {
        val pairs = listOf(
            "amita" to 19, "don" to 14, "charlie" to 20, "AShapiro" to 22, "RAPEEPORN_SINGH" to 18,
            "alice" to 17, "Carlos" to 9, "chud" to 19, "scroll" to 14, "bombuzal" to 3,
        )
        for ((nick, expected) in pairs) assertEquals(nick, expected, nickColorIndex(nick))
    }

    /** iOS hashes the lowercased nick as-is (no web `normaliseIdentifier` underscore stripping). */
    @Test
    fun trailingUnderscoreIsHashedLikeIos() {
        assertEquals(17, nickColorIndex("oper1_"))
        assertEquals(3, nickColorIndex("oper1"))
    }

    @Test
    fun indexIgnoresCaseAndStaysInRange() {
        assertEquals(nickColorIndex("alice"), nickColorIndex("Alice"))
        assertEquals(nickColorIndex("alice"), nickColorIndex("ALICE"))
        for (n in listOf("", "a", "long-nick-name", "WAY|TOO|MANY|PIPES")) {
            assertTrue(nickColorIndex(n) in 0 until 27)
        }
    }

    @Test
    fun darkKeepsPaletteColour() {
        assertEquals(Color(0xFF000000.toInt() or NickPalette[17]), nickColor("alice", dark = true))
    }

    /** Light appearance: every nick palette colour and member-prefix colour reaches 4.5:1 on white. */
    @Test
    fun lightPaletteIsLegibleOnWhite() {
        for ((i, rgb) in NickPalette.withIndex()) {
            val c = legible(Color(0xFF000000.toInt() or rgb), dark = false)
            assertTrue("nick colour $i", contrastRatio(c, Color.White) >= 4.5)
        }
        for (p in listOf("!", "~", "&", "@", "%", "+")) {
            assertTrue("prefix $p", contrastRatio(prefixColor(p, FiberIrcLightColors), Color.White) >= 4.5)
        }
        assertEquals(FiberIrcLightColors.chatText, prefixColor("", FiberIrcLightColors))
    }

    @Test
    fun notificationFillIsEightyPercentBrightness() {
        for (nick in listOf("alice", "Bob", "x")) {
            val p = Color(0xFF000000.toInt() or NickPalette[nickColorIndex(nick)])
            val f = notificationFill(nick)
            val h0 = rgbToHsb(p.red.toDouble(), p.green.toDouble(), p.blue.toDouble())
            val h = rgbToHsb(f.red.toDouble(), f.green.toDouble(), f.blue.toDouble())
            assertEquals(nick, h0[0], h[0], 0.01)
            assertEquals(nick, h0[1], h[1], 0.01)
            assertEquals(nick, h0[2] * 0.8, h[2], 0.005)
            assertEquals(1f, f.alpha)
        }
    }

    @Test
    fun serverLogColorBucketsEveryPhase() {
        val c = FiberIrcDarkColors
        val buckets = listOf(
            "connecting" to c.eventTopic, "registering" to c.eventTopic, "sasl_start" to c.eventTopic,
            "welcome" to c.online, "sasl_done" to c.online,
            "warn" to c.eventNick, "retry" to c.eventNick,
            "error" to c.offlineError, "disabled" to c.offlineError, "sasl_fail" to c.offlineError, "fail" to c.offlineError,
        )
        for ((tag, color) in buckets) assertEquals(tag, color, serverLogColor(tag, c))
        assertNull(serverLogColor(null, c))
        assertNull(serverLogColor("motd", c))
    }
}
