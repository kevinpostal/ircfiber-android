package com.kevinpostal.fiberirc.data.e2ee

import com.kevinpostal.fiberirc.data.irc.IrcLockDecision
import org.junit.Assert.assertEquals
import org.junit.Test

/** The native lock rule: nothing is off without known prefs and a live policy decision (the runtime's `lockState`). */
class E2eeLockRuleTest {
    /** A known, unlocked channel under a live policy: the one case that is plainly off. */
    private val offChannel = E2eeLockInputs(
        waiting = false,
        prefsKnown = true,
        policyAvailable = true,
        pref = null,
        lockPending = false,
        channel = true,
        localDmDefault = false,
        policyDmDefault = false,
    )

    private fun decide(change: E2eeLockInputs.() -> E2eeLockInputs) = E2eeLockRule.decide(offChannel.change())

    @Test
    fun channelIsOffOnlyWithKnownPrefsAndLivePolicy() {
        assertEquals(IrcLockDecision.OFF, decide { this })
        assertEquals(IrcLockDecision.OFF, decide { copy(pref = false) })
    }

    @Test
    fun channelIsHeldWhilePrefsAreUnknownThenEncryptsWhenTheyLockIt() {
        // Bootstrap: prefs not loaded yet — held, not off (channels included).
        assertEquals(IrcLockDecision.PENDING, decide { copy(waiting = true, prefsKnown = false, policyAvailable = false) })
        // Prefs arrive with the channel locked on.
        assertEquals(IrcLockDecision.ENCRYPT, decide { copy(pref = true) })
        // Still booting when the lock is known: held until the runtime can encrypt.
        assertEquals(IrcLockDecision.PENDING, decide { copy(waiting = true, policyAvailable = false, pref = true) })
    }

    @Test
    fun noPolicyDecisionMeansNothingIsOff() {
        // Runtime running without a policy yet: held. Runtime gone (unavailable, recovery, stopped): encrypt-or-fail.
        assertEquals(IrcLockDecision.PENDING, decide { copy(waiting = true, policyAvailable = false) })
        assertEquals(IrcLockDecision.ENCRYPT, decide { copy(policyAvailable = false) })
        assertEquals(IrcLockDecision.ENCRYPT, decide { copy(policyAvailable = false, pref = false) })
        // Prefs unknown outside bootstrap: encrypt-or-fail, never off.
        assertEquals(IrcLockDecision.ENCRYPT, decide { copy(prefsKnown = false) })
    }

    @Test
    fun lockOnOrSettlingAlwaysEncrypts() {
        assertEquals(IrcLockDecision.ENCRYPT, decide { copy(pref = true) })
        assertEquals(IrcLockDecision.ENCRYPT, decide { copy(lockPending = true) })
        assertEquals(IrcLockDecision.ENCRYPT, decide { copy(lockPending = true, pref = false) })
    }

    @Test
    fun conversationsFollowExplicitOffAndDmDefaults() {
        val dm: E2eeLockInputs.() -> E2eeLockInputs = { copy(channel = false) }
        assertEquals(IrcLockDecision.OFF, decide { dm().copy() })
        assertEquals(IrcLockDecision.ENCRYPT, decide { dm().copy(localDmDefault = true) })
        assertEquals(IrcLockDecision.ENCRYPT, decide { dm().copy(policyDmDefault = true) })
        assertEquals(IrcLockDecision.OFF, decide { dm().copy(policyDmDefault = true, pref = false) })
        assertEquals(IrcLockDecision.ENCRYPT, decide { dm().copy(pref = false, policyAvailable = false) })
    }
}
