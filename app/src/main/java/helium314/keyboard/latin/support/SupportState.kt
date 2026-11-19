// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.support

import helium314.keyboard.latin.support.SupportEngine.State
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Droneboard: support.v1 as stored, one JSON value (support-widget/spec/ask.md section 8, plus "declined"). No Android. */
object SupportState {
    /** the key of the value in the app's preferences */
    const val KEY = "support.v1"

    /** Corrupt or missing state is a fresh start, never a crash and never an early ask. */
    fun decode(raw: String?): State = runCatching {
        val o = Json.parseToJsonElement(raw ?: return State()) as JsonObject
        State(
            activeDays = o.int("activeDays"),
            lastActiveDay = o.day("lastActiveDay"),
            actions = o.int("actions"),
            asks = o.int("asks"),
            lastAskDay = o.day("lastAskDay"),
            supporter = o.boolean("supporter"),
            declined = o.boolean("declined"),
        )
    }.getOrDefault(State())

    fun encode(s: State): String = buildJsonObject {
        put("activeDays", s.activeDays)
        put("lastActiveDay", s.lastActiveDay?.let { JsonPrimitive(it) } ?: JsonNull)
        put("actions", s.actions)
        put("asks", s.asks)
        put("lastAskDay", s.lastAskDay?.let { JsonPrimitive(it) } ?: JsonNull)
        put("supporter", s.supporter)
        put("declined", s.declined)
    }.toString()

    /**
     * What a restored backup and the state on the phone make together: the larger counts, the later days,
     * and an asking that has ended stays ended. So restoring an older backup never brings the ask back after
     * "Don't ask again", and a backup restored on a new phone brings the answer along.
     */
    fun merge(a: State, b: State): State = State(
        activeDays = maxOf(a.activeDays, b.activeDays),
        lastActiveDay = later(a.lastActiveDay, b.lastActiveDay),
        actions = maxOf(a.actions, b.actions),
        asks = maxOf(a.asks, b.asks),
        lastAskDay = later(a.lastAskDay, b.lastAskDay),
        supporter = a.supporter || b.supporter,
        declined = a.declined || b.declined,
    )

    // ISO dates sort as text
    private fun later(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = get(key) as? JsonPrimitive
    private fun JsonObject.int(key: String): Int = (primitive(key)?.takeIf { !it.isString }?.intOrNull ?: 0).coerceAtLeast(0)
    private fun JsonObject.boolean(key: String): Boolean = primitive(key)?.takeIf { !it.isString }?.booleanOrNull ?: false
    private fun JsonObject.day(key: String): String? =
        primitive(key)?.takeIf { it.isString }?.content?.takeIf { SupportEngine.dayNumber(it) != null }
}
