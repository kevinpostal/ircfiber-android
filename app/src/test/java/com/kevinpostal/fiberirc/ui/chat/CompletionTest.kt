package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kevinpostal.fiberirc.data.irc.ComposeStyle
import com.kevinpostal.fiberirc.data.irc.IrcBuffer
import com.kevinpostal.fiberirc.data.irc.IrcBufferKind
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import com.kevinpostal.fiberirc.data.irc.IrcMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CompletionTest {
    private fun channel(vararg members: IrcMember) = IrcBuffer("#fiber", IrcBufferKind.CHANNEL).apply {
        setMembers(members.toList())
    }

    private fun suggest(text: String, buffer: IrcBuffer, channels: List<String> = emptyList(), own: String = "me", recent: List<String> = emptyList()) =
        Completion.suggestions(Completion.word(text, text.length)!!, buffer, channels, own, recent)

    // Word finding

    @Test fun wordIsNullAtStartOrAfterWhitespace() {
        assertNull(Completion.word("", 0))
        assertNull(Completion.word("hello", 0))
        assertNull(Completion.word("hello ", 6))
        assertNull(Completion.word("hello\n", 6))
        assertNull(Completion.word("hi", 3))
    }

    @Test fun wordScansBackToLastWhitespace() {
        assertEquals(Completion.Word(0 until 4, "nick", true), Completion.word("nick", 4))
        assertEquals(Completion.Word(6 until 9, "wor", false), Completion.word("hello world", 9))
        assertEquals(Completion.Word(0 until 3, "hel", true), Completion.word("hello world", 3))
        assertEquals(Completion.Word(2 until 4, "bo", false), Completion.word("a\nbo", 4))
        assertEquals(Completion.Word(2 until 5, "ali", false), Completion.word("a\tali", 5))
    }

    // Emoji

    @Test fun emojiNeedsColonAndMoreThanTwoCharacters() {
        assertEquals(emptyList<CompletionSuggestion>(), suggest(":s", channel()))
        val s = suggest(":sm", channel(IrcMember(":smurf", "")))
        assertEquals(listOf(":smile:", ":smiley:", ":smirk:"), s.map { it.label.substringAfter(' ') })
        assertEquals("\uD83D\uDE04 :smile:", s[0].label)
        assertEquals("\uD83D\uDE04", s[0].insertText)
        assertTrue(s.all { it.kind == CompletionKind.EMOJI && it.replaceRange == 0 until 3 })
    }

    @Test fun emojiSortedByCodeAndCaseInsensitivePrefix() {
        val s = suggest("x :CO", channel())
        assertEquals(
            listOf("cocktail", "coconut", "coffee", "cold_face", "cold_sweat", "compass", "computer", "confounded",
                "confused", "cookie", "cow", "cowboy"),
            s.map { it.label.substringAfter(" :").removeSuffix(":") },
        )
        assertTrue(s.size <= Completion.MAX_EMOJI)
        assertEquals(2 until 5, s[0].replaceRange)
    }

    // Channels then nicks

    @Test fun channelsComeBeforeNicksDedupedWithCurrentChannelFirst() {
        val buffer = channel(IrcMember("#fizz", ""), IrcMember("bob", ""))
        val s = suggest("join #FI", buffer, channels = listOf("#Fish", "#FIBER", "#other"))
        assertEquals(listOf("#fiber", "#Fish", "#fizz"), s.map { it.label })
        assertEquals(listOf(CompletionKind.CHANNEL, CompletionKind.CHANNEL, CompletionKind.NICK), s.map { it.kind })
        assertEquals("#fiber ", s[0].insertText)
        assertEquals(5 until 8, s[0].replaceRange)
    }

    @Test fun channelPrefixIgnoresLeadingAt() {
        val s = suggest("@#oth", channel(), channels = listOf("#other"))
        assertEquals(listOf("#other "), s.map { it.insertText })
    }

    @Test fun queryOffersNoOwnChannelName() {
        val q = IrcBuffer("#notachannel", IrcBufferKind.QUERY)
        assertEquals(listOf("#notachannel"), suggest("#no", q, channels = emptyList()).map { it.label })
        // the query partner itself is the nick candidate, not a channel
        assertEquals(CompletionKind.NICK, suggest("#no", q).single().kind)
    }

    @Test fun nicksMatchNickOrRealnameRecentFirstThenAlphaExcludingOwn() {
        val buffer = channel(
            IrcMember("alice", "", realname = "Zed"),
            IrcMember("Alicia", "@"),
            IrcMember("albert", ""),
            IrcMember("Al", "+"),
            IrcMember("bob", ""),
        )
        val s = suggest("al", buffer, own = "AL", recent = listOf("bob", "albert", "ALBERT", "alicia"))
        assertEquals(listOf("albert", "Alicia", "alice"), s.map { it.label })
        assertEquals("albert: ", s[0].insertText)
        assertEquals(listOf("alice"), suggest("@ze", buffer).map { it.label })
        assertEquals("alice ", suggest("hi ze", buffer).single().insertText)
    }

    @Test fun queryPartnerIsACandidate() {
        val q = IrcBuffer("Carol", IrcBufferKind.QUERY)
        assertEquals(listOf("Carol: "), suggest("ca", q).map { it.insertText })
        assertEquals(emptyList<String>(), suggest("ca", q, own = "carol").map { it.label })
    }

    @Test fun emptyPrefixAfterAtGivesNothing() {
        assertEquals(emptyList<CompletionSuggestion>(), suggest("@", channel(IrcMember("bob", ""))))
    }

    @Test fun cappedAtFifty() {
        val buffer = channel(*Array(60) { IrcMember("user$it", "") })
        assertEquals(Completion.MAX_SUGGESTIONS, suggest("us", buffer).size)
    }

    @Test fun recentSendersNewestFirstUnique() {
        val buffer = channel()
        val t = Instant.parse("2026-01-01T00:00:00Z")
        listOf("a", "B", "b", null, "c", "A").forEachIndexed { i, s ->
            buffer.append(IrcLine(t.plusSeconds(i.toLong()), s, "x", IrcLineKind.MESSAGE))
        }
        assertEquals(listOf("A", "c", "b"), Completion.recentSenders(buffer.lines))
    }

    // Tab cycling

    @Test fun cycleWrapsAround() {
        val list = listOf(CompletionSuggestion("a", "a", 0 until 1), CompletionSuggestion("b", "b", 0 until 1))
        assertEquals(0, cycleCompletion(list, null))
        assertEquals(1, cycleCompletion(list, 0))
        assertEquals(0, cycleCompletion(list, 1))
        assertEquals(-1, cycleCompletion(emptyList(), null))
    }

    @Test fun tabReplacesThePreviousChoice() {
        val buffer = channel(IrcMember("alice", ""), IrcMember("albert", ""))
        val suggestFn = { w: Completion.Word -> Completion.suggestions(w, buffer, emptyList(), "me", emptyList()) }
        val completion = ComposerCompletion()
        var value = TextFieldValue("al", TextRange(2))
        completion.refresh(value, suggestFn)
        value = completion.tab(value, suggestFn)!!
        assertEquals("albert: ", value.text)
        assertEquals(TextRange(8), value.selection)
        value = completion.tab(value, suggestFn)!!
        assertEquals("alice: ", value.text)
        value = completion.tab(value, suggestFn)!!
        assertEquals("albert: ", value.text)
        assertEquals(0, completion.selection)
    }

    @Test fun editRecomputesOnlyOnChange() {
        val buffer = channel(IrcMember("bob", ""))
        val suggestFn = { w: Completion.Word -> Completion.suggestions(w, buffer, emptyList(), "me", emptyList()) }
        val completion = ComposerCompletion()
        val old = TextFieldValue("hi ", TextRange(3))
        val new = TextFieldValue("hi b", TextRange(4))
        completion.onEdit(old, new, suggestFn)
        assertEquals(listOf("bob "), completion.suggestions.map { it.insertText })
        val picked = completion.pick(new, completion.suggestions[0], suggestFn)
        assertEquals("hi bob ", picked.text)
        assertEquals(emptyList<CompletionSuggestion>(), completion.suggestions)
    }

    // Send pipeline

    @Test fun sendSplitsIntoNonEmptyLines() {
        assertEquals(listOf("a", "b", "c"), ComposerSend.outgoingLines("a\n\nb\r\nc\n", ComposeStyle(), convertEmoji = false))
        assertEquals(emptyList<String>(), ComposerSend.outgoingLines("", ComposeStyle(), convertEmoji = true))
    }

    @Test fun commandsAreNeverStyledAndKeepColons() {
        val bold = ComposeStyle(bold = true)
        assertEquals(listOf("/join #a :smile:"), ComposerSend.outgoingLines("/join #a :smile:", bold, convertEmoji = true))
        assertEquals("\u0002hi\u000F", ComposerSend.outgoingText("hi", bold, convertEmoji = false))
        assertEquals("hi \uD83D\uDE04 :nope:", ComposerSend.outgoingText("hi :smile: :nope:", ComposeStyle(), convertEmoji = true))
    }

    @Test fun replyAndMentionInsertion() {
        assertEquals(TextFieldValue("bob: ", TextRange(5)), insertReply(TextFieldValue(""), "bob"))
        assertEquals(TextFieldValue("hi bob", TextRange(6)), insertReply(TextFieldValue("hi", TextRange(0)), "bob"))
        assertEquals(TextFieldValue("bob: ", TextRange(5)), insertMention(TextFieldValue(""), "bob"))
        assertEquals(TextFieldValue("hi bob there", TextRange(7)), insertMention(TextFieldValue("hi XXthere", TextRange(3, 5)), "bob"))
    }

    @Test fun returnInsertionDetection() {
        val old = TextFieldValue("hello", TextRange(5))
        assertTrue(isReturnInsertion(old, TextFieldValue("hello\n", TextRange(6))))
        assertEquals(false, isReturnInsertion(old, TextFieldValue("hello\nworld", TextRange(11))))
        assertEquals("he\n", insertNewline(TextFieldValue("hello", TextRange(2, 5))).text)
    }
}
