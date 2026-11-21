// SPDX-License-Identifier: GPL-3.0-only
// The recognizer handling follows Third Ear's Transcriber (same author), relicensed under GPL-3.0.
package helium314.keyboard.latin.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore

private const val TAG = "DroneboardOstt"

/**
 * Serves the recording of a dictation to Offline Speech to Text, and to nothing else: the file is in the app's
 * cache folder, the provider is not exported, and OSTT gets a read grant for the one file of the running dictation.
 * Separate from the clipboard's provider so that neither can hand out the other's files.
 */
class DictationAudioProvider : FileProvider() {
    companion object {
        /** The authority in the manifest: the app id (which differs in debug builds) plus this. */
        fun authority(context: Context) = context.packageName + ".dictationaudio"
    }
}

/**
 * Sends a dictation to Offline Speech to Text (OSTT). The keyboard records, OSTT only transcribes, so OSTT needs
 * no microphone grant and the keyboard stays on screen.
 *
 * Three ways, the best the installed OSTT can do:
 * - live ([prepare], an OSTT that declares a long caller-audio session): one request that runs while the user
 *   talks, fed through a pipe, answered once after the pipe is closed. The text comes soon after the last word.
 * - one audio file ([submitRecording], OSTT 0.22+): the whole recording in one request after the user stopped.
 *   Also what a live dictation falls back to when its stream breaks, so no dictation is lost to the stream.
 * - pieces: an OSTT that can't take files gets the recording cut up through the caller-audio contract
 *   (`EXTRA_AUDIO_SOURCE`, Android 13+), which such an OSTT ends after 60 s per request.
 * One request at a time, in order; results come back on the main thread in the same order.
 *
 * [words] gives the user's own words for a request (see [DictationWords]); they go with every request, whatever
 * the way, and are never logged.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class OsttTranscriber(
    private val context: Context,
    private val listener: Listener,
    private val words: () -> List<String> = { emptyList() },
) {

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

        /** OSTT's extra for an audio file to transcribe: a content Uri the caller granted read access to. */
        private const val EXTRA_AUDIO_URI = "app.offlinespeechtotext.extra.AUDIO_URI"
        /** OSTT's screen for transcribing a file; it came in the same version as [EXTRA_AUDIO_URI]. */
        private const val ACTION_TRANSCRIBE_AUDIO = "app.offlinespeechtotext.action.TRANSCRIBE_AUDIO"
        private const val PREF_FILE_ROUTE = "dictation_ostt_file_route"
        /** Pieces that wait in memory when a recording is cut up; the rest stays in the file until there is room. */
        private const val PIECES_IN_MEMORY = 2

        /** OSTT's service says in its manifest how long a caller-audio request may last, in seconds. */
        private const val META_CALLER_AUDIO_MAX_SECONDS = "app.offlinespeechtotext.capability.CALLER_AUDIO_MAX_SECONDS"
        /** OSTT's service says in its manifest how many of the caller's words it reads per request. */
        private const val META_BIASING_STRINGS_MAX_COUNT = "app.offlinespeechtotext.capability.BIASING_STRINGS_MAX_COUNT"
        /** RecognizerIntent.EXTRA_BIASING_STRINGS; the name is spelled out as OSTT's contract does. */
        private const val EXTRA_BIASING_STRINGS = "android.speech.extra.BIASING_STRINGS"

        /**
         * What the installed OSTT says about itself on its recognition service [component]. The keyboard may see
         * that service because the manifest lists OSTT under queries. An OSTT that says nothing is an old one.
         */
        @Suppress("DEPRECATION") // the newer overload only exists from Android 13 and does the same
        fun capabilities(context: Context, component: ComponentName): Capabilities = runCatching {
            val meta = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA).metaData
            Capabilities(
                meta?.getInt(META_CALLER_AUDIO_MAX_SECONDS, LiveRoute.OLD_BOUND_SECONDS) ?: LiveRoute.OLD_BOUND_SECONDS,
                meta?.getInt(META_BIASING_STRINGS_MAX_COUNT, 0) ?: 0,
            )
        }.getOrDefault(Capabilities(LiveRoute.OLD_BOUND_SECONDS, 0))

        /** OSTT's recognition service, or null when OSTT is not installed. */
        fun component(context: Context): ComponentName? = runCatching {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE).setPackage(OSTT_PACKAGE), 0)
                .firstOrNull { it.serviceInfo.exported && it.serviceInfo.enabled }
                ?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        }.getOrNull()

        /** Whether the OSTT package is on the phone at all, with or without a usable recognizer. */
        fun installed(context: Context): Boolean = runCatching {
            context.packageManager.getPackageInfo(OSTT_PACKAGE, 0)
            true
        }.getOrDefault(false)

        /** OSTT's version code, or -1 when it is not installed. */
        fun versionCode(context: Context): Long = runCatching {
            context.packageManager.getPackageInfo(OSTT_PACKAGE, 0).longVersionCode
        }.getOrDefault(-1L)

        /**
         * Whether the installed OSTT says it takes audio files. This is asked before a file request is sent, because
         * an OSTT that does not know the extra would treat the request as an ordinary one and open its own microphone.
         */
        fun declaresFileApi(context: Context): Boolean = runCatching {
            val probe = Intent(ACTION_TRANSCRIBE_AUDIO).setPackage(OSTT_PACKAGE)
                .setDataAndType(Uri.parse("content://droneboard.invalid/probe"), "audio/x-wav")
            context.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * [callerAudioMaxSeconds]: how long OSTT reads one caller-audio request (60 for an OSTT that does not say).
     * [biasingMaxCount]: how many of the caller's words it reads per request (0: none).
     */
    data class Capabilities(val callerAudioMaxSeconds: Int, val biasingMaxCount: Int)

    /** Why a piece failed, for what the panel tells the user to do. */
    enum class Problem { NOT_INSTALLED, NO_MODEL, PERMISSION, OTHER }

    interface Listener {
        /** Text of one piece, in recording order; empty when the piece held no words. */
        fun onText(text: String, last: Boolean)
        /** Transcription failed for good; [busy] when OSTT kept refusing. */
        fun onTranscriptionError(message: String, busy: Boolean, last: Boolean, problem: Problem)
    }

    /** One request: a piece of PCM through the pipe, or ([audio] set) the whole recording as a file. */
    private class Job(
        val pcm: ShortArray, val last: Boolean, var attempt: Int = 0,
        val audio: Uri? = null, val durationMs: Long = 0, val osttVersion: Long = -1,
        /** this piece holds one of the [PIECES_IN_MEMORY] places */
        val counted: Boolean = false,
    )

    private val memory = FileRouteMemory(object : FileRouteStore {
        override fun read(): String? = context.prefs().getString(PREF_FILE_ROUTE, null)
        override fun write(value: String) = context.prefs().edit { putString(PREF_FILE_ROUTE, value) }
    })
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Job>()
    private var running: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private var pipe: Array<ParcelFileDescriptor>? = null
    private var timeout: Runnable? = null
    @Volatile private var cancelled = false
    /** the recording that is being transcribed; deleted the moment it is not needed any more */
    private var recording: File? = null
    private var granted: Uri? = null
    private val room = Semaphore(PIECES_IN_MEMORY)

    /** The live request of this dictation, from [prepare] until its text is typed or the file took over. */
    private class Live(val readEnd: ParcelFileDescriptor) {
        val session = LiveSession()
        lateinit var feed: LiveFeed
        var recognizer: SpeechRecognizer? = null
        var timeout: Runnable? = null
        var released = false
        // the finished recording as the file route needs it, known once the recording ended
        var uri: Uri? = null
        var durationMs = 0L
        var endedAt = 0L
    }
    private var live: Live? = null

    /**
     * Main thread, before the recording starts: decides the way of this dictation and says so in the log.
     * [haveFile]: the recording is written to a file. Returns the feed for the recording thread when the dictation
     * is streamed live, else null.
     *
     * The request itself starts with the first recorded buffer and not here: OSTT gives up a request that gets no
     * byte for 60 seconds, its session bound counts from the start of the request, and a Bluetooth microphone can
     * take seconds to deliver. Until then nothing is lost, the first buffers wait in the pipe.
     */
    fun prepare(capabilities: Capabilities, haveFile: Boolean): LiveFeed? {
        val version = versionCode(context)
        val bound = capabilities.callerAudioMaxSeconds
        val feed = if (haveFile && LiveRoute.live(bound)) openLive() else null
        val takesFiles = memory.useFile(version, declaresFileApi(context))
        val route = when {
            feed != null -> "live stream"
            !haveFile -> "pieces while recording (no place for a file)"
            takesFiles -> "one file after the stop"
            else -> "pieces after the stop"
        }
        Log.i(TAG, "dictation route: $route (OSTT $version reads caller audio for $bound s, takes files: $takesFiles, " +
            "reads up to ${capabilities.biasingMaxCount} preferred words)")
        return feed
    }

    private fun openLive(): LiveFeed? {
        val p = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse {
            Log.w(TAG, "no pipe for a live stream (${it.javaClass.simpleName})")
            return null
        }
        val l = Live(p[0])
        l.feed = LiveFeed(
            ParcelFileDescriptor.AutoCloseOutputStream(p[1]),
            onFirstAudio = { main.post { startLive(l) } },
            onBroken = { reason -> main.post { liveBroken(l, reason) } },
        )
        live = l
        return l.feed
    }

    private fun startLive(l: Live) {
        if (cancelled || live !== l || l.released || !l.session.streaming || l.recognizer != null) return
        val c = component(context)
        if (c == null) {
            liveBroken(l, "Offline Speech to Text is not installed")
            return
        }
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(context, c)
            l.recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onResults(results: Bundle?) =
                    liveAnswer(l, results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty())

                override fun onError(error: Int) {
                    // "no words" is an answer like any other; everything else (busy with another job, OSTT gone,
                    // a problem with the audio) ends the stream and leaves the dictation to the file
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) liveAnswer(l, "")
                    else liveBroken(l, "recognizer error $error (${errorName(error)})")
                }
            })
            // the read end stays open until the request is released: startListening may parcel the intent later
            r.startListening(pipeIntent(l.readEnd))
            Log.i(TAG, "live request started")
        } catch (t: Throwable) {
            liveBroken(l, "the request could not start (${t.javaClass.simpleName})")
        }
    }

    private fun liveAnswer(l: Live, text: String) {
        if (live !== l || cancelled) return
        val complete = l.feed.completed
        if (!complete && l.session.streaming)
            Log.i(TAG, "live stream failed: OSTT answered before the recording was over; the recording goes as a file when it ends")
        perform(l, l.session.onResult(text, complete))
    }

    private fun liveBroken(l: Live, reason: String) {
        if (live !== l || cancelled) return
        if (l.session.streaming) Log.i(TAG, "live stream failed: $reason; the recording goes as a file when it ends")
        perform(l, l.session.onBroken())
    }

    private fun perform(l: Live, step: LiveSession.Step) {
        when (step) {
            LiveSession.Step.NOTHING -> Unit
            // OSTT is free for the file request later; the recording goes on into the file
            LiveSession.Step.ABANDON -> releaseLive(l, cancelRecognizer = true)
            LiveSession.Step.WAIT -> {
                val wait = LiveRoute.finalTimeoutMs(l.durationMs)
                Log.i(TAG, "recording of ${l.durationMs} ms was streamed live, waiting up to $wait ms for the text")
                val t = Runnable { liveBroken(l, "no answer from Offline Speech to Text") }
                l.timeout = t
                main.postDelayed(t, wait)
            }
            LiveSession.Step.SEND_FILE -> {
                releaseLive(l, cancelRecognizer = true)
                live = null
                val file = recording
                if (file == null) {
                    listener.onTranscriptionError("the recording is gone", busy = false, last = true, problem = Problem.OTHER)
                    return
                }
                Log.i(TAG, "falling back from the live stream to the recorded file")
                sendRecording(file, l.uri, l.durationMs)
            }
            LiveSession.Step.TYPE -> {
                Log.i(TAG, "live request answered ${SystemClock.elapsedRealtime() - l.endedAt} ms after the recording ended " +
                    "(${l.feed.bytesWritten} bytes streamed)")
                releaseLive(l, cancelRecognizer = false)
                live = null
                dropRecording()
                listener.onText(l.session.text.orEmpty(), true)
            }
        }
    }

    /** Frees the live request: its recognizer, its pipe, its timer. Safe to call twice. */
    private fun releaseLive(l: Live, cancelRecognizer: Boolean) {
        l.timeout?.let { main.removeCallbacks(it) }
        l.timeout = null
        if (l.released) return
        l.released = true
        l.feed.abort()
        l.recognizer?.let { r ->
            if (cancelRecognizer) runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        l.recognizer = null
        runCatching { l.readEnd.close() }
    }

    /** Main thread: the recording ended without a single sample, so there is nothing a live request could answer. */
    fun abandonLive() {
        live?.let { releaseLive(it, cancelRecognizer = true) }
        live = null
    }

    /** Every request carries the user's own words when the installed OSTT reads them. Only their number is logged. */
    private fun recognitionIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        val own = runCatching(words).getOrDefault(emptyList())
        if (own.isNotEmpty()) intent.putStringArrayListExtra(EXTRA_BIASING_STRINGS, ArrayList(own))
        Log.i(TAG, "request carries ${own.size} preferred words")
        return intent
    }

    /** A request whose audio comes through the pipe [readEnd]: 16 kHz mono PCM16. */
    private fun pipeIntent(readEnd: ParcelFileDescriptor): Intent = recognitionIntent()
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)

    /** Main thread or any thread: queues a piece. */
    fun submit(pcm: ShortArray, last: Boolean) = main.post {
        if (cancelled) return@post
        queue.addLast(Job(pcm, last))
        pump()
    }

    /**
     * Main thread: the whole dictation, recorded into [file] (a WAV of [durationMs]). When it was streamed live, the
     * text of the stream is waited for and the file is only the reserve. Otherwise, and when the stream broke, it is
     * transcribed in one request when the installed OSTT takes files, else cut into pieces. The file is this
     * object's from here on and is deleted when the text or a final error has arrived, or on [cancel]. [uri] is the
     * file as OSTT may read it.
     */
    fun submitRecording(file: File, uri: Uri?, durationMs: Long) {
        if (cancelled) {
            file.delete()
            return
        }
        recording = file
        val l = live
        if (l != null) {
            l.uri = uri
            l.durationMs = durationMs
            l.endedAt = SystemClock.elapsedRealtime()
            perform(l, l.session.onRecordingEnded())
            return
        }
        sendRecording(file, uri, durationMs)
    }

    private fun sendRecording(file: File, uri: Uri?, durationMs: Long) {
        val version = versionCode(context)
        val declared = declaresFileApi(context)
        if (uri == null || !memory.useFile(version, declared)) {
            Log.i(TAG, "recording of $durationMs ms goes in pieces (OSTT $version, takes files: $declared, remembered: ${memory.support(version)})")
            submitInPieces(file)
            return
        }
        // only OSTT, only this file, only for reading; taken back in [revokeGrant]
        val grant = runCatching { context.grantUriPermission(OSTT_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (grant.isFailure) {
            Log.w(TAG, "could not grant the recording to OSTT", grant.exceptionOrNull())
            submitInPieces(file)
            return
        }
        Log.i(TAG, "recording of $durationMs ms goes as one file (OSTT $version)")
        granted = uri
        queue.addLast(Job(ShortArray(0), last = true, audio = uri, durationMs = durationMs, osttVersion = version))
        pump()
    }

    /**
     * The way for an OSTT without the file route: [file] is read on its own thread, cut by the [Chunker] and the
     * pieces go through the pipe in order. The thread waits while [PIECES_IN_MEMORY] pieces are not yet transcribed,
     * so a long recording stays in the file and not in memory.
     */
    private fun submitInPieces(file: File) {
        Thread({
            val chunker = Chunker(RATE) { pcm, last ->
                room.acquireUninterruptibly()
                if (cancelled) return@Chunker
                main.post {
                    if (cancelled) return@post
                    queue.addLast(Job(pcm, last, counted = true))
                    pump()
                }
            }
            try {
                Wav.readPcm(file, RATE) { pcm, count ->
                    chunker.write(pcm, count)
                    !cancelled
                }
            } catch (e: IOException) {
                Log.w(TAG, "could not read the recording to the end: ${e.message}")
            } finally {
                // everything is in the pieces now; the last one always goes out, so the dictation ends
                file.delete()
                if (!cancelled) chunker.finish()
            }
        }, "droneboard-ostt-pieces").start()
    }

    val idle: Boolean get() = running == null && queue.isEmpty()

    /** Drops queued and running work (the user cancelled, or the keyboard went away). */
    fun cancel() {
        cancelled = true
        room.release(PIECES_IN_MEMORY + 1) // a reader waiting for room sees the cancel and stops
        main.post {
            queue.clear()
            running = null
            cleanup(cancelRecognizer = true)
            abandonLive()
            dropRecording()
        }
    }

    private fun pump() {
        if (running != null || cancelled) return
        val job = queue.removeFirstOrNull() ?: return
        if (job.pcm.isEmpty() && job.audio == null) {
            if (job.counted) room.release()
            if (job.last) dropRecording()
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
            fail(job, "Offline Speech to Text is not installed", busy = false, problem = Problem.NOT_INSTALLED)
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
                    if (job.audio != null) rememberFileRoute(job, null)
                    done(job)
                    listener.onText(text, job.last)
                    pump()
                }

                override fun onError(error: Int) {
                    if (running !== job) return
                    Log.i(TAG, "recognizer error $error (attempt ${job.attempt})")
                    if (job.audio != null) {
                        rememberFileRoute(job, error)
                        if (FileRoute.verdict(error) == FileRoute.Support.UNSUPPORTED) {
                            fileRefused(job, error)
                            return
                        }
                    }
                    when {
                        error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            done(job)
                            listener.onText("", job.last)
                            pump()
                        }
                        error in BUSY && job.attempt < BACKOFF_MS.size -> retry(job)
                        else -> fail(job, errorName(error), busy = error in BUSY, problem = problemOf(error))
                    }
                }
            })
            if (job.audio != null) {
                r.startListening(recognitionIntent().putExtra(EXTRA_AUDIO_URI, job.audio))
                // OSTT answers every file request; this only ends a wait when it never does. Sending a long
                // recording again after such a wait would only double it, so there is no second try.
                val t = Runnable {
                    if (running !== job) return@Runnable
                    cleanup(cancelRecognizer = true)
                    fail(job, "no answer from Offline Speech to Text", busy = true)
                }
                timeout = t
                main.postDelayed(t, FileRoute.timeoutMs(job.durationMs))
                return
            }
            val p = ParcelFileDescriptor.createPipe()
            pipe = p
            // the read end stays open until cleanup: startListening may parcel the intent later,
            // once the service is bound
            r.startListening(pipeIntent(p[0]))
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

    /**
     * OSTT answered the file request the way an OSTT without the file route does. Nothing is lost: the same
     * recording goes in pieces. When OSTT has a real problem instead (it lost a permission), the first piece
     * fails the same way and that is what the user is told.
     */
    private fun fileRefused(job: Job, error: Int) {
        Log.i(TAG, "OSTT ${job.osttVersion} refused the audio file (error $error), sending pieces")
        running = null
        cleanup(cancelRecognizer = false)
        revokeGrant()
        val file = recording
        if (file != null) submitInPieces(file) else fail(job, errorName(error), busy = false, problem = problemOf(error))
    }

    /** What the answer to a file request says about this version of OSTT ([error] null: a transcript). */
    private fun rememberFileRoute(job: Job, error: Int?) =
        memory.remember(job.osttVersion, versionCode(context), FileRoute.verdict(error))

    private fun retryOrFail(job: Job, message: String) {
        if (job.attempt < BACKOFF_MS.size) retry(job) else fail(job, message, busy = true)
    }

    private fun retry(job: Job) {
        cleanup(cancelRecognizer = true)
        val wait = BACKOFF_MS[job.attempt]
        job.attempt++
        main.postDelayed({ if (running === job && !cancelled) start(job) }, wait)
    }

    private fun fail(job: Job, message: String, busy: Boolean, problem: Problem = Problem.OTHER) {
        done(job)
        listener.onTranscriptionError(message, busy, job.last, problem)
        pump()
    }

    private fun done(job: Job) {
        if (running === job) running = null
        cleanup(cancelRecognizer = false)
        if (job.counted) room.release()
        // the text or the final error of the dictation is here: the audio has no reason to exist any more
        if (job.last) dropRecording()
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

    private fun revokeGrant() {
        granted?.let { uri ->
            runCatching { context.revokeUriPermission(OSTT_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        granted = null
    }

    private fun dropRecording() {
        revokeGrant()
        recording?.delete()
        recording = null
    }

    private fun problemOf(error: Int) = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Problem.PERMISSION
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> Problem.NO_MODEL
        else -> Problem.OTHER
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
