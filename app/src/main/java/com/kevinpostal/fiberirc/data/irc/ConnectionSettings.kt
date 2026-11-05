package com.kevinpostal.fiberirc.data.irc

import com.kevinpostal.fiberirc.data.api.Bouncer
import com.kevinpostal.fiberirc.data.api.BouncerNetwork
import com.kevinpostal.fiberirc.data.api.IrcAccount
import com.kevinpostal.fiberirc.data.api.IrcAccountStatus

/**
 * Connection profile for one session (ports `ios/Model/ConnectionSettings.swift`). Identity comes from the
 * ircfiber.com account. Preferred path: attach to the account's bouncer, which shares the engine's live session
 * with the web client. Fallback: direct TLS to the ircd with SASL PLAIN.
 *
 * iOS's debug-only `UserDefaults` launch-argument overrides have no Android counterpart and are not ported.
 */
data class ConnectionSettings(
    val host: String = "irc.ircfiber.com",
    val port: Int = 6697,
    val useTLS: Boolean = true,
    val nick: String,
    val realName: String,
    val saslAccount: String? = null,
    val saslPassword: String? = null,
    val serverPassword: String = "",
    /** Comma separated; ignored via the bouncer. */
    val autoJoinChannels: String = "#ircfiber,#support",
    /** True when attached through the bouncer: channel membership belongs to the engine session. */
    val viaBouncer: Boolean = false,
    /** Human-readable network name for the UI. */
    val networkName: String = "IRC Fiber",
    /** Site network UUID (pin keys are `<networkId>:<buffer>`); empty on the direct-ircd fallback, which has no pins. */
    val networkId: String = "",
) {
    val autoJoinChannelList: List<String>
        get() = autoJoinChannels.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        /**
         * Bouncer attach: `PASS <user>@<clientId>/<network>:<token>` (bnc/wire.d). The stable per-device clientId
         * (`DeviceIdentity.id`, a lower-case UUID, valid under bnc's `[A-Za-z0-9_.:-]`) gives this phone its own
         * playback cursor, so a reattach replays only what was missed.
         */
        fun forBouncer(
            username: String,
            bouncer: Bouncer,
            network: BouncerNetwork,
            token: String,
            clientId: String,
        ): ConnectionSettings = ConnectionSettings(
            host = bouncer.host,
            port = bouncer.port,
            useTLS = bouncer.tls,
            nick = network.nick.ifEmpty { username },
            realName = username,
            serverPassword = "$username@$clientId/${network.slug}:$token",
            autoJoinChannels = "",
            viaBouncer = true,
            networkName = network.name,
            networkId = network.id,
        )

        fun forAccount(username: String, irc: IrcAccount?): ConnectionSettings {
            val account = irc?.account
            val password = irc?.password
            val ready = irc?.status == IrcAccountStatus.READY && account != null && password != null
            return ConnectionSettings(
                host = irc?.host ?: "irc.ircfiber.com",
                port = irc?.port ?: 6697,
                nick = username,
                realName = username,
                saslAccount = if (ready) account else null,
                saslPassword = if (ready) password else null,
            )
        }
    }
}
