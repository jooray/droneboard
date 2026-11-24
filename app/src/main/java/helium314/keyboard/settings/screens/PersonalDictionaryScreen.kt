// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.UserDictionary
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.settings.DropDownField
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PersonalDictionaryScreen(
    onClickBack: () -> Unit,
    locale: Locale?
) {
    val ctx = LocalContext.current
    // Droneboard: the words are read once, off the main thread, and again after a change (this used to query
    // the provider in every recomposition). They are sorted here and not by the provider, whose
    // UPPER() knows only ASCII and put every word starting with an accented letter after Z.
    var reload by remember { mutableIntStateOf(0) }
    var list: LetterIndex.Result<Word>? by remember { mutableStateOf(null) }
    LaunchedEffect(locale, reload) {
        list = withContext(Dispatchers.IO) {
            val all = runCatching { getAll(locale, ctx) }.getOrDefault(emptyList())
            LetterIndex.build(all, Collator.getInstance(locale ?: Locale.getDefault())) { it.word }
        }
    }
    var selectedWord: Word? by remember { mutableStateOf(null) }
    val fileActions = personalDictionaryFileActions(onImported = { reload++ })
    // deletes only the words of this list
    val deleteAll = personalDictionaryDeleteAll(everything = false, locale = locale, export = fileActions.export) { reload++ }
    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.edit_personal_dictionary))
                Text(
                    locale.getLocaleDisplayNameForUserDictSettings(LocalContext.current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        menu = listOf(
            stringResource(R.string.personal_dictionary_import) to fileActions.import,
            stringResource(R.string.personal_dictionary_export) to fileActions.export,
            stringResource(R.string.personal_dictionary_delete_all) to deleteAll,
        ),
        filteredItems = { term ->
            list?.items.orEmpty().filter { it.word.startsWith(term, true) || it.shortcut?.startsWith(term, true) == true }
        },
        itemContent = { word -> WordRow(word) { selectedWord = word } },
        content = {
            list?.let {
                if (it.items.isEmpty()) {
                    EmptyPersonalDictionary(onImport = fileActions.import)
                } else {
                    WordList(it) { word -> selectedWord = word }
                }
            }
        }
    )
    if (selectedWord != null) {
        EditWordDialog(selectedWord!!, locale, onChanged = { reload++ }) { selectedWord = null }
    }
    ExtendedFloatingActionButton(
        onClick = { selectedWord = Word("", null, null) },
        text = { Text(stringResource(R.string.user_dict_add_word_button)) },
        icon = { Icon(painter = painterResource(R.drawable.ic_edit), contentDescription = null) },
        modifier = Modifier.wrapContentSize(Alignment.BottomEnd).padding(all = 12.dp)
            .then(Modifier.safeDrawingPadding())
    )
}

@Composable
private fun EmptyPersonalDictionary(onImport: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.personal_dictionary_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.personal_dictionary_empty_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.personal_dictionary_import))
                }
            }
        }
    }
}

