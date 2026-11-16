// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.AppLanguage
import helium314.keyboard.latin.utils.DirectBoot
import helium314.keyboard.latin.utils.IntentUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SettingsTransfer
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import helium314.keyboard.settings.filePicker

private const val TAG = "SettingsTransfer"

/** Droneboard: "Export settings", writes the settings JSON of [SettingsTransfer] to a file the user picks. */
@Composable
fun ExportSettingsPreference(setting: Setting) {
    val ctx = LocalContext.current
    var error: String? by remember { mutableStateOf(null) }
    val launcher = filePicker { uri ->
        try {
            val export = SettingsTransfer.export(ctx)
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("no output stream")
            out.use { it.write(export.json.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(ctx, ctx.getString(R.string.settings_exported), Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Log.w(TAG, "error during settings export", t)
            error = ctx.getString(R.string.settings_export_error, t.message ?: t.javaClass.simpleName)
        }
    }
    Preference(name = setting.title, description = setting.description, onClick = {
        if (!DirectBoot.isUnlocked(ctx)) {
            error = ctx.getString(R.string.settings_transfer_locked)
            return@Preference
        }
        val intent = IntentUtils.getResolvableTypeIntent(ctx, SettingsTransfer.MIME_TYPE)
            .setAction(Intent.ACTION_CREATE_DOCUMENT)
            .putExtra(Intent.EXTRA_TITLE, SettingsTransfer.defaultFileName())
        launcher.launch(intent)
    })
    error?.let { InfoDialog(it) { error = null } }
}

/** Droneboard: "Import settings", reads a settings JSON, says how many settings change, then applies it. */
@Composable
fun ImportSettingsPreference(setting: Setting) {
    val ctx = LocalContext.current
    var error: String? by remember { mutableStateOf(null) }
    var pending: Pair<SettingsTransfer.Parsed, Int>? by remember { mutableStateOf(null) }
    val launcher = filePicker { uri ->
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(4 * 1024 * 1024 + 1)
                if (bytes.size > 4 * 1024 * 1024) throw SettingsTransfer.InvalidFileException(SettingsTransfer.Reason.TOO_LARGE)
                String(bytes, Charsets.UTF_8)
            } ?: throw IllegalStateException("no input stream")
            val parsed = SettingsTransfer.parse(text)
            if (parsed.skipped.isNotEmpty()) Log.i(TAG, "ignoring ${parsed.skipped.size} entries: ${parsed.skipped}")
            val changes = SettingsTransfer.countChanges(ctx, parsed)
            if (changes == 0) error = ctx.getString(R.string.settings_import_nothing)
            else pending = parsed to changes
        } catch (e: SettingsTransfer.InvalidFileException) {
            Log.w(TAG, "invalid settings file: ${e.reason}")
            error = when (e.reason) {
                SettingsTransfer.Reason.NEWER_FORMAT -> ctx.getString(R.string.settings_import_error_newer)
                SettingsTransfer.Reason.NOTHING_TO_IMPORT -> ctx.getString(R.string.settings_import_error_empty)
                else -> ctx.getString(R.string.settings_import_error_foreign)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "error reading settings file", t)
            error = ctx.getString(R.string.settings_import_error, t.message ?: t.javaClass.simpleName)
        }
    }
    Preference(name = setting.title, description = setting.description, onClick = {
        if (!DirectBoot.isUnlocked(ctx)) {
            error = ctx.getString(R.string.settings_transfer_locked)
            return@Preference
        }
        val intent = IntentUtils.getResolvableTypeIntent(ctx, "*/*")
            .setAction(Intent.ACTION_OPEN_DOCUMENT)
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(SettingsTransfer.MIME_TYPE, "text/plain", "application/octet-stream"))
        launcher.launch(intent)
    })
    pending?.let { (parsed, changes) ->
        ConfirmationDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            content = { Text(pluralStringResource(R.plurals.settings_import_confirm_message, changes, changes)) },
            confirmButtonText = stringResource(R.string.settings_import_button),
            onConfirmed = {
                pending = null
                try {
                    importAndReload(ctx, parsed)
                    Toast.makeText(ctx, ctx.getString(R.string.settings_imported), Toast.LENGTH_LONG).show()
                } catch (t: Throwable) {
                    Log.w(TAG, "error during settings import", t)
                    error = ctx.getString(R.string.settings_import_error, t.message ?: t.javaClass.simpleName)
                }
            }
        )
    }
    error?.let { InfoDialog(it) { error = null } }
}

/** Applies the import and reloads like Backup and restore does, without restarting the process. */
private fun importAndReload(ctx: Context, parsed: SettingsTransfer.Parsed) {
    LayoutUtilsCustom.onLayoutFileChanged()
    Settings.getInstance().stopListener()
    try {
        SettingsTransfer.apply(ctx, parsed)
    } finally {
        Settings.getInstance().startListener()
    }
    SubtypeSettings.reloadEnabledSubtypes(ctx)
    LayoutUtilsCustom.onLayoutFileChanged()
    LayoutUtilsCustom.removeMissingLayouts(ctx)
    Settings.getInstance().onSharedPreferenceChanged(ctx.prefs(), null)
    ctx.getActivity()?.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION))
    KeyboardSwitcher.getInstance().setThemeNeedsReload()
    val activity = ctx.getActivity()
    (activity as? SettingsActivity)?.prefChanged()
    val language = ctx.prefs().getString(Settings.PREF_APP_LANGUAGE, "") ?: ""
    if (activity != null && language != AppLanguage.get(ctx)) AppLanguage.set(activity, language)
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
