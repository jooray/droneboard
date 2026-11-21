// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

/** A live dictation: when it is streamed, how the audio is handed over, and that the text is typed exactly once. */
class LiveRouteTest {

    private val dir: File = Files.createTempDirectory("dictation-live-test").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A pipe nobody reads: every write waits until [release]. */
    private class StuckStream : OutputStream() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        @Volatile var closed = false
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            entered.countDown()
            release.await()
        }
        override fun close() {
            closed = true
        }
    }

    private class ClosableBytes : ByteArrayOutputStream() {
        @Volatile var closed = false
        override fun close() {
            closed = true
        }
    }

    private fun samples(bytes: ByteArray): List<Short> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return List(b.remaining()) { b.get(it) }
    }

    // ---- which dictations are streamed ----

    @Test
    fun `an OSTT that declares nothing or only a short session is not streamed to`() {
        assertFalse(LiveRoute.live(LiveRoute.OLD_BOUND_SECONDS))
        assertFalse(LiveRoute.live(0))
        assertFalse(LiveRoute.live(120))
        assertTrue(LiveRoute.live(600))
        assertTrue(LiveRoute.live(3600))
    }

    @Test
    fun `a live recording ends before OSTT stops reading and never later than any dictation`() {
        // an hour declared: the usual half hour
        assertEquals(1800, LiveRoute.maxRecordingSeconds(3600, 1800))
        // a quarter of an hour declared: two minutes before it
        assertEquals(900 - LiveRoute.BOUND_MARGIN_SECONDS, LiveRoute.maxRecordingSeconds(900, 1800))
        assertEquals(0, LiveRoute.maxRecordingSeconds(60, 1800))
        // the margin holds everything that can still be on its way when the recording ends
        assertTrue(LiveRoute.BOUND_MARGIN_SECONDS * 32_000 >= 2 * LiveRoute.HANDOFF_BYTES)
    }

    @Test
    fun `the wait for the text covers what can still be undecoded and no more`() {
        assertEquals(90_000L, LiveRoute.finalTimeoutMs(10_000))
        // a window of a minute and two minutes in OSTT's queue, however long the dictation was
        assertEquals(600_000L, LiveRoute.finalTimeoutMs(180_000))
        assertEquals(600_000L, LiveRoute.finalTimeoutMs(30 * 60_000L))
        assertEquals(60_000L, LiveRoute.finalTimeoutMs(-5))
        // far below what sending the file again may take
        assertTrue(LiveRoute.finalTimeoutMs(30 * 60_000L) < FileRoute.timeoutMs(30 * 60_000L))
    }

    @Test
    fun `OSTT's ten seconds without audio are never reached while the recorder is only quiet`() {
        assertTrue(LiveRoute.KEEP_ALIVE_MS * 3 <= 10_000L)
    }

    // ---- exactly one of the stream and the file delivers ----

    @Test
    fun `a stream that works types its text once the recording has ended`() {
        val s = LiveSession()
        assertTrue(s.streaming)
        assertEquals(LiveSession.Step.WAIT, s.onRecordingEnded())
        assertEquals(LiveSession.Step.TYPE, s.onResult("hello there", inputComplete = true))
        assertEquals("hello there", s.text)
        // nothing after the text: not the timeout, not a late error, not a second answer
        assertEquals(LiveSession.Step.NOTHING, s.onBroken())
        assertEquals(LiveSession.Step.NOTHING, s.onResult("again", inputComplete = true))
        assertEquals(LiveSession.Step.NOTHING, s.onRecordingEnded())
        assertEquals("hello there", s.text)
    }

    @Test
    fun `a stream that breaks while recording leaves the dictation to the file at the end`() {
        val s = LiveSession()
        // for example OSTT was busy with another job when the request started
        assertEquals(LiveSession.Step.ABANDON, s.onBroken())
        assertFalse(s.streaming)
        assertEquals(LiveSession.Step.NOTHING, s.onBroken())
        // an answer of the abandoned request must not be typed
        assertEquals(LiveSession.Step.NOTHING, s.onResult("half", inputComplete = true))
        assertNull(s.text)
        assertEquals(LiveSession.Step.SEND_FILE, s.onRecordingEnded())
        assertEquals(LiveSession.Step.NOTHING, s.onRecordingEnded())
        assertEquals(LiveSession.Step.NOTHING, s.onBroken())
    }

    @Test
    fun `a stream that fails after the user stopped sends the file at once and only once`() {
        val s = LiveSession()
        assertEquals(LiveSession.Step.WAIT, s.onRecordingEnded())
        // an error, OSTT killed, or no answer in time
        assertEquals(LiveSession.Step.SEND_FILE, s.onBroken())
        assertEquals(LiveSession.Step.NOTHING, s.onBroken())
        // the stream's answer arriving after all is not typed on top of the file's
        assertEquals(LiveSession.Step.NOTHING, s.onResult("late", inputComplete = true))
        assertNull(s.text)
    }

    @Test
    fun `an answer before the whole recording was written is not the dictation`() {
        // OSTT ended the request on its own while the user was still talking
        val recording = LiveSession()
        assertEquals(LiveSession.Step.ABANDON, recording.onResult("the first half", inputComplete = false))
        assertNull(recording.text)
        assertEquals(LiveSession.Step.SEND_FILE, recording.onRecordingEnded())

        // or after the user stopped, while the end of the recording was still waiting for the pipe
        val stopped = LiveSession()
        assertEquals(LiveSession.Step.WAIT, stopped.onRecordingEnded())
        assertEquals(LiveSession.Step.SEND_FILE, stopped.onResult("most of it", inputComplete = false))
        assertNull(stopped.text)
    }

    @Test
    fun `an answer that overtakes the end of the recording is typed when the recording ends`() {
        val s = LiveSession()
        assertEquals(LiveSession.Step.NOTHING, s.onResult("short", inputComplete = true))
        assertEquals(LiveSession.Step.TYPE, s.onRecordingEnded())
        assertEquals("short", s.text)
        assertEquals(LiveSession.Step.NOTHING, s.onBroken())
    }

    @Test
    fun `a recording without words is an answer too`() {
        val s = LiveSession()
        s.onRecordingEnded()
        assertEquals(LiveSession.Step.TYPE, s.onResult("", inputComplete = true))
        assertEquals("", s.text)
    }

    // ---- the hand-off to the pipe ----

    @Test
    fun `buffers reach the pipe in order as little-endian PCM and the pipe is closed at the end`() {
        val out = ClosableBytes()
        var first = 0
        val broken = CopyOnWriteArrayList<String>()
        val feed = LiveFeed(out, onFirstAudio = { first++ }, onBroken = { broken += it })
        val all = ShortArray(8000) { (it * 5 - 9000).toShort() }
        var off = 0
        while (off < all.size) {
            feed.offer(all.copyOfRange(off, off + 1600), 1600)
            off += 1600
        }
        assertFalse(feed.completed)
        feed.end()
        feed.join(5000)
        assertTrue(feed.completed)
        assertTrue(out.closed)
        assertEquals(all.toList(), samples(out.toByteArray()))
        assertEquals(16_000L, feed.bytesWritten)
        assertEquals(1, first)
        assertTrue(broken.isEmpty())
        // audio after the end is not taken
        feed.offer(all, 100)
        assertEquals(16_000, out.size())
    }

    @Test
    fun `only the samples that count are sent from a buffer`() {
        val out = ClosableBytes()
        val feed = LiveFeed(out)
        feed.offer(shortArrayOf(1, 2, 3, 4), 2)
        feed.offer(shortArrayOf(9, 9), 0)
        feed.end()
        feed.join(5000)
        assertEquals(listOf<Short>(1, 2), samples(out.toByteArray()))
    }

    @Test
    fun `a reader that does not read never holds up the recording and ends the stream when the hand-off is full`() {
        val out = StuckStream()
        val broken = CopyOnWriteArrayList<String>()
        // room for one second
        val feed = LiveFeed(out, capacityBytes = 32_000, onBroken = { broken += it })
        val buffer = ShortArray(1600) { 7 }
        val started = System.nanoTime()
        // a minute of audio offered as fast as it goes: no call may wait for the reader
        repeat(600) { feed.offer(buffer, buffer.size) }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 2000, "offering took $tookMs ms")
        assertEquals(1, broken.size) // said once, not with every buffer
        assertFalse(feed.completed)
        // ending the recording does not turn the broken stream into a complete one
        feed.end()
        out.release.countDown()
        feed.join(5000)
        assertFalse(feed.completed)
        assertTrue(out.closed)
        assertEquals(1, broken.size)
    }

    @Test
    fun `a hand-off that is not full bridges a reader that starts late`() {
        val out = StuckStream()
        val broken = CopyOnWriteArrayList<String>()
        val feed = LiveFeed(out, capacityBytes = 32_000, onBroken = { broken += it })
        val buffer = ShortArray(1600)
        // one buffer is stuck in the write, nine wait: under a second
        repeat(10) { feed.offer(buffer, buffer.size) }
        assertTrue(out.entered.await(5, TimeUnit.SECONDS))
        feed.end()
        out.release.countDown()
        feed.join(5000)
        assertTrue(broken.isEmpty())
        assertTrue(feed.completed)
    }

    @Test
    fun `a pipe whose reader went away breaks the stream`() {
        val broken = CopyOnWriteArrayList<String>()
        val told = CountDownLatch(1)
        val out = object : OutputStream() {
            override fun write(b: Int) = throw IOException("EPIPE")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("EPIPE")
        }
        val feed = LiveFeed(out, onBroken = { broken += it; told.countDown() })
        feed.offer(ShortArray(1600), 1600)
        assertTrue(told.await(5, TimeUnit.SECONDS))
        feed.join(5000)
        feed.end()
        assertFalse(feed.completed)
        assertEquals(1, broken.size)
    }

    @Test
    fun `a cancelled stream writes nothing more and reports nothing`() {
        val out = StuckStream()
        val broken = CopyOnWriteArrayList<String>()
        val feed = LiveFeed(out, onBroken = { broken += it })
        repeat(5) { feed.offer(ShortArray(1600), 1600) }
        assertTrue(out.entered.await(5, TimeUnit.SECONDS))
        feed.abort()
        feed.offer(ShortArray(1600), 1600)
        out.release.countDown()
        feed.join(5000)
        assertFalse(feed.completed)
        assertTrue(out.closed)
        assertTrue(broken.isEmpty())
        assertEquals(1600L * 2, feed.bytesWritten) // the one buffer that was already in the write
    }

    @Test
    fun `silence keeps the request alive while the recorder delivers nothing but not before the first audio`() {
        val out = ClosableBytes()
        val feed = LiveFeed(out, keepAliveMs = 20)
        Thread.sleep(150)
        assertEquals(0, out.size()) // no request is running yet
        feed.offer(ShortArray(160) { 5 }, 160)
        // the microphone is being switched: no buffer for a while
        val deadline = System.currentTimeMillis() + 5000
        while (out.size() < 320 + 3200 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        feed.end()
        feed.join(5000)
        val bytes = out.toByteArray()
        assertTrue(bytes.size >= 320 + 3200, "no silence was written")
        assertEquals(0, (bytes.size - 320) % 3200) // whole frames, a tenth of a second each
        assertTrue(samples(bytes).drop(160).all { it == 0.toShort() })
        assertTrue(feed.completed)
    }

    // ---- the file and the stream hold the same recording ----

    @Test
    fun `the stream gets exactly what the file took and both end together`() {
        val file = assertNotNull(DictationFiles.create(dir))
        var limit = 0
        val wav = WavRecordingSink(file, 16_000, maxSamples = 4000) { limit++ }
        val out = ClosableBytes()
        val feed = LiveFeed(out)
        val sink = LiveRecordingSink(wav, feed)
        val buffer = ShortArray(1600) { (it + 1).toShort() }
        repeat(4) { sink.write(buffer, buffer.size) } // 6400 offered, the longest dictation is 4000
        assertEquals(4000L, sink.samplesWritten)
        assertEquals(1, limit)
        sink.finish()
        feed.join(5000)
        assertTrue(feed.completed)
        val streamed = samples(out.toByteArray())
        assertEquals(4000, streamed.size)
        val filed = mutableListOf<Short>()
        Wav.readPcm(file, 16_000) { pcm, count ->
            for (i in 0 until count) filed += pcm[i]
            true
        }
        assertEquals(filed, streamed)
    }

    @Test
    fun `cancelling a live dictation deletes the file and ends the stream without completing it`() {
        val file = assertNotNull(DictationFiles.create(dir))
        val out = ClosableBytes()
        val feed = LiveFeed(out)
        val sink = LiveRecordingSink(WavRecordingSink(file, 16_000, 1_000_000) {}, feed)
        sink.write(ShortArray(1600), 1600)
        sink.abort()
        feed.join(5000)
        assertFalse(file.exists())
        assertFalse(feed.completed)
        assertTrue(out.closed)
    }
}
