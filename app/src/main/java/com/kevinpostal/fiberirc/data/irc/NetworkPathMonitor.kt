package com.kevinpostal.fiberirc.data.irc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** Network reachability as the reconnect logic needs it (ports `ios/IRC/NetworkPathMonitor.swift`; injectable for tests). */
interface PathMonitoring {
    /** Whether a usable network path exists. True until the first report (optimistic). */
    val isSatisfied: Boolean

    /** Main thread: the path became (un)satisfied, or switched network while satisfied. */
    var onChange: ((Boolean) -> Unit)?
    fun start()
    fun cancel()
}

/**
 * `ConnectivityManager.registerDefaultNetworkCallback` on the main looper. [start] records the current default
 * network as the baseline; later reports call `onChange` when satisfaction flips or the default network changes
 * while satisfied (Wi-Fi ↔ cellular, or a different Wi-Fi network: on Android a socket is bound to its network, so
 * either change can leave it dead).
 */
class NetworkPathMonitor(context: Context) : PathMonitoring {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    override var isSatisfied = true
        private set
    private var key: String? = null
    private var hasBaseline = false
    private var current: Network? = null
    private var registered = false
    override var onChange: ((Boolean) -> Unit)? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // API 26+: always followed at once by onCapabilitiesChanged, which carries the state.
        override fun onAvailable(network: Network) {
            current = network
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            current = network
            update(network, caps)
        }

        override fun onLost(network: Network) {
            if (network != current) return
            current = null
            update(null, null)
        }
    }

    override fun start() {
        if (registered) return
        val active = cm.activeNetwork
        current = active
        update(active, active?.let { cm.getNetworkCapabilities(it) }, baseline = true)
        cm.registerDefaultNetworkCallback(callback, main)
        registered = true
    }

    override fun cancel() {
        if (!registered) return
        registered = false
        cm.unregisterNetworkCallback(callback)
    }

    private fun update(network: Network?, caps: NetworkCapabilities?, baseline: Boolean = false) {
        val satisfied = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val k = if (network != null && caps != null) "$network|${transport(caps)}" else null
        val changed = hasBaseline && !baseline && (satisfied != isSatisfied || (satisfied && k != key))
        isSatisfied = satisfied
        key = k
        hasBaseline = true
        if (changed) onChange?.invoke(satisfied)
    }

    private fun transport(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }
}

/**
 * App foreground state as the client needs it: iOS's `willEnterForeground` / `didEnterBackground` notifications and
 * `applicationState == .active`. Injectable for tests.
 */
interface AppForegroundMonitoring {
    /** Started (visible): between `onChange(true)` and `onChange(false)`. */
    val isForeground: Boolean

    /** Resumed (interactive): iOS `UIApplication.State.active`. */
    val isActive: Boolean

    /** Main thread: `true` on entering the foreground, `false` on entering the background. */
    var onChange: ((Boolean) -> Unit)?
    fun start()
    fun cancel()
}

/** [ProcessLifecycleOwner]: ON_START ≈ `willEnterForeground`, ON_STOP ≈ `didEnterBackground`. Main thread only. */
class ProcessForegroundMonitor : AppForegroundMonitoring, DefaultLifecycleObserver {
    private val lifecycle: Lifecycle get() = ProcessLifecycleOwner.get().lifecycle
    private var replaying = false
    private var registered = false
    override var onChange: ((Boolean) -> Unit)? = null

    override val isForeground: Boolean get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    override val isActive: Boolean get() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    override fun start() {
        if (registered) return
        registered = true
        // addObserver replays the events up to the current state; those are the baseline, not transitions.
        replaying = true
        lifecycle.addObserver(this)
        replaying = false
    }

    override fun cancel() {
        if (!registered) return
        registered = false
        lifecycle.removeObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!replaying) onChange?.invoke(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!replaying) onChange?.invoke(false)
    }
}