@Composable
private fun WordRow(word: Word, onClick: () -> Unit) {
    val weight = word.weight
    val weightText = if (weight != null && weight != WEIGHT_FOR_USER_DICTIONARY_ADDS) {
        stringResource(R.string.personal_dictionary_weight_label, weight)
    } else null
    val details = listOfNotNull(
        word.shortcut?.takeIf { it.isNotBlank() },
        weightText
    ).joinToString("  ·  ").ifEmpty { null }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp, horizontal = 16.dp)
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(word.word, style = MaterialTheme.typography.bodyLarge)
            if (details != null) {
                Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** the whole list; a long one gets a letter index at the end edge (the left one in right-to-left languages) */
@Composable
private fun WordList(list: LetterIndex.Result<Word>, onClick: (Word) -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showIndex = list.items.size > MIN_WORDS_FOR_INDEX && list.sections.size > 1
    var activeSection: Int? by remember { mutableStateOf(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    // the bottom inset contains the keyboard, so the index is as tall as the visible part of the list
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = state,
                    // the last rows can be scrolled out from under the "add word" button
                    contentPadding = PaddingValues(bottom = SPACE_FOR_ADD_BUTTON),
                ) {
                    items(list.items, key = { it.id }) { word -> WordRow(word) { onClick(word) } }
                }
                if (showIndex)
                    LetterIndexBar(
                        sections = list.sections,
                        onSection = { scope.launch { state.scrollToItem(list.sections[it].firstIndex) } },
                        onActive = { section, y -> activeSection = section; fingerY = y },
                        modifier = Modifier.fillMaxHeight().padding(top = INDEX_TOP_PADDING, bottom = SPACE_FOR_ADD_BUTTON),
                    )
            }
            val label = activeSection?.let { list.sections.getOrNull(it)?.label }
            if (showIndex && label != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = LetterIndexWidth + 12.dp)
                        .offset { IntOffset(0, (fingerY + INDEX_TOP_PADDING.toPx() - BUBBLE_SIZE.toPx() / 2).roundToInt().coerceAtLeast(0)) }
                        .size(BUBBLE_SIZE)
                        .clearAndSetSemantics { }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

private const val MIN_WORDS_FOR_INDEX = 30
private val SPACE_FOR_ADD_BUTTON = 80.dp
private val INDEX_TOP_PADDING = 4.dp
private val BUBBLE_SIZE = 56.dp

@Composable
private fun EditWordDialog(word: Word, locale: Locale?, onChanged: () -> Unit, onDismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var newWord by remember { mutableStateOf(word) }
    var newLocale by remember { mutableStateOf(locale) }
    val wordValid = (newWord.word == word.word && locale == newLocale) || !doesWordExist(newWord.word, newLocale, ctx)
    fun save() {
        if (newWord != word || locale != newLocale) {
            deleteWord(word, locale, ctx.contentResolver)
            val saveWeight = newWord.weight ?: WEIGHT_FOR_USER_DICTIONARY_ADDS
            UserDictionary.Words.addWord(ctx, newWord.word, saveWeight, newWord.shortcut, newLocale)
            onChanged()
        }
    }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { save() },
        checkOk = { newWord.word.isNotBlank() && wordValid },
        confirmButtonText = stringResource(R.string.save),
        neutralButtonText = stringResource(R.string.delete),
        onNeutral = {
            deleteWord(word, locale, ctx.contentResolver) // delete the originally selected word
            onChanged()
            onDismissRequest()
        },
        title = {
            Column {
                Text(stringResource(R.string.user_dict_settings_edit_dialog_title))
                Text(
                    locale.getLocaleDisplayNameForUserDictSettings(ctx),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        content = {
            LaunchedEffect(word) {
                if (word.word == "" && word.weight == null && word.shortcut == null)
                    focusRequester.requestFocus() // user clicked add word
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = newWord.word,
                    onValueChange = { newWord = newWord.copy(word = it) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true,
                    label = { Text(stringResource(R.string.user_dict_settings_add_word_hint))},
                    keyboardActions = KeyboardActions {
                        if (newWord.word.isNotBlank() && wordValid) {
                            save()
                            onDismissRequest()
                        }
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.user_dict_settings_add_shortcut_option_name), Modifier.fillMaxWidth(0.3f))
                    TextField(
                        value = newWord.shortcut ?: "",
                        onValueChange = { newWord = newWord.copy(shortcut = it.ifBlank { null }) },
                        label = { Text(stringResource(R.string.user_dict_settings_add_shortcut_hint))},
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.user_dict_settings_add_weight_value), Modifier.fillMaxWidth(0.3f))
                    TextField(
                        newWord.weight?.toString() ?: "",
                        {
                            if (it.isBlank())
                                newWord = newWord.copy(weight = null)
                            else if ((it.toIntOrNull() ?: -1) in 0..255)
                                newWord = newWord.copy(weight = it.toInt())
                        },
                        label = { Text(WEIGHT_FOR_USER_DICTIONARY_ADDS.toString()) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.user_dict_settings_add_locale_option_name), Modifier.fillMaxWidth(0.3f))
                    DropDownField(
                        items = getSpecificallySortedLocales(locale),
                        selectedItem = newLocale,
                        onSelected = { newLocale = it },
                    ) {
                        Text(it.getLocaleDisplayNameForUserDictSettings(ctx))
                    }
                }
                if (!wordValid)
                    Text(
                        stringResource(R.string.user_dict_word_already_present, newLocale.getLocaleDisplayNameForUserDictSettings(ctx)),
                        color = MaterialTheme.colorScheme.error
                    )
            }
        }
    )
}

private fun deleteWord(wordDetails: Word, locale: Locale?, resolver: ContentResolver) {
    val (word, shortcut, weightInt) = wordDetails
    val weight = weightInt.toString()
    if (shortcut.isNullOrBlank()) {
        if (locale == null) {
            resolver.delete(
                UserDictionary.Words.CONTENT_URI, DELETE_SELECTION_WITHOUT_SHORTCUT_AND_WITH_ALL_LOCALES,
                arrayOf(word, weight)
            )
        } else {
            resolver.delete( // requires use of locale string for interaction with Android system
                UserDictionary.Words.CONTENT_URI, DELETE_SELECTION_WITHOUT_SHORTCUT_AND_WITH_LOCALE,
                arrayOf(word, weight, locale.toString())
            )
        }
    } else {
        if (locale == null) {
            resolver.delete(
                UserDictionary.Words.CONTENT_URI, DELETE_SELECTION_WITH_SHORTCUT_AND_WITH_ALL_LOCALES,
                arrayOf(word, shortcut, weight)
            )
        } else {
            resolver.delete( // requires use of locale string for interaction with Android system
                UserDictionary.Words.CONTENT_URI, DELETE_SELECTION_WITH_SHORTCUT_AND_WITH_LOCALE,
                arrayOf(word, shortcut, weight, locale.toString())
            )
        }
    }
}

private fun doesWordExist(word: String, locale: Locale?, context: Context): Boolean {
    val hasWordProjection = arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.LOCALE)

    val select: String
    val selectArgs: Array<String>?
    if (locale == null) {
        select = "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.LOCALE} is null"
        selectArgs = arrayOf(word)
    } else {
        select = "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.LOCALE}=?"
        // requires use of locale string (as opposed to more useful language tag) for interaction with Android system
        selectArgs = arrayOf(word, locale.toString())
    }
    val cursor = context.contentResolver.query(UserDictionary.Words.CONTENT_URI, hasWordProjection, select, selectArgs, null)
    cursor.use {
        if (null == it) return false
        return it.count > 0
    }
}

private fun getSpecificallySortedLocales(firstLocale: Locale?): List<Locale?> {
    val list: MutableList<Locale?> = getSortedDictionaryLocales().toMutableList()
    list.remove(firstLocale)
    list.remove(null)
    list.add(0, firstLocale)
    if (firstLocale != null)
        list.add(null)
    return list
}

fun Locale?.getLocaleDisplayNameForUserDictSettings(context: Context) =
    this?.localizedDisplayName(context.resources) ?: context.resources.getString(R.string.user_dict_settings_all_languages)

// weight is frequency but different name towards user
// id is the row of the provider (the stable key of the list), 0 for a word that is not saved yet
private data class Word(val word: String, val shortcut: String?, val weight: Int?, val id: Long = 0)

// getting all words instead of reading directly cursor, because filteredItems expects a list
// Droneboard: one query; call it off the main thread. The order is made by LetterIndex.build.
private fun getAll(locale: Locale?, context: Context): List<Word> {
    val cursor = createCursor(locale, context) ?: return emptyList()

    if (!cursor.moveToFirst()) {
        cursor.close()
        return emptyList()
    }
    val result = mutableListOf<Word>()
    val wordIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.WORD)
    val shortcutIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.SHORTCUT)
    val frequencyIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.FREQUENCY)
    val idIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words._ID)
    while (!cursor.isAfterLast) {
        val word = cursor.getString(wordIndex)
        if (word != null)
            result.add(Word(word, cursor.getString(shortcutIndex), cursor.getInt(frequencyIndex), cursor.getLong(idIndex)))
        cursor.moveToNext()
    }
    cursor.close()
    return result
}

