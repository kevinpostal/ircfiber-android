package com.kevinpostal.fiberirc.support

import android.app.Application
import com.kevinpostal.fiberirc.data.Deps
import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings
import com.kevinpostal.fiberirc.data.irc.IrcSessionManager
import com.kevinpostal.fiberirc.ui.shell.SessionActions

/**
 * A signed-in session ready for [com.kevinpostal.fiberirc.ui.shell.SessionHost]: the real [Deps] object graph
 * (pointed at [server] instead of ircfiber.com) plus a fake-transport-backed [manager] — same production
 * [com.kevinpostal.fiberirc.ui.shell.ShellScreen]/chat/management/media/settings code, no real socket or network.
 *
 * [me] seeds the account stores (pins, archives, display prefs, …) as a real sign-in would via `loadAtLogin`;
 * pass one whose fields line up with [settings] when a test cares about pinned/archived buffers.
 */
class TestSession(
    app: Application,
    server: FakeSiteServer,
    settings: List<ConnectionSettings>,
    me: Me = Me(id = "u1", username = settings.firstOrNull()?.nick ?: "tester"),
) {
    val deps = Deps(app, apiBaseUrl = server.baseUrl)
    private val fakeSession = FakeSession(settings, deps.api)

    /** One [IrcClient][com.kevinpostal.fiberirc.data.irc.IrcClient] (already connected) per entry in [settings]. */
    val manager: IrcSessionManager = fakeSession.manager

    /** Each network's [FakeTransport], keyed by the [ConnectionSettings] it was built from. */
    val transports: Map<ConnectionSettings, FakeTransport> get() = fakeSession.transports

    /** The lone network's transport, for a session with exactly one entry in [settings]. */
    val transport: FakeTransport get() = fakeSession.transport

    /** Set by [sessionActions]' `showLogin` (sign-out, rejected credentials) — null while the session stays up. */
    var loggedOutNotice: String? = null
        private set

    val sessionActions = SessionActions(
        context = app,
        api = deps.api,
        stores = deps.accountStores,
        unregisterPush = {},
        showLogin = { notice -> loggedOutNotice = notice },
    )

    init {
        deps.accountStores.loadAtLogin(me)
    }
}
