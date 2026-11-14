// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Third Ear (app.thirdear.audio.RecordingEngine), by the same author, relicensed under GPL-3.0.
package helium314.keyboard.latin.voice

import java.util.concurrent.ConcurrentLinkedQueue

private const val PUBLISH_MS = 80L
private const val EMPTY_READ_SLEEP_MS = 10L

// AudioRecord.read's errors (this file stays free of Android for the tests)
private const val ERROR_INVALID_OPERATION = -3
private const val ERROR_BAD_VALUE = -2
private const val ERROR_DEAD_OBJECT = -6

/** Takes PCM from the engine thread. */
interface AudioSink {
    val samplesWritten: Long
    fun write(pcm: ShortArray, count: Int)
    /** The recording ended normally; flush what is buffered. */
    fun finish()
    /** The recording was cancelled; drop everything. */
    fun abort()
}

/** What the listening panel shows. */
data class EngineStatus(
    val starting: Boolean = true,
    /** The source that is open or being opened. */
    val source: Source = Source.PHONE,
    /** Where Android really routed it. */
    val input: RoutedInput = RoutedInput.PHONE,
    /** True between opening Bluetooth and its first real sample: "waiting for the headset". */
    val waitingForBluetooth: Boolean = false,
    val switching: Boolean = false,
    /** Exact digital zeros for a while, or Android silencing us. */
    val silent: Boolean = false,
    val fallbackReason: String? = null,
    val samples: Long = 0,
    val sampleRate: Int = 16_000,
    val amplitude: Int = 0,
) {
    val durationMs: Long get() = samples * 1000 / sampleRate
}

/**
 * One dictation recording: reads the current microphone on its own thread and feeds the [AudioSink].
 * The microphone can change mid-recording (a Bluetooth fallback, a disconnect) and the sink just continues.
 */
