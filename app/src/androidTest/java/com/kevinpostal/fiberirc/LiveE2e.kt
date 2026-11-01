package com.kevinpostal.fiberirc

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter

/** An opt-in test class against production: it runs only when the runner argument [arg] is "true". */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class LiveE2e(val arg: String)

/**
 * The default runner filter (build.gradle.kts). It leaves [LiveE2e] classes out of a plain `connectedDebugAndroidTest`
 * unless their argument is passed: an `assume` alone isn't enough, the connected report counts assumption failures as
 * failed tests, so the task would fail. With a `testlab` argument, `pkg.Class#method` ids joined by `+`
 * (tools/testlab/android.py), only those tests run: Gradle-managed devices keep just the first entry of a
 * comma-separated `class` argument, and the device shell splits a `tests_regex` at `|`.
 * Ported from vanlife's androidTest `LiveE2e.kt`.
 */
class LiveE2eFilter : Filter() {
    private val selected: Set<String>? by lazy {
        InstrumentationRegistry.getArguments().getString("testlab")?.split('+')?.filter(String::isNotEmpty)?.toSet()
    }

    override fun shouldRun(description: Description): Boolean {
        val live = description.testClass?.getAnnotation(LiveE2e::class.java)
        if (live != null && InstrumentationRegistry.getArguments().getString(live.arg) != "true") return false
        val tests = selected ?: return true
        if (description.isTest) return "${description.className}#${description.methodName}" in tests
        return description.children.any(::shouldRun)
    }

    override fun describe() = "live tests only with their runner argument; TestLab's selection"
}
