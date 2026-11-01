package com.kevinpostal.fiberirc.ui.actions

import com.kevinpostal.fiberirc.data.irc.ClientTagFeatures
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import com.kevinpostal.fiberirc.ui.actions.MessageMenu.Item
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ports the message-menu cases of `FiberIRCTests/BufferActionsTests.swift`. */
class MessageMenuTest {
    private fun line(sender: String, kind: IrcLineKind = IrcLineKind.MESSAGE, msgid: String? = "m1", redacted: Boolean = false) =
        IrcLine(Instant.now(), sender, "hi", kind, msgid = msgid, redacted = redacted)

    private val all = ClientTagFeatures(reply = true, typing = true, edit = true, redact = true)
    private val none = ClientTagFeatures(reply = false, typing = false, edit = false, redact = false)

    @Test
    fun ownMessageRepliesEditsAndDeletes() {
        assertEquals(
            listOf(Item.COPY_MESSAGE, Item.REPLY, Item.EDIT_MESSAGE, Item.DELETE_MESSAGE, Item.CLEAR_BACKLOG),
            MessageMenu.items(line("Me"), ownNick = "me", onURL = false, features = all, clearBacklog = true),
        )
        // An action can be deleted, not edited.
        assertEquals(
            listOf(Item.COPY_MESSAGE, Item.REPLY, Item.DELETE_MESSAGE),
            MessageMenu.items(line("me", IrcLineKind.ACTION), ownNick = "me", onURL = false, features = all, clearBacklog = false),
        )
    }

    @Test
    fun othersMessageReportsAndBlocks() {
        assertEquals(
            listOf(Item.COPY_URL, Item.SHARE_URL, Item.COPY_MESSAGE, Item.REPLY, Item.REPORT, Item.BLOCK, Item.CLEAR_BACKLOG),
            MessageMenu.items(line("bob"), ownNick = "me", onURL = true, features = all, clearBacklog = true),
        )
        assertEquals(
            listOf(Item.COPY_MESSAGE, Item.REPORT, Item.BLOCK),
            MessageMenu.items(line("bob", IrcLineKind.NOTICE), ownNick = "me", onURL = false, features = all, clearBacklog = false),
        )
    }

    @Test
    fun serverFeaturesAndMsgidGateReplyEditDelete() {
        assertEquals(listOf(Item.COPY_MESSAGE), MessageMenu.items(line("me"), "me", onURL = false, features = none, clearBacklog = false))
        assertEquals(
            listOf(Item.COPY_MESSAGE),
            MessageMenu.items(line("me", msgid = null), "me", onURL = false, features = all, clearBacklog = false),
        )
        assertEquals(
            listOf(Item.COPY_MESSAGE),
            MessageMenu.items(line("me", redacted = true), "me", onURL = false, features = all, clearBacklog = false),
        )
        // An inline preview: its URL and Close Preview, then the parent message's items.
        assertEquals(
            listOf(Item.COPY_URL, Item.SHARE_URL, Item.CLOSE_PREVIEW, Item.COPY_MESSAGE, Item.REPORT, Item.BLOCK),
            MessageMenu.items(line("bob"), "me", onURL = true, preview = true, features = none, clearBacklog = false),
        )
    }

    @Test
    fun eventsAndSenderlessLinesOfferOnlyCopy() {
        assertEquals(
            listOf(Item.COPY_MESSAGE),
            MessageMenu.items(line("bob", IrcLineKind.EVENT), "me", onURL = false, features = all, clearBacklog = false),
        )
        assertEquals(
            listOf(Item.COPY_MESSAGE),
            MessageMenu.items(line("", IrcLineKind.NOTICE), "me", onURL = false, features = all, clearBacklog = false),
        )
    }
}
