package com.kevinpostal.fiberirc.data

import com.kevinpostal.fiberirc.data.api.Me
import com.kevinpostal.fiberirc.data.irc.ConnectionSettings

/** Authenticated website identity is never inferred from an IRC nickname or connection. */
data class SessionStart(val account: Me?, val connections: List<ConnectionSettings>)