class RecordingEngine(
    private val sources: PcmSourceFactory,
    private val sink: AudioSink,
    private val sampleRate: Int,
    private val preferBluetooth: Boolean,
    private val bluetoothAvailable: () -> Boolean,
    private val listener: Listener,
    private val clock: () -> Long = System::currentTimeMillis,
    firstSoundTimeoutMs: Long = SourcePolicy.DEFAULT_FIRST_SOUND_TIMEOUT_MS,
    silenceSeconds: Int = 5,
) {
    interface Listener {
        fun onStatus(status: EngineStatus)
        fun onCue(cue: SourcePolicy.Cue)
        fun onLog(message: String)
        /** The loop ended; [failure] is set when it ended because the microphone failed. */
        fun onFinished(samples: Long, failure: String?)
    }

    /** An event from the source's callbacks, applied on the engine thread between reads. */
    private data class Command(val event: SourceEvent, val generation: Int)

    private val policy = SourcePolicy(firstSoundTimeoutMs)
    private val zeros = ZeroRunDetector(sampleRate * silenceSeconds)
    private val commands = ConcurrentLinkedQueue<Command>()
    @Volatile private var stopRequested = false
    @Volatile private var discard = false
    @Volatile var status = EngineStatus(sampleRate = sampleRate)
        private set
    private var source: PcmSource? = null
    private var generation = 0
    private var androidSilenced = false
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ runLoop() }, "droneboard-dictation").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Ends the recording; the sink is finished unless [discardAudio]. Safe from any thread. */
    fun stop(discardAudio: Boolean = false) {
        discard = discardAudio
        stopRequested = true
    }

    fun join(timeoutMs: Long) = thread?.join(timeoutMs)

    fun runLoop() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        var failure: String? = null
        try {
            val first = policy.start(preferBluetooth, preferBluetooth && bluetoothAvailable())
            publish { it.copy(starting = true, source = first, fallbackReason = policy.fallbackReason) }
            failure = openOrFail(SourcePolicy.Action.Open(first, reason = "start"))
            publish { it.copy(starting = false) }
            if (failure == null) failure = loop()
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.simpleName
            listener.onLog("recorder crashed: $failure")
        } finally {
            closeSource()
            try {
                if (discard) sink.abort() else sink.finish()
            } catch (t: Throwable) {
                listener.onLog("finishing failed: ${t.message}")
            }
            listener.onFinished(sink.samplesWritten, failure)
        }
    }

    private fun openOrFail(first: SourcePolicy.Action.Open): String? {
        var next: SourcePolicy.Action = first
        while (next is SourcePolicy.Action.Open) {
            if (stopRequested) return null
            val want = next.source
            next.cue?.let { listener.onCue(it) }
            if (next.reason != "start") listener.onLog("switching to ${want.name.lowercase()}: ${next.reason}")
            closeSource()
            publish { it.copy(source = want, switching = true, waitingForBluetooth = false) }
            val gen = ++generation
            val opened = try {
                sources.open(want, sampleRate) { e -> commands.add(Command(e, gen)) }
            } catch (t: Throwable) {
                null.also { listener.onLog("opening ${want.name.lowercase()} failed: ${t.message}") }
            }
            if (opened == null) {
                next = policy.openFailed(want, if (want == Source.BLUETOOTH) "not available" else "failed")
                continue
            }
            source = opened
            zeros.reset()
            val routed = opened.routed()
            policy.opened(want, clock())
            listener.onLog("recording from ${want.name.lowercase()} (routed: ${if (routed.bluetooth) "bluetooth" else "phone"})")
            publish {
                it.copy(
                    source = want, input = routed, switching = false, silent = false,
                    waitingForBluetooth = policy.waitingForBluetooth, fallbackReason = policy.fallbackReason,
                )
            }
            return null
        }
        publish { it.copy(switching = false) }
        return (next as? SourcePolicy.Action.Stop)?.reason
    }

    private fun loop(): String? {
        val buffer = ShortArray(sampleRate / 10)
        var empty = 0
        var lastPublish = 0L
        while (!stopRequested) {
            while (true) {
                val c = commands.poll() ?: break
                val action = if (c.generation != generation) SourcePolicy.Action.None else onSourceEvent(c.event)
                act(action)?.let { return it }
            }
            if (stopRequested) break
            val src = source ?: return "no microphone"
            val n = src.read(buffer, buffer.size)
            val now = clock()
            when {
                n > 0 -> {
                    empty = 0
                    val hadSound = zeros.soundSeen
                    val change = zeros.feed(buffer, n)
                    if (!hadSound && zeros.soundSeen) {
                        policy.firstSound()
                        listener.onLog("first sound from ${src.kind.name.lowercase()}")
                        publish { it.copy(waitingForBluetooth = false) }
                    }
                    when (change) {
                        ZeroRunDetector.Change.SILENT -> if (!policy.waitingForBluetooth) publish { it.copy(silent = true) }
                        ZeroRunDetector.Change.SOUND_BACK -> publish { it.copy(silent = androidSilenced) }
                        ZeroRunDetector.Change.NONE -> {}
                    }
                    // words said while a Bluetooth input still delivers zeros are lost anyway
                    if (zeros.soundSeen) sink.write(buffer, n)
                    if (now - lastPublish >= PUBLISH_MS) {
                        lastPublish = now
                        val amp = peak(buffer, n)
                        publish { it.copy(samples = sink.samplesWritten, amplitude = amp) }
                    }
                }

                n == 0 -> {
                    val limit = if (zeros.soundSeen) 10 else if (src.kind == Source.PHONE) 30 else Int.MAX_VALUE
                    if (++empty > limit) {
                        empty = 0
                        act(policy.readFailed("no audio for a second"))?.let { return it }
                        continue
                    }
                    Thread.sleep(EMPTY_READ_SLEEP_MS)
                }

                else -> {
                    act(policy.readFailed(readError(n)))?.let { return it }
                    continue
                }
            }
            act(policy.tick(now))?.let { return it }
        }
        return null
    }

    private fun onSourceEvent(e: SourceEvent): SourcePolicy.Action = when (e) {
        is SourceEvent.Routed -> {
            publish { it.copy(input = e.input) }
            policy.routed(e.input)
        }

        is SourceEvent.Silenced -> {
            androidSilenced = e.silenced
            publish { it.copy(silent = e.silenced || zeros.silent) }
            SourcePolicy.Action.None
        }
    }

    private fun act(action: SourcePolicy.Action): String? = when (action) {
        SourcePolicy.Action.None -> null
        is SourcePolicy.Action.Stop -> action.reason
        is SourcePolicy.Action.Open -> openOrFail(action)
    }

    private fun closeSource() {
        source?.let { runCatching { it.close() } }
        source = null
    }

    private inline fun publish(change: (EngineStatus) -> EngineStatus) {
        status = change(status)
        listener.onStatus(status)
    }

    private fun readError(n: Int) = when (n) {
        ERROR_INVALID_OPERATION -> "invalid operation"
        ERROR_BAD_VALUE -> "bad value"
        ERROR_DEAD_OBJECT -> "input lost"
        else -> "error $n"
    }
}
