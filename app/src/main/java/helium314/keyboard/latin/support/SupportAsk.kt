// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.support

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.DirectBoot
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.util.Calendar
import java.util.Locale

/**
 * Droneboard: the in-app support ask (support-widget/spec/ask.md). It decides locally from one JSON value
 * in the app's preferences and opens the checkout in the browser. No network code, no new permission.
 *
 * The keyboard only counts ([onInputViewStarted], [onAction], [onInputViewFinished]); the strip is shown
 * in the settings only, never in the keyboard. Nothing is read or written before the first unlock
 * (direct boot), and nothing here may ever throw into the keyboard.
 */
object SupportAsk {
    private const val TAG = "SupportAsk"

    @Volatile
    private var counter: SupportCounter? = null

    private class PrefsStore(private val prefs: SharedPreferences) : SupportStore {
        override fun read(): String? = runCatching { prefs.getString(SupportState.KEY, null) }.getOrNull()
        override fun write(raw: String) = prefs.edit().putString(SupportState.KEY, raw).apply()
    }

    /** null before the first unlock: the state is not touched while the user is locked */
    private fun counter(context: Context): SupportCounter? {
        if (!DirectBoot.isUnlocked(context)) return null
        counter?.let { return it }
        synchronized(this) {
            return counter ?: SupportCounter(PrefsStore(context.prefs())).also { counter = it }
        }
    }

    private inline fun <T> safely(default: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "support ask", t)
        default
    }

    /** today in the user's local calendar, as an ISO date (java.time needs API 26) */
    internal fun today(calendar: Calendar = Calendar.getInstance()): String = String.format(
        Locale.ROOT, "%04d-%02d-%02d",
        calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH)
    )

    // ---- counting, called by the keyboard ----

    /** The keyboard was shown: a day of use (one write per calendar day). */
    @JvmStatic
    fun onInputViewStarted(context: Context) = safely(Unit) { counter(context)?.onInputViewStarted(today()) }

    /** A swiped word was committed or a dictation typed text. Counts +1, nothing about the text. */
    @JvmStatic
    fun onAction(context: Context) = safely(Unit) { counter(context)?.onAction(SystemClock.elapsedRealtime()) }

    /** The keyboard was hidden: what was counted is written. */
    @JvmStatic
    fun onInputViewFinished(context: Context) = safely(Unit) { counter(context)?.flush() }

    // ---- the ask, called by the settings screens ----

    private fun forced(context: Context): Boolean =
        context.prefs().getBoolean(DebugSettings.PREF_FORCE_SUPPORT_ASK, Defaults.PREF_FORCE_SUPPORT_ASK)

    /** Whether the strip may be shown now, on a result surface of the settings (never in the keyboard). */
    fun shouldAsk(context: Context): Boolean = safely(false) {
        val c = counter(context) ?: return@safely false
        if (forced(context)) return@safely true
        SupportEngine.shouldAsk(c.state(), SupportEngine.Context(today = today(), momentOfValue = true), SupportEngine.DRONEBOARD)
    }

    /** The strip is on screen: one ask is counted (not when it is only forced by the debug setting). */
    fun onShown(context: Context) = safely(Unit) {
        if (forced(context)) return@safely
        counter(context)?.update { SupportEngine.onShown(it, today()) }
    }

    /** Support on the strip: ends the asking and opens the checkout. */
    fun support(context: Context, ref: String, dark: Boolean, language: String?) {
        safely(Unit) { counter(context)?.update { SupportEngine.onSupportTapped(it) } }
        open(context, ref, dark, language)
    }

    /** Don't ask again: ends the asking for good, opens nothing. */
    fun never(context: Context) = safely(Unit) { counter(context)?.update { SupportEngine.onDeclined(it) } }

    /**
     * Opens the checkout in the browser. The menu rows call only this, which changes no state. The app
     * needs no internet for it: the link is handed to whatever app opens links.
     */
    fun open(context: Context, ref: String, dark: Boolean, language: String?) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SupportCheckout.url(ref, dark, language)))
        val started = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        if (started.isFailure) Log.w(TAG, "nothing opens the support page", started.exceptionOrNull())
    }

    /**
     * A backup was restored over the preferences. [before] is the stored value from before the restore:
     * it is merged with the restored one, so "Don't ask again" survives a restore of an older backup.
     */
    @JvmStatic
    fun afterRestore(context: Context, before: String?) = safely(Unit) {
        val c = counter(context) ?: return@safely
        c.invalidate()
        if (before != null) c.update { SupportState.merge(it, SupportState.decode(before)) }
    }
}
