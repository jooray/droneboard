// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.os.SystemClock
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.common.LocaleUtils.engineKey
import helium314.keyboard.latin.lm.NeuralLanguageModel
import helium314.keyboard.latin.swipe.SwipeGestureDecoder

/**
 * Droneboard: self-healing of the prediction engines. Dictionaries and models are loaded once, when the
 * keyboard process starts. If one of them did not load then, for whatever reason, the keyboard used to predict
 * without it until the process restarted (a keyboard process lives for days). Whenever the keyboard is shown,
 * [check] looks at what should be loaded for the enabled languages and starts loading what is missing.
 *
 * Cheap when everything is in place (a few map lookups); the loading itself runs on the loader threads.
 * Anything it had to repair is written to the log with the full state, so that the cause can be found.
 */
object PredictionHealth {
    private const val TAG = "PredictionHealth"
    private const val CHECK_INTERVAL_MS = 3_000L
    private const val REPORT_INTERVAL_MS = 10 * 60_000L
    /** loads requested by the first check after the process started are the normal start, not a repair */
    private const val STARTUP_MS = 20_000L

    private val processStart = SystemClock.elapsedRealtime()
    @Volatile private var checkedAt = 0L
    @Volatile private var reportedAt = 0L
    @Volatile private var listingChecked = false

    @JvmStatic
    fun check(context: Context) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - checkedAt < CHECK_INTERVAL_MS) return
            checkedAt = now
            if (!DirectBoot.isUnlocked(context)) return // minimal keyboard before the first unlock, nothing to load
            val locales = SwipeGestureDecoder.enabledLocales()
            val repaired = ArrayList<String>()
            SwipeGestureDecoder.ensureLoaded(context, locales).forEach { repaired.add("swipe dictionary $it") }
            NeuralLanguageModel.ensureLoaded(context, locales.map { it.engineKey }).forEach { repaired.add("neural model $it") }
            DictionaryFacilitatorImpl.keyboardInstance?.get()?.retryMissingMainDictionaries(context)
                ?.forEach { repaired.add("main dictionary $it") }
            if (!listingChecked) {
                listingChecked = true
                val missing = helium314.keyboard.latin.swipe.SwipeGestureDecoder.filesMissingFromListing(context)
                if (missing.isNotEmpty()) Log.w(TAG, "the listing of the APK is incomplete, missing $missing (worked around)")
            }
            if (repaired.isEmpty() || now - processStart < STARTUP_MS) return
            Log.w(TAG, "was not loaded, loading now: $repaired")
            if (now - reportedAt > REPORT_INTERVAL_MS || reportedAt == 0L) {
                reportedAt = now
                Log.w(TAG, PredictionDiagnostics.report(context))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "check failed", e) // never in the way of the keyboard
        }
    }
}
