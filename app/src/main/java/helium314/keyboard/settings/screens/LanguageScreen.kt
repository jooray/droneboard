// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodSubtype
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.common.LocaleUtils
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.dictionary.DictionaryInfoUtils
import helium314.keyboard.latin.dictionary.MissingDictionaryDialog
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.EngineBadges
import helium314.keyboard.settings.PredictionEngines
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import java.util.Locale

@Composable
fun LanguageScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val sortedSubtypes by remember { mutableStateOf(getSortedSubtypes(ctx)) }
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val enabledSubtypes = SubtypeSettings.getEnabledSubtypes()
    val (fullSupportSubtypes, otherSubtypes) = remember(sortedSubtypes) {
        sortedSubtypes.partition { PredictionEngines.of(ctx, it.locale()).isFullSupport }
    }
    var showAllOther by rememberSaveable { mutableStateOf(false) }

    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.language_and_layouts_title))
                Text(
                    stringResource(R.string.text_tap_languages),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        filteredItems = { term ->
            sortedSubtypes.filter { subtype ->
                subtype.displayName().replace("(", "")
                    .splitOnWhitespace().any { it.startsWith(term, true) }
            }
        },
        itemContent = { SubtypeRow(it, it in enabledSubtypes) },
        content = {
            val enabledOther = otherSubtypes.filter { it in enabledSubtypes }
            val visibleOther = if (showAllOther) otherSubtypes else enabledOther
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                LazyColumn(contentPadding = innerPadding) {
                    if (fullSupportSubtypes.isNotEmpty()) {
                        item(key = "header_full_support") {
                            PreferenceCategory(stringResource(R.string.languages_section_full_support))
                        }
                        items(fullSupportSubtypes, key = { "full_${it.hashCode()}_${it.extraValue}" }) { subtype ->
                            SubtypeRow(subtype, subtype in enabledSubtypes)
                        }
                    }
                    if (otherSubtypes.isNotEmpty()) {
                        item(key = "header_other") {
                            PreferenceCategory(stringResource(R.string.languages_section_other))
                        }
                        items(visibleOther, key = { "other_${it.hashCode()}_${it.extraValue}" }) { subtype ->
                            SubtypeRow(subtype, subtype in enabledSubtypes)
                        }
                        if (otherSubtypes.size > enabledOther.size) {
                            item(key = "toggle_other") {
                                TextButton(
                                    onClick = { showAllOther = !showAllOther },
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                        .heightIn(min = 48.dp)
                                ) {
                                    val remaining = otherSubtypes.size - enabledOther.size
                                    Text(
                                        if (showAllOther) stringResource(R.string.languages_hide_other)
                                        else pluralStringResource(R.plurals.languages_show_all_other, remaining, remaining)
                                    )
                                }
                            }
                        }
                    }
                    item(key = "header_advanced") {
                        PreferenceCategory(stringResource(R.string.settings_screen_advanced))
                    }
                    item(key = "secondary_layouts") {
                        Preference(
                            name = stringResource(R.string.settings_screen_secondary_layouts),
                            description = stringResource(R.string.secondary_layouts_summary),
                            icon = R.drawable.ic_settings_layout,
                            onClick = { SettingsDestination.navigateTo(SettingsDestination.Layouts) }
                        ) { NextScreenIcon() }
                    }
                }
            }
        }
    )
}

@Composable
private fun SubtypeRow(subtype: InputMethodSubtype, isEnabled: Boolean) {
    val ctx = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                SettingsDestination.navigateTo(SettingsDestination.Subtype + subtype.toSettingsSubtype().toPref())
            }
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp, horizontal = 16.dp)
    ) {
        var showNoDictDialog by remember { mutableStateOf(false) }
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(subtype.displayName(), style = MaterialTheme.typography.bodyLarge)
            val description = if (SubtypeSettings.isAdditionalSubtype(subtype)) {
                val secondaryLocales = subtype.getExtraValueOf(ExtraValue.SECONDARY_LOCALES)?.split(Separators.KV)
                    ?.joinToString(", ") { it.constructLocale().localizedDisplayName(ctx.resources) }
                stringResource(R.string.custom_subtype) + (secondaryLocales?.let { "\n$it" } ?: "")
            } else null
            if (description != null)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            EngineBadges(subtype.locale())
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = {
                if (it && !dictsAvailable(subtype.locale(), ctx))
                    showNoDictDialog = true
                if (it) SubtypeSettings.addEnabledSubtype(ctx.prefs(), subtype)
                else SubtypeSettings.removeEnabledSubtype(ctx, subtype)
            }
        )
        if (showNoDictDialog)
            MissingDictionaryDialog({ showNoDictDialog = false }, subtype.locale())
    }
}

private fun dictsAvailable(locale: Locale, context: Context): Boolean {
    if (locale.language == SubtypeLocaleUtils.NO_LANGUAGE) return true // incorrect, but we don't want to show the dialog for "no language"
    val (dicts, hasInternal) = getUserAndInternalDictionaries(context, locale)
    return hasInternal || dicts.isNotEmpty()
}

// sorting by display name is still slow, even with the cache... but probably good enough
private fun getSortedSubtypes(context: Context): List<InputMethodSubtype> {
    val availableSubtypes = SubtypeSettings.getAllAvailableSubtypes()
    val availableLocales = availableSubtypes.mapTo(HashSet()) { it.locale() }
    val systemLocales = SubtypeSettings.getSystemLocales().map {
        LocaleUtils.getBestMatch(it, availableLocales) { st -> st }
    }
    val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
    val localesWithDictionary = DictionaryInfoUtils.getCacheDirectories(context).mapNotNull { dir ->
        if (dir.list()?.any { it.endsWith(DictionaryInfoUtils.USER_DICTIONARY_SUFFIX) } == true)
            dir.name.constructLocale()
        else null
    }

    val defaultAdditionalSubtypes = Defaults.PREF_ADDITIONAL_SUBTYPES.split(Separators.SETS).map {
        it.substringBefore(Separators.SET) to (it.substringAfter(Separators.SET) + ",AsciiCapable,EmojiCapable,isAdditionalSubtype")
    }
    fun isDefaultSubtype(subtype: InputMethodSubtype): Boolean =
        defaultAdditionalSubtypes.any { it.first == subtype.locale().language && it.second == subtype.extraValue }

    val subtypeSortComparator = compareBy<InputMethodSubtype>(
        { it !in enabledSubtypes },
        { !PredictionEngines.of(context, it.locale()).isFullSupport },
        { it.locale() !in localesWithDictionary },
        { it.locale() !in systemLocales },
        { !(SubtypeSettings.isAdditionalSubtype(it) && !isDefaultSubtype(it)) },
        {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) it.languageTag == SubtypeLocaleUtils.NO_LANGUAGE
            else it.locale == SubtypeLocaleUtils.NO_LANGUAGE
        },
        { it.displayName() }
    )
    return availableSubtypes.sortedWith(subtypeSortComparator)
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            LanguageScreen { }
        }
    }
}
