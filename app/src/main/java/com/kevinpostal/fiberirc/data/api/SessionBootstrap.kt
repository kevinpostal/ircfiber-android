package com.kevinpostal.fiberirc.data.api

import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import kotlinx.coroutines.delay

/** Building a signed-in session's connections from the account (ports `SessionBootstrap.swift`). */
object SessionBootstrap {
    /**
     * One bouncer connection per configured network, all sharing the engine's live session with the web client;
     * managed IRC Fiber networks first. Empty when the bouncer is disabled or has no networks.
     */
    suspend fun bouncerSettings(me: Me, api: SiteApi, clientId: String): Result<List<ConnectionSettings>> = attempt {
        val bnc = api.bouncerSettings().decodeAs(Bouncer.serializer()).getOrThrow()
        if (!bnc.enabled || bnc.networks.isEmpty()) return@attempt emptyList()
        val minted = api.bouncerToken().decodeAs(BouncerToken.serializer()).getOrThrow()
        val ordered = bnc.networks.filter { it.managed } + bnc.networks.filterNot { it.managed }
        ordered.map { ConnectionSettings.forBouncer(me.username, bnc, it, minted.token, clientId) }
    }

    /**
     * Waits for SASL provisioning (`ircAccountWaitingForReady`): asks for a retry once when `pending`/`none`, then
     * polls until `ready`, at most [attempts] times every [intervalMs].
     */
    suspend fun ircAccountWaitingForReady(
        api: SiteApi,
        attempts: Int = 8,
        intervalMs: Long = 2_500,
    ): Result<IrcAccount> = attempt {
        var acct = api.ircAccount().decodeAs(IrcAccount.serializer()).getOrThrow()
        if (acct.status == IrcAccountStatus.PENDING || acct.status == IrcAccountStatus.NONE) api.retryIrcAccount()
        var n = 0
        while (acct.status != IrcAccountStatus.READY && n < attempts) {
            delay(intervalMs)
            acct = api.ircAccount().decodeAs(IrcAccount.serializer()).getOrThrow()
            n++
        }
        acct
    }
}
