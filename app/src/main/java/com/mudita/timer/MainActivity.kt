package com.mudita.timer

import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    // ── State ────────────────────────────────────────────────────────────────

    private enum class State { SETUP, RUNNING, PAUSED, DONE }

    private var state = State.SETUP
    private var durationMs = DEFAULT_DURATION_MS
    private var remainingMs = DEFAULT_DURATION_MS
    private var endTimeMs = 0L
    private var customMinutes = DEFAULT_CUSTOM_MIN

    // ── Android objects ──────────────────────────────────────────────────────

    private var timer: CountDownTimer? = null
    private var toneGen: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())

    // ── Views ────────────────────────────────────────────────────────────────

    private lateinit var viewSetup: View
    private lateinit var viewTimer: View
    private lateinit var viewDone: View
    private lateinit var tvCustomMin: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var btnPauseResume: Button

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        restoreState(savedInstanceState)
        wireListeners()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (state == State.RUNNING && endTimeMs > 0) {
            val left = endTimeMs - System.currentTimeMillis()
            if (left <= 0) onTimerFinished() else schedule(left)
        }
    }

    override fun onPause() {
        super.onPause()
        // endTimeMs lets us reconstruct the correct remaining time on resume.
        timer?.cancel()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(KEY_STATE, state.ordinal)
        out.putLong(KEY_DURATION, durationMs)
        out.putLong(KEY_REMAINING, remainingMs)
        out.putLong(KEY_END_TIME, endTimeMs)
        out.putInt(KEY_CUSTOM_MIN, customMinutes)
    }

    override fun onDestroy() {
        timer?.cancel()
        handler.removeCallbacksAndMessages(null)
        toneGen?.release()
        super.onDestroy()
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun bindViews() {
        viewSetup      = findViewById(R.id.viewSetup)
        viewTimer      = findViewById(R.id.viewTimer)
        viewDone       = findViewById(R.id.viewDone)
        tvCustomMin    = findViewById(R.id.tvCustomMin)
        tvCountdown    = findViewById(R.id.tvCountdown)
        btnPauseResume = findViewById(R.id.btnPauseResume)
    }

    private fun restoreState(bundle: Bundle?) {
        if (bundle != null) {
            state         = State.entries[bundle.getInt(KEY_STATE, State.SETUP.ordinal)]
            durationMs    = bundle.getLong(KEY_DURATION, DEFAULT_DURATION_MS)
            remainingMs   = bundle.getLong(KEY_REMAINING, DEFAULT_DURATION_MS)
            endTimeMs     = bundle.getLong(KEY_END_TIME, 0L)
            customMinutes = bundle.getInt(KEY_CUSTOM_MIN, DEFAULT_CUSTOM_MIN)
        }
        tvCustomMin.text = customMinutes.toString()
        showTime(remainingMs)
    }

    private fun wireListeners() {
        findViewById<Button>(R.id.btn5min).setOnClickListener  { beginTimer(5) }
        findViewById<Button>(R.id.btn10min).setOnClickListener { beginTimer(10) }
        findViewById<Button>(R.id.btn25min).setOnClickListener { beginTimer(25) }

        findViewById<Button>(R.id.btnMinus).setOnClickListener {
            if (customMinutes > 1) {
                customMinutes--
                tvCustomMin.text = customMinutes.toString()
            }
        }
        findViewById<Button>(R.id.btnPlus).setOnClickListener {
            if (customMinutes < 99) {
                customMinutes++
                tvCustomMin.text = customMinutes.toString()
            }
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { beginTimer(customMinutes) }

        btnPauseResume.setOnClickListener {
            when (state) {
                State.RUNNING -> pause()
                State.PAUSED  -> resume()
                else          -> Unit
            }
        }
        findViewById<Button>(R.id.btnReset).setOnClickListener      { resetToSetup() }
        findViewById<Button>(R.id.btnStartAgain).setOnClickListener { beginMs(durationMs) }
        findViewById<Button>(R.id.btnBack).setOnClickListener       { resetToSetup() }
    }

    // ── Timer actions ────────────────────────────────────────────────────────

    private fun beginTimer(minutes: Int) = beginMs(minutes * 60_000L)

    private fun beginMs(ms: Long) {
        durationMs  = ms
        remainingMs = ms
        endTimeMs   = System.currentTimeMillis() + ms
        state       = State.RUNNING
        schedule(ms)
        render()
    }

    private fun schedule(ms: Long) {
        timer?.cancel()
        timer = object : CountDownTimer(ms, 1_000L) {
            override fun onTick(left: Long) {
                remainingMs = left
                showTime(left)
            }
            override fun onFinish() {
                onTimerFinished()
            }
        }.start()
    }

    private fun pause() {
        timer?.cancel()
        state = State.PAUSED
        render()
    }

    private fun resume() {
        endTimeMs = System.currentTimeMillis() + remainingMs
        state     = State.RUNNING
        schedule(remainingMs)
        render()
    }

    private fun resetToSetup() {
        timer?.cancel()
        state       = State.SETUP
        remainingMs = durationMs
        endTimeMs   = 0L
        showTime(durationMs)
        render()
    }

    private fun onTimerFinished() {
        remainingMs = 0
        showTime(0)
        state = State.DONE
        render()
        playAlarm()
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private fun render() {
        viewSetup.visibility = if (state == State.SETUP)                            View.VISIBLE else View.GONE
        viewTimer.visibility = if (state == State.RUNNING || state == State.PAUSED) View.VISIBLE else View.GONE
        viewDone.visibility  = if (state == State.DONE)                             View.VISIBLE else View.GONE

        btnPauseResume.text = getString(
            if (state == State.PAUSED) R.string.resume else R.string.pause
        )

        // E Ink holds its image with no power; keeping the screen on while
        // running costs little and ensures the process isn't deprioritized
        // before the alarm fires.
        if (state == State.RUNNING) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun showTime(ms: Long) {
        val totalSec = (ms + 500L) / 1_000L
        tvCountdown.text = "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    // ── Alarm ────────────────────────────────────────────────────────────────

    private fun playAlarm() {
        try {
            toneGen?.release()
            toneGen = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 2_000)
            handler.postDelayed({
                toneGen?.release()
                toneGen = null
            }, 2_500L)
        } catch (_: Exception) {
            // ToneGenerator can throw if audio hardware is unavailable.
        }
    }

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val DEFAULT_DURATION_MS = 25 * 60 * 1_000L
        private const val DEFAULT_CUSTOM_MIN  = 25

        private const val KEY_STATE      = "state"
        private const val KEY_DURATION   = "duration"
        private const val KEY_REMAINING  = "remaining"
        private const val KEY_END_TIME   = "end_time"
        private const val KEY_CUSTOM_MIN = "custom_min"
    }
}
