package com.jppelt.muditatimer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    // ── Mode / state ─────────────────────────────────────────────────────────

    private enum class Mode { TIMER, STOPWATCH }
    private enum class State { SETUP, RUNNING, PAUSED, DONE, STOPWATCH_RUNNING, STOPWATCH_PAUSED }

    private var mode  = Mode.TIMER
    private var state = State.SETUP

    private var durationMs  = DEFAULT_DURATION_MS
    private var remainingMs = DEFAULT_DURATION_MS
    private var elapsedMs   = 0L

    private var customMinutes = DEFAULT_CUSTOM_MIN
    private var customSeconds = 0

    // ── Views ────────────────────────────────────────────────────────────────

    private lateinit var viewSetup:             View
    private lateinit var viewTimer:             View
    private lateinit var viewDone:              View
    private lateinit var viewStopwatch:         View
    private lateinit var btnModeTimer:          Button
    private lateinit var btnModeStopwatch:      Button
    private lateinit var tvCustomMin:           TextView
    private lateinit var tvCustomSec:           TextView
    private lateinit var tvCountdown:           TextView
    private lateinit var btnPauseResume:        Button
    private lateinit var tvStopwatch:           TextView
    private lateinit var btnStopwatchStartStop: Button

    // ── BroadcastReceiver ────────────────────────────────────────────────────

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ms      = intent.getLongExtra(TimerService.EXTRA_MS, 0L)
            val svcMode = intent.getStringExtra(TimerService.EXTRA_MODE)

            when (intent.action) {
                TimerService.ACTION_TICK -> {
                    if (svcMode == Mode.STOPWATCH.name) {
                        elapsedMs = ms
                        tvStopwatch.text = formatElapsed(ms)
                    } else {
                        remainingMs = ms
                        tvCountdown.text = formatRemaining(ms)
                    }
                }
                TimerService.ACTION_STATE_PAUSED -> {
                    // Re-sync display after resuming from background
                    if (svcMode == Mode.STOPWATCH.name) {
                        elapsedMs = ms
                        tvStopwatch.text = formatElapsed(ms)
                        if (state != State.STOPWATCH_PAUSED) { state = State.STOPWATCH_PAUSED; render() }
                    } else {
                        remainingMs = ms
                        tvCountdown.text = formatRemaining(ms)
                        if (state != State.PAUSED) { state = State.PAUSED; render() }
                    }
                }
                TimerService.ACTION_FINISHED -> {
                    prefs().edit().remove(PREF_END_TIME_MS).apply()
                    remainingMs = 0
                    tvCountdown.text = formatRemaining(0L)
                    state = State.DONE
                    render()
                }
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        loadMode()
        restoreState(savedInstanceState)
        wireListeners()
        render()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(TimerService.ACTION_TICK)
            addAction(TimerService.ACTION_FINISHED)
            addAction(TimerService.ACTION_STATE_PAUSED)
        }
        registerReceiver(serviceReceiver, filter)

        // If the timer finished while the app was backgrounded the broadcast was
        // lost. Check the persisted end time and transition to DONE if it has passed.
        if (state == State.RUNNING) {
            val storedEnd = prefs().getLong(PREF_END_TIME_MS, 0L)
            if (storedEnd > 0L && storedEnd <= System.currentTimeMillis()) {
                prefs().edit().remove(PREF_END_TIME_MS).apply()
                remainingMs = 0
                tvCountdown.text = formatRemaining(0L)
                state = State.DONE
                render()
                return
            }
        }

        if (state in setOf(State.RUNNING, State.PAUSED, State.STOPWATCH_RUNNING, State.STOPWATCH_PAUSED)) {
            sendToService(TimerService.ACTION_REQUEST_STATE)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(serviceReceiver)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(KEY_STATE,      state.ordinal)
        out.putLong(KEY_DURATION,  durationMs)
        out.putLong(KEY_REMAINING, remainingMs)
        out.putLong(KEY_ELAPSED,   elapsedMs)
        out.putInt(KEY_CUSTOM_MIN, customMinutes)
        out.putInt(KEY_CUSTOM_SEC, customSeconds)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun bindViews() {
        viewSetup             = findViewById(R.id.viewSetup)
        viewTimer             = findViewById(R.id.viewTimer)
        viewDone              = findViewById(R.id.viewDone)
        viewStopwatch         = findViewById(R.id.viewStopwatch)
        btnModeTimer          = findViewById(R.id.btnModeTimer)
        btnModeStopwatch      = findViewById(R.id.btnModeStopwatch)
        tvCustomMin           = findViewById(R.id.tvCustomMin)
        tvCustomSec           = findViewById(R.id.tvCustomSec)
        tvCountdown           = findViewById(R.id.tvCountdown)
        btnPauseResume        = findViewById(R.id.btnPauseResume)
        tvStopwatch           = findViewById(R.id.tvStopwatch)
        btnStopwatchStartStop = findViewById(R.id.btnStopwatchStartStop)
    }

    private fun loadMode() {
        mode = if (prefs().getString(PREF_LAST_MODE, Mode.TIMER.name) == Mode.STOPWATCH.name)
            Mode.STOPWATCH else Mode.TIMER
    }

    private fun restoreState(bundle: Bundle?) {
        if (bundle != null) {
            state         = State.entries[bundle.getInt(KEY_STATE, State.SETUP.ordinal)]
            durationMs    = bundle.getLong(KEY_DURATION,  DEFAULT_DURATION_MS)
            remainingMs   = bundle.getLong(KEY_REMAINING, DEFAULT_DURATION_MS)
            elapsedMs     = bundle.getLong(KEY_ELAPSED,   0L)
            customMinutes = bundle.getInt(KEY_CUSTOM_MIN, DEFAULT_CUSTOM_MIN)
            customSeconds = bundle.getInt(KEY_CUSTOM_SEC, 0)
        }
        tvCustomMin.text = "%02d".format(customMinutes)
        tvCustomSec.text = "%02d".format(customSeconds)
        tvCountdown.text = formatRemaining(remainingMs)
        tvStopwatch.text = formatElapsed(elapsedMs)
    }

    private fun wireListeners() {
        // Mode toggle
        btnModeTimer.setOnClickListener     { setMode(Mode.TIMER) }
        btnModeStopwatch.setOnClickListener { setMode(Mode.STOPWATCH) }

        // Preset timer buttons
        findViewById<Button>(R.id.btn5min).setOnClickListener   { startTimer(5 * 60_000L) }
        findViewById<Button>(R.id.btn10min).setOnClickListener  { startTimer(10 * 60_000L) }
        findViewById<Button>(R.id.btn25min).setOnClickListener  { startTimer(25 * 60_000L) }

        // Custom picker — minutes
        findViewById<Button>(R.id.btnMinus).setOnClickListener {
            if (customMinutes > 0) { customMinutes--; tvCustomMin.text = "%02d".format(customMinutes) }
        }
        findViewById<Button>(R.id.btnPlus).setOnClickListener {
            if (customMinutes < 99) { customMinutes++; tvCustomMin.text = "%02d".format(customMinutes) }
        }

        // Custom picker — seconds
        findViewById<Button>(R.id.btnMinusSec).setOnClickListener {
            if (customSeconds > 0) { customSeconds--; tvCustomSec.text = "%02d".format(customSeconds) }
        }
        findViewById<Button>(R.id.btnPlusSec).setOnClickListener {
            if (customSeconds < 59) { customSeconds++; tvCustomSec.text = "%02d".format(customSeconds) }
        }

        // Start button: starts timer or stopwatch depending on active mode
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (mode == Mode.TIMER) {
                val ms = (customMinutes * 60 + customSeconds) * 1_000L
                if (ms > 0) startTimer(ms)
            } else {
                startStopwatch()
            }
        }

        // Timer controls
        btnPauseResume.setOnClickListener {
            when (state) {
                State.RUNNING -> { sendToService(TimerService.ACTION_PAUSE);  state = State.PAUSED;  render() }
                State.PAUSED  -> { sendToService(TimerService.ACTION_RESUME); state = State.RUNNING; render() }
                else          -> Unit
            }
        }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetToSetup() }

        // Done screen
        findViewById<Button>(R.id.btnStartAgain).setOnClickListener { startTimer(durationMs) }
        findViewById<Button>(R.id.btnBack).setOnClickListener       { resetToSetup() }

        // Stopwatch: stop/resume toggle (Start is handled by btnStart on setup screen)
        btnStopwatchStartStop.setOnClickListener {
            when (state) {
                State.STOPWATCH_RUNNING -> {
                    sendToService(TimerService.ACTION_PAUSE)
                    state = State.STOPWATCH_PAUSED
                    render()
                }
                State.STOPWATCH_PAUSED -> {
                    sendToService(TimerService.ACTION_RESUME)
                    state = State.STOPWATCH_RUNNING
                    render()
                }
                else -> Unit
            }
        }
        findViewById<Button>(R.id.btnStopwatchReset).setOnClickListener {
            sendToService(TimerService.ACTION_RESET)
            elapsedMs = 0L
            tvStopwatch.text = formatElapsed(0L)
            state = State.SETUP
            render()
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private fun setMode(m: Mode) {
        mode = m
        prefs().edit().putString(PREF_LAST_MODE, m.name).apply()
        render()
    }

    private fun startTimer(ms: Long) {
        durationMs       = ms
        remainingMs      = ms
        tvCountdown.text = formatRemaining(ms)
        state            = State.RUNNING
        prefs().edit().putLong(PREF_END_TIME_MS, System.currentTimeMillis() + ms).apply()
        sendToService(TimerService.ACTION_START_TIMER) { putExtra(TimerService.EXTRA_DURATION_MS, ms) }
        render()
    }

    private fun startStopwatch() {
        elapsedMs        = 0L
        tvStopwatch.text = formatElapsed(0L)
        state            = State.STOPWATCH_RUNNING
        sendToService(TimerService.ACTION_START_STOPWATCH)
        render()
    }

    private fun resetToSetup() {
        prefs().edit().remove(PREF_END_TIME_MS).apply()
        sendToService(TimerService.ACTION_RESET)
        state            = State.SETUP
        remainingMs      = durationMs
        tvCountdown.text = formatRemaining(durationMs)
        render()
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun sendToService(action: String, extras: Intent.() -> Unit = {}) {
        startService(Intent(this, TimerService::class.java).apply {
            this.action = action
            extras()
        })
    }

    // ── Render ───────────────────────────────────────────────────────────────

    private fun render() {
        val inStopwatch = mode == Mode.STOPWATCH
        val swActive    = state == State.STOPWATCH_RUNNING || state == State.STOPWATCH_PAUSED
        val timerTicking = state == State.RUNNING || state == State.PAUSED

        viewSetup.visibility     = if (!timerTicking && !swActive && state != State.DONE) View.VISIBLE else View.GONE
        viewTimer.visibility     = if (timerTicking)         View.VISIBLE else View.GONE
        viewDone.visibility      = if (state == State.DONE)  View.VISIBLE else View.GONE
        viewStopwatch.visibility = if (swActive)             View.VISIBLE else View.GONE

        // Mode toggle button styles
        btnModeTimer.setBackgroundResource(if (inStopwatch) R.drawable.btn_outline else R.drawable.btn_filled)
        btnModeTimer.setTextColor(getColor(if (inStopwatch) R.color.black else R.color.white))
        btnModeStopwatch.setBackgroundResource(if (inStopwatch) R.drawable.btn_filled else R.drawable.btn_outline)
        btnModeStopwatch.setTextColor(getColor(if (inStopwatch) R.color.white else R.color.black))

        // Show timer-specific controls only in timer mode; spacer fills gap in stopwatch mode
        val timerOnlyVisibility = if (inStopwatch) View.GONE else View.VISIBLE
        findViewById<View>(R.id.presetButtons).visibility      = timerOnlyVisibility
        findViewById<View>(R.id.dividerOr).visibility          = timerOnlyVisibility
        findViewById<View>(R.id.customPickerLayout).visibility = timerOnlyVisibility
        findViewById<View>(R.id.stopwatchSpacer).visibility    = if (inStopwatch) View.VISIBLE else View.GONE

        // Pause/resume label
        btnPauseResume.text = getString(if (state == State.PAUSED) R.string.resume else R.string.pause)

        // Stopwatch stop/resume label
        btnStopwatchStartStop.text = getString(
            if (state == State.STOPWATCH_RUNNING) R.string.stopwatch_stop else R.string.stopwatch_start
        )
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    private fun formatRemaining(ms: Long): String {
        val totalSec = (ms + 500L) / 1_000L
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1_000L
        return if (s < 3600) "%02d:%02d".format(s / 60, s % 60)
               else          "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val DEFAULT_DURATION_MS = 25 * 60 * 1_000L
        private const val DEFAULT_CUSTOM_MIN  = 25

        private const val PREFS_NAME          = "mudita_prefs"
        private const val PREF_LAST_MODE      = "last_mode"
        private const val PREF_END_TIME_MS    = "end_time_ms"

        private const val KEY_STATE           = "state"
        private const val KEY_DURATION        = "duration"
        private const val KEY_REMAINING       = "remaining"
        private const val KEY_ELAPSED         = "elapsed"
        private const val KEY_CUSTOM_MIN      = "custom_min"
        private const val KEY_CUSTOM_SEC      = "custom_sec"
    }
}
