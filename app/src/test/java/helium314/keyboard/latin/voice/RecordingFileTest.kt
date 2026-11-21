// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

/** One dictation is one recording in a file, sent whole: the file, the choice of route, the rotation. */
class RecordingFileTest {

    private val dir: File = Files.createTempDirectory("dictation-test").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun sink(max: Long = Long.MAX_VALUE / 4, onLimit: () -> Unit = {}): Pair<File, WavRecordingSink> {
        val file = assertNotNull(DictationFiles.create(dir))
        return file to WavRecordingSink(file, 16_000, max, onLimit)
    }

    private fun ascii(bytes: ByteArray, from: Int) = String(bytes, from, 4, Charsets.US_ASCII)

    // ---- the WAV file ----

    @Test
    fun `the header describes 16 kHz mono 16-bit PCM of the given length`() {
        val h = Wav.header(16_000, 32_000)
        assertEquals(44, h.size)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", ascii(h, 0))
        assertEquals(36 + 32_000, b.getInt(4))
        assertEquals("WAVE", ascii(h, 8))
        assertEquals("fmt ", ascii(h, 12))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // bytes per second
        assertEquals(2, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", ascii(h, 36))
        assertEquals(32_000, b.getInt(40))
    }

    @Test
    fun `a recording is written as it arrives and reads back sample for sample`() {
        val (file, s) = sink()
        val all = ShortArray(40_000) { (it * 7 - 20_000).toShort() }
        var off = 0
        while (off < all.size) { // 100 ms blocks, like the engine
            val n = minOf(1600, all.size - off)
            s.write(all.copyOfRange(off, off + n), n)
            off += n
        }
        assertEquals(40_000L, s.samplesWritten)
        s.finish()
        assertEquals(44L + 80_000, file.length())
        val bytes = file.readBytes()
        assertEquals(Wav.header(16_000, 80_000).toList(), bytes.take(44))
        // little-endian samples
        assertEquals(all[1], ByteBuffer.wrap(bytes, 46, 2).order(ByteOrder.LITTLE_ENDIAN).short)
        val back = mutableListOf<Short>()
        val blocks = mutableListOf<Int>()
        Wav.readPcm(file, 16_000) { pcm, count ->
            blocks += count
            for (i in 0 until count) back += pcm[i]
            true
        }
        assertEquals(all.toList(), back)
        assertEquals(listOf(16_000, 16_000, 8_000), blocks) // never more than a block in memory
    }

    @Test
    fun `reading stops when the reader has had enough`() {
        val (file, s) = sink()
        s.write(ShortArray(48_000) { 5 }, 48_000)
        s.finish()
        var blocks = 0
        Wav.readPcm(file, 16_000) { _, _ -> ++blocks < 2 }
        assertEquals(2, blocks)
    }

    @Test
    fun `nothing recorded is a valid empty file`() {
        val (file, s) = sink()
        s.finish()
        assertEquals(Wav.header(16_000, 0).toList(), file.readBytes().toList())
        var called = false
        Wav.readPcm(file, 16_000) { _, _ -> called = true; true }
        assertFalse(called)
    }

    @Test
    fun `cancel deletes the file`() {
        val (file, s) = sink()
        s.write(ShortArray(1600) { 9 }, 1600)
        s.abort()
        assertFalse(file.exists())
        s.write(ShortArray(1600) { 9 }, 1600) // a late block from the recording thread does no harm
        assertFalse(file.exists())
    }

    // ---- the longest dictation ----

    @Test
    fun `at the maximum length the recording asks to be stopped once and keeps what fits`() {
        var limits = 0
        val (file, s) = sink(max = 4000) { limits++ }
        s.write(ShortArray(1600) { 1 }, 1600)
        s.write(ShortArray(1600) { 1 }, 1600)
        assertEquals(0, limits)
        s.write(ShortArray(1600) { 1 }, 1600) // 800 of these fit
        assertEquals(1, limits)
        s.write(ShortArray(1600) { 1 }, 1600) // the engine may deliver more before it stops
        assertEquals(1, limits)
        assertEquals(4000L, s.samplesWritten)
        s.finish()
        assertEquals(44L + 8000, file.length())
    }

    @Test
    fun `the engine stops at the maximum length as if the user had stopped, and the recording is finished`() {
        val clock = FakeClock()
        val listener = RecordingListener()
        lateinit var engine: RecordingEngine
        val file = assertNotNull(DictationFiles.create(dir))
        // 2 s is the maximum; the microphone would go on forever
        val s = WavRecordingSink(file, 16_000, 32_000) { engine.stop() }
        val sources = PcmSourceFactory { kind, _, _ -> FakeSource(kind, clock, emptyList()) }
        engine = RecordingEngine(sources, s, 16_000, preferBluetooth = false, bluetoothAvailable = { false }, listener = listener, clock = clock::get)
        engine.runLoop()
        assertEquals(32_000L, listener.finishedSamples)
        assertEquals(null, listener.failure)
        assertEquals(44L + 64_000, file.length())
    }

    // ---- leftovers ----

    @Test
    fun `leftover recordings are deleted, new ones get a name that says nothing`() {
        val a = assertNotNull(DictationFiles.create(dir))
        val b = assertNotNull(DictationFiles.create(dir))
        File(dir, "crashed.wav").writeBytes(ByteArray(100))
        File(dir, "sub").mkdirs()
        File(dir, "sub/x.wav").writeBytes(ByteArray(10))
        assertTrue(a.name.endsWith(".wav") && a.name != b.name)
        assertEquals(4, DictationFiles.clear(dir))
        assertEquals(emptyList(), dir.listFiles()!!.toList())
        // a folder that does not exist yet is not an error, and is created on demand
        val fresh = File(dir, "not-yet")
        assertEquals(0, DictationFiles.clear(fresh))
        assertTrue(assertNotNull(DictationFiles.create(fresh)).exists())
    }

    // ---- file or pieces ----

    private class MemoryStore(var value: String? = null) : FileRouteStore {
        var writes = 0
        override fun read() = value
        override fun write(value: String) {
            this.value = value
            writes++
        }
    }

    @Test
    fun `an OSTT that declares the file route gets the file, one that does not gets pieces`() {
        val m = FileRouteMemory(MemoryStore())
        assertTrue(m.useFile(60, declaresFileApi = true))
        assertFalse(m.useFile(55, declaresFileApi = false))
    }

    @Test
    fun `a client or permission error means unsupported, a transcript or no words means supported, the rest says nothing`() {
        assertEquals(FileRoute.Support.SUPPORTED, FileRoute.verdict(null))
        assertEquals(FileRoute.Support.SUPPORTED, FileRoute.verdict(7)) // ERROR_NO_MATCH
        assertEquals(FileRoute.Support.UNSUPPORTED, FileRoute.verdict(5)) // ERROR_CLIENT
        assertEquals(FileRoute.Support.UNSUPPORTED, FileRoute.verdict(9)) // ERROR_INSUFFICIENT_PERMISSIONS
        // audio, server, busy, speech timeout, no model
        for (e in listOf(1, 2, 3, 4, 6, 8, 10, 11, 12, 13)) assertEquals(FileRoute.Support.UNKNOWN, FileRoute.verdict(e), "error $e")
    }

    @Test
    fun `a refusal is remembered for that version, so it is not asked again`() {
        val store = MemoryStore()
        val m = FileRouteMemory(store)
        m.remember(59, 59, FileRoute.verdict(5))
        assertEquals(FileRoute.Support.UNSUPPORTED, m.support(59))
        assertFalse(m.useFile(59, declaresFileApi = true))
        // survives a restart of the keyboard
        assertFalse(FileRouteMemory(store).useFile(59, declaresFileApi = true))
    }

    @Test
    fun `an update of OSTT is asked again`() {
        val store = MemoryStore()
        val m = FileRouteMemory(store)
        m.remember(59, 59, FileRoute.Support.UNSUPPORTED)
        assertEquals(FileRoute.Support.UNKNOWN, m.support(60))
        assertTrue(m.useFile(60, declaresFileApi = true))
        m.remember(60, 60, FileRoute.Support.SUPPORTED)
        assertEquals(FileRoute.Support.SUPPORTED, m.support(60))
        assertEquals(FileRoute.Support.UNKNOWN, m.support(59)) // one answer is kept, for the installed version
    }

    @Test
    fun `errors that say nothing and answers that span an update are not remembered`() {
        val store = MemoryStore()
        val m = FileRouteMemory(store)
        m.remember(60, 60, FileRoute.verdict(8)) // busy
        m.remember(60, 61, FileRoute.Support.UNSUPPORTED) // OSTT was updated while the request ran
        assertEquals(0, store.writes)
        assertEquals(FileRoute.Support.UNKNOWN, m.support(60))
        assertEquals(FileRoute.Support.UNKNOWN, m.support(61))
    }

    @Test
    fun `one refused request does not overrule a version that has transcribed a file`() {
        val store = MemoryStore()
        val m = FileRouteMemory(store)
        m.remember(60, 60, FileRoute.Support.SUPPORTED)
        m.remember(60, 60, FileRoute.Support.UNSUPPORTED)
        assertTrue(m.useFile(60, declaresFileApi = true))
        m.remember(60, 60, FileRoute.Support.SUPPORTED) // nothing new: nothing written
        assertEquals(1, store.writes)
    }

    @Test
    fun `a damaged remembered value counts as not known`() {
        for (v in listOf("", "60", "x:SUPPORTED", "60:MAYBE", "60:SUPPORTED:1"))
            assertEquals(FileRoute.Support.UNKNOWN, FileRouteMemory(MemoryStore(v)).support(60), v)
    }

    @Test
    fun `the wait for the text grows with the recording`() {
        assertEquals(2 * 60_000L + 30_000, FileRoute.timeoutMs(10_000))
        // half an hour of speech: several minutes beyond the audio, as OSTT's contract asks
        assertTrue(FileRoute.timeoutMs(30 * 60_000L) >= 35 * 60_000L)
        assertEquals(92 * 60_000L, FileRoute.timeoutMs(30 * 60_000L))
    }

    // ---- rotating the phone ----

    @Test
    fun `a keyboard hidden without a rotation ends the recording`() {
        assertEquals(RotationGuard.Finish.STOP, RotationGuard().onFinishInputView())
    }

    @Test
    fun `a keyboard hidden during a rotation keeps the recording, and the keyboard coming back keeps it for good`() {
        val g = RotationGuard()
        g.onOrientationChange()
        assertEquals(RotationGuard.Finish.KEEP, g.onFinishInputView())
        g.onStartInputView() // the panel is shown again here
        assertEquals(RotationGuard.Finish.KEEP, g.onTimeout())
        // the rotation is over: hiding the keyboard means leaving again
        assertEquals(RotationGuard.Finish.STOP, g.onFinishInputView())
    }

    @Test
    fun `shown, hidden and shown again after one rotation keeps the recording`() {
        // Android shows the rebuilt keyboard first, then the app lets go of it and takes it back
        val g = RotationGuard()
        g.onOrientationChange()
        g.onStartInputView()
        assertEquals(RotationGuard.Finish.KEEP, g.onFinishInputView())
        g.onStartInputView()
        assertEquals(RotationGuard.Finish.KEEP, g.onTimeout())
    }

    @Test
    fun `a keyboard that does not come back after a rotation ends the recording at the timeout`() {
        val g = RotationGuard()
        g.onOrientationChange()
        assertEquals(RotationGuard.Finish.KEEP, g.onFinishInputView())
        assertEquals(RotationGuard.Finish.STOP, g.onTimeout())
        // and only once
        assertEquals(RotationGuard.Finish.KEEP, g.onTimeout())
    }

    @Test
    fun `a rotation with the keyboard staying on screen changes nothing`() {
        val g = RotationGuard()
        g.onOrientationChange()
        assertEquals(RotationGuard.Finish.KEEP, g.onTimeout())
        assertEquals(RotationGuard.Finish.STOP, g.onFinishInputView())
    }

    @Test
    fun `a cancelled dictation forgets the rotation`() {
        val g = RotationGuard()
        g.onOrientationChange()
        g.onFinishInputView()
        g.reset()
        assertEquals(RotationGuard.Finish.KEEP, g.onTimeout())
        assertEquals(RotationGuard.Finish.STOP, g.onFinishInputView())
    }

    @Test
    fun `the rotation timeout is a few seconds`() {
        assertTrue(RotationGuard.TIMEOUT_MS in 2000L..5000L)
    }
}
