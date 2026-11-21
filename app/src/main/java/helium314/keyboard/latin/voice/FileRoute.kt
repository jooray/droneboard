// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Whether a whole recording can go to Offline Speech to Text (OSTT) as one audio file. OSTT 0.22 and newer
 * transcribe a file of any length in one request; older versions only take caller audio through a pipe, which they
 * end after 60 s, so there the recording is cut into pieces ([Chunker]). Free of Android, for the tests.
 */
object FileRoute {
    enum class Support { UNKNOWN, SUPPORTED, UNSUPPORTED }

    // android.speech.SpeechRecognizer's error codes (this file stays free of Android)
    const val ERROR_CLIENT = 5
    const val ERROR_NO_MATCH = 7
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9

    /**
     * What the answer to a file request says about the file route, by OSTT's integration contract: a transcript
     * ([error] null) or "no words in this audio" proves OSTT read the file; a client or permission error means this
     * OSTT can't do it; every other error (busy, no model, a crash) and a timeout say nothing either way.
     */
    fun verdict(error: Int?): Support = when (error) {
        null, ERROR_NO_MATCH -> Support.SUPPORTED
        ERROR_CLIENT, ERROR_INSUFFICIENT_PERMISSIONS -> Support.UNSUPPORTED
        else -> Support.UNKNOWN
    }

    /**
     * How long to wait for the transcript of a recording of [durationMs]. How fast OSTT is depends on the phone and
     * the speech model, so this is generous: two minutes for loading the model, plus three times the length of the
     * recording (a 30 minute dictation may take an hour and a half on a slow phone before it is given up).
     * OSTT reports its own failures much sooner; this only ends a wait for an answer that never comes.
     */
    fun timeoutMs(durationMs: Long): Long = 120_000L + 3 * durationMs.coerceAtLeast(0)
}

/** Where the remembered answer is kept (the keyboard's settings on the phone; a variable in the tests). */
interface FileRouteStore {
    fun read(): String?
    fun write(value: String)
}

/**
 * Remembers, for one installed version of OSTT, whether the file route works, so an old OSTT is asked once and not
 * with a failing request before every dictation. An update of OSTT (another version code) is asked again.
 */
class FileRouteMemory(private val store: FileRouteStore) {

    fun support(versionCode: Long): FileRoute.Support {
        val parts = store.read()?.split(':') ?: return FileRoute.Support.UNKNOWN
        if (parts.size != 2 || parts[0].toLongOrNull() != versionCode) return FileRoute.Support.UNKNOWN
        return FileRoute.Support.entries.firstOrNull { it.name == parts[1] } ?: FileRoute.Support.UNKNOWN
    }

    /**
     * True when the recording should be sent as a file. [declaresFileApi]: the installed OSTT says in its manifest
     * that it takes audio files. Without that the file request is never sent, because an OSTT that does not know
     * the request would take it for an ordinary one and listen on its own microphone.
     */
    fun useFile(versionCode: Long, declaresFileApi: Boolean): Boolean =
        declaresFileApi && support(versionCode) != FileRoute.Support.UNSUPPORTED

    /**
     * Keeps what a request showed. [versionBefore] and [versionAfter] are OSTT's version code when the request was
     * sent and when it was answered: an answer that spans an update of OSTT belongs to neither version. One refused
     * request does not overrule a version that has already transcribed a file; that refusal was about the request.
     */
    fun remember(versionBefore: Long, versionAfter: Long, verdict: FileRoute.Support) {
        if (verdict == FileRoute.Support.UNKNOWN || versionBefore != versionAfter) return
        val known = support(versionBefore)
        if (known == verdict || known == FileRoute.Support.SUPPORTED) return
        store.write("$versionBefore:${verdict.name}")
    }
}