private fun createCursor(locale: Locale?, context: Context): Cursor? {
    // locale can be any of:
    // - An actual locale, for use of Locale#toString()
    // - The emptyLocale. This means we want a cursor returning words valid for all locales.

    // Note that this contrasts with the data inside the database, where NULL means "all
    // locales" and there should never be an empty string.
    // The confusion is called by the historical use of null for "all locales".

    val select: String
    val selectArgs: Array<String>?
    if (locale == null) {
        select = QUERY_SELECTION_ALL_LOCALES
        selectArgs = null
    } else {
        select = QUERY_SELECTION
        // requires use of locale string (as opposed to more useful language tag) for interaction with Android system
        selectArgs = arrayOf(locale.toString())
    }

    return context.contentResolver.query(
        UserDictionary.Words.CONTENT_URI, QUERY_PROJECTION, select, selectArgs, SORT_ORDER
    )
}

private val QUERY_PROJECTION =
    arrayOf(UserDictionary.Words._ID, UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT, UserDictionary.Words.FREQUENCY)
// Case-insensitive sort
private const val SORT_ORDER = "UPPER(" + UserDictionary.Words.WORD + ")"

// Either the locale is empty (means the word is applicable to all locales)
// or the word equals our current locale
private const val QUERY_SELECTION = UserDictionary.Words.LOCALE + "=?"
private const val QUERY_SELECTION_ALL_LOCALES = UserDictionary.Words.LOCALE + " is null"

