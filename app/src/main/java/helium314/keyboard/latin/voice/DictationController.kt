// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DirectBoot
import helium314.keyboard.latin.utils.prefs

private const val TAG = "DroneboardDictation"

/**
 * One-tap dictation inside the keyboard: the keyboard records (phone or Bluetooth headset, the
 * Third Ear way), Offline Speech to Text transcribes, and the text lands at the cursor. No
 * keyboard switching. Tap the mic to start, tap again to stop; there is no stop on silence.
 * Everything here runs on the main thread except the recording loop.
 * Only reachable on Android 13+ ([supported] gates [onMicKey], the one entry point).
 */
@SuppressLint("NewApi")
class DictationController(private val ime: LatinIME) : VoiceInputView.Actions, OsttTranscriber.Listener {

    companion object {
        const val SAMPLE_RATE = 16_000
        /** Caller audio to a recognizer needs Android 13. */
        val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
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

    val isActive get() = state != State.IDLE || panel != null

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
            State.TRANSCRIBING -> Unit // still typing out the last words
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
        val t = OsttTranscriber(ime, this)
        transcriber = t
        val chunker = Chunker(SAMPLE_RATE) { pcm, last -> t.submit(pcm, last) }
        val e = RecordingEngine(
            sources = AndroidSources(ime),
            sink = chunker,
            sampleRate = SAMPLE_RATE,
            preferBluetooth = ime.prefs().getBoolean(Settings.PREF_DICTATION_USE_BLUETOOTH_MIC, Defaults.PREF_DICTATION_USE_BLUETOOTH_MIC),
            bluetoothAvailable = { BluetoothMics.available(ime) },
            listener = engineListener,
        )
        engine = e
        state = State.RECORDING
        showPanel(VoiceInputView.Mode.STARTING, ime.getString(R.string.voice_starting), null)
        e.start()
    }

    private fun stopRecording() {
        val e = engine ?: return
        state = State.TRANSCRIBING
        panel?.show(VoiceInputView.Mode.TRANSCRIBING, ime.getString(R.string.voice_transcribing), micLabel(lastStatus))
        e.stop()
    }

    /** Cancel: drops the recording and anything not yet typed. */
    fun cancel() {
        engine?.stop(discardAudio = true)
        engine = null
        transcriber?.cancel()
        transcriber = null
        state = State.IDLE
        hidePanel()
    }

    /** The keyboard went away: a running recording is dropped, a finished one still gets typed if the field comes back. */
    fun onFinishInputView() {
        if (state == State.RECORDING) cancel() else hidePanel()
    }

    // ---- recording ----

    private val engineListener = object : RecordingEngine.Listener {
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
                engine = null
                if (failure != null && samples == 0L && state != State.IDLE) {
                    transcriber?.cancel()
                    transcriber = null
                    state = State.IDLE
                    showPanel(VoiceInputView.Mode.ERROR, ime.getString(R.string.voice_mic_failed), failure)
                }
            }
        }
    }

    private fun onEngineStatus(s: EngineStatus) {
        val wasWaiting = lastStatus?.waitingForBluetooth == true
        lastStatus = s
        val p = panel ?: return
        if (state != State.RECORDING) return
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
        }
    }

    private fun finished() {
        transcriber = null
        state = State.IDLE
        if (!errorShown) hidePanel()
    }

    private fun type(text: String) {
        val editor: EditorInfo? = ime.currentInputEditorInfo
        if (editor == null || field != (editor.packageName to editor.fieldId)) {
            Log.i(TAG, "field changed, dropping ${text.length} dictated characters")
            return
        }
        val ic = ime.currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        val joined = TextJoiner.join(before, text)
        if (joined.isNotEmpty()) ime.onTextInput(joined)
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
        if (p == null || p.parent == null) {
            val w = wrapper() ?: return
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
