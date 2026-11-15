// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark

@Composable
fun InfoDialog(
    message: String,
    onDismissRequest: () -> Unit
) {
    InfoDialog(AnnotatedString(message), onDismissRequest)
}

@Composable
fun InfoDialog(
    message: AnnotatedString,
    onDismissRequest: () -> Unit
) {
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        content = { Text(message) },
        scrollContent = true,
        cancelButtonText = stringResource(android.R.string.ok),
        onConfirmed = { },
        confirmButtonText = null
    )
}

/** A small "?" button that opens an [InfoDialog] with [message]; [label] is read by screen readers. */
@Composable
fun InfoButton(message: String, label: String) {
    var show by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { show = true }) {
        Icon(painterResource(R.drawable.ic_help_outline), label)
    }
    if (show)
        InfoDialog(message) { show = false }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        InfoDialog("message") { }
    }
}