private const val DELETE_SELECTION_WITH_SHORTCUT_AND_WITH_LOCALE = (UserDictionary.Words.WORD + "=? AND "
        + UserDictionary.Words.SHORTCUT + "=? AND "
        + UserDictionary.Words.FREQUENCY + "=? AND "
        + UserDictionary.Words.LOCALE + "=?")

private const val DELETE_SELECTION_WITH_SHORTCUT_AND_WITH_ALL_LOCALES = (UserDictionary.Words.WORD + "=? AND "
        + UserDictionary.Words.SHORTCUT + "=? AND "
        + UserDictionary.Words.FREQUENCY + "=? AND "
        + UserDictionary.Words.LOCALE + " is null")

private const val DELETE_SELECTION_WITHOUT_SHORTCUT_AND_WITH_LOCALE = (UserDictionary.Words.WORD + "=? AND "
        + UserDictionary.Words.SHORTCUT + " is null AND "
        + UserDictionary.Words.FREQUENCY + "=? AND "
        + UserDictionary.Words.LOCALE + "=? OR "

        + UserDictionary.Words.SHORTCUT + "='' AND "
        + UserDictionary.Words.FREQUENCY + "=? AND "
        + UserDictionary.Words.LOCALE + "=?")

private const val DELETE_SELECTION_WITHOUT_SHORTCUT_AND_WITH_ALL_LOCALES = (UserDictionary.Words.WORD + "=? AND "
        + UserDictionary.Words.SHORTCUT + " is null AND "
        + UserDictionary.Words.FREQUENCY + "=? AND "
        + UserDictionary.Words.LOCALE + " is null OR "

        + UserDictionary.Words.SHORTCUT + "='' AND "
        + UserDictionary.Words.FREQUENCY + "=? AND "
        + UserDictionary.Words.LOCALE + " is null")

private const val WEIGHT_FOR_USER_DICTIONARY_ADDS = 250
