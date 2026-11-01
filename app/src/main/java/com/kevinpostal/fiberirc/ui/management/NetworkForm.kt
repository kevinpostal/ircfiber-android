package com.kevinpostal.fiberirc.ui.management

import com.kevinpostal.fiberirc.data.api.NetworkInfo
import com.kevinpostal.fiberirc.ui.shell.NetworkPrefill
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The network editor's fields and their `NetworkConfig` JSON (ports `NetworkForm.swift`): every key for
 * `POST /api/networks`, only the changed ones for `PATCH /api/networks/<id>`. Text fields hold what the user typed;
 * [values] turns them into JSON. A new form is TLS on port 6697, no SASL.
 *
 * [storedTLS]/[storedSASL]: the stored `tls` and `sasl` values, sent unchanged (an existing "enabled", "external", …)
 * while their toggle keeps the state they gave it.
 */
data class NetworkForm(
    val name: String = "",
    val host: String = "",
    val port: String = "6697",
    val tls: Boolean = true,
    val serverPass: String = "",
    val nick: String = "",
    val realName: String = "",
    val ident: String = "",
    val sasl: Boolean = false,
    val saslUsername: String = "",
    val saslPassword: String = "",
    val nspass: String = "",
    /** Comma-separated channels. */
    val autoJoin: String = "",
    /** One command per line. */
    val commands: String = "",
    val storedTLS: String? = null,
    val storedSASL: String? = null,
) {
    /** Why the form can't be saved yet (the web's wording), null when it can. */
    val validationError: String?
        get() {
            if (host.trim().isEmpty() || nick.trim().isEmpty()) return "Please provide a hostname and nickname"
            val p = port.trim().toIntOrNull()
            if (p == null || p !in 1..65535) return "Port must be a number from 1 to 65535"
            return null
        }

    /** Every field as `NetworkConfig` JSON. A blank name takes the hostname, as the web's add form does. */
    val values: Map<String, JsonElement>
        get() {
            val h = host.trim()
            val n = name.trim()
            return mapOf(
                "name" to JsonPrimitive(n.ifEmpty { h }),
                "host" to JsonPrimitive(h),
                "port" to JsonPrimitive(port.trim().toIntOrNull() ?: 0),
                "tls" to JsonPrimitive(stored(storedTLS, tls, onValue = "required", offValue = "disabled")),
                "serverPass" to JsonPrimitive(serverPass),
                "nick" to JsonPrimitive(nick.trim()),
                "realName" to JsonPrimitive(realName.trim()),
                "ident" to JsonPrimitive(ident.trim()),
                "sasl" to JsonPrimitive(stored(storedSASL, sasl, onValue = "plain", offValue = "none")),
                "saslUsername" to JsonPrimitive(saslUsername.trim()),
                "saslPassword" to JsonPrimitive(saslPassword),
                "nspass" to JsonPrimitive(nspass),
                "autoJoinChannels" to JsonArray(channels(autoJoin).map(::JsonPrimitive)),
                "commands" to JsonPrimitive(commands),
            )
        }

    /** `POST /api/networks` body. */
    val createBody: JsonObject get() = JsonObject(values)

    /** `PATCH /api/networks/<id>` body: the keys whose value differs from [original]'s. */
    fun changes(original: NetworkForm): JsonObject {
        val before = original.values
        return JsonObject(values.filter { (k, v) -> before[k] != v })
    }

    companion object {
        /**
         * A new network from an `irc://` / `ircs://` link: its host, its port (the scheme's default, 6697 or 6667,
         * when absent) and TLS for `ircs`.
         */
        fun fromLink(host: String, port: Int?, tls: Boolean): NetworkForm =
            NetworkForm(host = host, port = (port ?: if (tls) 6697 else 6667).toString(), tls = tls)

        fun fromLink(prefill: NetworkPrefill): NetworkForm = fromLink(prefill.host, prefill.port, prefill.tls)

        fun from(n: NetworkInfo): NetworkForm = NetworkForm(
            name = n.name,
            host = n.host ?: "",
            port = n.port?.toString() ?: "",
            storedTLS = n.tls,
            tls = (n.tls ?: "required") != "disabled",
            serverPass = n.serverPass ?: "",
            nick = n.nick ?: "",
            realName = n.realName ?: "",
            ident = n.ident ?: "",
            storedSASL = n.sasl,
            sasl = (n.sasl ?: "none") != "none",
            saslUsername = n.saslUsername ?: "",
            saslPassword = n.saslPassword ?: "",
            nspass = n.nspass ?: "",
            autoJoin = (n.autoJoinChannels ?: emptyList()).joinToString(", "),
            commands = n.commands ?: "",
        )

        /** The channels of a comma- (or space-) separated list, each with a channel prefix (`#` added when it has none). */
        fun channels(text: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            for (ch in text + ",") {
                if (ch == ',' || ch.isWhitespace()) {
                    if (cur.isNotEmpty()) out.add(cur.toString())
                    cur.clear()
                } else {
                    cur.append(ch)
                }
            }
            return out.map { c -> if (c.first() in "#&+!") c else "#$c" }
        }

        /** [stored] while the toggle still shows what it gave, else the toggle's own value. */
        private fun stored(stored: String?, on: Boolean, onValue: String, offValue: String): String {
            if (stored != null && (stored != offValue) == on) return stored
            return if (on) onValue else offValue
        }
    }
}
