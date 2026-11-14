// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class DictationTest {

    // ---- recording engine: the Third Ear Bluetooth rules in the keyboard ----

    @Test
    fun `bluetooth that only delivers zeros falls back to the phone after 8 s, with a cue`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val listener = RecordingListener()
        lateinit var engine: RecordingEngine
        var phoneReads = 0
        val sources = PcmSourceFactory { kind, _, _ ->
            when (kind) {
                // an LE input still starting: exact zeros, forever
                Source.BLUETOOTH -> FakeSource(kind, clock, emptyList(), tail = 2)
                Source.PHONE -> FakeSource(kind, clock, emptyList(), tail = 1, onRead = { if (++phoneReads == 20) engine.stop() })
            }
        }
        engine = RecordingEngine(sources, sink, 16_000, preferBluetooth = true, bluetoothAvailable = { true }, listener = listener, clock = clock::get)
        engine.runLoop()
        assertEquals(listOf(SourcePolicy.Cue.FALLBACK_TO_PHONE), listener.cues)
        assertTrue(sink.finished)
        // the zeros before the fallback are not dictation; only the phone's audio is kept
        assertEquals(20 * 1600L, sink.samplesWritten)
        assertTrue(listener.statuses.any { it.waitingForBluetooth })
        assertEquals(null, listener.failure)
    }

    @Test
    fun `a bluetooth input that comes up after 3 s is kept`() {
        val clock = FakeClock()
        val sink = FakeSink()
        val listener = RecordingListener()
        lateinit var engine: RecordingEngine
        val sources = PcmSourceFactory { kind, _, _ ->
            // 30 reads (3 s) of zeros, then speech; stop after 50 reads
            FakeSource(kind, clock, List(30) { 2 } + List(20) { 1 }, onRead = { if (it == 49) engine.stop() })
        }
        engine = RecordingEngine(sources, sink, 16_000, preferBluetooth = true, bluetoothAvailable = { true }, listener = listener, clock = clock::get)
        engine.runLoop()
        assertTrue(listener.cues.isEmpty())
        assertEquals(Source.BLUETOOTH, listener.statuses.last().source)
        assertEquals(20 * 1600L, sink.samplesWritten)
    }

    @Test
    fun `cancel drops the audio`() {
        val clock = FakeClock()
        val sink = FakeSink()
        lateinit var engine: RecordingEngine
        val sources = PcmSourceFactory { kind, _, _ -> FakeSource(kind, clock, emptyList(), onRead = { if (it == 5) engine.stop(discardAudio = true) }) }
        engine = RecordingEngine(sources, sink, 16_000, preferBluetooth = false, bluetoothAvailable = { false }, listener = RecordingListener(), clock = clock::get)
        engine.runLoop()
        assertTrue(sink.aborted)
        assertTrue(!sink.finished)
    }

    // ---- chunker: OSTT takes at most 60 s per request ----

    private fun speech(seconds: Double, rate: Int = 16_000, amp: Short = 3000) = ShortArray((seconds * rate).toInt()) { if (it % 2 == 0) amp else (-amp).toShort() }
    private fun silence(seconds: Double, rate: Int = 16_000) = ShortArray((seconds * rate).toInt())

    @Test
    fun `short dictation is one piece`() {
        val pieces = mutableListOf<Pair<Int, Boolean>>()
        val c = Chunker { pcm, last -> pieces += pcm.size to last }
        val s = speech(12.0)
        c.write(s, s.size)
        c.finish()
        assertEquals(listOf(12 * 16_000 to true), pieces)
    }

    @Test
    fun `long dictation is cut at the pause, under 50 s each`() {
        val pieces = mutableListOf<ShortArray>()
        val lasts = mutableListOf<Boolean>()
        val c = Chunker { pcm, last -> pieces += pcm; lasts += last }
        // 40 s speech, 0.5 s pause, 40 s speech, 0.5 s pause, 20 s speech: written in 100 ms blocks like the engine
        val all = speech(40.0) + silence(0.5) + speech(40.0) + silence(0.5) + speech(20.0)
        var off = 0
        while (off < all.size) {
            val n = minOf(1600, all.size - off)
            c.write(all.copyOfRange(off, off + n), n)
            off += n
        }
        c.finish()
        assertEquals(3, pieces.size)
        assertEquals(listOf(false, false, true), lasts)
        pieces.forEach { assertTrue(it.size <= 50 * 16_000, "piece of ${it.size / 16_000.0} s") }
        // the first cut lands inside the first pause (40.0..40.5 s)
        assertTrue(pieces[0].size in 40 * 16_000..(40.5 * 16_000).toInt(), "cut at ${pieces[0].size / 16_000.0} s")
        assertEquals(all.size, pieces.sumOf { it.size })
    }

    @Test
    fun `a very short tail is not sent but still ends the dictation`() {
        val pieces = mutableListOf<Pair<Int, Boolean>>()
        val c = Chunker { pcm, last -> pieces += pcm.size to last }
        val s = speech(50.1)
        c.write(s, s.size)
        c.finish()
        assertEquals(true, pieces.last().second)
        assertTrue(pieces.dropLast(1).all { !it.second })
    }

    @Test
    fun `nothing recorded still ends the dictation`() {
        val pieces = mutableListOf<Pair<Int, Boolean>>()
        val c = Chunker { pcm, last -> pieces += pcm.size to last }
        c.finish()
        assertEquals(listOf(0 to true), pieces)
    }

    // ---- text joiner: Gboard-like spacing and capitals ----

    @Test
    fun `starts a sentence with a capital and no space`() {
        assertEquals("Ahoj, ako sa máš", TextJoiner.join("", "ahoj, ako sa máš"))
        assertEquals(" Ďakujem", TextJoiner.join("Dobre.", "ďakujem"))
        assertEquals("Ďakujem", TextJoiner.join("Dobre. ", "ďakujem"))
        assertEquals("Hello", TextJoiner.join("line\n", "hello"))
    }

    @Test
    fun `continues a sentence in lowercase with one space`() {
        assertEquals(" zajtra ráno", TextJoiner.join("Stretneme sa", "Zajtra ráno"))
        assertEquals("zajtra ráno", TextJoiner.join("Stretneme sa ", "Zajtra ráno"))
        assertEquals(" NASA launched", TextJoiner.join("I read that", "NASA launched"))
        assertEquals(" I think so", TextJoiner.join("well,", "I think so"))
    }

    @Test
    fun `no space after an opening bracket or before punctuation`() {
        assertEquals("poznámka", TextJoiner.join("(", "Poznámka"))
        assertEquals(", a potom", TextJoiner.join("najprv", ", a potom"))
    }

    @Test
    fun `empty phrase types nothing`() {
        assertEquals("", TextJoiner.join("abc", "   "))
    }
}
