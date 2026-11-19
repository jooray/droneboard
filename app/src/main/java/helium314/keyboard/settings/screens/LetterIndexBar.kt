// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R

/** how wide the index is; the list ends before it, so the rows keep their whole tap target */
val LetterIndexWidth = 28.dp

/**
 * Droneboard: the narrow letter index at the end edge of a long list ([LetterIndex] computes [sections]).
 * A tap or a finger dragged along it reports the section under the finger through [onSection]; every section has
 * the same share of the height, also when only some of the letters fit and are drawn.
 * [onActive] gets the section and the finger's distance from the top while it is touched, and null after.
 */
@Composable
fun LetterIndexBar(
    sections: List<LetterIndex.Section>,
    onSection: (Int) -> Unit,
    onActive: (section: Int?, y: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val jump by rememberUpdatedState(onSection)
    val active by rememberUpdatedState(onActive)
    val otherDescription = stringResource(R.string.personal_dictionary_index_other)
    BoxWithConstraints(modifier.width(LetterIndexWidth)) {
        val lineHeight = with(LocalDensity.current) { FONT_SIZE.sp.toDp() } + 5.dp
        val fits = (maxHeight / lineHeight).toInt()
        val shown = remember(sections, fits) { LetterIndex.thinOut(sections.size, fits) }
        if (shown.isEmpty()) return@BoxWithConstraints
        Column(
            modifier = Modifier.fillMaxSize().pointerInput(sections) {
                awaitEachGesture {
                    var last = -1
                    fun at(y: Float) {
                        val section = LetterIndex.sectionAt(y, size.height.toFloat(), sections.size)
                        if (section < 0) return
                        active(section, y)
                        if (section != last) {
                            last = section
                            jump(section)
                        }
                    }
                    try {
                        val down = awaitFirstDown()
                        down.consume()
                        at(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            at(change.position.y)
                            change.consume()
                        }
                    } finally {
                        active(null, 0f)
                    }
                }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            shown.forEach { i ->
                val label = sections[i].label
                val description = if (label == LetterIndex.OTHER) otherDescription
                    else stringResource(R.string.personal_dictionary_index_letter, label)
                Box(
                    Modifier.weight(1f).fillMaxWidth().clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                        onClick { jump(i); true }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = FONT_SIZE.sp, lineHeight = FONT_SIZE.sp, maxLines = 1,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private const val FONT_SIZE = 11
