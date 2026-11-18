// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.swipe.TypingSwipePolicy
import helium314.keyboard.latin.swipe.TypingSwipeRecorder
import helium314.keyboard.latin.utils.IntentUtils
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.filePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything [TypingSwipeRecorder] has stored, as the user should see it: the swiped words with the
 * words before them and what happened next, newest first; with "Delete all" and "Export".
 */
@Composable
fun TypingSwipesScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats by TypingSwipeRecorder.stats.collectAsState()
    var entries by remember { mutableStateOf<List<TypingSwipeRecorder.Entry>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { TypingSwipeRecorder.refresh(ctx) }
    // read again when the stored data changes (deleted, or a swipe was written meanwhile)
    LaunchedEffect(stats.count, stats.bytes) {
        entries = withContext(Dispatchers.IO) { TypingSwipeRecorder.entries(ctx) }
    }
    val exportLauncher = filePicker { uri ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { TypingSwipeRecorder.export(ctx, uri) }
            Toast.makeText(ctx, if (ok) R.string.typing_swipes_exported else R.string.typing_swipes_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.typing_swipes_data_title),
        settings = emptyList(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.typing_swipes_count, stats.count, Formatter.formatShortFileSize(ctx, stats.bytes)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(stringResource(R.string.typing_swipes_intro), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.typing_swipes_limit, TypingSwipeRecorder.MAX_RECORDS, (TypingSwipeRecorder.MAX_BYTES shr 20).toInt()),
                style = MaterialTheme.typography.bodySmall
            )
            if (stats.full)
                Text(stringResource(R.string.typing_swipes_full), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirmExport = true }, enabled = stats.count > 0) {
                    Text(stringResource(R.string.typing_swipes_export))
                }
                TextButton(onClick = { confirmDelete = true }, enabled = stats.count > 0) {
                    Text(stringResource(R.string.typing_swipes_delete_all))
                }
            }
            HorizontalDivider()
            val list = entries
            if (list != null && list.isEmpty())
                Text(stringResource(R.string.typing_swipes_empty), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(list ?: emptyList()) { EntryRow(it) }
            }
        }
    }

    if (confirmDelete) {
        ConfirmationDialog(
            onDismissRequest = { confirmDelete = false },
            onConfirmed = { TypingSwipeRecorder.deleteAll(ctx) },
            content = { Text(stringResource(R.string.typing_swipes_delete_confirm, stats.count)) },
            confirmButtonText = stringResource(R.string.typing_swipes_delete_all),
        )
    }
    if (confirmExport) {
        ConfirmationDialog(
            onDismissRequest = { confirmExport = false },
            onConfirmed = {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                exportLauncher.launch(
                    IntentUtils.getResolvableTypeIntent(ctx, "application/octet-stream")
                        .setAction(Intent.ACTION_CREATE_DOCUMENT)
                        .putExtra(Intent.EXTRA_TITLE, "droneboard-typing-swipes-$date.jsonl")
                )
            },
            title = { Text(stringResource(R.string.typing_swipes_export)) },
            content = { Text(stringResource(R.string.typing_swipes_export_message, stats.count, TypingSwipeRecorder.PREV_WORDS)) },
            confirmButtonText = stringResource(R.string.typing_swipes_export_choose),
        )
    }
}

@Composable
private fun EntryRow(entry: TypingSwipeRecorder.Entry) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val deleted = entry.outcome == TypingSwipePolicy.DELETED
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(buildAnnotatedString {
            if (entry.prevWords.isNotEmpty())
                withStyle(SpanStyle(color = dim)) { append(entry.prevWords.joinToString(" ") + " ") }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = if (deleted) TextDecoration.LineThrough else null)) {
                append(entry.committed)
            }
        }, style = MaterialTheme.typography.bodyLarge)
        val label = when (entry.outcome) {
            TypingSwipePolicy.DELETED -> stringResource(R.string.typing_swipes_deleted)
            TypingSwipePolicy.REPLACED -> stringResource(R.string.typing_swipes_replaced, entry.chosen ?: "")
            else -> stringResource(R.string.typing_swipes_accepted)
        }
        val time = DateUtils.formatDateTime(ctx, entry.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
        Text("$label · $time", style = MaterialTheme.typography.bodySmall, color = dim)
    }
}
