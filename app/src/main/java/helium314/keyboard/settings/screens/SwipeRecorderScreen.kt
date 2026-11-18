// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.swipe.SwipeRecorder
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getSecondaryLocales
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.settings.SearchSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Asks the user to swipe prompt words or whole sentences and records the gestures locally (see
 * [SwipeRecorder]), so that the swipe decoder can be tuned on real swipes with tools/swipe-replay.
 *
 * In sentence mode the user swipes a sentence word by word into the field, so the decoder sees the
 * real context. Records of a sentence are kept until it is finished (Enter / Next sentence), then
 * each gets the word that ended up in the field at its position ("committed").
 */
@Composable
fun SwipeRecorderScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val session = remember { RecorderSession(ctx) }
    val file = remember { SwipeRecorder.start(ctx) }
    val recorded by SwipeRecorder.recorded.collectAsState()
    val last by SwipeRecorder.lastResult.collectAsState()
    var earlierTotal by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    DisposableEffect(Unit) {
        SwipeRecorder.listener = session::onRecord
        onDispose {
            session.finishSentence(advance = false)
            SwipeRecorder.stop()
        }
    }
    LaunchedEffect(Unit) {
        earlierTotal = withContext(Dispatchers.IO) { SwipeRecorder.countAll(ctx) }
        runCatching { focus.requestFocus() }
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
            Segmented(
                listOf(stringResource(R.string.swipe_recorder_mode_words), stringResource(R.string.swipe_recorder_mode_sentences)),
                if (session.sentenceMode) 1 else 0
            ) { session.setMode(it == 1) }
            Segmented(
                listOf(stringResource(R.string.swipe_recorder_style_normal), stringResource(R.string.swipe_recorder_style_sloppy)),
                if (session.style == STYLE_SLOPPY) 1 else 0
            ) { session.style = if (it == 1) STYLE_SLOPPY else STYLE_NORMAL }
            Text(
                stringResource(if (session.style == STYLE_SLOPPY) R.string.swipe_recorder_style_hint_sloppy else R.string.swipe_recorder_style_hint_normal),
                style = MaterialTheme.typography.bodySmall
            )
            if (session.sentenceMode) {
                Text(stringResource(R.string.swipe_recorder_languages), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SENTENCE_FILES.keys.forEach { lang ->
                        FilterChip(
                            selected = lang in session.languages,
                            onClick = { session.toggleLanguage(lang) },
                            label = { Text(lang.uppercase()) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            if (session.sentenceMode) SentencePrompt(session) else WordPrompt(session)

            if (last.isNotEmpty())
                Text(stringResource(R.string.swipe_recorder_last, last.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            val canType = if (session.sentenceMode) session.sentence != null else session.word != null
            OutlinedTextField(
                value = session.field,
                onValueChange = { session.field = it },
                singleLine = true,
                enabled = canType,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (session.sentenceMode) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                    imeAction = if (session.sentenceMode) ImeAction.Next else ImeAction.Default,
                    platformImeOptions = PlatformImeOptions(privateImeOptions = SwipeRecorder.IME_OPTION),
                ),
                keyboardActions = KeyboardActions(onNext = { session.finishSentence(advance = true) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.sentenceMode) {
                    // skipped sentences keep their swipes, marked "skipped"
                    TextButton(onClick = { session.finishSentence(advance = true, skipped = true) }, enabled = canType) {
                        Text(stringResource(R.string.swipe_recorder_skip_sentence))
                    }
                    TextButton(onClick = { session.finishSentence(advance = true) }, enabled = canType) {
                        Text(stringResource(R.string.swipe_recorder_next_sentence))
                    }
                } else {
                    // skip a word that was swiped badly or by mistake; the recorded swipe stays in the file
                    TextButton(onClick = { session.skipWord() }, enabled = canType) {
                        Text(stringResource(R.string.swipe_recorder_skip))
                    }
                }
            }
            Text(
                stringResource(R.string.swipe_recorder_counter, recorded + session.pendingCount, earlierTotal + recorded + session.pendingCount),
                style = MaterialTheme.typography.titleSmall
            )
            HorizontalDivider()
            ExportNote(ctx, file.parentFile?.absolutePath ?: file.absolutePath, file.name)
        }
    }
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                label = { Text(label, maxLines = 1) }
            )
        }
    }
}

@Composable
private fun WordPrompt(session: RecorderSession) {
    val target = session.word
    if (target != null) {
        Text(target, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.swipe_recorder_progress, session.wordIndex + 1, session.words.size))
    } else {
        Text(stringResource(R.string.swipe_recorder_done), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SentencePrompt(session: RecorderSession) {
    val sentence = session.sentence
    if (sentence == null) {
        Text(stringResource(R.string.swipe_recorder_done), style = MaterialTheme.typography.titleMedium)
        return
    }
    Text(stringResource(R.string.swipe_recorder_sentence_hint), style = MaterialTheme.typography.bodySmall)
    val current = typedWords(session.field).size
    val parts = sentence.text.split(' ')
    // the word to swipe next is underlined, the ones already swiped are dimmed
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            parts.forEachIndexed { i, part ->
                if (i > 0) append(' ')
                when {
                    i < current -> withStyle(SpanStyle(color = dim)) { append(part) }
                    i == current -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { append(part) }
                    else -> append(part)
                }
            }
        },
        fontSize = 26.sp, lineHeight = 34.sp, textAlign = TextAlign.Center
    )
    Text(
        stringResource(R.string.swipe_recorder_sentence_progress, session.sentenceIndex + 1, session.sentences.size,
            (current + 1).coerceAtMost(sentence.words.size), sentence.words.size) + " · " + sentence.lang.uppercase(),
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun ExportNote(ctx: Context, dir: String, fileName: String) {
    val command = "adb pull $dir"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.swipe_recorder_export_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.swipe_recorder_export_text), style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.swipe_recorder_file, fileName), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("adb", command))
            Toast.makeText(ctx, command, Toast.LENGTH_SHORT).show()
        }) { Text(stringResource(R.string.swipe_recorder_copy)) }
    }
}

