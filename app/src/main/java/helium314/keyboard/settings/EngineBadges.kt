// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import helium314.keyboard.latin.common.LocaleUtils.engineKey
import helium314.keyboard.latin.lm.NeuralLanguageModel
import helium314.keyboard.latin.swipe.SwipeGestureDecoder
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.settings.screens.getUserAndInternalDictionaries
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Which prediction engines a language has, derived from the APK assets at runtime: "neural" when
 * swipe/<lang>.lmb + .lmv exist, "ngrams" when only word dictionaries exist without neural LM,
 * "swipe" when a .kpd swipe dictionary exists. New languages show up as soon as their assets are added.
 */
data class PredictionEngines(val neural: Boolean, val ngrams: Boolean, val swipe: Boolean) {
    val isEmpty get() = !neural && !ngrams && !swipe
    val isFullSupport get() = swipe && neural

    companion object {
        private val cache = ConcurrentHashMap<Locale, PredictionEngines>()

        /** the asset name the engines load for a locale: sk, sk_nd for sk-x-nd (LocaleUtils.engineKey) */
        private fun keys(locale: Locale): List<String> = listOf(locale.engineKey.lowercase(Locale.ROOT))

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

private enum class BadgeStyle { Highlight, Subtle, Muted, Warning }

/** Plain-language capability pills describing what swipe and prediction support [locale] has. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EngineBadges(locale: Locale, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val engines = remember(locale) { PredictionEngines.of(ctx, locale) }
    // the keyboard could not load the swipe dictionary (it retries); otherwise swipe would just seem not to work
    val swipeFailed = remember(locale) { engines.swipe && SwipeGestureDecoder.loadFailed(locale.engineKey) }
    FlowRow(
        modifier = modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when {
            engines.swipe && engines.neural -> {
                Badge(stringResource(R.string.engine_swipe), BadgeStyle.Highlight)
                Badge(stringResource(R.string.engine_neural), BadgeStyle.Subtle)
            }
            engines.swipe -> {
                Badge(stringResource(R.string.engine_swipe), BadgeStyle.Highlight)
                if (engines.ngrams)
                    Badge(stringResource(R.string.engine_ngrams), BadgeStyle.Subtle)
            }
            engines.neural -> {
                Badge(stringResource(R.string.engine_neural), BadgeStyle.Subtle)
            }
            engines.ngrams -> {
                Badge(stringResource(R.string.engine_ngrams), BadgeStyle.Subtle)
            }
            else -> {
                Badge(stringResource(R.string.engine_layout_only), BadgeStyle.Muted)
            }
        }
        if (swipeFailed)
            Badge(stringResource(R.string.engine_swipe_failed), BadgeStyle.Warning)
    }
}

@Composable
private fun Badge(text: String, style: BadgeStyle) {
    val shape = RoundedCornerShape(6.dp)
    val colors = MaterialTheme.colorScheme
    val containerColor = when (style) {
        BadgeStyle.Highlight -> colors.secondaryContainer
        BadgeStyle.Subtle -> colors.surface
        BadgeStyle.Muted -> colors.surfaceContainerLow
        BadgeStyle.Warning -> colors.errorContainer
    }
    val contentColor = when (style) {
        BadgeStyle.Highlight -> colors.onSecondaryContainer
        BadgeStyle.Subtle -> colors.onSurfaceVariant
        BadgeStyle.Muted -> colors.onSurfaceVariant
        BadgeStyle.Warning -> colors.onErrorContainer
    }
    val borderModifier = when (style) {
        BadgeStyle.Highlight -> Modifier
        BadgeStyle.Subtle -> Modifier.border(1.dp, colors.outlineVariant, shape)
        BadgeStyle.Muted -> Modifier.border(1.dp, colors.outlineVariant, shape)
        BadgeStyle.Warning -> Modifier
    }
    Surface(
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = borderModifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
