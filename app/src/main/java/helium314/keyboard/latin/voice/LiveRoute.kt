// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// A live dictation is one request to Offline Speech to Text (OSTT) that lasts as long as the recording: the audio
// goes through a pipe while the user talks, OSTT transcribes it as it arrives, and when the user stops only the end
// is left to decode, so the text comes fast. The recording is written to the file all the same, so a stream that
// breaks costs nothing but time: the file goes the old way. Everything in this file is free of Android, for the tests.

/** When a dictation is streamed live, and the numbers that go with it. */
object LiveRoute {
    /** What an OSTT that says nothing reads of a caller-audio request: the first minute, the rest is dropped silently. */
    const val OLD_BOUND_SECONDS = 60

    /**
     * The shortest session bound a live dictation is tried with. A live recording has to end before the bound, and
     * the file route has no bound at all, so an OSTT that reads less than ten minutes would make dictations shorter
     * than they are without the stream. The OSTT that has the stream declares an hour.
     */
    const val MIN_BOUND_SECONDS = 600

    /**
     * How long before OSTT's bound the recording ends. The bound counts from the start of the request and OSTT stops
     * reading at it without an error, so everything recorded must be written before. Two minutes cover the time
     * between the start of the request and the audio it reads last: the hand-off ([HANDOFF_BYTES], 30 s) and a
     * pipe that is read late, with room to spare.
     */
    const val BOUND_MARGIN_SECONDS = 120

    /**
     * Audio that may wait between the recording thread and the pipe: 30 seconds of 16 kHz PCM16, just under 1 MB.
     * OSTT reads the pipe all the time and only stops when two minutes of audio wait for its speech model, so this
     * only has to bridge OSTT starting up (its process, its service). When it is full, OSTT is not keeping up or is
     * not reading at all: the stream is given up and the file is sent when the user stops.
     */
    const val HANDOFF_BYTES = 30 * 16_000 * 2

    /**
     * OSTT ends a request that gets no byte for 10 seconds, taking the writer for dead. The microphone can be quiet
     * for that long without the user having stopped (Android switches from the headset to the phone, a Bluetooth
     * input takes its time), so after this long without a buffer a tenth of a second of silence is written.
     */
    const val KEEP_ALIVE_MS = 3000L

    /** True when a dictation is streamed: OSTT declares it reads caller audio for [boundSeconds], long enough to be worth it. */
    fun live(boundSeconds: Int): Boolean = boundSeconds >= MIN_BOUND_SECONDS

    /** The longest live recording: [longestSeconds] as for every dictation, or less when OSTT's bound is nearer. */
    fun maxRecordingSeconds(boundSeconds: Int, longestSeconds: Int): Int =
        minOf(longestSeconds, boundSeconds - BOUND_MARGIN_SECONDS).coerceAtLeast(0)

    /**
     * How long to wait for the text once the recording of [durationMs] is over and the pipe is closed. What OSTT may
     * still have to decode then is its last window (up to a minute of audio) and, when its model is slower than
     * speech, the two minutes that can wait in its queue; a slower model than that has filled the hand-off long
     * before. A minute for a model that has to be loaded, plus three times that audio, as [FileRoute.timeoutMs]
     * allows three times the recording: a minute and a half for a ten second dictation, ten minutes at most. OSTT reports its own
     * failures at once; this only ends a wait for an answer that never comes, and then the file is sent.
     */
    fun finalTimeoutMs(durationMs: Long): Long = 60_000L + 3 * durationMs.coerceIn(0, 180_000L)
}

/**
 * What happens to a live dictation, decided in one place so the text can never be typed twice: either OSTT's
 * answer to the stream is typed ([Step.TYPE]) or the recording is sent as a file ([Step.SEND_FILE]), and after
 * either one nothing else is. All calls come from the main thread.
 */
class LiveSession {
    enum class Step {
        NOTHING,
        /** The stream is over but the recording goes on: free the request, the file is sent when the recording ends. */
        ABANDON,
        /** The recording ended and all of it is in the stream: wait for the text, for [LiveRoute.finalTimeoutMs]. */
        WAIT,
        /** The stream gave no text: send the finished recording as a file. */
        SEND_FILE,
        /** [text] is the dictation. */
        TYPE,
    }

    private var broken = false
    private var recordingEnded = false
    private var over = false

    /** OSTT's answer to the stream, once it is known to cover the whole recording; empty when there were no words. */
    var text: String? = null
        private set

    /** The stream is still what this dictation relies on. */
    val streaming: Boolean get() = !broken && !over

    /** The request failed, was refused (OSTT busy), got no answer in time, or the audio could not be handed over. */
    fun onBroken(): Step {
        if (over || broken) return Step.NOTHING
        broken = true
        text = null
        if (!recordingEnded) return Step.ABANDON
        over = true
        return Step.SEND_FILE
    }

    /** The recording is finished and in its file; the pipe is closed or about to be. */
    fun onRecordingEnded(): Step {
        if (over || recordingEnded) return Step.NOTHING
        recordingEnded = true
        if (!broken && text == null) return Step.WAIT
        over = true
        return if (broken) Step.SEND_FILE else Step.TYPE
    }

