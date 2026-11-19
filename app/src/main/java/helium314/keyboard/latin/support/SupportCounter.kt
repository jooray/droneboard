// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.support

import helium314.keyboard.latin.support.SupportEngine.State
import helium314.keyboard.latin.support.SupportEngine.Trigger

/** where support.v1 is kept; the app's preferences in the app, a variable in tests */
interface SupportStore {
    fun read(): String?
    fun write(raw: String)
}

/**
 * Droneboard: keeps the support-ask state and counts days of use and actions for it, cheaply. No Android.
 *
 * The stored value is the truth; this object only holds what is not written yet. Numbers only, never text.
 * - a day of use is written once per calendar day (the day is remembered in memory, so the other
 *   appearances of the keyboard on that day cost one comparison);
 * - actions are counted in memory and written at most once per [flushIntervalMs], and on [flush];
 * - once the first ask was shown, the asking has ended, or a count is [COUNT_MARGIN] times over its
 *   trigger, that count stops: nothing is read or written any more.
 */
class SupportCounter(
    private val store: SupportStore,
    private val trigger: Trigger = SupportEngine.DRONEBOARD,
    private val flushIntervalMs: Long = FLUSH_INTERVAL_MS,
) {
    private var loaded = false
    private var countDays = false
    private var countActions = false
    private var day: String? = null
    private var pending = 0
    private var pendingSinceMs = 0L

    /** The keyboard was shown on [today] (ISO date, local calendar). */
    @Synchronized
    fun onInputViewStarted(today: String) {
        if (!loaded) update { it }
        if (!countDays || day == today) return
        update { SupportEngine.onLaunch(it, today) }
        day = today
    }

    /** A swiped word was committed or a dictation typed text; [nowMs] is any clock that only moves forward. */
    @Synchronized
    fun onAction(nowMs: Long) {
        if (!loaded) update { it }
        if (!countActions) return
        if (pending == 0) pendingSinceMs = nowMs
        pending++
        if (nowMs - pendingSinceMs >= flushIntervalMs) update { it }
    }

    /** Writes the actions counted since the last write, if there are any (the keyboard was hidden). */
    @Synchronized
    fun flush() {
        if (pending > 0) update { it }
    }

    /** The current state, with everything counted so far. */
    @Synchronized
    fun state(): State = update { it }

    /** Applies [change] to the stored state (with the actions not written yet) and stores the result if it differs. */
    @Synchronized
    fun update(change: (State) -> State): State {
        val stored = SupportState.decode(store.read())
        var s = stored
        if (pending > 0 && countActions) s = s.copy(actions = s.actions + pending)
        pending = 0
        s = change(s)
        if (s != stored) store.write(SupportState.encode(s))
        loaded = true
        val counting = !s.supporter && !s.declined && s.asks == 0
        countDays = counting && s.activeDays < trigger.minDays * COUNT_MARGIN
        countActions = counting && s.actions < trigger.minActions * COUNT_MARGIN
        return s
    }

    /** The stored value was replaced from outside (a restored backup): forget what was held in memory. */
    @Synchronized
    fun invalidate() {
        loaded = false
        day = null
        pending = 0
    }

    companion object {
        const val FLUSH_INTERVAL_MS = 60_000L
        /** counting goes on to this many times the trigger, so a trigger raised by an update finds real numbers */
        const val COUNT_MARGIN = 10
    }
}
