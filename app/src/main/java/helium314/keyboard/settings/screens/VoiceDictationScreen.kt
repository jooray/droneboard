// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import helium314.keyboard.compat.locale
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.voice.DictationController
import helium314.keyboard.latin.voice.OsttTranscriber
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.SwitchPreference

enum class OsttStatus {
    READY,
    UNAVAILABLE,
    NOT_INSTALLED,
    UNSUPPORTED,
}

fun checkOsttStatus(context: Context): OsttStatus {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return OsttStatus.UNSUPPORTED
    return when {
        OsttTranscriber.component(context) != null -> OsttStatus.READY
        OsttTranscriber.installed(context) -> OsttStatus.UNAVAILABLE
        else -> OsttStatus.NOT_INSTALLED
    }
}

@SuppressLint("NewApi")
fun openOsttInstall(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, OsttTranscriber.ZAPSTORE_URL.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@SuppressLint("NewApi")
fun openOsttApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(OsttTranscriber.OSTT_PACKAGE)
    val intent = launch ?: Intent(Intent.ACTION_VIEW, OsttTranscriber.ZAPSTORE_URL.toUri())
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun openDictationHelp(context: Context) {
    val helpLanguages = setOf("sk", "cs", "de", "es")
    val lang = context.resources.configuration.locale().language
    val url = if (lang in helpLanguages) {
        "https://cypherpunk.today/droneboard/$lang/dictation/"
    } else {
        "https://cypherpunk.today/droneboard/dictation/"
    }
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun osttShortSummary(status: OsttStatus): String = when (status) {
    OsttStatus.READY -> stringResource(R.string.dictation_status_ready_short)
    OsttStatus.UNAVAILABLE -> stringResource(R.string.dictation_status_unavailable_short)
    OsttStatus.NOT_INSTALLED -> stringResource(R.string.dictation_status_missing_short)
    OsttStatus.UNSUPPORTED -> stringResource(R.string.dictation_status_unsupported_short)
}

@Composable
fun VoiceDictationScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var osttStatus by remember { mutableStateOf(checkOsttStatus(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        osttStatus = checkOsttStatus(ctx)
    }
    val prefKeys = listOf(
        Settings.PREF_DICTATION_USE_BLUETOOTH_MIC,
        Settings.PREF_DICTATION_BACKSPACE_UNDO,
        Settings.PREF_DICTATION_SEND_PERSONAL_WORDS,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_voice_dictation),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
            ) {
                OsttStatusCard(
                    status = osttStatus,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                prefKeys.forEach { key ->
                    SettingsActivity.settingsContainer[key]?.Preference()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OsttStatusCard(
    status: OsttStatus,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val containerColor = when (status) {
        OsttStatus.READY -> colors.secondaryContainer
        OsttStatus.UNSUPPORTED -> colors.surfaceContainerLow
        OsttStatus.UNAVAILABLE, OsttStatus.NOT_INSTALLED -> colors.surfaceContainer
    }
    val contentColor = when (status) {
        OsttStatus.READY -> colors.onSecondaryContainer
        else -> colors.onSurface
    }
    val title = when (status) {
        OsttStatus.READY -> stringResource(R.string.dictation_status_ready_title)
        OsttStatus.UNAVAILABLE -> stringResource(R.string.voice_ostt_unavailable)
        OsttStatus.NOT_INSTALLED -> stringResource(R.string.voice_ostt_missing)
        OsttStatus.UNSUPPORTED -> stringResource(R.string.dictation_status_unsupported_short)
    }
    val detail = when (status) {
        OsttStatus.READY -> stringResource(R.string.dictation_status_ready_summary)
        OsttStatus.UNAVAILABLE -> stringResource(R.string.voice_ostt_unavailable_detail)
        OsttStatus.NOT_INSTALLED -> stringResource(R.string.voice_ostt_missing_detail)
        OsttStatus.UNSUPPORTED -> stringResource(R.string.dictation_status_unsupported_detail)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        border = if (status == OsttStatus.READY) null else BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status == OsttStatus.READY) contentColor else colors.onSurfaceVariant
            )
            if (status != OsttStatus.UNSUPPORTED) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (status) {
                        OsttStatus.READY -> {
                            OutlinedButton(
                                onClick = { openOsttApp(ctx) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.voice_open_ostt))
                            }
                        }
                        OsttStatus.UNAVAILABLE -> {
                            Button(
                                onClick = { openOsttApp(ctx) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.voice_open_ostt))
                            }
                            OutlinedButton(
                                onClick = { openOsttInstall(ctx) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.voice_install_ostt))
                            }
                        }
                        OsttStatus.NOT_INSTALLED -> {
                            Button(
                                onClick = { openOsttInstall(ctx) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.voice_install_ostt))
                            }
                        }
                        OsttStatus.UNSUPPORTED -> Unit
                    }
                    TextButton(
                        onClick = { openDictationHelp(ctx) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.voice_dictation_help))
                    }
                }
            }
        }
    }
}

fun createVoiceDictationSettings(context: Context) = listOf(
    Setting(
        context, Settings.PREF_DICTATION_USE_BLUETOOTH_MIC,
        R.string.dictation_use_bluetooth_mic, R.string.dictation_use_bluetooth_mic_summary
    ) {
        SwitchPreference(it, Defaults.PREF_DICTATION_USE_BLUETOOTH_MIC)
    },
    Setting(
        context, Settings.PREF_DICTATION_BACKSPACE_UNDO,
        R.string.dictation_backspace_undo, R.string.dictation_backspace_undo_summary
    ) {
        SwitchPreference(it, Defaults.PREF_DICTATION_BACKSPACE_UNDO)
    },
    Setting(
        context, Settings.PREF_DICTATION_SEND_PERSONAL_WORDS,
        R.string.dictation_send_personal_words, R.string.dictation_send_personal_words_summary
    ) {
        SwitchPreference(it, Defaults.PREF_DICTATION_SEND_PERSONAL_WORDS)
    },
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            VoiceDictationScreen { }
        }
    }
}
