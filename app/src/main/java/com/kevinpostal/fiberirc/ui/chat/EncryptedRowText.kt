package com.kevinpostal.fiberirc.ui.chat

import com.kevinpostal.fiberirc.data.irc.IrcE2eState
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcSendState

/** What an encrypted chat row says beyond its plaintext (web `MessageRow.svelte` / `e2ee.row.*` wording). */
object EncryptedRowText {
    /** The sentence an encrypted row shows instead of text it can't show; null when its plaintext is shown. */
    fun placeholder(line: IrcLine): String? {
        val e = line.e2e ?: return null
        if (e.state.readable || (e.source.own && line.text.isNotEmpty())) return null
        return when (e.state) {
            IrcE2eState.PENDING -> "Decrypting…"
            IrcE2eState.PARTIAL -> "Waiting for the rest of this message…"
            IrcE2eState.BEFORE_JOIN -> "Sent before this device joined the conversation"
            IrcE2eState.LOCKED -> "Encrypted — unlock to read"
            IrcE2eState.EXPIRED -> "No longer decryptable (forward secrecy)"
            IrcE2eState.NOT_ADDRESSED -> "Not addressed to you"
            IrcE2eState.ERROR -> "Couldn't decrypt this message"
            IrcE2eState.VERIFIED, IrcE2eState.UNVERIFIED -> null
        }
    }

    /** The trailing note: your send's progress, else the encryption badge of a readable row; null on plaintext. */
    fun status(line: IrcLine): String? {
        when (val send = line.send) {
            IrcSendState.Sending -> return "· sending…"
            is IrcSendState.Failed -> return "· not sent: ${send.reason}"
            is IrcSendState.Uncertain -> return "· delivery uncertain: ${send.reason}"
            null -> Unit
        }
        val e = line.e2e ?: return null
        return when {
            e.state == IrcE2eState.UNVERIFIED -> "🔒 sender not verified yet"
            e.state.readable || (e.source.own && line.text.isNotEmpty()) -> "🔒"
            else -> null
        }
    }

    /** The message menu's heading for an encrypted row (web `E2eBadge` titles); null on plaintext. An unverified
     *  sender's identity is shown as what the runtime decrypted, never as a verified identity. */
    fun details(line: IrcLine): String? {
        val e = line.e2e ?: return null
        val who = e.identity?.let { " · $it" }.orEmpty()
        return when {
            e.source.own -> "End-to-end encrypted · sent by you"
            e.state == IrcE2eState.VERIFIED -> "End-to-end encrypted — sender verified$who"
            e.state == IrcE2eState.UNVERIFIED -> "Encrypted — sender not verified yet$who"
            else -> placeholder(line)
        }
    }
}
