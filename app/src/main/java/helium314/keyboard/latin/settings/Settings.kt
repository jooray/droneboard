// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.provider.Settings.Global
import androidx.core.content.edit
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import kotlinx.serialization.json.Json

fun customIconNames(prefs: SharedPreferences) = runCatching {
    Json.decodeFromString<Map<String, String>>(prefs.getString(Settings.PREF_CUSTOM_ICON_NAMES, Defaults.PREF_CUSTOM_ICON_NAMES)!!)
}.getOrElse { emptyMap() }

@SuppressLint("DiscouragedApi")
fun customIconIds(context: Context, prefs: SharedPreferences) = customIconNames(prefs)
    .mapNotNull { entry ->
        val id = runCatching { context.resources.getIdentifier(entry.value, "drawable", context.packageName) }.getOrNull()
        id?.let { entry.key to it }
    }

/** Derive an index from a number of boolean [settingValues], used to access the matching default value in a defaults arraY */
fun findIndexOfDefaultSetting(vararg settingValues: Boolean): Int {
    var i = -1
    return settingValues.sumOf { i++; if (it) 1.shl(i) else 0 }
}

/** Create pref key that is derived from a [number] of boolean conditions. The [index] is as created by [findIndexOfDefaultSetting]. */
fun createPrefKeyForBooleanSettings(prefix: String, index: Int, number: Int): String =
    "${prefix}_${Array(number) { index.shr(it) % 2 == 1 }.joinToString("_")}"

fun getTransitionAnimationScale(context: Context) =
    Global.getFloat(context.contentResolver, Global.TRANSITION_ANIMATION_SCALE, 1f)

/**
 * Floating is stored per screen width (so per orientation and fold state). When the user never chose for this
 * width, a large screen in landscape floats by default (Gboard's "auto float keyboard in landscape mode").
 */
fun isFloatingKeyboardEnabled(context: Context): Boolean {
    val key = Settings.PREF_FLOATING_ENABLED_PREFIX + context.resources.displayMetrics.widthPixels
    val prefs = context.prefs()
    return if (prefs.contains(key)) prefs.getBoolean(key, false) else autoFloats(context)
}

/** Large screen (smallest width >= 600dp) in landscape with the auto-float setting on. */
fun autoFloats(context: Context): Boolean {
    val config = context.resources.configuration
    return config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.smallestScreenWidthDp >= 600
            && context.prefs().getBoolean(Settings.PREF_AUTO_FLOAT_LANDSCAPE, Defaults.PREF_AUTO_FLOAT_LANDSCAPE)
}

fun setFloatingKeyboardEnabled(context: Context, enabled: Boolean) =
    context.prefs().edit { putBoolean(Settings.PREF_FLOATING_ENABLED_PREFIX + context.resources.displayMetrics.widthPixels, enabled) }

fun readFloatingHeight(context: Context): Int {
    val screenWidth = context.resources.displayMetrics.widthPixels
    val key = Settings.PREF_FLOATING_HEIGHT_PREFIX + screenWidth
    return context.prefs().getInt(key, defaultFloatingHeight(context))
}

fun readFloatingWidth(context: Context): Int {
    val screenWidth = context.resources.displayMetrics.widthPixels
    val key = Settings.PREF_FLOATING_WIDTH_PREFIX + screenWidth
    return context.prefs().getInt(key, defaultFloatingWidth(context))
}

/** About 45 % of the width on a landscape tablet (thumb-sized, Gboard-like), half the width elsewhere. */
fun defaultFloatingWidth(context: Context): Int {
    val dm = context.resources.displayMetrics
    val config = context.resources.configuration
    val large = config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.smallestScreenWidthDp >= 600
    return (dm.widthPixels * (if (large) 0.45f else 0.5f)).toInt()
}

/** Keep the phone-like key aspect: the default keyboard height scaled down with the width, at most a third of the screen. */
fun defaultFloatingHeight(context: Context): Int {
    val dm = context.resources.displayMetrics
    val docked = ResourceUtils.getDefaultKeyboardHeight(context.resources, false)
    val ratio = defaultFloatingWidth(context).toFloat() / dm.widthPixels
    return (docked * (ratio * 1.6f).coerceIn(0.6f, 1f)).toInt().coerceAtMost(dm.heightPixels / 3)
}

/** Forget the floating size and position for the current screen width. */
fun resetFloatingSize(context: Context) {
    val screenWidth = context.resources.displayMetrics.widthPixels
    context.prefs().edit {
        remove(Settings.PREF_FLOATING_WIDTH_PREFIX + screenWidth)
        remove(Settings.PREF_FLOATING_HEIGHT_PREFIX + screenWidth)
        remove(Settings.PREF_FLOATING_POS_X_PREFIX + screenWidth)
        remove(Settings.PREF_FLOATING_POS_Y_PREFIX + screenWidth)
    }
}

fun setFloatingSize(context: Context, width: Int, height: Int) {
    val screenWidth = context.resources.displayMetrics.widthPixels
    context.prefs().edit {
        putInt(Settings.PREF_FLOATING_WIDTH_PREFIX + screenWidth, width)
        putInt(Settings.PREF_FLOATING_HEIGHT_PREFIX + screenWidth, height)
    }
}
