package com.kevinpostal.fiberirc.data.voice

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kevinpostal.fiberirc.FiberIrcApp
import com.kevinpostal.fiberirc.MainActivity
import com.kevinpostal.fiberirc.R

/** No boot/start-sticky path: only a visible Join may create a live, generation-bound service. */
class VoiceConnectionService : Service() {
    private val owner get() = (application as FiberIrcApp).deps.voice
    private var instance: String? = null
    private var epoch = -1L
    private val qualification = VoiceForegroundQualification()
    private var finishing = false
    private var media: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.voice_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.voice_channel_description); setShowBadge(false)
            })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(INSTANCE)
        val generation = intent?.getLongExtra(GENERATION, -1L) ?: -1L
        if (id == null || id != owner.instanceId || generation != owner.actionGeneration || owner.state.value.session?.ownedByThisClient != true) {
            if (instance == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != START && instance == null) { stopSelf(startId); return START_NOT_STICKY }
        if (intent?.action == START) {
            instance = id; epoch = generation
            // startForeground completes synchronously before the owner is allowed to connect.
            try {
                promote(false)
                if (!owner.serviceAttached(this, id, epoch)) { finish(); return START_NOT_STICKY }
                createMediaSession(); update()
            } catch (_: RuntimeException) { finish(); owner.serviceLost(this) }
        } else if (instance == id && epoch == generation) {
            owner.action(id, generation, intent?.action.orEmpty())
        }
        return START_NOT_STICKY
    }
    internal fun retarget(id: String, generation: Long) { instance = id; epoch = generation; update() }
    fun promote(microphone: Boolean) {
        // Keep a legitimate microphone qualification until Leave; never downgrade/restart in background.
        qualification.promote(microphone, owner.isVisible,
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            (if (Build.VERSION.SDK_INT >= 30 && qualification.microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) else 0
        ServiceCompat.startForeground(this, NOTIFICATION, notification(), type)
    }
    private fun createMediaSession() {
        media?.release()
        media = MediaSession(this, "Fiber Voice").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setSessionActivity(openIntent())
            setCallback(object : MediaSession.Callback() {
                override fun onPause() { if (!owner.state.value.muted) owner.action(instance, epoch, "mute") }
                override fun onStop() = owner.action(instance, epoch, "leave")
                override fun onCustomAction(action: String, extras: Bundle?) = owner.action(instance, epoch, action)
            })
            isActive = true
        }
    }
    fun update() {
        if (finishing) return
        val s = owner.state.value
        val title = s.snapshot?.rooms?.firstOrNull { it.id == s.session?.roomId }?.name ?: getString(R.string.voice_audio)
        media?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.voice_audio)).build())
        media?.setPlaybackState(PlaybackState.Builder()
            .setState(if (s.phase == "connected") PlaybackState.STATE_PLAYING else PlaybackState.STATE_CONNECTING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(PlaybackState.ACTION_STOP)
            .addCustomAction("mute", getString(if (s.muted) R.string.voice_unmute else R.string.voice_mute), android.R.drawable.ic_btn_speak_now)
            .addCustomAction("deafen", getString(if (s.deafened) R.string.voice_undeafen else R.string.voice_deafen), android.R.drawable.ic_lock_silent_mode)
            .addCustomAction("leave", getString(R.string.voice_leave), android.R.drawable.ic_menu_close_clear_cancel).build())
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    private fun notification(): Notification {
        val s = owner.state.value
        val name = s.snapshot?.rooms?.firstOrNull { it.id == s.session?.roomId }?.name ?: getString(R.string.voice_audio)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(name)
            .setContentText("${s.phase} · ${getString(if (s.muted) R.string.voice_muted else R.string.voice_speaking)}")
            .setContentIntent(openIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.voice_audio)).setContentText(getString(R.string.voice_ongoing)).build())
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .addAction(android.R.drawable.ic_btn_speak_now, getString(if (s.muted) R.string.voice_unmute else R.string.voice_mute), action("mute"))
            .addAction(android.R.drawable.ic_lock_silent_mode, getString(if (s.deafened) R.string.voice_undeafen else R.string.voice_deafen), action("deafen"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.voice_leave), action("leave"))
            .apply { media?.let { setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1, 2)) } }
            .build()
    }
    private fun action(action: String): PendingIntent {
        // An unqualified first unmute is an Activity intent, not a background microphone service start.
        if (action == "mute" && owner.state.value.muted && (!qualification.microphone ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)) return openIntent()
        return PendingIntent.getService(this, 0,
            Intent(this, VoiceConnectionService::class.java).setAction(action)
                .setData(Uri.parse("fiberirc-voice://$instance/$epoch/$action"))
                .putExtra(INSTANCE, instance).putExtra(GENERATION, epoch), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun openIntent(): PendingIntent = PendingIntent.getActivity(this, 3,
        Intent(this, MainActivity::class.java).putExtra(OPEN_AUDIO, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun openApp() { openIntent().send() }
    fun finish() {
        if (finishing) return
        finishing = true; media?.isActive = false; media?.release(); media = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        media?.isActive = false; media?.release(); media = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        owner.serviceLost(this)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "voice_rooms"
        private const val NOTIFICATION = 4
        private const val INSTANCE = "voiceInstance"
        private const val GENERATION = "voiceGeneration"
        private const val START = "voiceStart"
        const val OPEN_AUDIO = "openAudio"
        fun start(context: Context, instance: String, generation: Long) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceConnectionService::class.java)
                .setAction(START).putExtra(INSTANCE, instance).putExtra(GENERATION, generation))
        }
    }
}
