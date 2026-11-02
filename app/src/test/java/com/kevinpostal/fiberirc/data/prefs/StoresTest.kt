package com.kevinpostal.fiberirc.data.prefs

import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.api.NotificationPolicyView
import com.kevinpostal.fiberirc.data.api.PolicyHighlightWord
import com.kevinpostal.fiberirc.data.api.SiteApiError
import com.kevinpostal.fiberirc.data.api.SiteJson
import com.kevinpostal.fiberirc.data.api.policyView
import com.kevinpostal.fiberirc.data.api.policyViewJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        val obj = SiteJson.parseToJsonElement("""{"showUserIcons":false,"modeIndicator":"weird","timestampFormat":"24h","enlargeEmoji":"no","chatScalePct":120}""").jsonObject
        val d = DisplayPrefs.fromJson(obj)
        assertFalse(d.showUserIcons)
        assertEquals(ModeIndicator.DOTS, d.modeIndicator)
        assertEquals(TimestampFormat.TWENTY_FOUR_HOUR, d.timestampFormat)
        assertTrue(d.enlargeEmoji)
        assertEquals(120, d.chatScalePct)
        assertEquals(1.2, d.chatFontScale, 1e-9)
        for (bad in listOf("7", "115.5", "\"120\"", "145", "112")) {
            val o = SiteJson.parseToJsonElement("""{"chatScalePct":$bad}""").jsonObject
            assertEquals(bad, 100, DisplayPrefs.fromJson(o).chatScalePct)
        }
        assertEquals("""{"modeIndicator":"symbols"}""", d.copy(modeIndicator = ModeIndicator.SYMBOLS).patch(d).toString())
        assertEquals("""{"chatScalePct":130}""", d.copy(chatScalePct = 130).patch(d).toString())
        assertEquals(d, DisplayPrefs.fromJson(d.toJson()))
    }

    @Test
    fun deviceSettingsHandsOverLegacyChatFontScaleOnce() {
        val prefs = MemoryPrefs().apply { edit().putString("device.chatFontScale", "1.2").apply() }
        val store = DeviceSettingsStore(prefs)
        assertEquals(120, store.takeLegacyChatScalePct())
        assertNull(store.takeLegacyChatScalePct())
        assertFalse(prefs.contains("device.chatFontScale"))
        val atDefault = DeviceSettingsStore(MemoryPrefs().apply { edit().putString("device.chatFontScale", "1.0").apply() })
        assertNull(atDefault.takeLegacyChatScalePct())
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

    private fun conflict(rev: Long) = SiteApiError.Conflict(
        SiteJson.parseToJsonElement("""{"error":"changed","code":"rev_conflict","current":${policyViewJson(rev = rev)}}"""),
    )

    private fun me(view: NotificationPolicyView?, muteAll: Boolean? = null) =
        Me(id = "1", username = "u", muteAll = muteAll, notificationPolicy = view)

    @Test
    fun notificationPolicyRetriesOnceOnConflict() = runTest {
        val revs = ArrayList<Long>()
        lateinit var store: NotificationPolicyStore
        store = NotificationPolicyStore(
            MemoryPrefs(),
            savePolicy = { patch, rev ->
                revs += rev
                assertEquals(JsonObject(mapOf("applyPreset" to JsonPrimitive("everything"))), patch)
                // Optimistic: the preset's cells are applied and recognised before the server answers.
                assertEquals("everything", store.view.value!!.preset)
                assertTrue(store.view.value!!.policy.matrix["notice"]!!.mobile)
                if (rev == 12L) throw conflict(20) else policyView(rev = 21, preset = "everything")
            },
            saveClient = null,
            saveSnooze = null,
        )
        store.load(me(policyView()))
        store.applyPreset("everything")
        assertEquals(listOf(12L, 20L), revs)
        assertEquals(21L, store.view.value!!.rev)
        assertEquals("everything", store.view.value!!.preset)
    }

    @Test
    fun notificationPolicyRevertsOnFailure() = runTest {
        var failure: Exception = Boom()
        val store = NotificationPolicyStore(MemoryPrefs(), savePolicy = { _, _ -> throw failure }, saveClient = null, saveSnooze = null)
        val original = policyView()
        store.load(me(original))
        runCatching { store.patchPolicy(JsonObject(mapOf("quiet" to JsonObject(mapOf("enabled" to JsonPrimitive(true)))))) }
        assertEquals(original, store.view.value)

        // A second conflict is not retried again: the error surfaces and the server's view stays.
        failure = conflict(20)
        val error = runCatching { store.applyPreset("quiet") }.exceptionOrNull()
        assertTrue(error is SiteApiError.Conflict)
        assertEquals(20L, store.view.value!!.rev)
        assertEquals("focused", store.view.value!!.preset)
    }

    @Test
    fun notificationPolicyDeviceCellsOverrideOnlyWhenTheyDiffer() = runTest {
        val sent = ArrayList<Pair<String, JsonObject>>()
        val gate = CompletableDeferred<Unit>()
        val store = NotificationPolicyStore(
            MemoryPrefs(),
            savePolicy = null,
            saveClient = { ref, patch, _ ->
                sent += ref to patch
                if (!gate.isCompleted) gate.await()
                policyView(rev = 13)
            },
            saveSnooze = null,
        )
        store.load(me(policyView()))
        // dm.sound is overridden off; turning it back on equals the account cell, so the override goes.
        val job = launch { store.setDeviceCell("dm", "sound", true) }
        testScheduler.runCurrent()
        assertEquals("self", sent.single().first)
        assertEquals(JsonObject(mapOf("cells" to JsonObject(mapOf("dm.sound" to JsonNull)))), sent.single().second)
        assertNull(store.view.value!!.policy.clients["a1b2c3"])
        assertTrue(store.view.value!!.policy.resolveCell("a1b2c3", "dm", "sound"))
        // /api/me during the save doesn't undo the edit.
        store.load(me(policyView(rev = 12)))
        assertNull(store.view.value!!.policy.clients["a1b2c3"])
        gate.complete(Unit)
        job.join()
        assertEquals(13L, store.view.value!!.rev)

        // all.mobile is off on the account: turning it on for this phone is an override.
        sent.clear()
        store.setDeviceCell("all", "mobile", true)
        assertEquals(JsonObject(mapOf("cells" to JsonObject(mapOf("all.mobile" to JsonPrimitive(true))))), sent.single().second)
    }

    @Test
    fun notificationPolicyLoadKeepsNewerAndClearsOnV1Servers() = runTest {
        val words = ArrayList<List<String>>()
        val store = NotificationPolicyStore(MemoryPrefs(), null, null, null)
        store.onHighlightChanged = { h -> words += h.words.map { it.w } }
        store.load(me(policyView(rev = 12)))
        assertEquals(listOf(listOf("outage", "deploy")), words)
        store.load(me(policyView(rev = 11, preset = "custom")))
        assertEquals("focused", store.view.value!!.preset)
        store.load(me(null))
        assertEquals(12L, store.view.value!!.rev)
        store.load(me(null, muteAll = false))
        assertNull(store.view.value)

        // The cache survives a restart; reset forgets it.
        val prefs = MemoryPrefs()
        val first = NotificationPolicyStore(prefs, null, null, null)
        first.load(me(policyView()))
        assertEquals(policyView(), NotificationPolicyStore(prefs, null, null, null).view.value)
        first.reset()
        assertNull(NotificationPolicyStore(prefs, null, null, null).view.value)
    }

    @Test
    fun notificationPolicyUrgentStarAndHighlightSync() = runTest {
        val settings = AccountSettingsStore(MemoryPrefs(), null, null, null)
        val store = NotificationPolicyStore(MemoryPrefs(), null, null, null)
        val stores = AccountStores(
            accountSettings = settings,
            notificationPrefs = NotificationPrefsStore(MemoryPrefs(), null),
            notificationPolicy = store,
            pinList = PinListStore(MemoryPrefs(), null, null, null),
            archiveList = ArchiveListStore(MemoryPrefs(), null, null),
            blockList = BlockListStore { },
            networkLayout = NetworkLayoutStore(MemoryPrefs(), null, null),
            bufferPrefs = BufferPrefsStore(MemoryPrefs(), null),
        )
        stores.reloadFromMe(me(policyView()))
        assertEquals(listOf("outage", "deploy"), settings.highlightWords.value)
        store.toggleUrgent("deploy")
        assertEquals(
            listOf(PolicyHighlightWord("outage", true), PolicyHighlightWord("deploy", true)),
            store.view.value!!.policy.highlight.words,
        )
        store.patchPolicy(JsonObject(mapOf("highlight" to JsonObject(mapOf("caseSensitive" to JsonPrimitive(true))))))
        assertEquals(HighlightOptions(wholeWord = true, caseSensitive = true), settings.highlightOptions.value)
        store.setHighlightWords(listOf(PolicyHighlightWord("pager", true)))
        assertEquals(listOf("pager"), settings.highlightWords.value)
        stores.resetAccount()
        assertNull(store.view.value)
        assertEquals(HighlightOptions(), settings.highlightOptions.value)
    }

    @Test
    fun mergePatchFollowsRfc7396() {
        val target = SiteJson.parseToJsonElement("""{"a":"b","c":{"d":"e","f":"g"},"x":[1]}""")
        val patch = SiteJson.parseToJsonElement("""{"a":"z","c":{"f":null},"x":[2,3]}""")
        assertEquals(
            SiteJson.parseToJsonElement("""{"a":"z","c":{"d":"e"},"x":[2,3]}"""),
            NotificationPolicyStore.mergePatch(target, patch),
        )
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