    /**
     * OSTT answered the stream with [result] (empty: no words). [inputComplete] says the whole recording had been
     * written and the pipe closed before. An answer that comes earlier means OSTT ended the request on its own (it
     * saw no audio for too long), so its text stops somewhere in the middle: it is dropped and the file is used.
     */
    fun onResult(result: String, inputComplete: Boolean): Step {
        if (over || broken || text != null) return Step.NOTHING
        if (!inputComplete) return onBroken()
        text = result
        // the answer can overtake the news that the recording ended; then it waits for it
        if (!recordingEnded) return Step.NOTHING
        over = true
        return Step.TYPE
    }
}

/**
 * Hands the recording to the pipe without ever making the recording thread wait. [offer] only puts a buffer in a
 * queue; a thread of its own writes the queue to [out], which blocks whenever OSTT is not reading. The queue holds
 * at most [capacityBytes]. When it is full nothing is dropped from the middle of the stream (a transcript with a
 * hole is worse than a late one): the whole stream is given up, [onBroken] is called once, and the caller sends the
 * file instead. [onFirstAudio] is called with the first buffer, from the recording thread; [onBroken] from the
 * recording thread or the writer.
 */
class LiveFeed(
    private val out: OutputStream,
    private val capacityBytes: Int = LiveRoute.HANDOFF_BYTES,
    private val keepAliveMs: Long = LiveRoute.KEEP_ALIVE_MS,
    private val onFirstAudio: () -> Unit = {},
    private val onBroken: (reason: String) -> Unit = {},
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val waiting = AtomicLong(0)
    private val end = ByteArray(0)
    @Volatile private var closed = false // no more audio is taken: ended, aborted or broken
    @Volatile private var aborted = false
    private var first = true // recording thread only
    private var failed = false
    private val writer = Thread({ writeLoop() }, "droneboard-ostt-live").apply { isDaemon = true }

    /** Everything that was offered is written and the pipe is closed: an answer from now on covers the whole recording. */
    @Volatile var completed = false
        private set

    /** Bytes written to the pipe, for the log. */
    @Volatile var bytesWritten = 0L
        private set

    init {
        writer.start()
    }

    /** From the recording thread: [count] samples of [pcm]. Returns at once, whatever the reader does. */
    fun offer(pcm: ShortArray, count: Int) {
        if (closed || count <= 0) return
        if (first) {
            first = false
            onFirstAudio()
        }
        val bytes = ByteArray(count * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, count)
        if (waiting.get() + bytes.size > capacityBytes) {
            fail("the reader is ${waiting.get()} bytes behind")
            return
        }
        waiting.addAndGet(bytes.size.toLong())
        queue.add(bytes)
    }

    /** The recording ended: what is queued is still written, then the pipe is closed, which tells OSTT to finish. */
    fun end() {
        if (closed) return
        closed = true
        queue.add(end)
    }

    /** Cancelled, or the stream is given up: nothing more is written. */
    fun abort() {
        aborted = true
        closed = true
        queue.clear()
        queue.add(end)
    }

    /** For the tests: waits until the writer thread is done. */
    fun join(timeoutMs: Long) = writer.join(timeoutMs)

    private fun fail(reason: String) {
        val report = synchronized(this) {
            if (failed || aborted || completed) false else true.also { failed = true }
        }
        aborted = true
        closed = true
        queue.clear()
        queue.add(end)
        if (report) onBroken(reason)
    }

    private fun writeLoop() {
        val silence = ByteArray(3200) // a tenth of a second
        var started = false
        try {
            while (!aborted) {
                val bytes = queue.poll(keepAliveMs, TimeUnit.MILLISECONDS)
                if (bytes === end) break
                if (bytes == null) {
                    // nothing was recorded for a while; before the first audio there is no request to keep alive
                    if (started && !closed) out.write(silence)
                    continue
                }
                out.write(bytes)
                started = true
                waiting.addAndGet(-bytes.size.toLong())
                bytesWritten += bytes.size
            }
            if (!aborted) {
                out.flush()
                completed = true
            }
        } catch (e: IOException) {
            // the reader closed its end: OSTT ended the request, or is gone
            fail("the pipe broke")
        } catch (_: InterruptedException) {
            aborted = true
        } finally {
            runCatching { out.close() }
        }
    }
}

/**
 * The sink of a live dictation: everything goes to the file first, and what the file took goes to the stream, so
 * both hold the same recording and the file can stand in for the stream at any moment. The limits are the file's.
 */
class LiveRecordingSink(private val file: AudioSink, private val feed: LiveFeed) : AudioSink {
    override val samplesWritten: Long get() = file.samplesWritten

    override fun write(pcm: ShortArray, count: Int) {
        val before = file.samplesWritten
        file.write(pcm, count)
        // the file takes less than it was given once the longest dictation is reached or the storage is full
        feed.offer(pcm, (file.samplesWritten - before).toInt())
    }

    override fun finish() {
        try {
            file.finish()
        } finally {
            feed.end()
        }
    }

    override fun abort() {
        feed.abort()
        file.abort()
    }
}
