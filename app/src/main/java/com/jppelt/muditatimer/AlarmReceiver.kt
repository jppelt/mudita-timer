package com.jppelt.muditatimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync() keeps the process alive past onReceive() so the full burst
        // sequence and delayed release complete before Android reclaims the receiver.
        val pendingResult = goAsync()

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)

        val handler = Handler(Looper.getMainLooper())

        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)

            // 5 bursts: 900ms on, 400ms gap → 6 500ms total. Release 500ms after last burst.
            for (i in 0 until BURST_COUNT) {
                handler.postDelayed(
                    { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, BURST_ON_MS.toInt()) },
                    i * (BURST_ON_MS + BURST_GAP_MS)
                )
            }

            handler.postDelayed({
                toneGen.release()
                if (wakeLock.isHeld) wakeLock.release()
                pendingResult.finish()
            }, RELEASE_DELAY_MS)

        } catch (_: Exception) {
            if (wakeLock.isHeld) wakeLock.release()
            pendingResult.finish()
        }

        // Notify the UI if it is currently visible.
        context.sendBroadcast(Intent(TimerService.ACTION_FINISHED).apply {
            setPackage(context.packageName)
        })
    }

    companion object {
        private const val BURST_COUNT          = 5
        private const val BURST_ON_MS          = 900L   // tone on per burst
        private const val BURST_GAP_MS         = 400L   // silence between bursts
        // 5 × (900 + 400) = 6 500ms; 500ms buffer after the last burst
        private const val RELEASE_DELAY_MS     = 7_000L
        private const val WAKE_LOCK_TAG        = "muditatimer:alarm"
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
        private const val REQUEST_CODE         = 1001

        fun schedule(context: Context, endTimeMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                endTimeMs,
                pendingIntent(context)
            )
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