private const val STYLE_NORMAL = "normal"
private const val STYLE_SLOPPY = "sloppy"

private val SENTENCE_FILES = linkedMapOf(
    "sk" to R.raw.recorder_sentences_sk,
    "en" to R.raw.recorder_sentences_en,
    "cs" to R.raw.recorder_sentences_cs,
)

private class Sentence(val lang: String, val text: String) {
    /** the words to swipe, without punctuation (apostrophes inside words stay) */
    val words: List<String> = text.split(' ').map { w -> w.trim { !it.isLetter() } }.filter { it.isNotEmpty() }
}

/** words in the field that are finished (followed by a space), without punctuation */
private fun typedWords(text: String): List<String> =
    Regex("(\\S+)\\s").findAll(text).map { it.groupValues[1].trim { c -> !c.isLetter() } }.toList()

private fun allWords(text: String): List<String> =
    text.split(Regex("\\s+")).map { w -> w.trim { !it.isLetter() } }.filter { it.isNotEmpty() }

private class RecorderSession(private val context: Context) {
    private val sessionId = UUID.randomUUID().toString().substring(0, 8)

    var sentenceMode by mutableStateOf(true)
        private set
    var style by mutableStateOf(STYLE_NORMAL)
    var field by mutableStateOf("")
    var pendingCount by mutableIntStateOf(0)
        private set

    // word mode
    val words = SWIPE_PROMPTS.shuffled()
    var wordIndex by mutableIntStateOf(0)
        private set
    val word get() = words.getOrNull(wordIndex)

    // sentence mode
    var languages by mutableStateOf(defaultLanguages())
        private set
    var sentences by mutableStateOf(loadSentences(languages))
        private set
    var sentenceIndex by mutableIntStateOf(0)
        private set
    val sentence get() = sentences.getOrNull(sentenceIndex)
    private val pending = ArrayList<JSONObject>()

    private var promptShownAt = System.currentTimeMillis()
    private var previousGestureEnd = 0L

