package com.kevinpostal.fiberirc.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three-step resolve of `DeviceIdentity.swift:36-46`. */
class DeviceIdentityTest {
    private val upper = "0E3A1C52-9B4D-4F7A-8C2E-1D6B5A4F3E21"
    private val lower = upper.lowercase()

    @Test
    fun storedIdWinsLowerCased() {
        val prefs = MemoryPrefs().apply { edit().putString(DeviceIdentity.FALLBACK_KEY, "11111111-1111-1111-1111-111111111111").apply() }
        var writes = 0
        val id = DeviceIdentity.resolve(read = { upper }, write = { writes++; true }, prefs = prefs)
        assertEquals(lower, id)
        assertEquals(0, writes)
    }

    @Test
    fun parkedIdMovesToBlockStore() {
        val prefs = MemoryPrefs().apply { edit().putString(DeviceIdentity.FALLBACK_KEY, upper).apply() }
        var written: String? = null
        val id = DeviceIdentity.resolve(read = { "not-a-uuid" }, write = { written = it; true }, prefs = prefs)
        assertEquals(lower, id)
        assertEquals(lower, written)
        assertNull(prefs.getString(DeviceIdentity.FALLBACK_KEY, null))
    }

    @Test
    fun parkedIdStaysWhenBlockStoreRefuses() {
        val prefs = MemoryPrefs().apply { edit().putString(DeviceIdentity.FALLBACK_KEY, upper).apply() }
        val id = DeviceIdentity.resolve(read = { null }, write = { false }, prefs = prefs)
        assertEquals(lower, id)
        assertEquals(upper, prefs.getString(DeviceIdentity.FALLBACK_KEY, null))
    }

    @Test
    fun newIdParksWhenBlockStoreRefuses() {
        val prefs = MemoryPrefs()
        val id = DeviceIdentity.resolve(read = { null }, write = { false }, prefs = prefs)
        assertTrue(DeviceIdentity.isUuid(id))
        assertEquals(id.lowercase(), id)
        assertEquals(id, prefs.getString(DeviceIdentity.FALLBACK_KEY, null))
    }

    @Test
    fun newIdGoesToBlockStore() {
        val prefs = MemoryPrefs()
        var written: String? = null
        val id = DeviceIdentity.resolve(read = { null }, write = { written = it; true }, prefs = prefs)
        assertEquals(id, written)
        assertFalse(prefs.contains(DeviceIdentity.FALLBACK_KEY))
    }

    @Test
    fun uuidMustBeCanonical() {
        assertTrue(DeviceIdentity.isUuid(upper))
        assertFalse(DeviceIdentity.isUuid("1-1-1-1-1"))
        assertFalse(DeviceIdentity.isUuid(null))
    }
}
