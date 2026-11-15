// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.lm.NeuralLanguageModel
import helium314.keyboard.latin.swipe.SwipeGestureDecoder
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.settings.screens.getUserAndInternalDictionaries
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Which prediction engines a language has, derived from the APK assets at runtime: "neural" when
 * swipe/<lang>.lmb + .lmv exist, "n-grams" when only the trigram LM (.kpd) or word dictionaries exist,
 * "swipe" when a .kpd swipe dictionary exists. New languages show up as soon as their assets are added.
 */
data class PredictionEngines(val neural: Boolean, val ngrams: Boolean, val swipe: Boolean) {
    val isEmpty get() = !neural && !ngrams && !swipe

    companion object {
        private val cache = ConcurrentHashMap<Locale, PredictionEngines>()

        /** asset names a locale may use, most specific first (e.g. sk_nd before sk) */
        private fun keys(locale: Locale): List<String> = listOf(
            locale.toString().lowercase(Locale.ROOT),
            locale.toLanguageTag().lowercase(Locale.ROOT).replace('-', '_'),
            locale.language.lowercase(Locale.ROOT),
        ).distinct()

        fun of(context: Context, locale: Locale): PredictionEngines = cache.getOrPut(locale) {
            if (locale.language == SubtypeLocaleUtils.NO_LANGUAGE) return@getOrPut PredictionEngines(false, false, false)
            val keys = keys(locale)
            val neuralLangs = runCatching { NeuralLanguageModel.neuralLanguages(context) }.getOrDefault(emptySet())
            val swipeLangs = runCatching { SwipeGestureDecoder.languagesWithDictionary(context) }.getOrDefault(emptySet())
            val neural = keys.any { it in neuralLangs }
            val swipe = keys.any { it in swipeLangs }
            val dictionary = swipe || runCatching {
                val (userDicts, internal) = getUserAndInternalDictionaries(context, locale)
                internal || userDicts.isNotEmpty()
            }.getOrDefault(false)
            PredictionEngines(neural, !neural && dictionary, swipe)
        }

        /** user dictionaries may have been added or removed */
        fun clearCache() = cache.clear()
    }
}

/** Small Gboard-like chips with the prediction engines of [locale]. */
@Composable
fun EngineBadges(locale: Locale, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val engines = remember(locale) { PredictionEngines.of(ctx, locale) }
    if (engines.isEmpty) return
    Row(modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (engines.neural) Badge(stringResource(R.string.engine_neural), highlight = true)
        if (engines.ngrams) Badge(stringResource(R.string.engine_ngrams), highlight = false)
        if (engines.swipe) Badge(stringResource(R.string.engine_swipe), highlight = false)
    }
}

@Composable
private fun Badge(text: String, highlight: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (highlight) colors.secondaryContainer else colors.surface,
        contentColor = if (highlight) colors.onSecondaryContainer else colors.onSurfaceVariant,
        modifier = if (highlight) Modifier else Modifier.border(1.dp, colors.outlineVariant, shape)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}
