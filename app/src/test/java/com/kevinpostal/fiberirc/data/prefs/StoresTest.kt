package com.kevinpostal.fiberirc.data.prefs

import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.SiteJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoresTest {
    private class Boom : Exception()

    @Test
    fun blockListMasks() = runTest {
        assertEquals("kev", BlockListStore.nickPattern("kev"))
        assertEquals("kev", BlockListStore.nickPattern("kev!*"))
        assertEquals("kev", BlockListStore.nickPattern("kev!*@*"))
        assertNull(BlockListStore.nickPattern("kev!*@host"))
        assertNull(BlockListStore.nickPattern("kev!user@*"))
        assertNull(BlockListStore.nickPattern("!*@*"))
        assertTrue(BlockListStore.glob("Ke?*", "kevin"))
        assertFalse(BlockListStore.glob("k?", "kev"))

        val saved = ArrayList<List<String>>()
        val store = BlockListStore { saved += it }
        store.load(listOf("spam*!*@*", "x!*@host"))
        assertTrue(store.isBlocked("SpamBot"))
        store.block("kev")
        assertEquals(listOf("spam*!*@*", "x!*@host", "kev!*@*"), store.masks.value)
        store.unblock("KEV")
        assertEquals(listOf("spam*!*@*", "x!*@host"), store.masks.value)
        assertEquals(2, saved.size)
    }

    @Test
    fun blockListAppliesOnlyAfterSave() = runTest {
        val store = BlockListStore { throw Boom() }
        try {
            store.block("kev")
            fail()
        } catch (_: Boom) {
        }
        assertTrue(store.masks.value.isEmpty())
    }

    @Test
    fun pinFailureUndoesOnlyThatChange() = runTest {
        val prefs = MemoryPrefs()
        val gate = CompletableDeferred<Unit>()
        val store = PinListStore(prefs, pin = { _, ch -> if (ch == "#b") { gate.await(); throw Boom() } }, unpin = { _, _ -> }, saveOrder = null)
        store.load(listOf("n:#a"))
        val job = launch { runCatching { store.pin("n", "#b") } }
        testScheduler.runCurrent()
        assertEquals(listOf("n:#a", "n:#b"), store.keys.value)
        store.load(listOf("n:#z")) // a save in flight keeps the list
        store.pin("n", "#c")
        gate.complete(Unit)
        job.join()
        assertEquals(listOf("n:#a", "n:#c"), store.keys.value)
        assertEquals(listOf("n:#a", "n:#c"), prefs.getStringList(PinListStore.KEY))
    }

    @Test
    fun unpinRemovesEverySpellingAndTellsServerEach() = runTest {
        val removed = ArrayList<String>()
        val store = PinListStore(MemoryPrefs(), pin = null, unpin = { _, ch -> removed += ch }, saveOrder = null)
        store.load(listOf("n:#A", "n:#b", "n:#a"))
        assertTrue(store.isPinned("n", "#a"))
        store.unpin("n", "#a")
        assertEquals(listOf("#A", "#a"), removed)
        assertEquals(listOf("n:#b"), store.keys.value)
        assertFalse(store.isPinned("", "#b"))
    }

    @Test
    fun pinOrderNamesFirstThenTheRest() = runTest {
        var sent: List<String>? = null
        val store = PinListStore(MemoryPrefs(), pin = null, unpin = null, saveOrder = { sent = it })
        store.load(listOf("n:#a", "n:#b", "n:#c"))
        store.setOrder(listOf("N:#C", "n:#gone", "n:#a"))
        assertEquals(listOf("n:#c", "n:#a", "n:#b"), store.keys.value)
        assertEquals(store.keys.value, sent)
    }

    @Test
    fun bufferPrefsDefaultsAndRevert() = runTest {
        val store = BufferPrefsStore(MemoryPrefs()) { _, _, _ -> throw Boom() }
        store.load(mapOf("n:#Chan" to mapOf("mute" to true)))
        val device = DeviceSettings(collapseJoinPart = false, embedUploads = false)
        assertEquals(true, store.value(BufferPrefsStore.Key.MUTE, "n", "#chan"))
        assertTrue(store.effective(BufferPrefsStore.Key.EXPAND_JOIN_PART, "n", "#x", device, formatColors = true))
        assertFalse(store.effective(BufferPrefsStore.Key.INLINE_FILES, "n", "#x", device, formatColors = true))
        assertFalse(store.effective(BufferPrefsStore.Key.FORMAT_COLOR, "n", "#x", device, formatColors = false))
        assertTrue(store.effective(BufferPrefsStore.Key.NICK_SUGGESTIONS, "n", "#x", device, formatColors = true))
        assertNull(store.value(BufferPrefsStore.Key.MUTE, "", "#chan"))
        runCatching { store.set(mapOf(BufferPrefsStore.Key.TYPING to false), "n", "#chan") }
        assertEquals(mapOf("n:#Chan" to mapOf("mute" to true)), store.values.value)
    }

    @Test
    fun bufferPrefsSavesChangedKeysUnderStoredSpelling() = runTest {
        var call: Triple<String, String, Map<String, Boolean>>? = null
        val store = BufferPrefsStore(MemoryPrefs()) { n, c, p -> call = Triple(n, c, p) }
        store.load(mapOf("n:#Chan" to mapOf("mute" to true)))
        store.set(mapOf(BufferPrefsStore.Key.TYPING to false), "n", "#chan")
        assertEquals(Triple("n", "#Chan", mapOf("typing" to false)), call)
        assertEquals(mapOf("n:#Chan" to mapOf("mute" to true, "typing" to false)), store.values.value)
    }

    @Test
    fun displayPrefsDecodeLenientlyAndPatchChangedKeys() {
        val obj = SiteJson.parseToJsonElement("""{"showUserIcons":false,"modeIndicator":"weird","timestampFormat":"24h","enlargeEmoji":"no"}""").jsonObject
        val d = DisplayPrefs.fromJson(obj)
        assertFalse(d.showUserIcons)
        assertEquals(ModeIndicator.DOTS, d.modeIndicator)
        assertEquals(TimestampFormat.TWENTY_FOUR_HOUR, d.timestampFormat)
        assertTrue(d.enlargeEmoji)
        assertEquals("""{"modeIndicator":"symbols"}""", d.copy(modeIndicator = ModeIndicator.SYMBOLS).patch(d).toString())
        assertEquals(d, DisplayPrefs.fromJson(d.toJson()))
    }

    @Test
    fun accountSettingsServerAnswerWinsAndFailureReverts() = runTest {
        var fail = false
        val store = AccountSettingsStore(
            MemoryPrefs(),
            saveDisplay = { if (fail) throw Boom() else DisplayPrefs(displayPrefsSet = true, showUserIcons = false) },
            saveHighlightWords = { words -> words.map { it.trim() }.distinct() },
            saveShowMemberPrefixes = { throw Boom() },
        )
        store.updateDisplay { it.copy(showUserIcons = false) }
        assertTrue(store.display.value.displayPrefsSet)
        fail = true
        runCatching { store.updateDisplay { it.copy(showTimestamps = false) } }
        assertTrue(store.display.value.showTimestamps)
        store.setHighlightWords(listOf(" a", "a"))
        assertEquals(listOf("a"), store.highlightWords.value)
        runCatching { store.setShowMemberPrefixes(true) }
        assertFalse(store.showMemberPrefixes.value)
        store.reset()
        assertEquals(DisplayPrefs(), store.display.value)
    }

    @Test
    fun networkLayoutKeepsOnlyCollapsedTrue() = runTest {
        val store = NetworkLayoutStore(MemoryPrefs(), saveCollapsed = null, saveOrder = null)
        store.load(Me(id = "1", username = "u", collapsed = mapOf("a" to true, "b" to false), networkOrder = listOf("b", "a")))
        assertEquals(setOf("a"), store.collapsed.value)
        assertEquals(listOf("b", "a"), store.networkOrder.value)
        store.load(Me(id = "1", username = "u"))
        assertEquals(setOf("a"), store.collapsed.value)
    }

    @Test
    fun notificationPrefsRevertOnFailure() = runTest {
        val store = NotificationPrefsStore(MemoryPrefs()) { _, _ -> throw Boom() }
        assertFalse(store.muteAll.value)
        assertTrue(store.notificationSound.value)
        runCatching { store.set(NotificationPrefsStore.Key.MUTE_ALL, true) }
        assertFalse(store.muteAll.value)
        store.load(Me(id = "1", username = "u", muteAll = true))
        assertTrue(store.muteAll.value)
    }

    @Test
    fun shareTargetsRankPinnedRecentThenDiscovery() {
        fun t(b: String, net: String = "n") = ShareTarget(net, "Net", b, ShareTarget.CHANNEL)
        val ranked = ShareTargetsStore.rank(
            listOf(t("#a"), t("#b"), t("#c"), t("#d"), t("#direct", net = "")),
            pins = listOf("n:#D"),
            recents = listOf("n:#c"),
        )
        assertEquals(listOf("#d", "#c", "#a", "#b"), ranked.map { it.buffer })
    }
}
