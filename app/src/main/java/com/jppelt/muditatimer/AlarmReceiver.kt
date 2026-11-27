package com.jppelt.muditatimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Bridge the gap between this broadcast returning and the service taking
        // its own wakelock. Released automatically by the timeout.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).acquire(WAKE_LOCK_TIMEOUT_MS)

        // Normal path: hand off to TimerService, which rings until the user stops it.
        // The service is usually still running in the foreground from the countdown;
        // if the process was killed, an exact alarm is allowed to start it again.
        val ring = Intent(context, TimerService::class.java).setAction(TimerService.ACTION_ALARM_FIRED)
        try {
            context.startForegroundService(ring)
            return
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService refused from alarm: $e")
        }
        // Refused (possible only under the inexact-alarm fallback). If the service is
        // still alive in the foreground, a plain start reaches it.
        try {
            context.startService(ring)
            return
        } catch (e: Exception) {
            Log.w(TAG, "startService refused from alarm, ringing briefly here instead: $e")
        }

        // Last resort: no service can be started, so the process had been killed.
        // Ring for the few seconds a receiver is allowed to stay alive, so the timer
        // is never fully silent.
        val pendingResult = goAsync()
        val player = AlarmPlayer(context)
        player.start()
        Handler(Looper.getMainLooper()).postDelayed({
            player.stop()
            pendingResult.finish()
        }, FALLBACK_RING_MS)

        context.sendBroadcast(Intent(TimerService.ACTION_FINISHED).apply {
            setPackage(context.packageName)
        })
    }

    companion object {
        private const val TAG                  = "AlarmReceiver"
        private const val FALLBACK_RING_MS     = 8_000L
        private const val WAKE_LOCK_TAG        = "muditatimer:alarm"
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
        private const val REQUEST_CODE         = 1001

        fun schedule(context: Context, endTimeMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                val useExact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                if (useExact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeMs, pendingIntent(context))
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeMs, pendingIntent(context))
                }
            } catch (e: SecurityException) {
                // Exact alarm refused at the OS level — fall back to an inexact
                // alarm so the timer still fires, just without guaranteed precision.
                android.util.Log.w("AlarmReceiver", "Exact alarm denied, falling back to inexact: $e")
                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeMs, pendingIntent(context))
                } catch (e2: Exception) {
                    android.util.Log.w("AlarmReceiver", "Inexact alarm also failed, no alarm scheduled: $e2")
                }
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
