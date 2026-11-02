package com.kevinpostal.fiberirc.ui.chat

import com.kevinpostal.fiberirc.data.api.PolicyHighlightWord
import com.kevinpostal.fiberirc.data.api.SiteJson
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import com.kevinpostal.fiberirc.data.irc.mentions
import com.kevinpostal.fiberirc.data.prefs.HighlightOptions
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared Notification Policy v2 highlight vectors (`app/src/test/resources/highlight-vectors.json`, copied from
 * the site's `fixtures/notify/vectors.json` `highlight` section): `mention` is the own nick (whole word, ignoring
 * case) or any word under the case's options; `urgent` is any matching word marked urgent.
 */
class HighlightVectorsTest {
    @Serializable
    private data class Case(
        val text: String,
        val nick: String,
        val words: List<PolicyHighlightWord>,
        val wholeWord: Boolean,
        val caseSensitive: Boolean,
        val mention: Boolean,
        val urgent: Boolean,
    )

    @Serializable
    private data class Vectors(val source: String, val highlight: List<Case>)

    private fun vectors(): Vectors {
        val text = javaClass.classLoader!!.getResourceAsStream("highlight-vectors.json")!!.bufferedReader().use { it.readText() }
        return SiteJson.decodeFromString(Vectors.serializer(), text)
    }

    @Test
    fun everyHighlightVectorMatches() {
        val v = vectors()
        assertTrue(v.source.contains("fixtures/notify/vectors.json"))
        assertTrue(v.highlight.isNotEmpty())
        for (case in v.highlight) {
            val line = IrcLine(java.time.Instant.now(), "someone", case.text, IrcLineKind.MESSAGE)
            val options = HighlightOptions(case.wholeWord, case.caseSensitive)
            val words = case.words.map { it.w }
            val mention = line.mentions(case.nick) || HighlightMatcher.matches(line, words, case.nick, options)
            val urgent = HighlightMatcher.matches(line, case.words.filter { it.urgent }.map { it.w }, case.nick, options)
            assertEquals("mention: $case", case.mention, mention)
            assertEquals("urgent: $case", case.urgent, urgent)
            for (w in case.words) {
                assertEquals(
                    "matchesWord agrees with the list matcher: $case",
                    HighlightMatcher.matches(line, listOf(w.w), case.nick, options),
                    HighlightMatcher.matchesWord(case.text, w.w, options),
                )
            }
        }
    }
}
