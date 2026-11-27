package com.jppelt.muditatimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Plays the end-of-timer alarm continuously until [stop] is called.
 *
 * Primary path: the bundled res/raw/alarm.wav, looped through MediaPlayer on the
 * alarm audio stream. Fallback path (only if MediaPlayer cannot start): the
 * ToneGenerator beep used before 1.6.0, repeated until stopped.
 *
 * The tone is attenuated in-app by [ALARM_GAIN] and otherwise plays at the
 * phone's alarm volume (which the Kompakt does not appear to let the user
 * lower, hence the in-app gain). Setting
 * [RING_AT_MAX_VOLUME] to true instead forces maximum while ringing and puts the
 * user's setting back on stop; on the Kompakt that proved far too loud.
 */
internal class AlarmPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val audio      = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler    = Handler(Looper.getMainLooper())

    private var mediaPlayer:  MediaPlayer? = null
    private var toneGen:      ToneGenerator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var started = false

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start() {
        if (started) return
        started = true
        raiseVolume()
        requestFocus()
        if (!startMediaPlayer()) startToneFallback()
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(toneRunnable)
        releaseMediaPlayer()
        try { toneGen?.release() } catch (_: Exception) {}
        toneGen = null
        abandonFocus()
        restoreVolume(appContext)
    }

    // ── MediaPlayer (primary) ────────────────────────────────────────────────

    private fun startMediaPlayer(): Boolean {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(alarmAttributes)
            appContext.resources.openRawResourceFd(R.raw.alarm).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.isLooping = true
            mp.setVolume(ALARM_GAIN, ALARM_GAIN)
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra, switching to tone fallback")
                releaseMediaPlayer()
                if (started) startToneFallback()
                true
            }
            mp.prepare()
            mp.start()
            mediaPlayer = mp
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer failed, using tone fallback: $e")
            try { mp.release() } catch (_: Exception) {}
            false
        }
    }

    private fun releaseMediaPlayer() {
        val mp = mediaPlayer ?: return
        mediaPlayer = null
        try { mp.stop() } catch (_: Exception) {}
        try { mp.release() } catch (_: Exception) {}
    }

    // ── ToneGenerator (fallback) ─────────────────────────────────────────────

    private val toneRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            try { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_ON_MS) } catch (_: Exception) {}
            handler.postDelayed(this, TONE_PERIOD_MS)
        }
    }

    private fun startToneFallback() {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            handler.post(toneRunnable)
        } catch (e: Exception) {
            Log.w(TAG, "Tone fallback failed, alarm is silent: $e")
        }
    }

    // ── Audio focus ──────────────────────────────────────────────────────────

    private fun requestFocus() {
        try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(alarmAttributes)
                .build()
            audio.requestAudioFocus(request)
            focusRequest = request
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed: $e")
        }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        try { audio.abandonAudioFocusRequest(request) } catch (_: Exception) {}
    }

    // ── Volume ───────────────────────────────────────────────────────────────

    private fun raiseVolume() {
        if (!RING_AT_MAX_VOLUME) return
        try {
            val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val max     = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (current < max) {
                // Persisted, not just held in memory, so the user's setting can be
                // put back even if the process dies while the alarm is ringing.
                val prefs = prefs(appContext)
                if (!prefs.contains(PREF_SAVED_ALARM_VOLUME)) {
                    prefs.edit().putInt(PREF_SAVED_ALARM_VOLUME, current).apply()
                }
                audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not raise alarm volume: $e")
        }
    }

    companion object {
        private const val TAG = "AlarmPlayer"

        /** false: use the phone's alarm volume. true: force full alarm volume, then restore. */
        private const val RING_AT_MAX_VOLUME = false

        /**
         * Loudness of the bundled tone, applied inside the app on top of whatever the
         * alarm stream is set to. 1.0 is full scale (far too loud on the Kompakt);
         * each halving sounds clearly quieter. Tune by ear on the device.
         */
        private const val ALARM_GAIN = 0.3f

        private const val TONE_ON_MS     = 900
        private const val TONE_PERIOD_MS = 1_300L

        private const val PREFS_NAME              = "mudita_prefs"
        private const val PREF_SAVED_ALARM_VOLUME = "saved_alarm_volume"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** Puts back the alarm volume the user had before the alarm raised it. Safe to call anytime. */
        fun restoreVolume(context: Context) {
            val prefs = prefs(context)
            val saved = prefs.getInt(PREF_SAVED_ALARM_VOLUME, -1)
            if (saved < 0) return
            try {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore alarm volume: $e")
            }
            prefs.edit().remove(PREF_SAVED_ALARM_VOLUME).apply()
        }
    }
}
