// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import helium314.keyboard.latin.InputStyle
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestedWords
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a gesture commits when the decoder finds nothing. The handler numbers gestures from 1:
 * every onStartBatchInput() begins the next one.
 */
@RunWith(RobolectricTestRunner::class)
class InputLogicHandlerTest {
    private val uiHandler = mock(LatinIME.UIHandler::class.java)
    private val inputLogic = mock(InputLogic::class.java)
    private val handler = InputLogicHandler(uiHandler, inputLogic)

    private fun words(style: InputStyle, vararg w: String) = SuggestedWords(
        ArrayList(w.map {
            SuggestedWordInfo(it, "", 100, SuggestedWordInfo.KIND_CORRECTION, Dictionary.DICTIONARY_USER_TYPED, 0, 0)
        }),
        null, null, true, false, false, style, SuggestedWords.NOT_A_SEQUENCE_NUMBER
    )

    @Test
    fun emptyResultDoesNotCommitWhatTheStripShowedBeforeTheSwipe() {
        // the strip showed next-word predictions, and nothing cleared them on the UI side
        inputLogic.mSuggestedWords = words(InputStyle.PREDICTION, "do", "be", "the")
        handler.onStartBatchInput()

        val emptyUpdate = words(InputStyle.UPDATE_BATCH)
        handler.showGestureSuggestionsWithPreviewVisuals(emptyUpdate, false, 1)
        verify(uiHandler).showGesturePreviewAndSetSuggestions(emptyUpdate, false)

        val emptyTail = words(InputStyle.TAIL_BATCH)
        handler.showGestureSuggestionsWithPreviewVisuals(emptyTail, true, 1)
        verify(uiHandler).showGesturePreviewAndSetSuggestions(emptyTail, true)
        verify(uiHandler).showTailBatchInputResult(emptyTail)
        assertFalse(handler.isInBatchInput)
    }

    @Test
    fun emptyTailCommitsTheLastPreviewOfTheSameGesture() {
        handler.onStartBatchInput()
        val first = words(InputStyle.UPDATE_BATCH, "yo")
        val preview = words(InputStyle.UPDATE_BATCH, "you")
        handler.showGestureSuggestionsWithPreviewVisuals(first, false, 1)
        handler.showGestureSuggestionsWithPreviewVisuals(preview, false, 1)
        // an empty update in between keeps showing the preview
        handler.showGestureSuggestionsWithPreviewVisuals(words(InputStyle.UPDATE_BATCH), false, 1)

        handler.showGestureSuggestionsWithPreviewVisuals(words(InputStyle.TAIL_BATCH), true, 1)
        verify(uiHandler).showGesturePreviewAndSetSuggestions(preview, true)
        verify(uiHandler).showTailBatchInputResult(preview)
    }

    @Test
    fun tailResultWinsOverThePreview() {
        handler.onStartBatchInput()
        handler.showGestureSuggestionsWithPreviewVisuals(words(InputStyle.UPDATE_BATCH, "you"), false, 1)
        val tail = words(InputStyle.TAIL_BATCH, "your")
        handler.showGestureSuggestionsWithPreviewVisuals(tail, true, 1)
        verify(uiHandler).showTailBatchInputResult(tail)
    }

    @Test
    fun emptyResultDoesNotCommitThePreviewOfTheGestureBefore() {
        handler.onStartBatchInput()
        handler.showGestureSuggestionsWithPreviewVisuals(words(InputStyle.UPDATE_BATCH, "hello"), false, 1)
        handler.showGestureSuggestionsWithPreviewVisuals(words(InputStyle.TAIL_BATCH, "hello"), true, 1)

        handler.onStartBatchInput()
        val emptyTail = words(InputStyle.TAIL_BATCH)
        handler.showGestureSuggestionsWithPreviewVisuals(emptyTail, true, 2)
        verify(uiHandler).showTailBatchInputResult(emptyTail)
    }

    @Test
    fun lateResultsOfTheGestureBeforeLeaveTheNewGestureAlone() {
        handler.onStartBatchInput()
        // the second swipe begins while the first one is still being decoded
        handler.onStartBatchInput()

        // a late preview of the first swipe is not shown, and is not kept as the second swipe's preview
        val latePreview = words(InputStyle.UPDATE_BATCH, "hello")
        handler.showGestureSuggestionsWithPreviewVisuals(latePreview, false, 1)
        verify(uiHandler, never()).showGesturePreviewAndSetSuggestions(latePreview, false)

        // its tail is still committed, and the second swipe goes on
        val lateTail = words(InputStyle.TAIL_BATCH, "hello")
        handler.showGestureSuggestionsWithPreviewVisuals(lateTail, true, 1)
        verify(uiHandler).showTailBatchInputResult(lateTail)
        assertTrue(handler.isInBatchInput)

        // the second swipe cannot be read: it commits nothing, not "hello"
        val emptyTail = words(InputStyle.TAIL_BATCH)
        handler.showGestureSuggestionsWithPreviewVisuals(emptyTail, true, 2)
        verify(uiHandler).showTailBatchInputResult(emptyTail)
        assertFalse(handler.isInBatchInput)
        verify(uiHandler).showGesturePreviewAndSetSuggestions(lateTail, true)
        verify(uiHandler).showGesturePreviewAndSetSuggestions(emptyTail, true)
        verifyNoMoreInteractions(uiHandler)
    }
}
