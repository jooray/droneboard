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
        when (state) {
            State.RECORDING -> stopRecording()
            State.TRANSCRIBING -> Unit // still typing out the last words
            State.IDLE -> if (panel != null) hidePanel() else start()
        }
        return true
    }

    private fun start() {
        if (OsttTranscriber.component(ime) == null) {
            showPanel(VoiceInputView.Mode.MISSING_OSTT, ime.getString(R.string.voice_ostt_missing), ime.getString(R.string.voice_ostt_missing_detail))
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
            preferBluetooth = true,
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

    override fun onTranscriptionError(message: String, busy: Boolean, last: Boolean) {
        Log.w(TAG, "transcription failed: $message")
        if (!errorShown) {
            errorShown = true
            showPanel(VoiceInputView.Mode.ERROR, ime.getString(R.string.voice_transcription_failed), message)
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

    private fun showPanel(mode: VoiceInputView.Mode, title: String, detail: String?) {
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
        p.show(mode, title, detail)
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

    override fun onInstallTapped() {
        hidePanel()
        runCatching {
            ime.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OsttTranscriber.ZAPSTORE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun tone(type: Int, ms: Int, stream: Int) {
        runCatching {
            val g = ToneGenerator(stream, 70)
            g.startTone(type, ms)
            main.postDelayed({ g.release() }, ms + 200L)
        }
    }
}
