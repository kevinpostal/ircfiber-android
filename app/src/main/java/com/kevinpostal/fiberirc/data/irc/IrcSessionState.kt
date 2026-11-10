package com.kevinpostal.fiberirc.data.irc

/**
 * What the app process shows: sign-in, or the signed-in session's connections. Owned by
 * [com.kevinpostal.fiberirc.data.Deps] (not an activity), so recreating or reopening the activity shows the same
 * [IrcSessionManager] and [IrcConnectionService] keeps it alive while the app is in the background.
 */
sealed interface IrcSessionState {
    /** [resumeStoredSession] is false after an explicit service stop or a refused foreground-service start. */
    data class Login(val notice: String?, val generation: Int, val resumeStoredSession: Boolean = true) : IrcSessionState

    class Session(val manager: IrcSessionManager, val account: com.kevinpostal.fiberirc.data.api.Me?) : IrcSessionState
}
