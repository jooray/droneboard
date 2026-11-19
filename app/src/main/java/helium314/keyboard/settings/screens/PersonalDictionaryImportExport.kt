// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.filePicker
import helium314.keyboard.settings.preferences.PersonalDictionaryBackup
import helium314.keyboard.settings.preferences.PersonalDictionaryImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * what the two rows of the personal dictionary screen do; [importDone] is true once an import added words
 * and its result was closed (the screen may then show the support ask, never inside the dialog)
 */
class PersonalDictionaryFileActions(val import: () -> Unit, val export: () -> Unit, val importDone: Boolean = false)

private class ImportPreview(val parsed: PersonalDictionaryImport.Parsed, val plan: PersonalDictionaryImport.Plan)

private const val PREVIEW_WORDS = 20
private const val TAG = "PersonalDictionaryImport"

/**
 * Droneboard: import of a word list into the personal dictionary and export of the dictionary to a text file.
 * Android lets only an enabled keyboard write to the personal dictionary, so this has to happen in the app.
 * The file is read and the words are written off the main thread; nothing is written before the user confirms.
 */
@Composable
fun personalDictionaryFileActions(): PersonalDictionaryFileActions {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var message: String? by remember { mutableStateOf(null) }
    var preview: ImportPreview? by remember { mutableStateOf(null) }
    var importResultShown by remember { mutableStateOf(false) }
    var importDone by remember { mutableStateOf(false) }

    val importPicker = filePicker { uri ->
        working = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { readImport(ctx, uri) } }
            working = false
            result.onSuccess { r ->
                when (r) {
                    is ImportPreview -> preview = r
                    is String -> message = r
                }
            }.onFailure {
                Log.w(TAG, "could not read the file", it)
                message = ctx.getString(R.string.personal_dictionary_import_unreadable)
            }
        }
    }
    val exportPicker = filePicker { uri ->
        working = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val entries = PersonalDictionaryBackup.readAll(ctx).orEmpty()
                    val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: error("no output stream")
                    out.use { it.write(PersonalDictionaryImport.toText(entries).toByteArray(Charsets.UTF_8)) }
                    entries.size
                }
            }
            working = false
            message = result.fold(
                { ctx.getString(R.string.personal_dictionary_export_done, it) },
                { ctx.getString(R.string.personal_dictionary_export_error, it.message ?: it.javaClass.simpleName) }
            )
        }
    }

    if (working) WorkingDialog()
    message?.let {
        InfoDialog(it) {
            message = null
            if (importResultShown) {
                importResultShown = false
                importDone = true
            }
        }
    }
    preview?.let { p ->
        if (p.plan.toAdd.isEmpty()) {
            InfoDialog(previewText(ctx, p)) { preview = null }
            return@let
        }
        ConfirmationDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.personal_dictionary_import_title)) },
            content = { Text(previewText(ctx, p)) },
            confirmButtonText = stringResource(R.string.personal_dictionary_import_confirm),
            onConfirmed = {
                preview = null
                working = true
                scope.launch {
                    // leaving the screen must not stop the import half way
                    val added = withContext(Dispatchers.IO + NonCancellable) {
                        runCatching { PersonalDictionaryBackup.addMissing(ctx, p.plan.toAdd) }
                            .onFailure { Log.w(TAG, "could not write to the personal dictionary", it) }.getOrNull()
                    }
                    working = false
                    importResultShown = added != null && added > 0
                    message = if (added == null || added == 0) ctx.getString(R.string.personal_dictionary_unavailable)
                        else ctx.getString(R.string.personal_dictionary_import_done, added)
                }
            }
        )
    }

    return PersonalDictionaryFileActions(
        import = {
            if (!isKeyboardEnabled(ctx)) message = ctx.getString(R.string.personal_dictionary_unavailable)
            else importPicker.launch(
                // by content, not by type: word lists come as text/plain, application/zip, application/json
                // or as an unknown type, depending on the file manager
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            )
        },
        export = {
            scope.launch {
                val count = withContext(Dispatchers.IO) {
                    runCatching { PersonalDictionaryBackup.readAll(ctx)?.size }.getOrNull()
                }
                when {
                    !isKeyboardEnabled(ctx) || count == null ->
                        message = ctx.getString(R.string.personal_dictionary_unavailable)
                    count == 0 -> message = ctx.getString(R.string.personal_dictionary_export_empty)
                    else -> exportPicker.launch(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "personal_dictionary.txt")
                    )
                }
            }
        },
        importDone = importDone
    )
}

/** an [ImportPreview] to confirm, or the message to show when there is nothing to import */
private fun readImport(ctx: Context, uri: Uri): Any {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { PersonalDictionaryImport.readLimited(it) }
        ?: return ctx.getString(R.string.personal_dictionary_import_unreadable)
    val parsed = PersonalDictionaryImport.parse(bytes)
    when (parsed.error) {
        PersonalDictionaryImport.Error.TOO_LARGE -> return ctx.getString(
            R.string.personal_dictionary_import_too_large,
            PersonalDictionaryImport.MAX_BYTES / (1024 * 1024), PersonalDictionaryImport.MAX_ENTRIES
        )
        PersonalDictionaryImport.Error.NOT_A_WORD_LIST -> return ctx.getString(R.string.personal_dictionary_import_unreadable)
        null -> Unit
    }
    if (parsed.entries.isEmpty())
        return ctx.getString(R.string.personal_dictionary_import_empty, parsed.invalidLines)
    val existing = PersonalDictionaryBackup.existingKeys(ctx)
        ?: return ctx.getString(R.string.personal_dictionary_unavailable)
    return ImportPreview(parsed, PersonalDictionaryImport.plan(parsed.entries, existing))
}

private fun previewText(ctx: Context, p: ImportPreview): String {
    val sb = StringBuilder()
    val format = when (p.parsed.format) {
        PersonalDictionaryImport.Format.GBOARD -> R.string.personal_dictionary_format_gboard
        PersonalDictionaryImport.Format.DRONEBOARD -> R.string.personal_dictionary_format_droneboard
        else -> R.string.personal_dictionary_format_text
    }
    sb.append(ctx.getString(format)).append("\n\n")
    sb.append(ctx.getString(R.string.personal_dictionary_import_summary,
        p.parsed.entries.size, p.plan.toAdd.size, p.plan.existing, p.parsed.invalidLines))
    if (p.parsed.duplicates > 0)
        sb.append('\n').append(ctx.getString(R.string.personal_dictionary_import_repeated, p.parsed.duplicates))
    if (p.plan.toAdd.isEmpty()) {
        sb.append("\n\n").append(ctx.getString(R.string.personal_dictionary_import_nothing_new))
    } else {
        sb.append("\n\n").append(ctx.getString(R.string.personal_dictionary_import_preview)).append('\n')
        sb.append(p.plan.toAdd.take(PREVIEW_WORDS).joinToString(", ") { it.word })
        val more = p.plan.toAdd.size - PREVIEW_WORDS
        if (more > 0) sb.append(' ').append(ctx.getString(R.string.personal_dictionary_import_more, more))
    }
    return sb.toString()
}

private fun isKeyboardEnabled(ctx: Context): Boolean = runCatching {
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)
}.getOrDefault(true)

@Composable
private fun WorkingDialog() {
    Dialog(onDismissRequest = { }, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(stringResource(R.string.personal_dictionary_working))
            }
        }
    }
}
