// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.support.SupportAsk
import helium314.keyboard.latin.utils.DirectBoot
import helium314.keyboard.latin.utils.prefs
import java.io.File

private const val TAG = "DroneboardDictation"

/**
 * One-tap dictation inside the keyboard: the keyboard records (phone or Bluetooth headset, the
 * Third Ear way), Offline Speech to Text transcribes, and the text lands at the cursor. No
 * keyboard switching. Tap the mic to start, tap again to stop; there is no stop on silence.
 * Nothing is transcribed while the user talks: the recording goes to a file and is sent whole when they stop, so
 * no sentence is cut in the middle. The price is that the text of a long dictation takes a while to appear.
 * Everything here runs on the main thread except the recording loop.
 * Only reachable on Android 13+ ([supported] gates [onMicKey], the one entry point).
 */
@SuppressLint("NewApi")
class DictationController(private val ime: LatinIME) : VoiceInputView.Actions, OsttTranscriber.Listener {

    companion object {
        const val SAMPLE_RATE = 16_000
        /** Caller audio to a recognizer needs Android 13. */
        val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        /** how long after the last dictated piece one backspace still takes the whole block back */
        private const val UNDO_WINDOW_MS = 30_000L
        /**
         * The longest single dictation. After this the recording ends as if the user had tapped the mic, and what
         * was said is transcribed. Half an hour is far more than anyone dictates in one go, and it bounds the file
         * (58 MB) and the wait for the text when a recording is left running by mistake.
         */
        const val MAX_RECORDING_MINUTES = 30
        private const val HELP_BASE = "https://cypherpunk.today/droneboard/"
        /** Languages the website has a dictation page in, besides English at the root. */
        private val HELP_LANGUAGES = setOf("sk", "cs", "de", "es")
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private val main = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var engine: RecordingEngine? = null
    private var transcriber: OsttTranscriber? = null
    private var panel: VoiceInputView? = null
    /** The text field dictation started in; text is only typed back into the same one. */
    private var field: Pair<String?, Int>? = null
    private var lastStatus: EngineStatus? = null
    private var errorShown = false
    /** this dictation typed something into the field; one action for the support ask, see [finished] */
    private var typedAny = false
    /** dictated text that could not be typed because its field went away; see [deliverUntyped] */
    private val untyped = StringBuilder()
    /** what the last dictation typed, for taking it back with one backspace; see [onKeyEvent] */
    private val typedBlock = StringBuilder()
    private var typedBlockAt = 0L
    private var typing = false
    private var focusRequest: AudioFocusRequest? = null
    private val rotation = RotationGuard()
    private val rotationTimeout = Runnable {
        // the keyboard did not come back after the rotation: the user left, as in [onFinishInputView]
        if (rotation.onTimeout() == RotationGuard.Finish.STOP) {
            if (state == State.RECORDING) {
                stopRecording()
                hidePanel()
            }
        } else if (state != State.IDLE && panel == null && ime.isInputViewShown) {
            // the keyboard stayed on screen and nothing announced it again: the panel still has to come back
            showProgress()
        }
    }

    val isActive get() = state != State.IDLE || panel != null

    /** The keyboard process started: a recording left on the phone by a crash is deleted. */
    fun onCreate() {
        runCatching { recordingDir()?.let { DictationFiles.clear(it) } }
    }

    /**
     * Where the recording of a dictation is kept until it is transcribed: the app's cache folder, which Android
     * never backs up and which is encrypted with the user's credentials. Null before the first unlock, or if the
     * folder were ever the one that is readable before unlock: speech does not go there.
     */
    private fun recordingDir(): File? = runCatching {
        val ctx = ime.applicationContext ?: ime
        if (!DirectBoot.isUnlocked(ime) || ctx.isDeviceProtectedStorage) null
        else File(ctx.cacheDir, DictationFiles.DIR)
    }.getOrNull()

    /** The mic key or toolbar button. Returns false when dictation can't run here (old Android). */
    fun onMicKey(): Boolean {
        if (!supported) return false
        // no dictation on the lock screen, and none before the first unlock (direct boot): the permission screen,
        // Offline Speech to Text and the help page can't open there; the key does nothing instead of crashing
        val locked = runCatching {
            !DirectBoot.isUnlocked(ime) || helium314.keyboard.compat.isDeviceLocked(ime)
        }.getOrDefault(true)
        if (locked) {
            if (state != State.IDLE || panel != null) runCatching { cancel() }
            return true
        }
        when (state) {
            State.RECORDING -> stopRecording()
            State.TRANSCRIBING -> showProgress() // the text is on its way; the panel says so
            State.IDLE -> if (panel != null) hidePanel() else start()
        }
        return true
    }

    private fun start() {
        if (OsttTranscriber.component(ime) == null) {
            showOsttProblem(if (OsttTranscriber.installed(ime)) OsttTranscriber.Problem.OTHER else OsttTranscriber.Problem.NOT_INSTALLED)
            return
        }
        if (!MicPermissionActivity.hasMic(ime)) {
            MicPermissionActivity.start(ime)
            return
        }
        val editor = ime.currentInputEditorInfo
        field = editor?.packageName to (editor?.fieldId ?: 0)
        errorShown = false
        typedAny = false
        untyped.setLength(0)
        typedBlock.setLength(0)
        lastStatus = null
        rotation.reset()
        main.removeCallbacks(rotationTimeout)
        val t = OsttTranscriber(ime, this)
        transcriber = t
        // no dictation is running here, so any file in the folder is a leftover
        val dir = recordingDir()
        runCatching { dir?.let { DictationFiles.clear(it) } }
        var file = dir?.let { DictationFiles.create(it) }
        val toFile = file?.let { f ->
            runCatching {
                WavRecordingSink(f, SAMPLE_RATE, MAX_RECORDING_MINUTES * 60L * SAMPLE_RATE) {
                    // from the recording thread: the longest dictation is reached (or the storage is full)
                    main.post { if (transcriber === t && state == State.RECORDING) stopRecording() }
                }
            }.getOrNull()
        }
        if (toFile == null) {
            // no private place for a file: the recording is cut and transcribed while it runs, as it always was
            Log.w(TAG, "no file for the recording, transcribing in pieces while recording")
            file?.delete()
            file = null
        }
        val e = RecordingEngine(
            sources = AndroidSources(ime),
            sink = toFile ?: Chunker(SAMPLE_RATE) { pcm, last -> t.submit(pcm, last) },
            sampleRate = SAMPLE_RATE,
            preferBluetooth = ime.prefs().getBoolean(Settings.PREF_DICTATION_USE_BLUETOOTH_MIC, Defaults.PREF_DICTATION_USE_BLUETOOTH_MIC),
            bluetoothAvailable = { BluetoothMics.available(ime) },
            listener = engineListener(t, file),
        )
        engine = e
        state = State.RECORDING
        showPanel(VoiceInputView.Mode.STARTING, ime.getString(R.string.voice_starting), null)
        pauseOtherAudio()
        e.start()
    }

    private fun stopRecording() {
        val e = engine ?: return
        state = State.TRANSCRIBING
        showProgress()
        e.stop()
    }

    /** Cancel: drops the recording and anything not yet typed. */
    fun cancel() {
        engine?.stop(discardAudio = true)
        engine = null
        resumeOtherAudio()
        transcriber?.cancel()
        transcriber = null
        untyped.setLength(0)
        state = State.IDLE
        rotation.reset()
        main.removeCallbacks(rotationTimeout)
        hidePanel()
    }

    /**
     * The keyboard went away (screen off, a notification, another app): a running recording is not dropped, it
     * ends here and is written down. The text is typed when the field is still there, else it goes to the clipboard.
     * Not so while the phone is being rotated: then only the panel goes, see [RotationGuard].
     */
    fun onFinishInputView() {
        if (rotation.onFinishInputView() == RotationGuard.Finish.STOP && state == State.RECORDING) stopRecording()
        hidePanel()
    }

    /**
     * The phone was rotated. Android builds the keyboard again and hides it for a moment; a dictation goes on
     * through that, and the panel returns with the keyboard in [onStartInputView].
     */
    fun onOrientationChange() {
        // the panel hangs on the views of the old orientation, which are off the screen now
        hidePanel()
        if (state == State.IDLE) return
        rotation.onOrientationChange()
        main.removeCallbacks(rotationTimeout)
        main.postDelayed(rotationTimeout, RotationGuard.TIMEOUT_MS)
    }

    /** The keyboard is on screen (again): a dictation that is still running shows its panel, in its current state. */
    fun onStartInputView() {
        rotation.onStartInputView()
        if (state != State.IDLE) showProgress()
    }

    /** The panel for where the running dictation is: listening, or waiting for the text. */
    private fun showProgress() {
        when (state) {
            State.RECORDING -> {
                showPanel(VoiceInputView.Mode.STARTING, ime.getString(R.string.voice_starting), null)
                lastStatus?.let { s -> panel?.let { showRecording(it, s, wasWaiting = false) } }
            }
            State.TRANSCRIBING ->
                showPanel(VoiceInputView.Mode.TRANSCRIBING, ime.getString(R.string.voice_transcribing), micLabel(lastStatus))
            State.IDLE -> Unit
        }
    }

    // ---- recording ----

    /** For one dictation: [t] is its transcriber, [file] its recording (null when it is transcribed in pieces while recording). */
    private fun engineListener(t: OsttTranscriber, file: File?) = object : RecordingEngine.Listener {
        override fun onStatus(status: EngineStatus) {
            main.post { onEngineStatus(status) }
        }

        override fun onCue(cue: SourcePolicy.Cue) {
            // the Bluetooth microphone did not come up: a falling tone, then the phone records
            tone(ToneGenerator.TONE_PROP_NACK, 250, AudioManager.STREAM_SYSTEM)
        }

        override fun onLog(message: String) {
            Log.i(TAG, message)
        }

        override fun onFinished(samples: Long, failure: String?) {
            main.post {
                if (failure != null) Log.w(TAG, "recording ended: $failure")
                if (transcriber !== t) {
                    // cancelled, or the user has started another dictation since: this recording is not wanted
                    file?.delete()
                    return@post
                }
                engine = null
                resumeOtherAudio()
                if (failure != null && samples == 0L) {
                    file?.delete()
                    t.cancel()
                    transcriber = null
                    state = State.IDLE
                    showPanel(VoiceInputView.Mode.ERROR, ime.getString(R.string.voice_mic_failed), failure)
                    return@post
                }
                if (state == State.RECORDING) {
                    // the microphone gave up in the middle: what was said until then is still written down
                    state = State.TRANSCRIBING
                    if (panel != null) showProgress()
                }
                if (file == null) return@post // the pieces are already on their way
                if (samples == 0L) {
                    file.delete()
                    t.submit(ShortArray(0), true)
                    return@post
                }
                // the whole recording, in one request
                val uri = runCatching { FileProvider.getUriForFile(ime, DictationAudioProvider.authority(ime), file) }
                if (uri.isFailure) Log.w(TAG, "the recording can't be shared as a file", uri.exceptionOrNull())
                t.submitRecording(file, uri.getOrNull(), samples * 1000 / SAMPLE_RATE)
            }
        }
    }

    private fun onEngineStatus(s: EngineStatus) {
        val wasWaiting = lastStatus?.waitingForBluetooth == true
        lastStatus = s
        val p = panel ?: return
        if (state != State.RECORDING) return
        showRecording(p, s, wasWaiting)
    }

    private fun showRecording(p: VoiceInputView, s: EngineStatus, wasWaiting: Boolean) {
        when {
            s.starting || s.switching -> p.show(VoiceInputView.Mode.STARTING, ime.getString(R.string.voice_starting), micLabel(s))
            s.waitingForBluetooth -> p.show(VoiceInputView.Mode.WAITING_BLUETOOTH, ime.getString(R.string.voice_waiting_bluetooth), micLabel(s))
            else -> {
                if (wasWaiting && s.input.bluetooth) tone(ToneGenerator.TONE_PROP_BEEP, 120, AudioManager.STREAM_VOICE_CALL) // go ahead, into the headset
                val title = if (s.silent) ime.getString(R.string.voice_silent) else ime.getString(R.string.voice_speak_now)
                p.show(VoiceInputView.Mode.LISTENING, title, micLabel(s))
                p.setLevel(s.amplitude)
            }
        }
    }

    private fun micLabel(s: EngineStatus?): String {
        if (s == null) return ""
        val reason = s.fallbackReason
        return when {
            s.input.bluetooth -> ime.getString(R.string.voice_mic_headset, s.input.name ?: ime.getString(R.string.voice_mic_bluetooth))
            s.source == Source.BLUETOOTH -> ime.getString(R.string.voice_mic_headset, ime.getString(R.string.voice_mic_bluetooth))
            reason != null && s.source == Source.PHONE && reason != "no Bluetooth microphone connected" ->
                ime.getString(R.string.voice_mic_phone_fallback)
            else -> ime.getString(R.string.voice_mic_phone)
        }
    }

    // ---- transcription ----

    override fun onText(text: String, last: Boolean) {
        if (text.isNotEmpty()) type(text)
        if (last) finished()
    }

    override fun onTranscriptionError(message: String, busy: Boolean, last: Boolean, problem: OsttTranscriber.Problem) {
        Log.w(TAG, "transcription failed: $message ($problem)")
        if (!errorShown) {
            errorShown = true
            if (problem == OsttTranscriber.Problem.OTHER)
                showPanel(VoiceInputView.Mode.ERROR, ime.getString(R.string.voice_transcription_failed), message)
            else showOsttProblem(problem)
        }
        if (last) {
            transcriber = null
            state = State.IDLE
            deliverUntyped()
            countDictation()
        }
    }

    private fun finished() {
        transcriber = null
        state = State.IDLE
        deliverUntyped()
        countDictation()
        if (!errorShown) hidePanel()
    }

    /** A dictation that typed text is one action for the support ask (a number only). */
    private fun countDictation() {
        if (!typedAny) return
        typedAny = false
        SupportAsk.onAction(ime)
    }

    private fun type(text: String) {
        val editor: EditorInfo? = ime.currentInputEditorInfo
        val ic = ime.currentInputConnection
        // once a piece could not be typed, the rest follows it, so the text stays whole and in order
        if (untyped.isNotEmpty() || editor == null || ic == null || field != (editor.packageName to editor.fieldId)) {
            Log.i(TAG, "the field is gone, keeping ${text.length} dictated characters for the clipboard")
            untyped.append(TextJoiner.join(untyped, text))
            return
        }
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        val joined = TextJoiner.join(before, text)
        if (joined.isEmpty()) return
        typing = true
        try { ime.onTextInput(joined) } finally { typing = false }
        typedAny = true
        typedBlock.append(joined)
        typedBlockAt = SystemClock.elapsedRealtime()
    }

    /** Anything typed by the user after dictation: the block is theirs now, backspace is a plain backspace again. */
    fun onOtherInput() {
        if (!typing) typedBlock.setLength(0)
    }

    /**
     * Every key press comes through here. The first backspace after a dictation deletes the whole dictated block,
     * like Gboard, as long as nothing was typed since, the block still stands right before the cursor and the
     * dictation is fresh. Returns true when the key was used up that way.
     */
    fun onKeyEvent(backspace: Boolean): Boolean {
        if (typedBlock.isEmpty()) return false
        val block = typedBlock.toString()
        val fresh = state == State.IDLE && SystemClock.elapsedRealtime() - typedBlockAt < UNDO_WINDOW_MS
        if (state == State.IDLE) typedBlock.setLength(0) // while pieces are still coming, keys do not end the block
        if (!backspace || !fresh) return false
        if (!ime.prefs().getBoolean(Settings.PREF_DICTATION_BACKSPACE_UNDO, Defaults.PREF_DICTATION_BACKSPACE_UNDO)) return false
        val editor = ime.currentInputEditorInfo ?: return false
        if (field != (editor.packageName to editor.fieldId)) return false
        return runCatching { ime.deleteDictatedBlock(block) }.getOrDefault(false)
    }

    // ---- other audio ----

    /** Music and podcasts pause while the microphone is on, and continue afterwards. */
    private fun pauseOtherAudio() {
        if (focusRequest != null) return
        runCatching {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener({ }, main)
                .build()
            ime.getSystemService(AudioManager::class.java).requestAudioFocus(request)
            focusRequest = request
        }.onFailure { Log.w(TAG, "could not take audio focus", it) }
    }

    private fun resumeOtherAudio() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { ime.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request) }
    }

    /** Dictated text that had no field to go to is never thrown away: it lands in the clipboard, with a message. */
    private fun deliverUntyped() {
        val text = untyped.toString().trim()
        untyped.setLength(0)
        if (text.isEmpty()) return
        val copied = runCatching {
            ime.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
        }
        if (copied.isFailure) Log.w(TAG, "could not copy the dictated text", copied.exceptionOrNull())
        else runCatching { Toast.makeText(ime, R.string.voice_text_in_clipboard, Toast.LENGTH_LONG).show() }
    }

    // ---- panel ----

    private fun wrapper(): ViewGroup? =
        ime.window?.window?.decorView?.findViewById(R.id.keyboard_view_wrapper)

    /**
     * What to do when Offline Speech to Text can't take the job: [OsttTranscriber.Problem.OTHER]
     * here means installed but without a usable recognition service (disabled or too old).
     */
    private fun showOsttProblem(problem: OsttTranscriber.Problem) {
        val help = VoiceInputView.Button.HELP
        when (problem) {
            OsttTranscriber.Problem.NOT_INSTALLED -> showPanel(VoiceInputView.Mode.MISSING_OSTT,
                ime.getString(R.string.voice_ostt_missing), ime.getString(R.string.voice_ostt_missing_detail),
                VoiceInputView.Button.INSTALL, help)
            OsttTranscriber.Problem.NO_MODEL -> showPanel(VoiceInputView.Mode.OSTT_NEEDS_SETUP,
                ime.getString(R.string.voice_ostt_no_model), ime.getString(R.string.voice_ostt_no_model_detail),
                VoiceInputView.Button.OPEN_OSTT, help)
            OsttTranscriber.Problem.PERMISSION -> showPanel(VoiceInputView.Mode.OSTT_NEEDS_SETUP,
                ime.getString(R.string.voice_ostt_permission), ime.getString(R.string.voice_ostt_permission_detail),
                VoiceInputView.Button.OPEN_OSTT, help)
            OsttTranscriber.Problem.OTHER -> showPanel(VoiceInputView.Mode.OSTT_UNAVAILABLE,
                ime.getString(R.string.voice_ostt_unavailable), ime.getString(R.string.voice_ostt_unavailable_detail),
                VoiceInputView.Button.INSTALL, help)
        }
    }

    private fun showPanel(
        mode: VoiceInputView.Mode, title: String, detail: String?,
        primary: VoiceInputView.Button? = null, secondary: VoiceInputView.Button? = null,
    ) {
        var p = panel
        val current = wrapper()
        // a panel on another wrapper than the one on screen is a leftover of the keyboard's previous views
        // (Android builds them again on rotation): it has a parent, but nobody sees it
        if (p == null || p.parent !== current) {
            val w = current ?: return
            p?.let { (it.parent as? ViewGroup)?.removeView(it) }
            p = VoiceInputView(ime, this)
            w.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            p.elevation = 100f
            panel = p
        }
        p.visibility = View.VISIBLE
        p.show(mode, title, detail, primary, secondary)
    }

    private fun hidePanel() {
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        panel = null
    }

    override fun onMicTapped() {
        when (state) {
            State.RECORDING -> stopRecording()
            State.TRANSCRIBING -> Unit
            State.IDLE -> { hidePanel(); start() } // retry after an error
        }
    }

    override fun onCancelTapped() = cancel()

    override fun onInstallTapped() = leaveFor(Intent(Intent.ACTION_VIEW, Uri.parse(OsttTranscriber.ZAPSTORE_URL)))

    override fun onHelpTapped() = leaveFor(Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl())))

    override fun onOpenOsttTapped() {
        val launch = ime.packageManager.getLaunchIntentForPackage(OsttTranscriber.OSTT_PACKAGE)
        leaveFor(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(OsttTranscriber.ZAPSTORE_URL)))
    }

    /**
     * The website's dictation page in the language the keyboard shows its own text in; English
     * for languages the site doesn't have.
     */
    private fun helpUrl(): String {
        val language = ime.resources.configuration.locales[0].language
        return if (language in HELP_LANGUAGES) "$HELP_BASE$language/dictation/" else "${HELP_BASE}dictation/"
    }

    /**
     * Opens [intent] (a link opens in Zapstore when it handles it, else the browser; our app
     * needs no internet for that) and gets the keyboard out of the way.
     */
    private fun leaveFor(intent: Intent) {
        hidePanel()
        val started = runCatching { ime.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        if (started.isFailure) Log.w(TAG, "nothing opens $intent", started.exceptionOrNull())
        else ime.requestHideSelf(0)
    }

    private fun tone(type: Int, ms: Int, stream: Int) {
        runCatching {
            val g = ToneGenerator(stream, 70)
            g.startTone(type, ms)
            main.postDelayed({ g.release() }, ms + 200L)
        }
    }
}
