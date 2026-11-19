// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.support

import helium314.keyboard.latin.support.SupportEngine.State
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar
import java.util.GregorianCalendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plain JVM tests of the support ask: no Android, no Robolectric.
 *
 * The vectors in src/test/resources/support/engine-vectors.json are a copy of
 * ~/projects/support-widget/spec/engine-vectors.json (the shared specification of the author's apps);
 * refresh the copy whenever the spec changes. Every port of the engine must pass all of them.
 */
class SupportEngineTest {

    private val vectors = Json.parseToJsonElement(javaClass.getResource("/support/engine-vectors.json")!!.readText()).jsonObject

    private fun trigger(o: JsonObject) = SupportEngine.Trigger(
        minDays = o["minDays"]!!.jsonPrimitive.int,
        minActions = o["minActions"]!!.jsonPrimitive.int,
        cooldownDays = o["cooldownDays"]!!.jsonArray.map { it.jsonPrimitive.int },
        maxAsks = o["maxAsks"]!!.jsonPrimitive.int,
    )

    private fun context(o: JsonObject) = SupportEngine.Context(
        today = o["today"]!!.jsonPrimitive.content,
        momentOfValue = o["momentOfValue"]?.jsonPrimitive?.booleanOrNull ?: false,
        busy = o["busy"]?.jsonPrimitive?.booleanOrNull ?: false,
        isSupporter = o["isSupporter"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    /** Replays one case; returns the number of ask steps checked and the failed ones. */
    private fun replay(c: JsonObject, trigger: SupportEngine.Trigger): Pair<Int, List<String>> {
        var s = State()
        var asks = 0
        val failures = mutableListOf<String>()
        val name = c["name"]!!.jsonPrimitive.content
        for (stepElement in c["steps"]!!.jsonArray) {
            val step = stepElement.jsonArray
            when (val op = step[0].jsonPrimitive.content) {
                "launch" -> s = SupportEngine.onLaunch(s, step[1].jsonPrimitive.content)
                "action" -> repeat(step[1].jsonPrimitive.int) { s = SupportEngine.onAction(s) }
                "shown" -> s = SupportEngine.onShown(s, step[1].jsonPrimitive.content)
                "tapped" -> s = SupportEngine.onSupportTapped(s)
                "declined" -> s = SupportEngine.onDeclined(s)
                "ask" -> {
                    asks++
                    val ctx = context(step[1].jsonObject)
                    val got = SupportEngine.shouldAsk(s, ctx, trigger)
                    if (got != step[2].jsonPrimitive.boolean) failures += "$name @ ${ctx.today}: got $got"
                }
                else -> error("unknown op $op")
            }
        }
        return asks to failures
    }

    @Test
    fun `every shared vector passes`() {
        val trigger = trigger(vectors["trigger"]!!.jsonObject)
        val cases = vectors["cases"]!!.jsonArray
        val results = cases.map { replay(it.jsonObject, trigger) }
        println("support engine vectors: ${cases.size} cases, ${results.sumOf { it.first }} ask steps, ${results.sumOf { it.second.size }} failed")
        assertTrue(cases.size >= 14, "vectors file looks truncated")
        assertEquals(emptyList(), results.flatMap { it.second })
    }

    @Test
    fun `days between dates are calendar days`() {
        assertEquals(0L, SupportEngine.dayNumber("1970-01-01"))
        assertEquals(20406L, SupportEngine.dayNumber("2025-11-14"))
        assertEquals(14L, SupportEngine.daysBetween("2026-11-01", "2026-11-15"))
        assertEquals(1L, SupportEngine.daysBetween("2028-02-29", "2028-03-01"))
        assertEquals(2L, SupportEngine.daysBetween("2027-02-27", "2027-03-01"))
        assertEquals(366L, SupportEngine.daysBetween("2028-01-01", "2029-01-01"))
        assertEquals(-1L, SupportEngine.daysBetween("2027-01-01", "2026-12-31"))
        // the same numbers the platform's calendar gives, for every day of five years
        val calendar = GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(2024, 0, 1) }
        repeat(5 * 366) {
            val iso = SupportAsk.today(calendar)
            assertEquals(calendar.timeInMillis / 86_400_000L, SupportEngine.dayNumber(iso), iso)
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        assertNull(SupportEngine.daysBetween("yesterday", "2026-11-15"))
        assertNull(SupportEngine.daysBetween("2026-13-01", "2026-11-15"))
    }

    @Test
    fun `droneboard waits for a week of use and a hundred actions`() {
        assertEquals(7, SupportEngine.DRONEBOARD.minDays)
        assertEquals(100, SupportEngine.DRONEBOARD.minActions)
        assertEquals(listOf(14, 45), SupportEngine.DRONEBOARD.cooldownDays)
        assertEquals(3, SupportEngine.DRONEBOARD.maxAsks)
        var s = State()
        for (d in 1..6) s = SupportEngine.onLaunch(s, "2026-11-0$d")
        repeat(500) { s = SupportEngine.onAction(s) }
        val ctx = SupportEngine.Context(today = "2026-11-06", momentOfValue = true)
        assertFalse(SupportEngine.shouldAsk(s, ctx, SupportEngine.DRONEBOARD))
        s = SupportEngine.onLaunch(s, "2026-11-07")
        assertTrue(SupportEngine.shouldAsk(s, ctx.copy(today = "2026-11-07"), SupportEngine.DRONEBOARD))
        assertFalse(SupportEngine.shouldAsk(s, ctx.copy(today = "2026-11-07", busy = true), SupportEngine.DRONEBOARD))
        assertFalse(SupportEngine.shouldAsk(s.copy(actions = 99), ctx.copy(today = "2026-11-07"), SupportEngine.DRONEBOARD))
    }

    @Test
    fun `an unreadable day of the last ask never makes an ask come early`() {
        val s = State(activeDays = 9, actions = 200, asks = 1, lastAskDay = "garbage")
        val ctx = SupportEngine.Context(today = "2030-01-01", momentOfValue = true)
        assertFalse(SupportEngine.shouldAsk(s, ctx, SupportEngine.DRONEBOARD))
        assertFalse(SupportEngine.shouldAsk(s.copy(lastAskDay = null), ctx, SupportEngine.DRONEBOARD))
        assertFalse(SupportEngine.shouldAsk(s.copy(lastAskDay = "2029-12-25"), ctx.copy(today = "nonsense"), SupportEngine.DRONEBOARD))
        // the clock was set back: not yet
        assertFalse(SupportEngine.shouldAsk(s.copy(lastAskDay = "2030-06-01"), ctx, SupportEngine.DRONEBOARD))
    }

    // ---- the stored value ----

    @Test
    fun `stored state survives a round trip`() {
        val s = State(activeDays = 6, lastActiveDay = "2026-11-15", actions = 12, asks = 1, lastAskDay = "2026-11-10", supporter = true, declined = true)
        assertEquals(s, SupportState.decode(SupportState.encode(s)))
        assertEquals(State(), SupportState.decode(SupportState.encode(State())))
    }

    @Test
    fun `the stored value has the shape of the specification`() {
        assertEquals(
            """{"activeDays":0,"lastActiveDay":null,"actions":0,"asks":0,"lastAskDay":null,"supporter":false,"declined":false}""",
            SupportState.encode(State())
        )
        assertEquals("support.v1", SupportState.KEY)
        // a value written by an app without "declined" (the shape of spec section 8) is read as it is
        assertEquals(
            State(activeDays = 3, lastActiveDay = "2026-11-03", actions = 4, asks = 1, lastAskDay = "2026-11-03", supporter = true),
            SupportState.decode("""{"activeDays":3,"lastActiveDay":"2026-11-03","actions":4,"asks":1,"lastAskDay":"2026-11-03","supporter":true}""")
        )
    }

    @Test
    fun `missing or corrupt state is a fresh start`() {
        for (raw in listOf(null, "", "{not json", "[]", "null", "7", "\"text\"", "{", "\u0000\u0001")) {
            assertEquals(State(), SupportState.decode(raw), "raw: $raw")
        }
        // wrong types and impossible values in single fields fall back to the field's fresh value
        val odd = SupportState.decode(
            """{"activeDays":"many","lastActiveDay":17,"actions":-5,"asks":{"a":1},"lastAskDay":"soon","supporter":"yes","declined":null}"""
        )
        assertEquals(State(), odd)
        assertFalse(SupportEngine.shouldAsk(odd, SupportEngine.Context("2026-11-20", momentOfValue = true), SupportEngine.DRONEBOARD))
    }

    @Test
    fun `merging keeps an ended asking ended and the larger counts`() {
        val phone = State(activeDays = 20, lastActiveDay = "2026-11-20", actions = 300, asks = 1, lastAskDay = "2026-11-18", declined = true)
        val olderBackup = State(activeDays = 9, lastActiveDay = "2026-10-01", actions = 120)
        val merged = SupportState.merge(olderBackup, phone)
        assertEquals(phone, merged)
        assertEquals(merged, SupportState.merge(phone, olderBackup))
        // a new phone: the backup's answer comes along
        assertEquals(phone, SupportState.merge(State(), phone))
        assertTrue(SupportState.merge(State(supporter = true), State(declined = true)).let { it.supporter && it.declined })
    }

    // ---- the checkout address ----

    @Test
    fun `the checkout url carries lang theme and ref only`() {
        assertEquals(
            "https://hackyourself.io/support/s/droneboard?lang=sk&theme=dark&ref=settings-home",
            SupportCheckout.url(SupportCheckout.REF_SETTINGS_HOME, dark = true, language = "sk"),
        )
        assertEquals(
            "https://hackyourself.io/support/s/droneboard?lang=cs&theme=light&ref=after-import",
            SupportCheckout.url(SupportCheckout.REF_AFTER_IMPORT, dark = false, language = "cs"),
        )
        assertEquals(
            "https://hackyourself.io/support/s/droneboard?lang=en&theme=light&ref=menu",
            SupportCheckout.url(SupportCheckout.REF_MENU, dark = false, language = "en"),
        )
    }

    @Test
    fun `the language is a two letter code or english`() {
        fun lang(language: String?) = SupportCheckout.url("menu", false, language).substringAfter("lang=").substringBefore("&")
        assertEquals("de", lang("de")) // the checkout falls back itself for languages it does not have
        assertEquals("sk", lang("SK"))
        for (odd in listOf(null, "", "und", "sk-SK", "s&k", "x y", "čs", "e")) assertEquals("en", lang(odd), "language: $odd")
    }

    @Test
    fun `the ref is encoded`() {
        val url = SupportCheckout.url("a b&c=d#e/é", dark = true, language = "sk")
        assertEquals("https://hackyourself.io/support/s/droneboard?lang=sk&theme=dark&ref=a+b%26c%3Dd%23e%2F%C3%A9", url)
        val uri = java.net.URI(url)
        assertEquals("hackyourself.io", uri.host)
        assertEquals(listOf("lang", "theme", "ref"), uri.rawQuery.split("&").map { it.substringBefore("=") })
    }

    // ---- counting ----

    private class MemoryStore(var raw: String? = null) : SupportStore {
        var writes = 0
        var reads = 0
        override fun read(): String? { reads++; return raw }
        override fun write(raw: String) { writes++; this.raw = raw }
        val state get() = SupportState.decode(raw)
    }

    @Test
    fun `a day of use is written once per calendar day`() {
        val store = MemoryStore()
        val counter = SupportCounter(store)
        repeat(50) { counter.onInputViewStarted("2026-11-01") }
        assertEquals(1, store.writes)
        assertEquals(State(activeDays = 1, lastActiveDay = "2026-11-01"), store.state)
        val readsOnThatDay = store.reads
        repeat(50) { counter.onInputViewStarted("2026-11-01") }
        assertEquals(readsOnThatDay, store.reads, "the day is remembered in memory")
        counter.onInputViewStarted("2026-11-02")
        assertEquals(2, store.writes)
        assertEquals(2, store.state.activeDays)
        // the keyboard process restarted on the same day: nothing is written again
        val restarted = SupportCounter(store)
        repeat(5) { restarted.onInputViewStarted("2026-11-02") }
        assertEquals(2, store.writes)
        assertEquals(2, store.state.activeDays)
    }

    @Test
    fun `actions are written at most once a minute and when the keyboard hides`() {
        val store = MemoryStore()
        val counter = SupportCounter(store)
        var now = 1_000_000L
        repeat(30) { counter.onAction(now); now += 1_000 } // half a minute of swiping
        assertEquals(0, store.writes)
        repeat(31) { counter.onAction(now); now += 1_000 }
        assertEquals(1, store.writes)
        assertEquals(61, store.state.actions)
        repeat(10) { counter.onAction(now); now += 1_000 }
        assertEquals(1, store.writes)
        counter.flush() // the keyboard was hidden
        assertEquals(2, store.writes)
        assertEquals(71, store.state.actions)
        counter.flush()
        counter.flush()
        assertEquals(2, store.writes, "nothing to write, nothing written")
        // ten minutes of swiping, a word every two seconds: one write a minute
        repeat(300) { counter.onAction(now); now += 2_000 }
        assertTrue(store.writes - 2 <= 10, "writes: ${store.writes}")
        assertEquals(371, counter.state().actions)
    }

    @Test
    fun `counting stops by itself and then costs nothing`() {
        val cap = SupportEngine.DRONEBOARD_MIN_ACTIONS * SupportCounter.COUNT_MARGIN
        val store = MemoryStore(SupportState.encode(State(activeDays = 3, lastActiveDay = "2026-11-03", actions = cap - 2)))
        val counter = SupportCounter(store)
        var now = 0L
        repeat(5) { counter.onAction(now); now += 61_000 }
        assertTrue(store.state.actions >= cap)
        val writes = store.writes
        val reads = store.reads
        repeat(1000) { counter.onAction(now); now += 61_000 }
        counter.flush()
        assertEquals(writes, store.writes)
        assertEquals(reads, store.reads)
        // days go on until their own margin
        counter.onInputViewStarted("2026-11-04")
        assertEquals(4, store.state.activeDays)
    }

    @Test
    fun `nothing is counted after the first ask or once the asking has ended`() {
        val due = State(activeDays = 7, lastActiveDay = "2026-11-07", actions = 100)
        for (ended in listOf(
            SupportEngine.onShown(due, "2026-11-07"),
            SupportEngine.onDeclined(due),
            SupportEngine.onSupportTapped(due),
            due.copy(asks = 3, lastAskDay = "2026-11-07"),
        )) {
            val store = MemoryStore(SupportState.encode(ended))
            val counter = SupportCounter(store)
            counter.onInputViewStarted("2026-12-01")
            val reads = store.reads
            repeat(200) { counter.onAction(it * 61_000L) }
            repeat(20) { counter.onInputViewStarted("2026-12-${10 + it}") }
            counter.flush()
            assertEquals(0, store.writes, "state: $ended")
            assertEquals(reads, store.reads, "state: $ended")
            assertEquals(ended, store.state)
        }
    }

    @Test
    fun `the whole road from the first day to the last ask`() {
        val store = MemoryStore("{corrupt")
        val counter = SupportCounter(store)
        fun ask(today: String) = SupportEngine.shouldAsk(counter.state(), SupportEngine.Context(today, momentOfValue = true), SupportEngine.DRONEBOARD)
        var now = 0L
        for (d in 1..7) {
            val today = "2026-11-0$d"
            counter.onInputViewStarted(today)
            assertFalse(ask(today), today)
            repeat(15) { counter.onAction(now); now += 5_000 }
            counter.flush()
        }
        assertEquals(State(activeDays = 7, lastActiveDay = "2026-11-07", actions = 105), store.state)
        assertTrue(ask("2026-11-07"))
        counter.update { SupportEngine.onShown(it, "2026-11-07") } // the strip was shown; "Not now"
        assertFalse(ask("2026-11-07"))
        assertFalse(ask("2026-11-20"))
        assertTrue(ask("2026-11-21"))
        counter.update { SupportEngine.onShown(it, "2026-11-21") }
        assertFalse(ask("2027-01-04"))
        assertTrue(ask("2027-01-05"))
        counter.update { SupportEngine.onShown(it, "2027-01-05") }
        assertFalse(ask("2030-01-01"), "three asks in a lifetime")
        // "Don't ask again" on the first ask ends it at once
        val other = SupportCounter(MemoryStore(SupportState.encode(State(activeDays = 7, lastActiveDay = "2026-11-07", actions = 100))))
        other.update { SupportEngine.onDeclined(SupportEngine.onShown(it, "2026-11-07")) }
        assertFalse(SupportEngine.shouldAsk(other.state(), SupportEngine.Context("2030-01-01", momentOfValue = true), SupportEngine.DRONEBOARD))
    }

    @Test
    fun `a restored backup replaces what was held in memory`() {
        val store = MemoryStore()
        val counter = SupportCounter(store)
        counter.onInputViewStarted("2026-11-01")
        counter.onAction(0)
        val before = store.raw
        // the restore wrote an older backup over the preferences
        store.raw = SupportState.encode(State(activeDays = 40, lastActiveDay = "2026-10-01", actions = 500, declined = true))
        counter.invalidate()
        val merged = counter.update { SupportState.merge(it, SupportState.decode(before)) }
        assertEquals(State(activeDays = 40, lastActiveDay = "2026-11-01", actions = 500, declined = true), merged)
        assertEquals(merged, store.state)
        val writes = store.writes
        counter.onInputViewStarted("2026-11-02")
        counter.onAction(100_000)
        counter.flush()
        assertEquals(writes, store.writes, "declined: nothing is counted any more")
    }
}
