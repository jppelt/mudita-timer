package com.jppelt.muditatimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class TimerService : Service() {

    // ── Mode / state ─────────────────────────────────────────────────────────

    private enum class Mode { TIMER, STOPWATCH }
    private enum class TickState { IDLE, RUNNING, PAUSED }

    private var mode      = Mode.TIMER
    private var tickState = TickState.IDLE

    // Timer
    private var durationMs          = 0L
    private var endTimeMs           = 0L
    private var pausedRemainingMs   = 0L

    // Stopwatch
    private var startTimeMs         = 0L
    private var pausedElapsedMs     = 0L

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TIMER -> {
                durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                if (durationMs <= 0) return START_NOT_STICKY
                mode       = Mode.TIMER
                endTimeMs  = System.currentTimeMillis() + durationMs
                tickState  = TickState.RUNNING
                AlarmReceiver.schedule(this, endTimeMs)
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_timer_running)))
                scheduleTick()
            }
            ACTION_START_STOPWATCH -> {
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
                } else {
                    pausedElapsedMs = System.currentTimeMillis() - startTimeMs
                }
            }
            ACTION_RESUME -> {
                if (tickState != TickState.PAUSED) return START_NOT_STICKY
                if (mode == Mode.TIMER) {
                    endTimeMs = System.currentTimeMillis() + pausedRemainingMs
                    AlarmReceiver.schedule(this, endTimeMs)
                } else {
                    startTimeMs = System.currentTimeMillis() - pausedElapsedMs
                }
                tickState = TickState.RUNNING
                scheduleTick()
            }
            ACTION_RESET -> {
                cancelTick()
                AlarmReceiver.cancel(this)
                tickState = TickState.IDLE
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_STOP -> {
                cancelTick()
                AlarmReceiver.cancel(this)
                tickState = TickState.IDLE
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_REQUEST_STATE -> broadcastCurrentState()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelTick()
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
                        onTimerFinished()
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

    // ── Timer finish ─────────────────────────────────────────────────────────

    private fun onTimerFinished() {
        tickState = TickState.IDLE
        // Cancel the AlarmManager alarm — it may have already fired (no-op) or
        // may still be pending if the tick loop noticed zero first (rare).
        // Sound is played by AlarmReceiver; the service only handles display/state.
        AlarmReceiver.cancel(this)
        sendBroadcast(Intent(ACTION_FINISHED).apply { setPackage(packageName) })
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        const val ACTION_START_TIMER      = "com.jppelt.muditatimer.START_TIMER"
        const val ACTION_START_STOPWATCH  = "com.jppelt.muditatimer.START_STOPWATCH"
        const val ACTION_PAUSE            = "com.jppelt.muditatimer.PAUSE"
        const val ACTION_RESUME           = "com.jppelt.muditatimer.RESUME"
        const val ACTION_RESET            = "com.jppelt.muditatimer.RESET"
        const val ACTION_STOP             = "com.jppelt.muditatimer.STOP"
        const val ACTION_REQUEST_STATE    = "com.jppelt.muditatimer.REQUEST_STATE"

        const val ACTION_TICK             = "com.jppelt.muditatimer.TICK"
        const val ACTION_FINISHED         = "com.jppelt.muditatimer.FINISHED"
        const val ACTION_STATE_PAUSED     = "com.jppelt.muditatimer.STATE_PAUSED"

        const val EXTRA_DURATION_MS       = "duration_ms"
        const val EXTRA_MODE              = "mode"
        const val EXTRA_MS                = "ms"

        private const val CHANNEL_ID      = "mudita_timer"
        private const val NOTIF_ID        = 1
        private const val TICK_MS         = 1_000L
    }
}
