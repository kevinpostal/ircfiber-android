package com.kevinpostal.fiberirc.data.api

import com.kevinpostal.fiberirc.data.irc.IrcMember
import kotlinx.serialization.Serializable

@Serializable
private class ChannelMemberRow(
    val nick: String,
    val prefixes: String? = null,
    val user: String? = null,
    val host: String? = null,
    val realname: String? = null,
    val account: String? = null,
)

@Serializable
private class ChannelMembersAnswer(val members: List<ChannelMemberRow>)

/**
 * GET /api/networks/<network>/channels/<channel>/members (ports `SiteAPI.channelMembers`): real names, accounts and
 * hosts the bouncer's NAMES list lacks. A channel the engine doesn't know (404) has none.
 */
suspend fun SiteApi.channelMembers(network: String, channel: String): Result<List<IrcMember>> {
    val answer = getJson(listOf("api", "networks", network, "channels", channel, "members"))
    answer.exceptionOrNull()?.let { if (it is SiteApiError.Server && it.code == 404) return Result.success(emptyList()) }
    return answer.decodeAs(ChannelMembersAnswer.serializer()).map { a ->
        a.members.map {
            IrcMember(
                nick = it.nick,
                prefixes = it.prefixes.orEmpty(),
                user = it.user.orEmpty(),
                host = it.host.orEmpty(),
                realname = it.realname.orEmpty(),
                account = it.account.orEmpty(),
            )
        }
    }
}
