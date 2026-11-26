/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.addPinnedKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getPinnedToolbarKeys
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.onLongClickToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.removePinnedKey
import helium314.keyboard.latin.utils.setToolbarButtonActivatedState
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

@SuppressLint("InflateParams")
class SuggestionStripView(context: Context, attrs: AttributeSet?, defStyle: Int) :
    RelativeLayout(context, attrs, defStyle), View.OnClickListener, OnLongClickListener, OnSharedPreferenceChangeListener {

    /** Construct a [SuggestionStripView] for showing suggestions to be picked by the user. */
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.suggestionStripViewStyle)

    interface Listener {
        fun pickSuggestionManually(word: SuggestedWordInfo?)
        fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean)
        fun removeSuggestion(word: String?)
        fun removeExternalSuggestions()
        fun onSwipeDownOnToolbar()
    }

    private val moreSuggestionsContainer: View
    private val wordViews = ArrayList<TextView>()
    private val debugInfoViews = ArrayList<TextView>()
    private val dividerViews = ArrayList<View>()
    private val removeSuggestionActionId = View.generateViewId()
    private var confirmingDeleteWord: String? = null
    // Snapshot of the suggestion under the finger at ACTION_DOWN so an in-flight async update
    // reusing the same TextView cannot silently commit a different word on ACTION_UP.
    private var pressedWordView: View? = null
    private var pressedWordInfo: SuggestedWordInfo? = null

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.suggestions_strip, this)
        moreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null)

        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        repeat(SuggestedWords.MAX_SUGGESTIONS) {
            val word = TextView(context, null, R.attr.suggestionWordStyle)
            word.contentDescription = resources.getString(R.string.spoken_empty_suggestion)
            word.setOnClickListener(this)
            word.setOnLongClickListener(this)
            @SuppressLint("ClickableViewAccessibility")
            word.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val idx = v.tag as? Int
                        pressedWordView = v
                        pressedWordInfo = if (idx != null && idx in 0 until suggestedWords.size) suggestedWords.getInfo(idx) else null
                    }
                    MotionEvent.ACTION_UP -> {
                        if (pressedWordView === v && (!v.isPressed || !v.isEnabled)) {
                            pressedWordView = null
                            pressedWordInfo = null
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (pressedWordView === v) {
                            pressedWordView = null
                            pressedWordInfo = null
                        }
                    }
                }
                false
            }
            colors.setBackground(word, ColorType.STRIP_BACKGROUND)
            ViewCompat.setAccessibilityDelegate(word, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (getRemovableWord(host) != null) {
                        info.addAction(
                            AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                                removeSuggestionActionId,
                                resources.getString(R.string.remove_suggestion_action)
                            )
                        )
                    }
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == removeSuggestionActionId) {
                        val removable = getRemovableWord(host)
                        if (removable != null) {
                            removeSuggestionByWord(removable)
                            return true
                        }
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            })
            wordViews.add(word)
            val divider = inflater.inflate(R.layout.suggestion_divider, null)
            dividerViews.add(divider)
            val info = TextView(context, null, R.attr.suggestionWordStyle)
            info.setTextColor(colors.get(ColorType.KEY_TEXT))
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DEBUG_INFO_TEXT_SIZE_IN_DIP)
            debugInfoViews.add(info)
        }

        DEBUG_SUGGESTIONS = context.prefs().getBoolean(DebugSettings.PREF_SHOW_SUGGESTION_INFOS, Defaults.PREF_SHOW_SUGGESTION_INFOS)
    }

    // toolbar views, drawables and setup
    private val stripWrapper: LinearLayout = findViewById(R.id.suggestions_strip_wrapper)
    private val toolbar: ViewGroup = findViewById(R.id.toolbar)
    private val toolbarContainer: View = findViewById(R.id.toolbar_container)
    private val pinnedKeys: ViewGroup = findViewById(R.id.pinned_keys)
    private val suggestionsStrip: ViewGroup = findViewById(R.id.suggestions_strip)
    private val toolbarExpandKey = findViewById<ImageButton>(R.id.suggestions_strip_toolbar_key)
    private val moreSuggestionsButton: ImageButton
    private val incognitoIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.INCOGNITO.name, context)
    private val toolbarArrowIcon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_TOOLBAR_KEY, context)
    private val defaultToolbarBackground: Drawable = toolbarExpandKey.background
    private val enabledToolKeyBackground = GradientDrawable()
    private var direction = 1 // 1 if LTR, -1 if RTL

    private val toolbarKeyLayoutParams = LinearLayout.LayoutParams(
        resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width),
        LinearLayout.LayoutParams.MATCH_PARENT
    )

    private lateinit var listener: Listener
    private var suggestedWords = SuggestedWords.emptyInstance
    private var startIndexOfMoreSuggestions = 0
    private var isExternalSuggestionVisible = false // Required to disable the more suggestions if other suggestions are visible
    private val layoutHelper = SuggestionStripLayoutHelper(context, attrs, defStyle, wordViews, dividerViews, debugInfoViews)
    private val moreSuggestionsView = moreSuggestionsContainer.findViewById<MoreSuggestionsView>(R.id.more_suggestions_view).apply {
        val slidingListener = object : SimpleOnGestureListener() {
            override fun onScroll(down: MotionEvent?, me: MotionEvent, deltaX: Float, deltaY: Float): Boolean {
                if (down == null) return false
                val dy = me.y - down.y
                val dx = me.x - down.x

                if (Settings.getValues().mToolbarSwipeDownToHide && dy > 50.dpToPx(resources) && abs(dy) > abs(dx)) {
                    listener.onSwipeDownOnToolbar()
                    return true
                }

                return if (!isExternalSuggestionVisible && toolbarContainer.visibility != VISIBLE && deltaY > 0 && dy < (-10).dpToPx(resources)) showMoreSuggestions()
                else false
            }
        }
        gestureDetector = GestureDetector(context, slidingListener)
    }

    init {
        val colors = Settings.getValues().mColors

        // expand key
        // weird way of setting size (default is config_suggestions_strip_edge_key_width)
        // but better not change it or people will complain
        val toolbarHeight = min(toolbarExpandKey.layoutParams.height, resources.getDimension(R.dimen.config_suggestions_strip_height).toInt())
        toolbarExpandKey.layoutParams.height = toolbarHeight
        toolbarExpandKey.layoutParams.width = toolbarHeight // we want it square
        colors.setBackground(toolbarExpandKey, ColorType.STRIP_BACKGROUND) // necessary because background is re-used for defaultToolbarBackground
        colors.setColor(toolbarExpandKey, ColorType.TOOL_BAR_EXPAND_KEY)
        colors.setColor(toolbarExpandKey.background, ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND)

        // background indicator for pinned keys
        val color = colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND) or -0x1000000 // ignore alpha (in Java this is more readable 0xFF000000)
        enabledToolKeyBackground.colors = intArrayOf(color, Color.TRANSPARENT)
        enabledToolKeyBackground.gradientType = GradientDrawable.RADIAL_GRADIENT
        enabledToolKeyBackground.gradientRadius = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) / 2.1f

        // Discoverable More Suggestions overflow chevron button placed at the end of the strip (before pinned keys)
        moreSuggestionsButton = ImageButton(context, null, R.attr.suggestionWordStyle).apply {
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = resources.getString(R.string.more_suggestions_button_description)
            setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_TOOLBAR_KEY, context))
            rotation = 90f
            isVisible = false
            colors.setColor(this, ColorType.TOOL_BAR_KEY)
            colors.setBackground(this, ColorType.STRIP_BACKGROUND)
            setOnClickListener {
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
                if (isShowingMoreSuggestionPanel) {
                    dismissMoreSuggestionsPanel()
                } else {
                    showMoreSuggestions()
                }
                updateMoreSuggestionsButtonState()
            }
        }
        val stripIdx = stripWrapper.indexOfChild(suggestionsStrip)
        val moreBtnWidth = 36.dpToPx(resources)
        val moreBtnLp = LinearLayout.LayoutParams(moreBtnWidth, LinearLayout.LayoutParams.MATCH_PARENT)
        if (stripIdx >= 0) {
            stripWrapper.addView(moreSuggestionsButton, stripIdx + 1, moreBtnLp)
        } else {
            stripWrapper.addView(moreSuggestionsButton, moreBtnLp)
        }

        val mToolbarMode = if (isGone) ToolbarMode.HIDDEN else Settings.getValues().mToolbarMode
        if (mToolbarMode == ToolbarMode.TOOLBAR_KEYS) {
            setToolbarVisibility(true)
        }

        // toolbar keys setup (no need to hide them any more when locked, because then suggestion strip is gone anyway
        for (key in getEnabledToolbarKeys(context.prefs())) {
            val button = createToolbarKey(context, key)
            button.layoutParams = toolbarKeyLayoutParams
            setupKey(button, colors)
            toolbar.addView(button)
        }
        for (pinnedKey in getPinnedToolbarKeys(context.prefs())) {
            val button = createToolbarKey(context, pinnedKey)
            button.layoutParams = toolbarKeyLayoutParams
            setupKey(button, colors)
            pinnedKeys.addView(button)
            val pinnedKeyInToolbar = toolbar.findViewWithTag<View>(pinnedKey)
            if (pinnedKeyInToolbar != null && Settings.getValues().mQuickPinToolbarKeys)
                pinnedKeyInToolbar.background = enabledToolKeyBackground
        }
        // set min width of the toolbar so the weight of the toolbar keys actually does something, and on a narrow
        // (compact or floating) keyboard make the keys narrower so all of them fit instead of the last one being cut
        toolbarContainer.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width <= 0 || width == fittedToolbarWidth) return@addOnLayoutChangeListener
            fittedToolbarWidth = width
            post { fitToolbarKeys(width) }
        }

        updateKeys()
    }

    private var fittedToolbarWidth = -1

    private fun fitToolbarKeys(width: Int) {
        toolbar.minimumWidth = width
        val count = toolbar.childCount
        if (count == 0) return
        val normal = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width)
        val narrowest = (resources.displayMetrics.density * 34).toInt()
        val keyWidth = if (count * normal <= width) normal else (width / count).coerceAtLeast(narrowest)
        for (i in 0 until count) {
            val child = toolbar.getChildAt(i)
            if (child.layoutParams.width != keyWidth)
                child.layoutParams = LinearLayout.LayoutParams(keyWidth, LinearLayout.LayoutParams.MATCH_PARENT)
        }
    }

    // public stuff

    val isShowingMoreSuggestionPanel get() = moreSuggestionsView.isShowingInParent

    /** A connection back to the input method. */
    fun setListener(newListener: Listener, inputView: View) {
        listener = newListener
        moreSuggestionsView.listener = newListener
        moreSuggestionsView.mainKeyboardView = inputView.findViewById(R.id.keyboard_view)
    }

    fun setRtl(isRtlLanguage: Boolean) {
        val newLayoutDirection: Int
        if (!Settings.getValues().mVarToolbarDirection)
            newLayoutDirection = LAYOUT_DIRECTION_LOCALE
        else {
            newLayoutDirection = if (isRtlLanguage) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
            direction = if (isRtlLanguage) -1 else 1
            toolbarExpandKey.scaleX = (if (toolbarContainer.visibility != VISIBLE) 1f else -1f) * direction
        }
        layoutDirection = newLayoutDirection
        suggestionsStrip.layoutDirection = newLayoutDirection
    }

    fun setToolbarVisibility(toolbarVisible: Boolean) {
        if (toolbarVisible && confirmingDeleteWord != null) {
            confirmingDeleteWord = null
            suggestionsStrip.removeAllViews()
            layoutHelper.resetLayoutState()
        }
        // Droneboard: pinned keys (the mic) stay at the right end, also while the toolbar is expanded
        pinnedKeys.isVisible = true
        suggestionsStrip.isVisible = !toolbarVisible
        toolbarContainer.isVisible = toolbarVisible

        if (DEBUG_SUGGESTIONS) {
            for (view in debugInfoViews) {
                view.visibility = suggestionsStrip.visibility
            }
        }

        toolbarExpandKey.scaleX = (if (toolbarVisible) -1f else 1f) * direction
        updateToolbarExpandKeyDescription()
        updateMoreSuggestionsButtonState()
    }

    fun setSuggestions(suggestions: SuggestedWords, isRtlLanguage: Boolean) {
        if (isExternalSuggestionVisible || confirmingDeleteWord != null) {
            clear()
        } else {
            if (DEBUG_SUGGESTIONS) removeAllDebugInfoViews()
            if (!toolbarContainer.isVisible) suggestionsStrip.isVisible = true
            if (isShowingMoreSuggestionPanel) dismissMoreSuggestionsPanel()
        }
        setRtl(isRtlLanguage)
        suggestedWords = suggestions
        startIndexOfMoreSuggestions = layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
            context, suggestedWords, suggestionsStrip, this
        )
        isExternalSuggestionVisible = false
        updateKeys()
        updateMoreSuggestionsButtonState()
    }

    fun setExternalSuggestionView(view: View?, addCloseButton: Boolean) {
        clear()
        isExternalSuggestionVisible = true

        if (addCloseButton) {
            val wrapper = LinearLayout(context)
            suggestionsStrip.doOnNextLayout {
                wrapper.layoutParams = LinearLayout.LayoutParams(suggestionsStrip.width - 30.dpToPx(resources), LayoutParams.MATCH_PARENT)
            }
            wrapper.addView(view)
            suggestionsStrip.addView(wrapper)

            val closeButton = createToolbarKey(context, ToolbarKey.CLOSE_HISTORY)
            closeButton.layoutParams = toolbarKeyLayoutParams
            setupKey(closeButton, Settings.getValues().mColors)
            closeButton.setOnClickListener {
                listener.removeExternalSuggestions()
            }
            suggestionsStrip.addView(closeButton)
        } else if (view != null) {
            val lp = view.layoutParams ?: LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            suggestionsStrip.addView(view, lp)
        }

        if (Settings.getValues().mAutoHideToolbar) setToolbarVisibility(false)
        updateMoreSuggestionsButtonState()
    }

    private var swipePreparingView: View? = null

    /**
     * Droneboard: a short message while a swipe waits for its dictionary (after an install or update). Shown like
     * an external suggestion view, so the next suggestions replace it; [show] false removes it if still shown.
     */
    fun showSwipeDictionaryPreparing(show: Boolean) {
        val prev = swipePreparingView
        val wasAttached = prev?.parent != null
        prev?.let { (it.parent as? ViewGroup)?.removeView(it) }
        swipePreparingView = null
        if (!show) {
            if (wasAttached) {
                isExternalSuggestionVisible = false
                updateMoreSuggestionsButtonState()
            }
            return
        }
        val view = TextView(context).apply {
            text = resources.getString(R.string.swipe_dictionary_preparing)
            setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        setExternalSuggestionView(view, false)
        swipePreparingView = view
    }

    fun setMoreSuggestionsHeight(remainingHeight: Int) {
        layoutHelper.setMoreSuggestionsHeight(remainingHeight)
    }

    fun dismissMoreSuggestionsPanel() {
        moreSuggestionsView.dismissPopupKeysPanel()
        updateMoreSuggestionsButtonState()
    }

    // overrides: necessarily public, but not used from outside

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(pinnedKeys, key)
        setToolbarButtonsActivatedStateOnPrefChange(toolbar, key)
        if (key == Settings.PREF_ALWAYS_INCOGNITO_MODE)
            GlobalScope.launch { delay(10); withContext(Dispatchers.Main) { updateKeys() } }
    }

    override fun onVisibilityChanged(view: View, visibility: Int) {
        super.onVisibilityChanged(view, visibility)
        // workaround for a bug with inline suggestions views that just keep showing up otherwise, https://github.com/HeliBorg/HeliBoard/pull/386
        if (view === this)
            suggestionsStrip.visibility = visibility
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissMoreSuggestionsPanel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Called by the framework when the size is known. Show the important notice if applicable.
        // This may be overridden by showing suggestions later, if applicable.
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        // Don't populate accessibility event with suggested words and voice key.
        return true
    }

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        // Detecting sliding up finger to show MoreSuggestionsView.
        return moreSuggestionsView.shouldInterceptTouchEvent(motionEvent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(motionEvent: MotionEvent): Boolean {
        moreSuggestionsView.touchEvent(motionEvent)
        return true
    }

    override fun onClick(view: View) {
        val tag = view.tag
        if (tag is ToolbarKey) {
            onClickToolbarKey(view) { listener.onCodeInput(it, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false) }
            return
        }
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
        if (view === toolbarExpandKey) {
            setToolbarVisibility(toolbarContainer.visibility != VISIBLE)
        }

        // tag for word views is set in SuggestionStripLayoutHelper (setupWordViewsTextAndColor, layoutPunctuationSuggestions)
        if (tag is Int) {
            val captured = if (pressedWordView === view) pressedWordInfo else null
            pressedWordView = null
            pressedWordInfo = null
            val wordInfo = captured ?: run {
                if (tag >= suggestedWords.size) return
                suggestedWords.getInfo(tag)
            }
            listener.pickSuggestionManually(wordInfo)
        }
    }

    override fun onLongClick(view: View): Boolean {
        pressedWordView = null
        pressedWordInfo = null
        if (view.tag is ToolbarKey) {
            onLongClickToolbarKey(view)
            return true
        }
        AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
        return if (view is TextView && wordViews.contains(view)) {
            onLongClickSuggestion(view)
        } else {
            showMoreSuggestions()
        }
    }

    // actually private stuff

    private fun onLongClickToolbarKey(view: View) {
        val tag = view.tag as? ToolbarKey ?: return
        if (!Settings.getValues().mQuickPinToolbarKeys || view.parent === pinnedKeys) {
            onLongClickToolbarKey(view) { code, isRepeat -> listener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, isRepeat) }
        } else if (view.parent === toolbar) {
            AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
            val pinnedKeyView = pinnedKeys.findViewWithTag<View>(tag)
            if (pinnedKeyView == null) {
                addKeyToPinnedKeys(tag)
                toolbar.findViewWithTag<View>(tag).background = enabledToolKeyBackground
                addPinnedKey(context.prefs(), tag)
            } else {
                removePinnedKey(context.prefs(), tag)
                toolbar.findViewWithTag<View>(tag).background = defaultToolbarBackground.constantState?.newDrawable(resources)
                pinnedKeys.removeView(pinnedKeyView)
            }
        }
    }

    private fun getRemovableWord(view: View): String? {
        val index = view.tag as? Int ?: return null
        if (index < 0 || index >= suggestedWords.size) return null
        val info = suggestedWords.getInfo(index)
        val type = info.sourceDict
        if (type == Dictionary.DICTIONARY_USER_TYPED || type == Dictionary.DICTIONARY_HARDCODED) {
            return null
        }
        return info.word.takeIf { it.isNotEmpty() }
    }

    private fun onLongClickSuggestion(wordView: TextView): Boolean {
        val removableWord = getRemovableWord(wordView)
        if (removableWord != null) {
            // Decouple long-press delete from MoreSuggestionsView: show a clean full-height
            // inline confirmation pill so tapping the trash area can never insert the word.
            showDeleteConfirmation(removableWord)
            return true
        }
        if (DebugFlags.DEBUG_ENABLED) {
            showSourceDict(wordView)
            return true
        }
        return false
    }

    private fun showDeleteConfirmation(word: String) {
        dismissMoreSuggestionsPanel()
        confirmingDeleteWord = word
        suggestionsStrip.removeAllViews()
        layoutHelper.resetLayoutState()
        updateMoreSuggestionsButtonState()

        val colors = Settings.getValues().mColors
        val density = resources.displayMetrics.density
        val textColor = colors.get(ColorType.KEY_TEXT)
        val pillFill = colors.get(ColorType.KEY_BACKGROUND)
        val strokeColor = ColorUtils.setAlphaComponent(textColor, 42)
        val rippleColor = ColorUtils.setAlphaComponent(textColor, 28)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            // Tapping outside the pill cancels delete confirmation cleanly.
            setOnClickListener { dismissDeleteConfirmation() }
        }

        val pillShape = GradientDrawable().apply {
            cornerRadius = 18f * density
            setColor(pillFill)
            setStroke((1f * density).toInt().coerceAtLeast(1), strokeColor)
        }
        val binIcon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_BIN, context)?.apply {
            colors.setColor(this, ColorType.REMOVE_SUGGESTION_ICON)
            val iconSize = (18f * density).toInt()
            setBounds(0, 0, iconSize, iconSize)
        }

        val confirmPill = TextView(context).apply {
            text = resources.getString(R.string.remove_suggestion_confirm, word)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            compoundDrawablePadding = (6f * density).toInt()
            setCompoundDrawablesRelative(binIcon, null, null, null)
            val hPad = (14f * density).toInt()
            setPaddingRelative(hPad, 0, hPad, 0)
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), pillShape, null)
            isHapticFeedbackEnabled = false
            isSoundEffectsEnabled = false
            setOnClickListener {
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
                removeSuggestionByWord(word)
            }
        }
        val pillLp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
            val vMargin = (3f * density).toInt()
            topMargin = vMargin
            bottomMargin = vMargin
            marginEnd = (6f * density).toInt()
        }
        row.addView(confirmPill, pillLp)

        val cancelBtn = ImageButton(context, null, R.attr.suggestionWordStyle).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.CLOSE_HISTORY.name, context))
            colors.setColor(this, ColorType.TOOL_BAR_KEY)
            colors.setBackground(this, ColorType.STRIP_BACKGROUND)
            contentDescription = resources.getString(R.string.cancel_remove_suggestion)
            val pad = (10f * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
                dismissDeleteConfirmation()
            }
        }
        val cancelSize = (40f * density).toInt()
        row.addView(cancelBtn, LinearLayout.LayoutParams(cancelSize, LayoutParams.MATCH_PARENT))

        suggestionsStrip.addView(row)
        suggestionsStrip.isVisible = true
    }

    private fun dismissDeleteConfirmation() {
        if (confirmingDeleteWord == null) return
        confirmingDeleteWord = null
        suggestionsStrip.removeAllViews()
        layoutHelper.resetLayoutState()
        startIndexOfMoreSuggestions = layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
            context, suggestedWords, suggestionsStrip, this
        )
        updateMoreSuggestionsButtonState()
    }

    private fun updateMoreSuggestionsButtonState() {
        val available = !toolbarContainer.isVisible
                && !isExternalSuggestionVisible
                && confirmingDeleteWord == null
                && layoutHelper.isMoreSuggestionsAvailable
                && suggestedWords.size > startIndexOfMoreSuggestions
        moreSuggestionsButton.isVisible = available
        moreSuggestionsButton.rotation = if (isShowingMoreSuggestionPanel) -90f else 90f
    }

    private fun showMoreSuggestions(): Boolean {
        if (suggestedWords.size <= startIndexOfMoreSuggestions) {
            return false
        }
        if (!moreSuggestionsView.show(
                suggestedWords, startIndexOfMoreSuggestions, moreSuggestionsContainer, layoutHelper, this
        ))
            return false
        for (i in 0..<startIndexOfMoreSuggestions) {
            wordViews[i].isPressed = false
        }
        updateMoreSuggestionsButtonState()
        return true
    }

    private fun showSourceDict(wordView: TextView) {
        val word = wordView.text.toString()
        val index = wordView.tag as? Int ?: return
        val suggestedWords = suggestedWords
        if (index >= suggestedWords.size) return
        val info = suggestedWords.getInfo(index)
        if (info.word != word) return

        if (isShowingMoreSuggestionPanel) {
            moreSuggestionsView.dismissPopupKeysPanel()
        }
        var text = info.sourceDict.mDictType + ":" + info.sourceDict.mLocale
        if (index == 1 && suggestedWords.typedWordInfo != null && suggestedWords.typedWordInfo.sourceDict.mDictType != Dictionary.TYPE_USER_TYPED) {
            val dict = suggestedWords.typedWordInfo.sourceDict
            text += "\n${suggestedWords.typedWordInfo.word}: ${dict.mDictType}:${dict.mLocale}"
        }
        KeyboardSwitcher.getInstance().showToast(text, true)
    }

    private fun removeSuggestionByWord(word: String) {
        confirmingDeleteWord = null
        listener.removeSuggestion(word)
        moreSuggestionsView.dismissPopupKeysPanel()
        // show suggestions, but without the removed word
        val newSuggestedWords = SuggestedWords(
            suggestedWords.filter { it.word != word },
            suggestedWords.rawSuggestions?.filter { it.word != word },
            suggestedWords.typedWordInfo, suggestedWords.typedWordValid, suggestedWords.willAutoCorrect,
            suggestedWords.isObsoleteSuggestions, suggestedWords.inputStyle, suggestedWords.sequenceNumber
        )
        clear()
        setSuggestions(newSuggestedWords, direction != 1)
        suggestionsStrip.isVisible = true

        // Show the toolbar if no suggestions are left and the "Auto show toolbar" setting is enabled
        if (this.suggestedWords.isEmpty() && Settings.getValues().mAutoShowToolbar) {
            setToolbarVisibility(true)
        }
    }

    private fun clear() {
        confirmingDeleteWord = null
        pressedWordView = null
        pressedWordInfo = null
        suggestionsStrip.removeAllViews()
        layoutHelper.resetLayoutState()
        if (DEBUG_SUGGESTIONS) removeAllDebugInfoViews()
        if (!toolbarContainer.isVisible)
            suggestionsStrip.isVisible = true
        dismissMoreSuggestionsPanel()
        updateMoreSuggestionsButtonState()
    }

    private fun removeAllDebugInfoViews() {
        for (debugInfoView in debugInfoViews) {
            val parent = debugInfoView.parent
            if (parent is ViewGroup) {
                parent.removeView(debugInfoView)
            }
        }
    }

    fun updateVoiceKey() {
        val show = Settings.getValues().mShowsVoiceInputKey
        toolbar.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
        pinnedKeys.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
    }

    private fun updateKeys() {
        updateVoiceKey()
        val settingsValues = Settings.getValues()

        val toolbarIsExpandable = settingsValues.mToolbarMode == ToolbarMode.EXPANDABLE
        if (settingsValues.mIncognitoModeEnabled) {
            toolbarExpandKey.setImageDrawable(incognitoIcon)
            toolbarExpandKey.isVisible = true
        } else {
            toolbarExpandKey.setImageDrawable(toolbarArrowIcon)
            toolbarExpandKey.isVisible = toolbarIsExpandable
        }

        toolbarExpandKey.setOnClickListener(if (!toolbarIsExpandable) null else this)
        updateToolbarExpandKeyDescription()
        pinnedKeys.visibility = VISIBLE
        isExternalSuggestionVisible = false
    }

    private fun updateToolbarExpandKeyDescription() {
        val incognito = Settings.getValues().mIncognitoModeEnabled
        val open = toolbarContainer.isVisible
        toolbarExpandKey.contentDescription = resources.getString(
            when {
                incognito && open -> R.string.toolbar_incognito_close_description
                incognito -> R.string.toolbar_incognito_open_description
                open -> R.string.toolbar_close_description
                else -> R.string.toolbar_open_description
            }
        )
    }

    private fun addKeyToPinnedKeys(pinnedKey: ToolbarKey) {
        val original = toolbar.findViewWithTag<ImageButton>(pinnedKey) ?: return
        // copy the original key to a new ImageButton
        val copy = ImageButton(context, null, R.attr.suggestionWordStyle)
        copy.tag = pinnedKey
        copy.scaleType = original.scaleType
        copy.scaleX = original.scaleX
        copy.scaleY = original.scaleY
        copy.contentDescription = original.contentDescription
        copy.setImageDrawable(original.drawable)
        copy.layoutParams = original.layoutParams
        copy.isActivated = original.isActivated
        setupKey(copy, Settings.getValues().mColors)
        pinnedKeys.addView(copy)
    }

    private fun setupKey(view: ImageButton, colors: Colors) {
        view.setOnClickListener(this)
        view.setOnLongClickListener(this)
        (view.layoutParams as LinearLayout.LayoutParams).weight = 1f
        colors.setColor(view, ColorType.TOOL_BAR_KEY)
        colors.setBackground(view, ColorType.STRIP_BACKGROUND)
        setToolbarButtonActivatedState(view, colors)
    }

    companion object {
        @JvmField
        var DEBUG_SUGGESTIONS = false
        private const val DEBUG_INFO_TEXT_SIZE_IN_DIP = 6.5f
        private val TAG = SuggestionStripView::class.java.simpleName
    }
}
