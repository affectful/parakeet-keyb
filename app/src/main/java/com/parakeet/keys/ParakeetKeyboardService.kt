package com.parakeet.keys

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class ParakeetKeyboardService : InputMethodService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val modifierLatch = ModifierLatch()
    private val modifierButtons = mutableMapOf<String, Button>()
    private val inputSession = AtomicInteger()

    private lateinit var statusView: TextView
    private lateinit var micButton: Button
    private var recognizer: ParakeetRecognizer? = null
    private var recordingSession: RecordingSession? = null
    private var isLoadingModel = false
    private var inputViewActive = false
    private var destroyed = false
    private var compactLayout = false
    private var repeatKeyRunnable: Runnable? = null

    override fun onCreateInputView(): View {
        compactLayout = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        modifierButtons.clear()
        var dragHandle: TextView? = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = if (compactLayout) 4 else 6
            val bottomPadding = if (compactLayout) 4 else 7 + PORTRAIT_NAV_CLEARANCE_DP
            setPadding(dp(horizontalPadding), dp(4), dp(horizontalPadding), dp(bottomPadding))
            if (compactLayout) {
                setBackgroundResource(R.drawable.keyboard_panel_background)
            } else {
                setBackgroundColor(getColor(R.color.keyboard_background))
            }
        }

        statusView = TextView(this).apply {
            text = getString(
                R.string.model_ready,
                ModelManager.selectedVersion(this@ParakeetKeyboardService).displayName,
            )
            textSize = if (compactLayout) 10f else 12f
            setTextColor(getColor(R.color.muted_text))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(3))
        }
        if (compactLayout) {
            val statusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(statusView, LinearLayout.LayoutParams(0, dp(24), 1f))
                dragHandle = TextView(this@ParakeetKeyboardService).apply {
                    setText(R.string.drag)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.key_text))
                    setBackgroundResource(R.drawable.key_background)
                    contentDescription = getString(R.string.drag_keyboard)
                }
                addView(dragHandle, LinearLayout.LayoutParams(dp(64), dp(24)))
            }
            root.addView(statusRow)
        } else {
            root.addView(statusView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(22),
            ))
        }

        micButton = styledButton(getString(R.string.mic)).apply {
            setBackgroundResource(R.drawable.mic_background)
            setOnClickListener { toggleSpeech() }
        }
        root.addView(keyRow(
            key("Esc", KeyEvent.KEYCODE_ESCAPE),
            key("Tab", KeyEvent.KEYCODE_TAB),
            micButton,
            repeatingKey("Backspace", KeyEvent.KEYCODE_DEL, 13f),
        ))
        root.addView(keyRow(
            modifier("Ctrl"),
            modifier("Alt"),
            modifier("Shift"),
            modifier("Meta"),
        ))
        root.addView(keyRow(
            key("PgUp", KeyEvent.KEYCODE_PAGE_UP),
            key("↑", KeyEvent.KEYCODE_DPAD_UP),
            key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
            key("Delete", KeyEvent.KEYCODE_FORWARD_DEL, 14f),
        ))
        root.addView(keyRow(
            key("←", KeyEvent.KEYCODE_DPAD_LEFT),
            key("↓", KeyEvent.KEYCODE_DPAD_DOWN),
            key("→", KeyEvent.KEYCODE_DPAD_RIGHT),
            key("Enter", KeyEvent.KEYCODE_ENTER),
        ))

        root.addView(bottomRow())

        if (!compactLayout) return root

        val panelWidth = minOf(
            dp(LANDSCAPE_PANEL_WIDTH_DP),
            resources.displayMetrics.widthPixels - dp(24),
        )
        return FrameLayout(this).apply {
            setPadding(dp(12), dp(2), dp(12), dp(6))
            setBackgroundColor(Color.TRANSPARENT)
            addView(root, FrameLayout.LayoutParams(
                panelWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
            ))
            dragHandle?.let { installLandscapeDrag(it, root, this) }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputSession.incrementAndGet()
        modifierLatch.clear()
        updateModifierButtons()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputViewActive = true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputViewActive = false
        inputSession.incrementAndGet()
        stopKeyRepeat()
        stopRecording(transcribe = false)
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        inputSession.incrementAndGet()
        stopKeyRepeat()
        stopRecording(transcribe = false)
        super.onFinishInput()
    }

    override fun onDestroy() {
        destroyed = true
        inputViewActive = false
        inputSession.incrementAndGet()
        stopKeyRepeat()
        stopRecording(transcribe = false)
        executor.execute {
            recognizer?.close()
            recognizer = null
        }
        executor.shutdown()
        super.onDestroy()
    }

    private fun key(
        label: String,
        keyCode: Int,
        size: Float = 16f,
        additionalMetaState: Int = 0,
    ): Button = styledButton(label, size).apply {
        setOnClickListener { sendKey(keyCode, additionalMetaState) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun repeatingKey(label: String, keyCode: Int, size: Float): Button =
        key(label, keyCode, size).apply {
            setOnTouchListener { button, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        stopKeyRepeat()
                        button.isPressed = true
                        button.performClick()
                        val repeat = object : Runnable {
                            override fun run() {
                                if (repeatKeyRunnable !== this) return
                                sendKey(keyCode)
                                mainHandler.postDelayed(this, KEY_REPEAT_INTERVAL_MS)
                            }
                        }
                        repeatKeyRunnable = repeat
                        mainHandler.postDelayed(repeat, KEY_REPEAT_DELAY_MS)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        button.isPressed = false
                        stopKeyRepeat()
                        true
                    }
                    else -> true
                }
            }
        }

    private fun stopKeyRepeat() {
        repeatKeyRunnable?.let(mainHandler::removeCallbacks)
        repeatKeyRunnable = null
    }

    private fun modifier(name: String): Button = styledButton(name).apply {
        modifierButtons[name] = this
        setOnClickListener {
            modifierLatch.tap(name)
            updateModifierButtons()
        }
        setOnLongClickListener {
            modifierLatch.lock(name)
            updateModifierButtons()
            true
        }
    }

    private fun styledButton(label: String, size: Float = 16f) = Button(this).apply {
        text = label
        textSize = size
        includeFontPadding = false
        setTextColor(getColor(R.color.key_text))
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(2), 0, dp(2), 0)
        setBackgroundResource(R.drawable.key_background)
    }

    private fun keyRow(vararg buttons: Button): LinearLayout =
        keyRow(*buttons.map { it to 1f }.toTypedArray())

    private fun keyRow(vararg buttons: Pair<Button, Float>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { (button, weight) ->
            addView(button, rowButtonParams(0, weight))
        }
    }

    private fun bottomRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val height = bottomKeyHeight()
        addView(
            key(".", KeyEvent.KEYCODE_PERIOD, 22f),
            rowButtonParams(dp(if (compactLayout) 64 else 76), height = height),
        )
        addView(
            key(
                "?",
                KeyEvent.KEYCODE_SLASH,
                22f,
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
            ),
            rowButtonParams(dp(if (compactLayout) 64 else 76), height = height),
        )
        addView(key("Space", KeyEvent.KEYCODE_SPACE), rowButtonParams(0, 1f, height))
    }

    private fun rowButtonParams(
        width: Int,
        weight: Float = 0f,
        height: Int = keyHeight(),
    ) = LinearLayout.LayoutParams(
        width,
        height,
        weight,
    ).apply {
        val margin = dp(if (compactLayout) 2 else 3)
        setMargins(margin, margin, margin, margin)
    }

    private fun keyHeight(): Int = dp(if (compactLayout) 40 else 49)

    private fun bottomKeyHeight(): Int = dp(if (compactLayout) 48 else 60)

    @SuppressLint("ClickableViewAccessibility")
    private fun installLandscapeDrag(handle: View, panel: View, container: View) {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        container.post {
            panel.translationX = dp(preferences.getInt(PANEL_OFFSET_DP, 0)).toFloat()
                .coerceIn(-maxPanelOffset(container, panel), maxPanelOffset(container, panel))
        }

        var startX = 0f
        var startTranslation = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startTranslation = panel.translationX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val limit = maxPanelOffset(container, panel)
                    panel.translationX = (startTranslation + event.rawX - startX)
                        .coerceIn(-limit, limit)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    preferences.edit()
                        .putInt(PANEL_OFFSET_DP, (panel.translationX / resources.displayMetrics.density).toInt())
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun maxPanelOffset(container: View, panel: View): Float = max(
        0f,
        (container.width - container.paddingLeft - container.paddingRight - panel.width) / 2f,
    )

    private fun sendKey(keyCode: Int, additionalMetaState: Int = 0) {
        val connection = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        val metaState = currentMetaState() or additionalMetaState
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_SOFT_KEYBOARD,
            android.view.InputDevice.SOURCE_KEYBOARD,
        )
        connection.sendKeyEvent(down)
        connection.sendKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
        modifierLatch.consumeArmed()
        updateModifierButtons()
    }

    private fun currentMetaState(): Int {
        var state = 0
        if (modifierLatch.active("Ctrl")) {
            state = state or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (modifierLatch.active("Alt")) {
            state = state or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        if (modifierLatch.active("Shift")) {
            state = state or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        if (modifierLatch.active("Meta")) {
            state = state or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        }
        return state
    }

    private fun updateModifierButtons() {
        modifierButtons.forEach { (name, button) ->
            val state = modifierLatch.state(name)
            button.isSelected = state != LatchState.OFF
            button.text = if (state == LatchState.LOCKED) "$name*" else name
        }
    }

    private fun toggleSpeech() {
        if (recordingSession != null) {
            stopRecording(transcribe = true)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Grant microphone access in setup")
            startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val version = ModelManager.selectedVersion(this)
        if (!ModelManager.isInstalled(this, version)) {
            setStatus("Install the ${version.displayName} model first")
            startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (isLoadingModel) return

        if (recognizer?.version != version) {
            val oldRecognizer = recognizer
            recognizer = null
            if (oldRecognizer != null) executor.execute { oldRecognizer.close() }
        }

        if (recognizer == null) {
            val session = inputSession.get()
            isLoadingModel = true
            micButton.isEnabled = false
            setStatus("Loading ${version.displayName}...")
            executor.execute {
                val result = runCatching { ParakeetRecognizer(applicationContext, version) }
                mainHandler.post {
                    if (destroyed) {
                        result.getOrNull()?.close()
                        return@post
                    }
                    isLoadingModel = false
                    if (inputViewActive) micButton.isEnabled = true
                    result.onSuccess {
                        recognizer = it
                        if (inputViewActive &&
                            inputSession.get() == session &&
                            ModelManager.selectedVersion(this) == version
                        ) {
                            startRecording()
                        }
                    }.onFailure {
                        setStatus("Model load failed: ${it.message}")
                    }
                }
            }
        } else {
            startRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (!inputViewActive || recordingSession != null) return
        val minBufferBytes = AudioRecord.getMinBufferSize(
            ParakeetRecognizer.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            setStatus("Microphone is unavailable")
            return
        }

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                ParakeetRecognizer.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBufferBytes * 2, 16_000),
            )
        }.getOrElse {
            setStatus("Could not open microphone: ${it.message}")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            setStatus("Could not initialize microphone")
            return
        }

        val session = RecordingSession(recorder)
        val started = runCatching {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        }
        if (started.isFailure) {
            recorder.release()
            setStatus("Could not start microphone: ${started.exceptionOrNull()?.message}")
            return
        }

        recordingSession = session
        micButton.isSelected = true
        micButton.setText(R.string.stop)
        setStatus("Listening - tap Stop to transcribe")
        session.thread = Thread({
            val buffer = ShortArray(1600)
            var totalSamples = 0
            val maxSamples = ParakeetRecognizer.SAMPLE_RATE * MAX_RECORDING_SECONDS
            val deadline = SystemClock.elapsedRealtime() + MAX_RECORDING_SECONDS * 1000L
            var readError: Int? = null
            while (!session.stopping.get() &&
                totalSamples < maxSamples &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count > 0) {
                    session.chunks.add(buffer.copyOf(count))
                    totalSamples += count
                } else if (count < 0) {
                    readError = count
                    break
                }
            }
            mainHandler.post {
                if (recordingSession !== session) return@post
                stopRecording(transcribe = readError == null)
                if (readError != null) setStatus("Microphone read failed ($readError)")
            }
        }, "parakeet-audio").also { it.start() }
    }

    private fun stopRecording(transcribe: Boolean) {
        val recording = recordingSession ?: return
        recordingSession = null
        recording.stopping.set(true)
        runCatching { recording.recorder.stop() }
        recording.recorder.release()
        if (::micButton.isInitialized) {
            micButton.isSelected = false
            micButton.setText(R.string.mic)
        }
        if (!transcribe) {
            setStatus("${ModelManager.selectedVersion(this).displayName} ready")
            return
        }

        val session = inputSession.get()
        micButton.isEnabled = false
        setStatus("Transcribing locally...")
        executor.execute {
            recording.thread?.join()
            val samples = FloatArray(recording.chunks.sumOf { it.size })
            var offset = 0
            recording.chunks.forEach { chunk ->
                chunk.forEach { sample -> samples[offset++] = sample / 32768.0f }
            }
            val result = runCatching {
                if (samples.isEmpty()) "" else recognizer?.transcribe(samples).orEmpty()
            }
            mainHandler.post {
                if (destroyed) return@post
                if (inputViewActive) micButton.isEnabled = true
                result.onSuccess { text ->
                    if (inputSession.get() == session && text.isNotBlank()) {
                        val connection = currentInputConnection
                        val beforeCursor = connection
                            ?.getTextBeforeCursor(DICTATION_CONTEXT_CHARS, 0)
                            ?.toString()
                            .orEmpty()
                        val insertion = DictationFormatter.format(text, beforeCursor)
                        val inserted = connection?.commitText(insertion, 1) == true
                        setStatus(if (inserted) "Inserted ${insertion.length} characters" else "Could not insert text")
                    } else if (text.isBlank()) {
                        setStatus("No speech recognized")
                    }
                }.onFailure { setStatus("Transcription failed: ${it.message}") }
            }
        }
    }

    private fun setStatus(value: String) {
        if (::statusView.isInitialized) statusView.text = value
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_RECORDING_SECONDS = 30
        private const val LANDSCAPE_PANEL_WIDTH_DP = 520
        private const val PREFERENCES_NAME = "keyboard_layout"
        private const val PANEL_OFFSET_DP = "landscape_panel_offset_dp"
        private const val PORTRAIT_NAV_CLEARANCE_DP = 36
        private const val KEY_REPEAT_DELAY_MS = 350L
        private const val KEY_REPEAT_INTERVAL_MS = 55L
        private const val DICTATION_CONTEXT_CHARS = 256
    }

    private class RecordingSession(val recorder: AudioRecord) {
        val stopping = AtomicBoolean(false)
        val chunks = ArrayList<ShortArray>()
        var thread: Thread? = null
    }
}
