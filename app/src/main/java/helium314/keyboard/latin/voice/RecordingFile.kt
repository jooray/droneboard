// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

// A dictation is one recording and one transcription: the audio goes to a file while the user talks, and the
// whole file goes to Offline Speech to Text when they stop. Everything in this file is free of Android, for the tests.

/** The plain WAV container (RIFF, PCM 16-bit little-endian, mono): the simplest file every Android decoder reads. */
object Wav {
    const val HEADER_BYTES = 44

    /** The 44-byte header of a mono PCM16 file holding [dataBytes] bytes of samples. */
    fun header(sampleRate: Int, dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16) // size of the format block
        b.putShort(1) // PCM
        b.putShort(1) // mono
        b.putInt(sampleRate)
        b.putInt(sampleRate * 2) // bytes per second
        b.putShort(2) // bytes per frame
        b.putShort(16) // bits per sample
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes.toInt())
        return b.array()
    }

    /**
     * Reads the samples of a file written by [WavRecordingSink] in blocks of [blockSamples], until the end or until
     * [onBlock] returns false. Never holds more than one block.
     */
    fun readPcm(file: File, blockSamples: Int, onBlock: (pcm: ShortArray, count: Int) -> Boolean) {
        BufferedInputStream(FileInputStream(file)).use { input ->
            var toSkip = HEADER_BYTES.toLong()
            while (toSkip > 0) {
                val skipped = input.skip(toSkip)
                if (skipped <= 0) return
                toSkip -= skipped
            }
            val bytes = ByteArray(blockSamples * 2)
            val pcm = ShortArray(blockSamples)
            while (true) {
                var got = 0
                while (got < bytes.size) {
                    val n = input.read(bytes, got, bytes.size - got)
                    if (n < 0) break
                    got += n
                }
                val count = got / 2 // half a sample at the very end is not a sample
                if (count > 0) {
                    ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm, 0, count)
                    if (!onBlock(pcm, count)) return
                }
                if (got < bytes.size) return
            }
        }
    }
}

/**
 * Writes the recording to [file] as it arrives, so a long dictation never sits in memory. The header is written
 * first with empty sizes and corrected in [finish]. After [maxSamples] the rest is dropped and [onLimit] is called
 * once (from the recording thread), so the caller stops the recording as if the user had; the same happens when the
 * file can't be written any more (storage full), so what was recorded until then is still transcribed.
 * @throws IOException from the constructor when the file can't be created
 */
class WavRecordingSink(
    private val file: File,
    private val sampleRate: Int,
    private val maxSamples: Long,
    private val onLimit: () -> Unit,
) : AudioSink {

    private var out: OutputStream? = BufferedOutputStream(FileOutputStream(file), 32 * 1024)
    private var bytes = ByteArray(0)
    @Volatile private var total = 0L
    private var limitReported = false

    init {
        try {
            out!!.write(Wav.header(sampleRate, 0))
        } catch (e: IOException) {
            abort()
            throw e
        }
    }

    override val samplesWritten: Long get() = total

    override fun write(pcm: ShortArray, count: Int) {
        val stream = out ?: return
        val n = minOf(count.toLong(), maxSamples - total).toInt()
        if (n > 0) {
            if (bytes.size < n * 2) bytes = ByteArray(n * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, n)
            try {
                stream.write(bytes, 0, n * 2)
                total += n
            } catch (_: IOException) {
                reportLimit()
                return
            }
        }
        if (total >= maxSamples) reportLimit()
    }

    private fun reportLimit() {
        if (limitReported) return
        limitReported = true
        onLimit()
    }

    override fun finish() {
        val stream = out ?: return
        out = null
        try {
            stream.flush()
        } catch (_: IOException) {
            // storage is full: the header below still describes what did get written
        }
        runCatching { stream.close() }
        RandomAccessFile(file, "rw").use { f ->
            // whole samples only, whatever the last write managed
            val dataBytes = minOf(total * 2, (f.length() - Wav.HEADER_BYTES).coerceAtLeast(0)) / 2 * 2
            total = dataBytes / 2
            f.setLength(Wav.HEADER_BYTES + dataBytes)
            f.seek(0)
            f.write(Wav.header(sampleRate, dataBytes))
        }
    }

    override fun abort() {
        runCatching { out?.close() }
        out = null
        file.delete()
    }
}

/** The folder the recordings of dictations live in, for the few moments they exist. */
object DictationFiles {
    /** Under the app's cache folder: never backed up, and encrypted with the user's credentials like the rest of it. */
    const val DIR = "dictation"

    /** A new, empty recording file in [dir] with a name that says nothing; null when it can't be created. */
    fun create(dir: File): File? = runCatching {
        dir.mkdirs()
        File(dir, "${UUID.randomUUID()}.wav").takeIf { it.createNewFile() }
    }.getOrNull()

    /**
     * Deletes everything in [dir]: a recording left by a crash or a killed process must not stay on the phone.
     * Called when the keyboard starts and before every dictation. Returns how many files were deleted.
     */
    fun clear(dir: File): Int {
        var deleted = 0
        dir.listFiles()?.forEach { f ->
            if (if (f.isDirectory) f.deleteRecursively() else f.delete()) deleted++
        }
        return deleted
    }
}
