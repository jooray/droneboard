// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Cuts a dictation into pieces the recognizer accepts. Offline Speech to Text ends a caller-audio
 * request after 60 s, so a piece is at most [maxSamples] long and is cut at the quietest
 * [frameSamples] window between [minSamples] and [maxSamples], which is usually a pause between
 * words. This is the way for an Offline Speech to Text that can't take the whole recording as a
 * file (before 0.22): a cut can fall inside a sentence, which is why the file is preferred.
 * Free of Android, for the tests.
 */
class Chunker(
    private val sampleRate: Int = 16_000,
    private val minSamples: Int = sampleRate * 30,
    private val maxSamples: Int = sampleRate * 50,
    private val frameSamples: Int = sampleRate / 10,
    /** A last piece shorter than this holds no words worth sending. */
    private val minLastSamples: Int = sampleRate * 3 / 10,
    private val onChunk: (pcm: ShortArray, last: Boolean) -> Unit,
) : AudioSink {

    private var buf = ShortArray(maxSamples + sampleRate)
    private var len = 0
    private var total = 0L
    var chunks = 0
        private set

    override val samplesWritten: Long get() = total

    override fun write(pcm: ShortArray, count: Int) {
        var off = 0
        while (off < count) {
            val room = buf.size - len
            val n = minOf(room, count - off)
            System.arraycopy(pcm, off, buf, len, n)
            len += n
            off += n
            total += n
            while (len >= maxSamples) cut()
        }
    }

    /** Emits the first piece, cut at the quietest frame in [minSamples, maxSamples]. */
    private fun cut() {
        val at = quietestCut()
        chunks++
        onChunk(buf.copyOfRange(0, at), false)
        System.arraycopy(buf, at, buf, 0, len - at)
        len -= at
    }

    internal fun quietestCut(): Int {
        var best = maxSamples
        var bestEnergy = Long.MAX_VALUE
        var start = minSamples
        while (start + frameSamples <= maxSamples) {
            var e = 0L
            for (i in start until start + frameSamples) {
                val v = buf[i].toLong()
                e += v * v
            }
            // ties go to the later cut, so pieces stay long
            if (e <= bestEnergy) {
                bestEnergy = e
                best = start + frameSamples / 2
            }
            start += frameSamples
        }
        return best
    }

    override fun finish() {
        if (len >= minLastSamples || (chunks == 0 && len > 0)) {
            chunks++
            onChunk(buf.copyOfRange(0, len), true)
        } else {
            onChunk(ShortArray(0), true)
        }
        len = 0
    }

    override fun abort() {
        len = 0
    }
}
