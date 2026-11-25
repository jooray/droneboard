// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.widget.TextView
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.accessibility.AccessibilityUtils
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.InputStyle
import helium314.keyboard.latin.R
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SuggestionStripViewTest {
    private lateinit var themedContext: Context

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        Settings.init(app)
        AccessibilityUtils.init(app)
        AudioAndHapticFeedbackManager.init(app)
        KeyboardIconsSet.instance.loadIcons(app)
        themedContext = ContextThemeWrapper(app, R.style.KeyboardTheme_Rounded_Base)
    }

    private fun initSettingsForToolbarMode(mode: ToolbarMode) {
        themedContext.prefs().edit(commit = true) {
            putString(Settings.PREF_TOOLBAR_MODE, mode.name)
        }
        val dm = themedContext.resources.displayMetrics
        Settings.getInstance().loadSettings(
            themedContext,
            themedContext.resources.configuration.locales[0],
            helium314.keyboard.latin.InputAttributes(null, false, themedContext.packageName)
        )
        assertNotNull(dm)
    }

    @Test
    fun constructsCleanlyInEveryToolbarMode() {
        for (mode in ToolbarMode.entries) {
            initSettingsForToolbarMode(mode)
            val strip = SuggestionStripView(themedContext, null)
            assertNotNull(strip, "SuggestionStripView failed to construct in mode $mode")
        }
    }

    @Test
    fun asyncSuggestionUpdateBetweenDownAndUpCommitsPressedWord() {
        initSettingsForToolbarMode(ToolbarMode.EXPANDABLE)
        val strip = SuggestionStripView(themedContext, null)
        val picked = mutableListOf<String>()
        val keyboardView = MainKeyboardView(themedContext, null).apply {
            id = R.id.keyboard_view
        }
        strip.setListener(object : SuggestionStripView.Listener {
            override fun pickSuggestionManually(word: SuggestedWordInfo?) {
                word?.word?.let { picked.add(it) }
            }
            override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {}
            override fun removeSuggestion(word: String?) {}
            override fun removeExternalSuggestions() {}
            override fun onSwipeDownOnToolbar() {}
        }, keyboardView)

        fun makeWords(vararg words: String): SuggestedWords {
            val list = ArrayList(words.map {
                SuggestedWordInfo(it, "", 100, SuggestedWordInfo.KIND_CORRECTION, Dictionary.DICTIONARY_USER_TYPED, 0, 0)
            })
            return SuggestedWords(
                list, null, list.first(), false, false, false,
                InputStyle.TYPING, 1
            )
        }

        strip.setSuggestions(makeWords("hello", "help", "hero"), false)
        val stripGroup = strip.findViewById<android.view.ViewGroup>(R.id.suggestions_strip)
        val firstWordView = (0 until stripGroup.childCount)
            .map { stripGroup.getChildAt(it) }
            .filterIsInstance<TextView>()
            .first { it.tag is Int }
        val expectedWord = firstWordView.text.toString()

        // User presses down on the currently visible word.
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        firstWordView.dispatchTouchEvent(down)
        down.recycle()

        // Async update arrives before finger-up and replaces the strip contents in place.
        strip.setSuggestions(makeWords("world", "work", "word"), false)

        // Finger lifts / click fires: must commit the word the user actually pressed!
        firstWordView.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(expectedWord), picked)
    }

    @Test
    fun slideOffLiftClearsSnapshotSoSubsequentClickCommitsCurrentWord() {
        initSettingsForToolbarMode(ToolbarMode.EXPANDABLE)
        val strip = SuggestionStripView(themedContext, null)
        val picked = mutableListOf<String>()
        val keyboardView = MainKeyboardView(themedContext, null).apply {
            id = R.id.keyboard_view
        }
        strip.setListener(object : SuggestionStripView.Listener {
            override fun pickSuggestionManually(word: SuggestedWordInfo?) {
                word?.word?.let { picked.add(it) }
            }
            override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {}
            override fun removeSuggestion(word: String?) {}
            override fun removeExternalSuggestions() {}
            override fun onSwipeDownOnToolbar() {}
        }, keyboardView)

        fun makeWords(vararg words: String): SuggestedWords {
            val list = ArrayList(words.map {
                SuggestedWordInfo(it, "", 100, SuggestedWordInfo.KIND_CORRECTION, Dictionary.DICTIONARY_USER_TYPED, 0, 0)
            })
            return SuggestedWords(
                list, null, list.first(), false, false, false,
                InputStyle.TYPING, 1
            )
        }

        strip.setSuggestions(makeWords("hello", "help", "hero"), false)
        val stripGroup = strip.findViewById<android.view.ViewGroup>(R.id.suggestions_strip)
        val firstWordView = (0 until stripGroup.childCount)
            .map { stripGroup.getChildAt(it) }
            .filterIsInstance<TextView>()
            .first { it.tag is Int }

        // Press down on the first word, then slide off (view unpressed) and lift.
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        firstWordView.dispatchTouchEvent(down)
        down.recycle()
        firstWordView.isPressed = false
        val up = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_UP, 500f, 500f, 0)
        firstWordView.dispatchTouchEvent(up)
        up.recycle()

        // Strip updates to new suggestions; a later accessibility click must commit the current word.
        strip.setSuggestions(makeWords("world", "work", "word"), false)
        val currentWord = firstWordView.text.toString()
        firstWordView.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(currentWord), picked)
    }
}
