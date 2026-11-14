// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens.gesturedata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.swipe.SwipeRecorder
import helium314.keyboard.settings.SearchSettingsScreen

/**
 * Asks the user to swipe prompt words and records the gestures locally (see [SwipeRecorder]),
 * so that the swipe decoder can be tuned on real swipes with tools/swipe-replay.
 */
@Composable
fun SwipeRecorderScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val words = remember { SWIPE_PROMPTS.shuffled() }
    var index by remember { mutableIntStateOf(0) }
    var field by remember { mutableStateOf("") }
    val file = remember { SwipeRecorder.start(ctx) }
    val recorded by SwipeRecorder.recorded.collectAsState()
    val last by SwipeRecorder.lastResult.collectAsState()
    val focus = remember { FocusRequester() }
    val target = words.getOrNull(index)

    DisposableEffect(Unit) { onDispose { SwipeRecorder.stop() } }
    LaunchedEffect(target) { SwipeRecorder.setTarget(target) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // every recorded swipe moves on to the next word and clears the field
    LaunchedEffect(recorded) {
        if (recorded > 0) {
            index++
            field = ""
        }
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.swipe_recorder_title),
        settings = emptyList(),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.swipe_recorder_intro), style = MaterialTheme.typography.bodyMedium)
            if (file == null) {
                Text(stringResource(R.string.swipe_recorder_no_storage), color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            if (target != null) {
                Text(target, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.swipe_recorder_progress, index + 1, words.size, recorded))
            } else {
                Text(stringResource(R.string.swipe_recorder_done), style = MaterialTheme.typography.titleMedium)
            }
            if (last.isNotEmpty())
                Text(stringResource(R.string.swipe_recorder_last, last.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                enabled = target != null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    platformImeOptions = PlatformImeOptions(privateImeOptions = SwipeRecorder.IME_OPTION),
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // skip a word that was swiped badly or by mistake; the recorded swipe stays in the file
                TextButton(onClick = { index++; field = "" }, enabled = target != null) {
                    Text(stringResource(R.string.swipe_recorder_skip))
                }
            }
            Text(stringResource(R.string.swipe_recorder_file, file.absolutePath), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Common Slovak (with diacritics) and English words, the languages Droneboard swipes by default. */
private val SWIPE_PROMPTS = listOf(
    "ďakujem", "dobrý", "deň", "ahoj", "áno", "nie", "môžem", "môžeš", "človek", "ľudia", "ľahko", "ešte",
    "teraz", "zajtra", "včera", "dnes", "práca", "robím", "píšem", "správa", "stretnutie", "večer", "ráno",
    "prosím", "určite", "myslím", "viem", "neviem", "hovoril", "povedala", "kde", "kedy", "prečo", "pretože",
    "ktorý", "ktorá", "všetko", "niečo", "nikto", "mesto", "domov", "auto", "cesta", "telefón", "klávesnica",
    "počítač", "peniaze", "rodina", "priateľ", "kamarát", "škola", "knihu", "hudba", "jedlo", "káva", "čaj",
    "pekne", "rýchlo", "pomaly", "šťastie", "život", "svet", "príde", "budem", "bude", "mám", "máš", "chcem",
    "musím", "napíšem", "zavolám", "uvidíme", "fajn", "super",
    "thanks", "hello", "meeting", "tomorrow", "today", "please", "keyboard", "message", "people", "world",
    "because", "something", "really", "think", "would", "could", "should", "about", "where", "there",
    "great", "good", "night", "morning", "phone", "time", "work", "home", "music", "coffee", "maybe", "okay",
)
