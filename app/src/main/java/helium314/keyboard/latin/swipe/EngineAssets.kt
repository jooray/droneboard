// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.swipe

import android.content.Context
import android.os.SystemClock
import helium314.keyboard.latin.utils.Log
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which files exist, asking [probe] for each one. "It exists" is remembered for good. "It does not
 * exist" is asked again after [retryMs]: a file that could not be seen once must not stay missing for the rest
 * of the process.
 */
class AssetPresence(
    private val retryMs: Long,
    private val now: () -> Long,
    /** called when a file that was missing before is found (for the log) */
    private val onFoundLater: (String) -> Unit = {},
    private val probe: (String) -> Boolean,
) {
    private val present = ConcurrentHashMap.newKeySet<String>()
    private val absentAt = ConcurrentHashMap<String, Long>()

    fun has(name: String): Boolean {
        if (name in present) return true
        val t = now()
        absentAt[name]?.let { if (t - it < retryMs) return false }
        return if (probe(name)) {
            if (absentAt.remove(name) != null) onFoundLater(name)
            present.add(name)
            true
        } else {
            absentAt[name] = t
            false
        }
    }

    fun known(): Set<String> = present.toSet()
}

/**
 * Droneboard: which swipe dictionaries (.kpd) and neural models (.lmb, .lmv) ship in the APK.
 *
 * This used to be one directory listing (AssetManager.list), remembered for the life of the process. That
 * listing can come back incomplete: seen on Android 17 in a keyboard process that started while Android's
 * backup of the app was running with the phone locked, where the listing had four of nine dictionaries. A
 * language missing from it was then treated as "has no dictionary" until the process restarted, days later:
 * no neural predictions, or no swipe, in the languages the user types in.
 *
 * Now every file is checked by opening it, which does not depend on the listing, and a file that was not
 * found is checked again after a while.
 */
internal object EngineAssets {
    private const val TAG = "EngineAssets"
    private const val DIR = "swipe"
    private const val RETRY_MS = 20_000L

    @Volatile private var presence: AssetPresence? = null

    private fun presence(context: Context): AssetPresence = presence ?: synchronized(this) {
        presence ?: AssetPresence(RETRY_MS, SystemClock::elapsedRealtime,
            { Log.w(TAG, "$it could not be opened before and is there now") }) { name ->
            try {
                context.assets.open("$DIR/$name").close()
                true
            } catch (e: FileNotFoundException) {
                false
            } catch (e: Throwable) {
                Log.w(TAG, "could not open $name", e)
                false
            }
        }.also { presence = it }
    }

    fun exists(context: Context, name: String): Boolean = presence(context).has(name)

    /**
     * Files that can be opened but are missing from the directory listing: the fault described above, written
     * to the log when it is seen so that it can be followed up.
     */
    fun missingFromListing(context: Context): List<String> {
        val listed = runCatching { context.assets.list(DIR)?.toSet() }.getOrNull() ?: emptySet()
        return presence(context).known().filter { it !in listed && '/' !in it }.sorted()
    }

    /** File names in the directory: the listing (not trusted to be complete) and every file seen by opening it. */
    fun names(context: Context): Set<String> {
        val listed = runCatching { context.assets.list(DIR)?.toSet() }
            .onFailure { Log.w(TAG, "could not list $DIR", it) }.getOrNull() ?: emptySet()
        return listed + presence(context).known()
    }

    /**
     * The languages that have every file "<language><suffix>" of [suffixes]. `in` opens the files and is the
     * authoritative, cheap check (cached); iterating goes through the listing and is for the settings screen.
     */
    fun languages(context: Context, vararg suffixes: String): Set<String> = object : AbstractSet<String>() {
        private val app = context.applicationContext ?: context
        override fun contains(element: String) =
            element.isNotEmpty() && element.all { it.isLetterOrDigit() || it == '_' } && suffixes.all { exists(app, element + it) }
        override fun iterator(): Iterator<String> =
            names(app).filter { it.endsWith(suffixes[0]) }.map { it.removeSuffix(suffixes[0]) }.filter { contains(it) }.sorted().iterator()
        override val size: Int get() = iterator().asSequence().count()
        override fun isEmpty(): Boolean = !iterator().hasNext()
    }
}
