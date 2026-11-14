// SPDX-License-Identifier: GPL-3.0-only
// The recognizer handling follows Third Ear's Transcriber (same author), relicensed under GPL-3.0.
package helium314.keyboard.latin.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "DroneboardOstt"

/**
 * Sends dictation pieces to Offline Speech to Text (OSTT) through the caller-audio contract
 * (`EXTRA_AUDIO_SOURCE`, Android 13+): the keyboard records, OSTT only transcribes, so OSTT needs
 * no microphone grant and the keyboard stays on screen. One piece at a time, in order; results
 * come back on the main thread in the same order.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class OsttTranscriber(private val context: Context, private val listener: Listener) {

    companion object {
        const val OSTT_PACKAGE = "app.offlinespeechtotext"
        const val ZAPSTORE_URL = "https://zapstore.dev/apps/app.offlinespeechtotext"
        private const val RATE = 16_000

        /** OSTT answers a request it can't take now (one caller-audio job at a time) with these. */
        private val BUSY = setOf(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        )
        private val BACKOFF_MS = longArrayOf(500, 1000, 2000, 3000, 5000, 5000, 8000, 10_000)

        /** OSTT's recognition service, or null when OSTT is not installed. */
        fun component(context: Context): ComponentName? = runCatching {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE).setPackage(OSTT_PACKAGE), 0)
                .firstOrNull { it.serviceInfo.exported && it.serviceInfo.enabled }
                ?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        }.getOrNull()
    }

    interface Listener {
        /** Text of one piece, in recording order; empty when the piece held no words. */
        fun onText(text: String, last: Boolean)
        /** Transcription failed for good; [busy] when OSTT kept refusing. */
        fun onTranscriptionError(message: String, busy: Boolean, last: Boolean)
    }

    private class Job(val pcm: ShortArray, val last: Boolean, var attempt: Int = 0)

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Job>()
    private var running: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private var pipe: Array<ParcelFileDescriptor>? = null
    private var timeout: Runnable? = null
    private var cancelled = false

    /** Main thread or any thread: queues a piece. */
    fun submit(pcm: ShortArray, last: Boolean) = main.post {
        if (cancelled) return@post
        queue.addLast(Job(pcm, last))
        pump()
    }

    val idle: Boolean get() = running == null && queue.isEmpty()

    /** Drops queued and running work (the user cancelled, or the keyboard went away). */
    fun cancel() = main.post {
        cancelled = true
        queue.clear()
        running = null
        cleanup(cancelRecognizer = true)
    }

    private fun pump() {
        if (running != null || cancelled) return
        val job = queue.removeFirstOrNull() ?: return
        if (job.pcm.isEmpty()) {
            listener.onText("", job.last)
            pump()
            return
        }
        running = job
        start(job)
    }

    private fun start(job: Job) {
        val c = component(context)
        if (c == null) {
            fail(job, "Offline Speech to Text is not installed", busy = false)
            return
        }
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(context, c)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onResults(results: Bundle?) {
                    if (running !== job) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    done(job)
                    listener.onText(text, job.last)
                    pump()
                }

                override fun onError(error: Int) {
                    if (running !== job) return
                    Log.i(TAG, "recognizer error $error (attempt ${job.attempt})")
                    when {
                        error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            done(job)
                            listener.onText("", job.last)
                            pump()
                        }
                        error in BUSY && job.attempt < BACKOFF_MS.size -> retry(job)
                        else -> fail(job, errorName(error), busy = error in BUSY)
                    }
                }
            })
            val p = ParcelFileDescriptor.createPipe()
            pipe = p
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            // the read end stays open until cleanup: startListening may parcel the intent later,
            // once the service is bound
            r.startListening(intent)
            val writeEnd = p[1]
            Thread({ feed(writeEnd, job.pcm) }, "droneboard-ostt-feed").start()
            // a newer request from another app can retire ours without an answer
            val t = Runnable { if (running === job) retryOrFail(job, "no answer from Offline Speech to Text") }
            timeout = t
            main.postDelayed(t, 60_000L + job.pcm.size * 3000L / RATE)
        } catch (t: Throwable) {
            fail(job, t.message ?: t.javaClass.simpleName, busy = false)
        }
    }

    /** Whole PCM16 frames, then EOF: OSTT finalises when the writer closes. */
    private fun feed(fd: ParcelFileDescriptor, pcm: ShortArray) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
                val step = RATE // one second per write
                val bb = ByteBuffer.allocate(step * 2).order(ByteOrder.LITTLE_ENDIAN)
                var off = 0
                while (off < pcm.size) {
                    val n = minOf(step, pcm.size - off)
                    bb.clear()
                    for (i in off until off + n) bb.putShort(pcm[i])
                    out.write(bb.array(), 0, n * 2)
                    off += n
                }
            }
        } catch (_: IOException) {
            // the reader went away; the listener reports why
        }
    }

    private fun retryOrFail(job: Job, message: String) {
        if (job.attempt < BACKOFF_MS.size) retry(job) else fail(job, message, busy = true)
    }

    private fun retry(job: Job) {
        cleanup(cancelRecognizer = true)
        val wait = BACKOFF_MS[job.attempt]
        job.attempt++
        main.postDelayed({ if (running === job && !cancelled) start(job) }, wait)
    }

    private fun fail(job: Job, message: String, busy: Boolean) {
        done(job)
        listener.onTranscriptionError(message, busy, job.last)
        pump()
    }

    private fun done(job: Job) {
        if (running === job) running = null
        cleanup(cancelRecognizer = false)
    }

    private fun cleanup(cancelRecognizer: Boolean) {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        recognizer?.let { r ->
            if (cancelRecognizer) runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
        pipe?.forEach { runCatching { it.close() } }
        pipe = null
    }

    private fun errorName(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Offline Speech to Text is busy"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Offline Speech to Text disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "no speech model in Offline Speech to Text"
        else -> "recognition error $error"
    }
}
