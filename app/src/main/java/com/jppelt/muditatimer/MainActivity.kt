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

    // Numpad entry: up to 6 digits, filled from the right. Padded to "HHMMSS".
    private var customEntry = ""

    // ── Views ────────────────────────────────────────────────────────────────

    private lateinit var viewSetup:             View
    private lateinit var viewTimer:             View
    private lateinit var viewDone:              View
    private lateinit var viewStopwatch:         View
    private lateinit var btnModeTimer:          Button
    private lateinit var btnModeStopwatch:      Button
    private lateinit var btnRingShort:          Button
    private lateinit var btnRingContinuous:     Button
    private lateinit var tvCustomDisplay:       TextView
    private lateinit var tvCountdown:           TextView
    private lateinit var btnPauseResume:        Button
    private lateinit var tvStopwatch:           TextView
    private lateinit var btnStopwatchStartStop: Button

    private var ringContinuous = false

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
                    remainingMs = 0
                    tvCountdown.text = formatRemaining(0L)
                    state = State.DONE
                    prefs().edit().remove(PREF_END_TIME_MS).apply()
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
        loadRingMode()
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

        // If the timer expired while the app was backgrounded the broadcast was
        // missed. Check the persisted end time and transition to DONE locally.
        if (state == State.RUNNING) {
            val endTimeMs = prefs().getLong(PREF_END_TIME_MS, 0L)
            if (endTimeMs > 0L && System.currentTimeMillis() >= endTimeMs) {
                remainingMs = 0
                tvCountdown.text = formatRemaining(0L)
                state = State.DONE
                prefs().edit().remove(PREF_END_TIME_MS).apply()
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
        out.putString(KEY_CUSTOM_ENTRY, customEntry)
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun bindViews() {
        viewSetup             = findViewById(R.id.viewSetup)
        viewTimer             = findViewById(R.id.viewTimer)
        viewDone              = findViewById(R.id.viewDone)
        viewStopwatch         = findViewById(R.id.viewStopwatch)
        btnModeTimer          = findViewById(R.id.btnModeTimer)
        btnModeStopwatch      = findViewById(R.id.btnModeStopwatch)
        btnRingShort          = findViewById(R.id.btnRingShort)
        btnRingContinuous     = findViewById(R.id.btnRingContinuous)
        tvCustomDisplay       = findViewById(R.id.tvCustomDisplay)
        tvCountdown           = findViewById(R.id.tvCountdown)
        btnPauseResume        = findViewById(R.id.btnPauseResume)
        tvStopwatch           = findViewById(R.id.tvStopwatch)
        btnStopwatchStartStop = findViewById(R.id.btnStopwatchStartStop)
    }

    private fun loadMode() {
        val saved = prefs().getString(PREF_LAST_MODE, Mode.TIMER.name)
        mode = if (saved == Mode.STOPWATCH.name) Mode.STOPWATCH else Mode.TIMER
    }

    private fun loadRingMode() {
        ringContinuous = prefs().getBoolean(AlarmReceiver.PREF_RING_CONTINUOUS, false)
    }

    private fun restoreState(bundle: Bundle?) {
        if (bundle != null) {
            state       = State.entries[bundle.getInt(KEY_STATE, State.SETUP.ordinal)]
            durationMs  = bundle.getLong(KEY_DURATION,  DEFAULT_DURATION_MS)
            remainingMs = bundle.getLong(KEY_REMAINING, DEFAULT_DURATION_MS)
            elapsedMs   = bundle.getLong(KEY_ELAPSED,   0L)
            customEntry = bundle.getString(KEY_CUSTOM_ENTRY, "")
        }
        updateCustomDisplay()
        tvCountdown.text = formatRemaining(remainingMs)
        tvStopwatch.text = formatElapsed(elapsedMs)
    }

    private fun wireListeners() {
        // Mode toggle
        btnModeTimer.setOnClickListener     { setMode(Mode.TIMER) }
        btnModeStopwatch.setOnClickListener { setMode(Mode.STOPWATCH) }

        // Ring mode toggle
        btnRingShort.setOnClickListener      { setRingContinuous(false) }
        btnRingContinuous.setOnClickListener { setRingContinuous(true) }

        // Preset timer buttons
        findViewById<Button>(R.id.btn5min).setOnClickListener   { startTimer(5 * 60_000L) }
        findViewById<Button>(R.id.btn10min).setOnClickListener  { startTimer(10 * 60_000L) }
        findViewById<Button>(R.id.btn25min).setOnClickListener  { startTimer(25 * 60_000L) }

        // Custom numpad — digits 0-9
        val digitIds = intArrayOf(
            R.id.btnNum0, R.id.btnNum1, R.id.btnNum2, R.id.btnNum3, R.id.btnNum4,
            R.id.btnNum5, R.id.btnNum6, R.id.btnNum7, R.id.btnNum8, R.id.btnNum9
        )
        for (d in 0..9) {
            findViewById<Button>(digitIds[d]).setOnClickListener { onDigit(d) }
        }
        findViewById<Button>(R.id.btnBackspace).setOnClickListener { onBackspace() }

        // Start button
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (mode == Mode.TIMER) {
                // Treat the six digits as a raw HHMMSS value; seconds and minutes
                // carry on start (e.g. 00:61:00 → 01:01:00, 00:00:61 → 00:01:01).
                val ms = currentCustomMs()
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

        // Done screen — dismissing stops a continuous ring
        findViewById<Button>(R.id.btnStartAgain).setOnClickListener {
            stopAlarmSound()
            startTimer(durationMs)
        }
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            stopAlarmSound()
            resetToSetup()
        }

        // Stopwatch controls
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

    private fun setRingContinuous(continuous: Boolean) {
        ringContinuous = continuous
        prefs().edit().putBoolean(AlarmReceiver.PREF_RING_CONTINUOUS, continuous).apply()
        render()
    }

    private fun stopAlarmSound() {
        sendToService(TimerService.ACTION_STOP_ALARM)
    }

    private fun startTimer(ms: Long) {
        stopAlarmSound()
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
        stopAlarmSound()
        sendToService(TimerService.ACTION_RESET)
        prefs().edit().remove(PREF_END_TIME_MS).apply()
        state            = State.SETUP
        remainingMs      = durationMs
        tvCountdown.text = formatRemaining(durationMs)
        render()
    }

    private fun sendToService(action: String, extras: Intent.() -> Unit = {}) {
        startService(Intent(this, TimerService::class.java).apply {
            this.action = action
            extras()
        })
    }

    // ── Render ───────────────────────────────────────────────────────────────

    private fun render() {
        val inStopwatch  = mode == Mode.STOPWATCH
        val swActive     = state == State.STOPWATCH_RUNNING || state == State.STOPWATCH_PAUSED
        val timerTicking = state == State.RUNNING || state == State.PAUSED

        viewSetup.visibility     = if (!timerTicking && !swActive && state != State.DONE) View.VISIBLE else View.GONE
        viewTimer.visibility     = if (timerTicking)         View.VISIBLE else View.GONE
        viewDone.visibility      = if (state == State.DONE)  View.VISIBLE else View.GONE
        viewStopwatch.visibility = if (swActive)             View.VISIBLE else View.GONE

        btnModeTimer.setBackgroundResource(if (inStopwatch) R.drawable.btn_outline else R.drawable.btn_filled)
        btnModeTimer.setTextColor(getColor(if (inStopwatch) R.color.black else R.color.white))
        btnModeStopwatch.setBackgroundResource(if (inStopwatch) R.drawable.btn_filled else R.drawable.btn_outline)
        btnModeStopwatch.setTextColor(getColor(if (inStopwatch) R.color.white else R.color.black))

        btnRingShort.setBackgroundResource(if (ringContinuous) R.drawable.btn_outline else R.drawable.btn_filled)
        btnRingShort.setTextColor(getColor(if (ringContinuous) R.color.black else R.color.white))
        btnRingContinuous.setBackgroundResource(if (ringContinuous) R.drawable.btn_filled else R.drawable.btn_outline)
        btnRingContinuous.setTextColor(getColor(if (ringContinuous) R.color.white else R.color.black))

        val timerOnlyVisibility = if (inStopwatch) View.GONE else View.VISIBLE
        findViewById<View>(R.id.ringModeToggle).visibility     = timerOnlyVisibility
        findViewById<View>(R.id.presetButtons).visibility      = timerOnlyVisibility
        findViewById<View>(R.id.dividerOr).visibility          = timerOnlyVisibility
        findViewById<View>(R.id.customPickerLayout).visibility = timerOnlyVisibility
        findViewById<View>(R.id.stopwatchSpacer).visibility    = if (inStopwatch) View.VISIBLE else View.GONE

        btnPauseResume.text = getString(if (state == State.PAUSED) R.string.resume else R.string.pause)
        btnStopwatchStartStop.text = getString(
            if (state == State.STOPWATCH_RUNNING) R.string.stopwatch_stop else R.string.stopwatch_start
        )
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    private fun formatRemaining(ms: Long): String {
        val totalSec = (ms + 500L) / 1_000L
        return "%02d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1_000L
        return if (s < 3600) "%02d:%02d".format(s / 60, s % 60)
               else          "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    // ── Custom numpad ────────────────────────────────────────────────────────

    private fun onDigit(d: Int) {
        // Ignore leading zeros so the six digit slots are not wasted.
        if (customEntry.isEmpty() && d == 0) return
        if (customEntry.length >= CUSTOM_ENTRY_DIGITS) return
        // No intermediate validation: digits fill from the right, so a buffer
        // like "90" (00:00:90) is a legitimate in-progress state on the way to 9:00.
        customEntry += d
        updateCustomDisplay()
    }

    private fun onBackspace() {
        if (customEntry.isNotEmpty()) {
            customEntry = customEntry.dropLast(1)
            updateCustomDisplay()
        }
    }

    private fun updateCustomDisplay() {
        val padded = customEntry.padStart(CUSTOM_ENTRY_DIGITS, '0')
        tvCustomDisplay.text = "${padded.substring(0, 2)}:${padded.substring(2, 4)}:${padded.substring(4, 6)}"
    }

    /** Total milliseconds from the current numpad entry (rightmost two = seconds). */
    private fun currentCustomMs(): Long {
        val padded = customEntry.padStart(CUSTOM_ENTRY_DIGITS, '0')
        val hh = padded.substring(0, 2).toInt()
        val mm = padded.substring(2, 4).toInt()
        val ss = padded.substring(4, 6).toInt()
        return (hh * 3600L + mm * 60L + ss) * 1_000L
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val CUSTOM_ENTRY_DIGITS = 6
        private const val DEFAULT_DURATION_MS = 25 * 60 * 1_000L

        private const val PREFS_NAME          = AlarmReceiver.PREFS_NAME
        private const val PREF_LAST_MODE      = "last_mode"
        private const val PREF_END_TIME_MS    = "end_time_ms"

        private const val KEY_STATE           = "state"
        private const val KEY_DURATION        = "duration"
        private const val KEY_REMAINING       = "remaining"
        private const val KEY_ELAPSED         = "elapsed"
        private const val KEY_CUSTOM_ENTRY    = "custom_entry"
    }
}
