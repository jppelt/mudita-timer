package com.jppelt.muditatimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class TimerService : Service() {

    // ── Mode / state ─────────────────────────────────────────────────────────

    private enum class Mode { TIMER, STOPWATCH }
    private enum class TickState { IDLE, RUNNING, PAUSED, RINGING }

    private var mode      = Mode.TIMER
    private var tickState = TickState.IDLE

    // Timer
    private var durationMs          = 0L
    private var endTimeMs           = 0L
    private var pausedRemainingMs   = 0L

    // Stopwatch
    private var startTimeMs         = 0L
    private var pausedElapsedMs     = 0L

    // Alarm
    private var alarmPlayer: AlarmPlayer? = null
    private var ringWakeLock: PowerManager.WakeLock? = null

    // ── Android objects ──────────────────────────────────────────────────────

    private val handler   = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null) }
        )
        // High importance so the "time's up" notification can turn the screen on and
        // show over the lock screen. Silent: the service plays the alarm sound itself.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                RING_CHANNEL_ID,
                getString(R.string.notification_channel_ring_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        // A fresh service instance is never mid-ring. If an earlier ring was cut off
        // by a process kill, put the user's alarm volume back now.
        AlarmPlayer.restoreVolume(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TIMER -> {
                durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                if (durationMs <= 0) return START_NOT_STICKY
                stopRinging()
                notificationManager.cancel(MISSED_NOTIF_ID)
                mode       = Mode.TIMER
                endTimeMs  = System.currentTimeMillis() + durationMs
                tickState  = TickState.RUNNING
                saveEndTime(endTimeMs)
                AlarmReceiver.schedule(this, endTimeMs)
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_timer_running)))
                scheduleTick()
            }
            ACTION_START_STOPWATCH -> {
                stopRinging()
                mode        = Mode.STOPWATCH
                startTimeMs = System.currentTimeMillis()
                pausedElapsedMs = 0L
                tickState   = TickState.RUNNING
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_stopwatch_running)))
                scheduleTick()
            }
            ACTION_PAUSE -> {
                if (tickState != TickState.RUNNING) return START_NOT_STICKY
                cancelTick()
                tickState = TickState.PAUSED
                if (mode == Mode.TIMER) {
                    pausedRemainingMs = endTimeMs - System.currentTimeMillis()
                    if (pausedRemainingMs < 0) pausedRemainingMs = 0
                    AlarmReceiver.cancel(this)
                    clearEndTime()
                } else {
                    pausedElapsedMs = System.currentTimeMillis() - startTimeMs
                }
            }
            ACTION_RESUME -> {
                if (tickState != TickState.PAUSED) return START_NOT_STICKY
                if (mode == Mode.TIMER) {
                    endTimeMs = System.currentTimeMillis() + pausedRemainingMs
                    saveEndTime(endTimeMs)
                    AlarmReceiver.schedule(this, endTimeMs)
                } else {
                    startTimeMs = System.currentTimeMillis() - pausedElapsedMs
                }
                tickState = TickState.RUNNING
                scheduleTick()
            }
            ACTION_RESET, ACTION_STOP, ACTION_DISMISS -> {
                cancelTick()
                AlarmReceiver.cancel(this)
                stopRinging()
                clearEndTime()
                // Also covers a "ringing" flag left behind by a process kill mid-ring.
                clearRingingFlag()
                tickState = TickState.IDLE
                stopForeground(STOP_FOREGROUND_REMOVE)
                // startId form: do not tear the service down under a newer queued command.
                stopSelf(startId)
            }
            ACTION_ALARM_FIRED -> onAlarmFired(startId)
            ACTION_REQUEST_STATE -> {
                if (tickState != TickState.RINGING) clearRingingFlag()
                broadcastCurrentState()
                if (tickState == TickState.IDLE) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelTick()
        stopRinging()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── Tick loop ────────────────────────────────────────────────────────────

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (tickState != TickState.RUNNING) return
            when (mode) {
                Mode.TIMER -> {
                    val remaining = endTimeMs - System.currentTimeMillis()
                    if (remaining <= 0) {
                        broadcastTick(Mode.TIMER, 0L)
                        startRinging()
                    } else {
                        broadcastTick(Mode.TIMER, remaining)
                        handler.postDelayed(this, TICK_MS)
                    }
                }
                Mode.STOPWATCH -> {
                    val elapsed = System.currentTimeMillis() - startTimeMs
                    broadcastTick(Mode.STOPWATCH, elapsed)
                    handler.postDelayed(this, TICK_MS)
                }
            }
        }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun cancelTick() {
        handler.removeCallbacks(tickRunnable)
    }

    // ── Broadcasts ───────────────────────────────────────────────────────────

    private fun broadcastTick(tickMode: Mode, ms: Long) {
        sendBroadcast(Intent(ACTION_TICK).apply {
            setPackage(packageName)
            putExtra(EXTRA_MODE, tickMode.name)
            putExtra(EXTRA_MS, ms)
        })
    }

    private fun broadcastCurrentState() {
        if (tickState == TickState.IDLE) return
        if (tickState == TickState.RINGING) {
            broadcastFinished()
            return
        }
        val ms = when {
            mode == Mode.TIMER && tickState == TickState.RUNNING ->
                (endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
            mode == Mode.TIMER && tickState == TickState.PAUSED  -> pausedRemainingMs
            mode == Mode.STOPWATCH && tickState == TickState.RUNNING ->
                System.currentTimeMillis() - startTimeMs
            mode == Mode.STOPWATCH && tickState == TickState.PAUSED -> pausedElapsedMs
            else -> return
        }
        val action = if (tickState == TickState.PAUSED) ACTION_STATE_PAUSED else ACTION_TICK
        sendBroadcast(Intent(action).apply {
            setPackage(packageName)
            putExtra(EXTRA_MODE, mode.name)
            putExtra(EXTRA_MS, ms)
        })
    }

    private fun broadcastFinished() {
        sendBroadcast(Intent(ACTION_FINISHED).apply {
            setPackage(packageName)
            putExtra(EXTRA_RINGING, tickState == TickState.RINGING)
        })
    }

    // ── Timer finish / alarm ─────────────────────────────────────────────────

    /**
     * The AlarmManager alarm fired. AlarmReceiver delivers this with
     * startForegroundService(), so every branch must end up calling startForeground().
     */
    private fun onAlarmFired(startId: Int) {
        val now = System.currentTimeMillis()
        when {
            // Already ringing (the tick loop got to zero first): nothing to do.
            tickState == TickState.RINGING ->
                startForeground(NOTIF_ID, buildRingNotification())

            // Normal case: the countdown this service is running has reached zero.
            tickState == TickState.RUNNING && mode == Mode.TIMER &&
                now >= endTimeMs - ALARM_SLACK_MS ->
                startRinging()

            // The process was killed mid-countdown and the alarm restarted it. The
            // in-memory state is gone, so trust the persisted end time.
            tickState == TickState.IDLE && savedEndTime().let { it > 0L && now >= it - ALARM_SLACK_MS } -> {
                mode = Mode.TIMER
                startRinging()
            }

            // Stale alarm for a timer that was reset: satisfy the foreground contract and leave.
            tickState == TickState.IDLE -> {
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_timer_running)))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }

            // Stale alarm while paused or while the stopwatch runs: keep the current notification.
            else -> startForeground(
                NOTIF_ID,
                buildNotification(
                    getString(
                        if (mode == Mode.STOPWATCH) R.string.notification_stopwatch_running
                        else R.string.notification_timer_running
                    )
                )
            )
        }
    }

    /**
     * Single entry point for "the timer reached zero". Called by whichever of the
     * tick loop or the AlarmManager alarm notices first; the second call is a no-op.
     * Rings until the user stops it (or MAX_RING_MS passes).
     */
    private fun startRinging() {
        if (tickState == TickState.RINGING) return
        cancelTick()
        tickState = TickState.RINGING
        AlarmReceiver.cancel(this)
        clearEndTime()
        prefs().edit().putBoolean(PREF_RINGING, true).apply()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        ringWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, RING_WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_RING_MS + 10_000L)
        }

        startForeground(NOTIF_ID, buildRingNotification())
        alarmPlayer = AlarmPlayer(this).also { it.start() }
        handler.postDelayed(autoSilenceRunnable, MAX_RING_MS)
        broadcastFinished()
    }

    /** Stops the sound and releases everything the ring holds. Safe to call in any state. */
    private fun stopRinging() {
        handler.removeCallbacks(autoSilenceRunnable)
        alarmPlayer?.stop()
        alarmPlayer = null
        ringWakeLock?.let { if (it.isHeld) it.release() }
        ringWakeLock = null
        if (tickState != TickState.RINGING) return
        tickState = TickState.IDLE
        clearRingingFlag()   // also tells the UI the ring has stopped
    }

    /** Nobody stopped the alarm for MAX_RING_MS: go quiet and leave a note. */
    private val autoSilenceRunnable = Runnable {
        if (tickState != TickState.RINGING) return@Runnable
        stopRinging()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.notify(MISSED_NOTIF_ID, buildMissedNotification())
        stopSelf()
    }

    // ── Persisted state (read by MainActivity when it comes back) ────────────

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun saveEndTime(ms: Long) = prefs().edit().putLong(PREF_END_TIME_MS, ms).apply()
    private fun clearEndTime()        = prefs().edit().remove(PREF_END_TIME_MS).apply()
    private fun savedEndTime(): Long  = prefs().getLong(PREF_END_TIME_MS, 0L)

    private fun clearRingingFlag() {
        if (!prefs().getBoolean(PREF_RINGING, false)) return
        prefs().edit().remove(PREF_RINGING).apply()
        sendBroadcast(Intent(ACTION_RING_STOPPED).apply { setPackage(packageName) })
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildRingNotification(): Notification {
        val dismiss = PendingIntent.getService(
            this,
            REQUEST_DISMISS,
            Intent(this, TimerService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = openAppIntent()
        return Notification.Builder(this, RING_CHANNEL_ID)
            .setContentTitle(getString(R.string.time_up))
            .setContentText(getString(R.string.notification_ring_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            // Wakes the screen and shows the "Stop alarm" screen over the lock screen.
            .setFullScreenIntent(open, true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.stop_alarm), dismiss).build()
            )
            .build()
    }

    private fun buildMissedNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.time_up))
            .setContentText(getString(R.string.notification_missed_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        const val ACTION_START_TIMER      = "com.jppelt.muditatimer.START_TIMER"
        const val ACTION_START_STOPWATCH  = "com.jppelt.muditatimer.START_STOPWATCH"
        const val ACTION_PAUSE            = "com.jppelt.muditatimer.PAUSE"
        const val ACTION_RESUME           = "com.jppelt.muditatimer.RESUME"
        const val ACTION_RESET            = "com.jppelt.muditatimer.RESET"
        const val ACTION_STOP             = "com.jppelt.muditatimer.STOP"
        const val ACTION_DISMISS          = "com.jppelt.muditatimer.DISMISS"
        const val ACTION_ALARM_FIRED      = "com.jppelt.muditatimer.ALARM_FIRED"
        const val ACTION_REQUEST_STATE    = "com.jppelt.muditatimer.REQUEST_STATE"

        const val ACTION_TICK             = "com.jppelt.muditatimer.TICK"
        const val ACTION_FINISHED         = "com.jppelt.muditatimer.FINISHED"
        const val ACTION_RING_STOPPED     = "com.jppelt.muditatimer.RING_STOPPED"
        const val ACTION_STATE_PAUSED     = "com.jppelt.muditatimer.STATE_PAUSED"

        const val EXTRA_DURATION_MS       = "duration_ms"
        const val EXTRA_MODE              = "mode"
        const val EXTRA_MS                = "ms"
        const val EXTRA_RINGING           = "ringing"

        // Shared with MainActivity, which reads these when it returns to the foreground.
        const val PREFS_NAME              = "mudita_prefs"
        const val PREF_END_TIME_MS        = "end_time_ms"
        const val PREF_RINGING            = "ringing"

        const val MISSED_NOTIF_ID         = 2

        /** Safety cap: an unanswered alarm goes quiet after this long. */
        private const val MAX_RING_MS     = 10 * 60 * 1_000L

        /** How early an alarm broadcast may arrive and still count as "the timer ended". */
        private const val ALARM_SLACK_MS  = 1_500L

        private const val CHANNEL_ID      = "mudita_timer"
        private const val RING_CHANNEL_ID = "mudita_timer_ring"
        private const val NOTIF_ID        = 1
        private const val TICK_MS         = 1_000L
        private const val REQUEST_OPEN_APP = 2001
        private const val REQUEST_DISMISS  = 2002
        private const val RING_WAKE_LOCK_TAG = "muditatimer:ring"
    }
}
