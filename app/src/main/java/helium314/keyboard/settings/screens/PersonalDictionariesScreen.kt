// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.support.SupportCheckout
import helium314.keyboard.settings.SupportAskStrip
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.splitOnWhitespace
import helium314.keyboard.latin.utils.SubtypeLocaleUtils
import helium314.keyboard.latin.utils.SubtypeSettings.getEnabledSubtypes
import helium314.keyboard.latin.utils.SubtypeSettings.getSystemLocales
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.latin.utils.previewDark
import java.util.Locale
import java.util.TreeSet

@Composable
fun PersonalDictionariesScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val locales: MutableList<Locale?> = getSortedDictionaryLocales().toMutableList()
    locales.add(0, null)
    // Droneboard: a word list can be imported from a file and the dictionary exported to one
    val fileActions = personalDictionaryFileActions()
    val importLabel = stringResource(R.string.personal_dictionary_import)
    val exportLabel = stringResource(R.string.personal_dictionary_export)
    // Droneboard: "Delete all words" here empties the whole dictionary, in every language
    val deleteAll = personalDictionaryDeleteAll(everything = true, locale = null, export = fileActions.export)
    val deleteLabel = stringResource(R.string.personal_dictionary_delete_all)
    val items: List<Any?> = locales + listOf(
        FileAction(importLabel, fileActions.import),
        FileAction(exportLabel, fileActions.export),
        FileAction(deleteLabel, deleteAll, destructive = true),
    ) +
        // the support ask, under the rows, after a successful import (it decides itself whether it shows)
        (if (fileActions.importDone) listOf(SupportAskItem) else emptyList())
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.edit_personal_dictionary)) },
        filteredItems = { term ->
            items.filter { item ->
                if (item === SupportAskItem) return@filter term.isBlank() // never while searching
                val name = if (item is FileAction) item.label else (item as Locale?).getLocaleDisplayNameForUserDictSettings(ctx)
                name.replace("(", "").splitOnWhitespace().any { it.startsWith(term, true) }
            }
        },
        itemContent = { item ->
            if (item === SupportAskItem) {
                SupportAskStrip(SupportCheckout.REF_AFTER_IMPORT)
                return@SearchScreen
            }
            if (item is FileAction) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { item.onClick() }
                        .heightIn(min = 44.dp)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.label, style = MaterialTheme.typography.bodyLarge,
                        color = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                return@SearchScreen
            }
            val it = item as Locale?
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        SettingsDestination.navigateTo(SettingsDestination.PersonalDictionary + (it?.toLanguageTag() ?: ""))
                    }
                    .heightIn(min = 44.dp)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(it.getLocaleDisplayNameForUserDictSettings(ctx), style = MaterialTheme.typography.bodyLarge)
                NextScreenIcon()
            }
        }
    )
}

private class FileAction(val label: String, val onClick: () -> Unit, val destructive: Boolean = false)

private object SupportAskItem

fun getSortedDictionaryLocales(): TreeSet<Locale> {
    val sortedLocales = sortedSetOf<Locale>(compareBy { it.toLanguageTag().lowercase() })

    // Add the main language selected in the "Language and Layouts" setting except "No language"
    for (mainSubtype in getEnabledSubtypes(true)) {
        val mainLocale = mainSubtype.locale()
        if (mainLocale.toLanguageTag() != SubtypeLocaleUtils.NO_LANGUAGE) {
            sortedLocales.add(mainLocale)
        }
        // Secondary language is added only if main language is selected
        val enabled = getEnabledSubtypes(false)
        for (subtype in enabled) {
            if (subtype.locale() == mainLocale) sortedLocales.addAll(getSecondaryLocales(subtype.extraValue))
        }
    }

    sortedLocales.addAll(getSystemLocales())
    return sortedLocales
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            PersonalDictionariesScreen { }
        }
    }
}