    private fun defaultLanguages(): Set<String> {
        val enabled = runCatching {
            SubtypeSettings.getEnabledSubtypes(true).flatMap { listOf(it.locale()) + getSecondaryLocales(it.extraValue) }
                .map { it.language }.toSet()
        }.getOrDefault(emptySet())
        return SENTENCE_FILES.keys.filter { it in enabled }.toSet().ifEmpty { setOf("en") }
    }

    private fun loadSentences(langs: Set<String>): List<Sentence> = langs.flatMap { lang ->
        val res = SENTENCE_FILES[lang] ?: return@flatMap emptyList()
        runCatching {
            context.resources.openRawResource(res).bufferedReader().readLines()
        }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.map { Sentence(lang, it) }
    }.shuffled()

    fun setMode(sentences: Boolean) {
        if (sentences == sentenceMode) return
        finishSentence(advance = false)
        sentenceMode = sentences
        field = ""
        promptShownAt = System.currentTimeMillis()
    }

    fun toggleLanguage(lang: String) {
        val new = if (lang in languages) languages - lang else languages + lang
        if (new.isEmpty()) return
        finishSentence(advance = false)
        languages = new
        sentences = loadSentences(new)
        sentenceIndex = 0
        field = ""
        promptShownAt = System.currentTimeMillis()
    }

    fun skipWord() {
        wordIndex++
        field = ""
        promptShownAt = System.currentTimeMillis()
    }

    /** a decoded swipe from [SwipeRecorder], on the main thread */
    fun onRecord(json: JSONObject) {
        val touch = json.optJSONObject("touch")
        val down = touch?.optLong("down_time") ?: json.optLong("time")
        val end = if (touch != null && touch.has("duration_ms")) down + touch.optLong("duration_ms") else json.optLong("time")
        json.put("record_version", SwipeRecorder.RECORD_VERSION)
        json.put("session", sessionId)
        json.put("mode", if (sentenceMode) "sentence" else "word")
        json.put("style", style)
        json.put("prompt_ms", down - promptShownAt)
        if (previousGestureEnd > 0) json.put("since_prev_gesture_ms", down - previousGestureEnd)
        previousGestureEnd = end
        val pred = json.optJSONArray("pred")?.optString(0)

        if (!sentenceMode) {
            val target = word ?: return
            json.put("target", target)
            json.put("committed", pred ?: JSONObject.NULL)
            json.put("top1_hit", pred.equals(target, ignoreCase = true))
            SwipeRecorder.write(json)
            wordIndex++
            field = ""
            promptShownAt = System.currentTimeMillis()
            return
        }
        val s = sentence ?: return
        // the previous word is committed (followed by a space) before the next swipe is decoded
        val typed = typedWords(field)
        val index = typed.size
        json.put("sentence", s.text)
        json.put("sentence_lang", s.lang)
        json.put("word_index", index)
        json.put("target", s.words.getOrNull(index) ?: JSONObject.NULL)
        json.put("prev_words", JSONArray(s.words.take(index)))
        json.put("prev_typed", JSONArray(typed))
        pending.add(json)
        pendingCount = pending.size
    }

    /** Writes the swipes of the current sentence, with the words that ended up in the field. */
    fun finishSentence(advance: Boolean, skipped: Boolean = false) {
        if (pending.isNotEmpty()) {
            val final = allWords(field)
            pending.forEachIndexed { i, json ->
                val index = json.optInt("word_index")
                val committed = final.getOrNull(index)
                val target = json.optString("target")
                json.put("committed", committed ?: JSONObject.NULL)
                json.put("final_text", field)
                // the user deleted the word and swiped it again
                json.put("redone", pending.subList(i + 1, pending.size).any { it.optInt("word_index") == index })
                json.put("committed_hit", committed != null && committed.equals(target, ignoreCase = true))
                json.put("top1_hit", json.optJSONArray("pred")?.optString(0).equals(target, ignoreCase = true))
                json.put("skipped", skipped)
                json.put("finished", advance)
                SwipeRecorder.write(json)
            }
            pending.clear()
            pendingCount = 0
        }
        if (advance) {
            sentenceIndex++
            field = ""
            promptShownAt = System.currentTimeMillis()
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
