// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import helium314.keyboard.latin.DictionaryFacilitatorImpl
import helium314.keyboard.latin.lm.NeuralLanguageModel
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.swipe.SwipeGestureDecoder

/**
 * Droneboard: what the suggestions currently come from, as a few lines for the saved log (About, Save log).
 * Names of languages and of loaded files only, never typed text. Every part fails on its own, so that the
 * report still shows the rest when one of them is the broken one.
 */
object PredictionDiagnostics {
    @JvmStatic
    fun report(context: Context): String {
        val lines = ArrayList<String>()
        fun line(block: () -> String) { lines.add(runCatching(block).getOrElse { "failed: $it" }) }
        line {
            val prefs = context.prefs()
            "subtypes: selected=${prefs.getString(Settings.PREF_SELECTED_SUBTYPE, null)}" +
                " resolved=${SubtypeSettings.getSelectedSubtype(prefs).let { it.locale().toLanguageTag() + " " + it.extraValue }}" +
                " enabled=${SubtypeSettings.getEnabledSubtypes(false).map { it.locale().toLanguageTag() + " " + getSecondaryLocales(it.extraValue) }}"
        }
        line { "unlocked=${DirectBoot.isUnlocked(context)} settingsLoaded=${Settings.getValues() != null}" }
        line { DictionaryFacilitatorImpl.keyboardInstance?.get()?.debugState() ?: "dictionaries: the keyboard has not started in this process" }
        line { SwipeGestureDecoder.debugState() }
        line { NeuralLanguageModel.debugState() }
        return "Droneboard prediction state\n" + lines.joinToString("\n")
    }
}
