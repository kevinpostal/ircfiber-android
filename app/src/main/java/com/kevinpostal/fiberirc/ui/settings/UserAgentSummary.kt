package com.kevinpostal.fiberirc.ui.settings

/**
 * One-line summary of a login session's recorded User-Agent ("Chrome 152 on macOS") for Settings → Login
 * Sessions (ports `UserAgentSummary.swift`, itself the web's `lib/userAgent.ts` label plus the apps' own UAs).
 * Never empty: an unrecognised UA shows itself, clipped to one row.
 */
object UserAgentSummary {
    private const val MAX_RAW = 80

    fun label(ua: String): String {
        val raw = ua.trim()
        if (raw.startsWith("FiberIRC iOS")) return "IRC Fiber for iOS"
        if (raw.startsWith("FiberIRC Android")) return "IRC Fiber for Android"
        if (raw.isEmpty()) return "Unknown client"
        // Crawlers embed whole browser UAs: show their raw claim instead of "Chrome".
        if (matches(raw, "bot|spider", ignoreCase = true)) return clip(raw)
        val browser = detectTool(raw) ?: detectBrowser(raw)
        val os = detectOs(raw)
        if (browser.isNotEmpty() && os.isNotEmpty()) return "$browser on $os"
        if (browser.isNotEmpty()) return browser
        if (os.isNotEmpty()) return os
        return clip(raw)
    }

    private fun clip(raw: String): String = if (raw.length <= MAX_RAW) raw else raw.take(MAX_RAW - 1) + "…"

    /**
     * First capture of [pattern]: null when it doesn't match, "" when the group took no part (a token without a
     * version) — "not this client" vs "this client, version unknown".
     */
    private fun capture(ua: String, pattern: String, ignoreCase: Boolean = false): String? {
        val re = if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        val m = re.find(ua) ?: return null
        return if (m.groups.size > 1) m.groups[1]?.value ?: "" else ""
    }

    private fun matches(ua: String, pattern: String, ignoreCase: Boolean = false) = capture(ua, pattern, ignoreCase) != null

    private fun named(name: String, version: String) = if (version.isEmpty()) name else "$name $version"

    private data class Tool(val name: String, val pattern: String, val ignoreCase: Boolean)

    /** Non-browser clients, versions in full; `HeadlessChrome` before Chrome, whose token it contains. */
    private val tools = listOf(
        Tool("curl", """\bcurl/([\d.]+)""", true),
        Tool("Wget", """\bWget/([\d.]+)""", true),
        Tool("python-requests", """\bpython-requests/([\d.]+)""", true),
        Tool("Postman", """\bPostmanRuntime/([\d.]+)""", true),
        Tool("Playwright", """\bPlaywright(?:/([\d.]+))?""", true),
        Tool("Headless Chrome", """\bHeadlessChrome(?:/(\d+))?""", false),
    )

    private fun detectTool(ua: String): String? {
        for (t in tools) capture(ua, t.pattern, t.ignoreCase)?.let { return named(t.name, it) }
        return null
    }

    /** Browser and major version, most specific first: every Chromium fork keeps `Chrome/`, and Chrome keeps `Safari/`. */
    private val browsers = listOf(
        "Edge" to """\bEdg(?:e|A|iOS)?(?:/(\d+))?""",
        "Opera" to """\bOPR(?:/(\d+))?""",
        "Samsung Internet" to """\bSamsungBrowser(?:/(\d+))?""",
        "Chrome" to """\b(?:Chrome|CriOS)(?:/(\d+))?""",
        "Firefox" to """\b(?:Firefox|FxiOS)(?:/(\d+))?""",
    )

    private fun detectBrowser(ua: String): String {
        for ((name, pattern) in browsers) capture(ua, pattern)?.let { return named(name, it) }
        if (matches(ua, """\bSafari\b""")) {
            // The product version ("18.4") is in `Version/`; drop a pointless trailing ".0".
            val version = capture(ua, """\bVersion/(\d+(?:\.\d+)?)""") ?: ""
            return named("Safari", version.replace(Regex("""\.0$"""), ""))
        }
        return ""
    }

    /** Platform, narrowest first: iPhones say "like Mac OS X", Android says "Linux". */
    private fun detectOs(ua: String): String {
        if (matches(ua, """\bWindows\b""")) return "Windows"
        if (matches(ua, """\b(?:iPhone|iPad|iPod)\b""")) return named("iOS", capture(ua, """\bOS (\d+)""") ?: "")
        if (matches(ua, """\bMac OS X\b|\bMacintosh\b""")) return "macOS"
        if (matches(ua, """\bAndroid\b""")) return named("Android", capture(ua, """\bAndroid (\d+(?:\.\d+)?)""") ?: "")
        if (matches(ua, """\bCrOS\b""")) return "ChromeOS"
        capture(ua, """\b(FreeBSD|OpenBSD|NetBSD)\b""")?.takeIf { it.isNotEmpty() }?.let { return it }
        if (matches(ua, """\bLinux\b""")) return "Linux"
        return ""
    }
}
