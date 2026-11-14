// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.Gravity
import android.provider.Settings.Global
import androidx.core.content.edit
import helium314.keyboard.latin.utils.FoldableUtils
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

fun isFloatingKeyboardEnabled(context: Context) =
    context.prefs().getBoolean(Settings.PREF_FLOATING_ENABLED_PREFIX + context.resources.displayMetrics.widthPixels, false)

/** Tablets and unfolded foldables (smallest width >= 600dp). */
fun isLargeScreen(context: Context) = context.resources.configuration.smallestScreenWidthDp >= 600

// ---- compact docked keyboard: attached to the bottom, narrower than the screen, left / center / right ----
// stored per orientation and fold state, like the keyboard height

private fun compactKey(prefix: String, landscape: Boolean, folded: Boolean) =
    createPrefKeyForBooleanSettings(prefix, findIndexOfDefaultSetting(landscape, folded), 2)

private fun currentLandscape() = Settings.getValues().mDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE

/** Unset means: compact on large screens when "Compact keyboard on large screens" is on, full width elsewhere. */
fun readCompactEnabled(context: Context, landscape: Boolean, folded: Boolean): Boolean {
    val key = compactKey(Settings.PREF_COMPACT_ENABLED_PREFIX, landscape, folded)
    val prefs = context.prefs()
    return if (prefs.contains(key)) prefs.getBoolean(key, false)
    else isLargeScreen(context) && prefs.getBoolean(Settings.PREF_COMPACT_LARGE_SCREENS, Defaults.PREF_COMPACT_LARGE_SCREENS)
}

/** Narrowest compact keyboard as a fraction of the full width: about 280dp, at least 20 %. */
fun minCompactWidth(context: Context): Float {
    val fullDp = ResourceUtils.getDefaultKeyboardWidth(context) / context.resources.displayMetrics.density
    return (280f / fullDp).coerceIn(0.2f, 1f)
}

/** Width as a fraction of the full keyboard width; by default about a phone keyboard (440dp). */
fun readCompactWidth(context: Context, landscape: Boolean, folded: Boolean): Float {
    val fullDp = ResourceUtils.getDefaultKeyboardWidth(context) / context.resources.displayMetrics.density
    val default = (440f / fullDp).coerceIn(minCompactWidth(context), 1f)
    return context.prefs().getFloat(compactKey(Settings.PREF_COMPACT_WIDTH_PREFIX, landscape, folded), default)
}

/** Gravity.LEFT, CENTER_HORIZONTAL or RIGHT; the right side (thumb) by default. */
fun readCompactGravity(context: Context, landscape: Boolean, folded: Boolean): Int =
    context.prefs().getInt(compactKey(Settings.PREF_COMPACT_GRAVITY_PREFIX, landscape, folded), Gravity.RIGHT)

/** Keys about as tall as on a phone (220dp keyboard) unless the user changed it. */
fun readCompactHeightScale(context: Context, landscape: Boolean, folded: Boolean): Float {
    val res = context.resources
    val defaultHeightDp = ResourceUtils.getDefaultKeyboardHeight(res, false) / res.displayMetrics.density
    val default = (220f / defaultHeightDp).coerceIn(0.5f, 1f)
    return context.prefs().getFloat(compactKey(Settings.PREF_COMPACT_HEIGHT_SCALE_PREFIX, landscape, folded), default)
}

/** Changes the compact keyboard for the current orientation and fold state; null leaves a value as it is. */
fun writeCompact(context: Context, enabled: Boolean? = null, width: Float? = null, gravity: Int? = null, heightScale: Float? = null) {
    val landscape = currentLandscape()
    val folded = FoldableUtils.isFolded
    context.prefs().edit {
        enabled?.let { putBoolean(compactKey(Settings.PREF_COMPACT_ENABLED_PREFIX, landscape, folded), it) }
        width?.let { putFloat(compactKey(Settings.PREF_COMPACT_WIDTH_PREFIX, landscape, folded), it) }
        gravity?.let { putInt(compactKey(Settings.PREF_COMPACT_GRAVITY_PREFIX, landscape, folded), it) }
        heightScale?.let { putFloat(compactKey(Settings.PREF_COMPACT_HEIGHT_SCALE_PREFIX, landscape, folded), it) }
    }
}

/** Back to the default compact width and height (the side is kept). */
fun resetCompactSize(context: Context) {
    val landscape = currentLandscape()
    val folded = FoldableUtils.isFolded
    context.prefs().edit {
        remove(compactKey(Settings.PREF_COMPACT_WIDTH_PREFIX, landscape, folded))
        remove(compactKey(Settings.PREF_COMPACT_HEIGHT_SCALE_PREFIX, landscape, folded))
    }
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
