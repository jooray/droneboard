// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.support

/**
 * Droneboard: port of the shared support-ask engine (support-widget/spec/engine.mjs, the same rules as
 * Third Ear's SupportEngine.kt). Pure functions over a small state: no Android, no I/O, no network, no
 * clock of its own. It must pass every case of spec/engine-vectors.json (SupportEngineTest).
 * Days are ISO dates ("2026-11-14") in the user's local calendar.
 */
object SupportEngine {

    data class State(
        val activeDays: Int = 0,
        val lastActiveDay: String? = null,
        val actions: Int = 0,
        val asks: Int = 0,
        val lastAskDay: String? = null,
        val supporter: Boolean = false,
        val declined: Boolean = false,
    )

    data class Trigger(val minDays: Int, val minActions: Int, val cooldownDays: List<Int>, val maxAsks: Int)

    /**
     * momentOfValue: the call site is a result surface, not a task in progress.
     * busy: the user is in the middle of something (typing, dictating).
     * isSupporter: the app's own knowledge (Droneboard has none).
     */
    data class Context(val today: String, val momentOfValue: Boolean, val busy: Boolean = false, val isSupporter: Boolean = false)

    val DEFAULT_TRIGGER = Trigger(minDays = 3, minActions = 3, cooldownDays = listOf(14, 45), maxAsks = 3)

    /** Droneboard's two own numbers; they may still change, so they live here and nowhere else. */
    const val DRONEBOARD_MIN_DAYS = 7
    /** swiped words and dictations that typed text */
    const val DRONEBOARD_MIN_ACTIONS = 100

    /** Droneboard's trigger: a week of use and a hundred swiped words or dictations; the rest is the spec default. */
    val DRONEBOARD = DEFAULT_TRIGGER.copy(minDays = DRONEBOARD_MIN_DAYS, minActions = DRONEBOARD_MIN_ACTIONS)

    private val ISO_DAY = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

    /** Days since 1970-01-01 of an ISO date, null when it is not one (java.time needs API 26, the app runs on 21). */
    internal fun dayNumber(iso: String): Long? {
        val m = ISO_DAY.matchEntire(iso) ?: return null
        val year = m.groupValues[1].toLong()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        if (month !in 1..12 || day !in 1..31) return null
        // days from civil (proleptic Gregorian calendar), with the year starting in March
        val y = if (month <= 2) year - 1 else year
        val era = y / 400 // years are 0000..9999, so never negative enough to need a floor
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146097 + dayOfEra - 719468
    }

    /** Whole days from one ISO date to another; null when either is not a date. */
    fun daysBetween(fromIso: String, toIso: String): Long? {
        val from = dayNumber(fromIso) ?: return null
        val to = dayNumber(toIso) ?: return null
        return to - from
    }

    /** The keyboard was shown. Counts one day of use per calendar day, never per appearance. */
    fun onLaunch(s: State, today: String): State =
        if (s.lastActiveDay == today) s else s.copy(activeDays = s.activeDays + 1, lastActiveDay = today)

    /** One successful core action: a swiped word was committed or a dictation typed text. */
    fun onAction(s: State): State = s.copy(actions = s.actions + 1)

    /** The ask was put on screen. Counted when shown, so a dismissal needs no extra bookkeeping. */
    fun onShown(s: State, today: String): State = s.copy(asks = s.asks + 1, lastAskDay = today)

    /** The user tapped Support. The checkout opens in the browser; nobody asks again. */
    fun onSupportTapped(s: State): State = s.copy(supporter = true)

    /** The user said "don't ask again". Nothing is ever shown again; the settings keep a plain Support entry. */
    fun onDeclined(s: State): State = s.copy(declined = true)

    @Suppress("ReturnCount")
    fun shouldAsk(s: State, ctx: Context, trigger: Trigger = DEFAULT_TRIGGER): Boolean {
        if (s.supporter || s.declined || ctx.isSupporter) return false
        if (!ctx.momentOfValue || ctx.busy) return false
        if (s.activeDays < trigger.minDays || s.actions < trigger.minActions) return false
        if (s.asks >= trigger.maxAsks) return false
        if (s.asks > 0) {
            val wait = trigger.cooldownDays[minOf(s.asks - 1, trigger.cooldownDays.size - 1)]
            // a date that can't be read never makes an ask come early
            val waited = s.lastAskDay?.let { daysBetween(it, ctx.today) } ?: return false
            if (waited < wait) return false
        }
        return true
    }
}
