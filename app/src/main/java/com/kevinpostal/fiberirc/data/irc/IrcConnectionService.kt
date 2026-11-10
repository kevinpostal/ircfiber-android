package com.kevinpostal.fiberirc.data.irc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kevinpostal.fiberirc.FiberIrcApp
import com.kevinpostal.fiberirc.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the signed-in IRC session out of Android's cached-app freezer while chat runs in the background.
 * The application owns the session; opening or recreating an activity never opens another set of sockets.
 * There is no boot receiver, automatic sign-in or sticky restart: force-stop stays stopped. Android can still
 * suspend network access in deep Doze; the existing liveness/reconnect path recovers when access returns.
 */
class IrcConnectionService : Service() {
    private val scope = MainScope()
    private val deps get() = (application as FiberIrcApp).deps
    private var manager: IrcSessionManager? = null
    private var clientsJob: Job? = null
    private var connected = -1
    private var total = -1
    private val listener = object : IrcClientListener {
        override fun onStateChanged(client: IrcClient, state: IrcState, error: Throwable?) = updateNotification()
    }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "IRC connections", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps your signed-in chat connections running in the background"
                setShowBadge(false)
            },
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Connecting to your networks…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
        )
        scope.launch {
            deps.session.collect { state ->
                val next = (state as? IrcSessionState.Session)?.manager
                if (next == null) {
                    stopSelf()
                } else if (next !== manager) {
                    manager?.removeListener(listener)
                    clientsJob?.cancel()
                    manager = next
                    next.addListener(listener)
                    clientsJob = launch { next.clients.collect { updateNotification() } }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (deps.session.value !is IrcSessionState.Session) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val owned = manager
        manager?.removeListener(listener)
        manager = null
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // An explicitly stopped service must not leave a hidden session reconnecting in a cached process.
        if (owned != null && (deps.session.value as? IrcSessionState.Session)?.manager === owned) {
            deps.stopConnections("IRC connections paused — reopen the app to reconnect. Voice ownership is independent.")
        }
        super.onDestroy()
    }

    private fun updateNotification() {
        val clients = manager?.clients?.value ?: return
        val count = clients.count { it.state == IrcState.CONNECTED }
        if (connected == count && total == clients.size) return
        connected = count
        total = clients.size
        val text = if (total == 0) "No networks configured" else "Connected to $connected of $total networks"
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("IRC Fiber")
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL = "irc_connections"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, IrcConnectionService::class.java))
        }

    }
}
