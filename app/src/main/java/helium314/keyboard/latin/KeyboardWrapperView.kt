// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.ImageButton
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.prefs
import kotlin.math.abs

class KeyboardWrapperView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle), View.OnClickListener {

    var keyboardActionListener: KeyboardActionListener? = null

    private lateinit var stopOneHandedModeBtn: ImageButton
    private lateinit var switchOneHandedModeBtn: ImageButton
    private lateinit var resizeOneHandedModeBtn: ImageButton

    var oneHandedModeEnabled = false
        set(enabled) {
            field = enabled
            updateViewsVisibility()
            requestLayout()
        }

    var oneHandedGravity = Gravity.NO_GRAVITY
        set(value) {
            field = value
            updateSwitchButtonSide()
            requestLayout()
        }


    @SuppressLint("ClickableViewAccessibility")
    override fun onFinishInflate() {
        super.onFinishInflate()
        val keyboardIconsSet = KeyboardIconsSet.instance
        keyboardIconsSet.loadIcons(context)
        stopOneHandedModeBtn = findViewById(R.id.btn_stop_one_handed_mode)
        stopOneHandedModeBtn.setImageDrawable(keyboardIconsSet.getNewDrawable(KeyboardIconsSet.NAME_STOP_ONEHANDED_KEY, context))
        stopOneHandedModeBtn.visibility = GONE
        switchOneHandedModeBtn = findViewById(R.id.btn_switch_one_handed_mode)
        switchOneHandedModeBtn.setImageDrawable(keyboardIconsSet.getNewDrawable(KeyboardIconsSet.NAME_SWITCH_ONEHANDED_KEY, context))
        switchOneHandedModeBtn.visibility = GONE
        resizeOneHandedModeBtn = findViewById(R.id.btn_resize_one_handed_mode)
        resizeOneHandedModeBtn.setImageDrawable(keyboardIconsSet.getNewDrawable(KeyboardIconsSet.NAME_RESIZE_ONEHANDED_KEY, context))
        resizeOneHandedModeBtn.visibility = GONE

        stopOneHandedModeBtn.setOnClickListener(this)
        switchOneHandedModeBtn.setOnClickListener(this)
        resizeOneHandedModeBtn.setOnClickListener(this)

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var x = 0f
        var dragged = false
        resizeOneHandedModeBtn.setOnTouchListener { v, motionEvent ->
            if (!oneHandedModeEnabled) {
                // In compact mode, let standard click handling open KeyboardResizeOverlay cleanly.
                return@setOnTouchListener false
            }
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = motionEvent.rawX
                    x = downX
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragged && abs(motionEvent.rawX - downX) > touchSlop) {
                        dragged = true
                    }
                    if (!dragged) return@setOnTouchListener true
                    val sign = -switchOneHandedModeBtn.scaleX
                    val changePercent = 2 * sign * (x - motionEvent.rawX) / context.resources.displayMetrics.density
                    if (abs(changePercent) < 1) return@setOnTouchListener true
                    x = motionEvent.rawX
                    val landscape = Settings.getValues().mDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE
                    val split = Settings.getValues().mIsSplitKeyboardEnabled
                    val oldScale = Settings.readOneHandedModeScale(context.prefs(), landscape, split, FoldableUtils.isFolded)
                    val newScale = (oldScale + changePercent / 100f).coerceAtMost(2.5f).coerceAtLeast(0.5f)
                    if (newScale == oldScale) return@setOnTouchListener true
                    Settings.getInstance().writeOneHandedModeScale(newScale)
                    KeyboardSwitcher.getInstance().setOneHandedModeEnabled(true, true)
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        v.performClick()
                    }
                    x = 0f
                    dragged = false
                }
                else -> {
                    x = 0f
                    dragged = false
                }
            }
            true
        }

        val colors = Settings.getValues().mColors
        colors.setColor(stopOneHandedModeBtn, ColorType.ONE_HANDED_MODE_BUTTON)
        colors.setColor(switchOneHandedModeBtn, ColorType.ONE_HANDED_MODE_BUTTON)
        colors.setColor(resizeOneHandedModeBtn, ColorType.ONE_HANDED_MODE_BUTTON)
        colors.setBackground(stopOneHandedModeBtn, ColorType.ONE_HANDED_MODE_BUTTON)
        colors.setBackground(switchOneHandedModeBtn, ColorType.ONE_HANDED_MODE_BUTTON)
        colors.setBackground(resizeOneHandedModeBtn, ColorType.ONE_HANDED_MODE_BUTTON)
    }

    @SuppressLint("RtlHardcoded")
    fun switchOneHandedModeSide() {
        oneHandedGravity = if (oneHandedGravity == Gravity.LEFT) Gravity.RIGHT else Gravity.LEFT
    }

    @SuppressLint("RtlHardcoded")
    private fun isCompactGutterActive(): Boolean {
        val sv = Settings.getValues()
        return !oneHandedModeEnabled
                && sv.mCompactEnabled
                && !sv.mIsFloatingKeyboard
                && !sv.mIsSplitKeyboardEnabled
                && (sv.mCompactGravity == Gravity.LEFT || sv.mCompactGravity == Gravity.RIGHT)
    }

    private fun updateViewsVisibility() {
        if (!::stopOneHandedModeBtn.isInitialized) return
        val showCompactGutter = isCompactGutterActive()
        val show = oneHandedModeEnabled || showCompactGutter
        val vis = if (show) VISIBLE else GONE
        stopOneHandedModeBtn.visibility = vis
        switchOneHandedModeBtn.visibility = vis
        resizeOneHandedModeBtn.visibility = vis

        val alpha = if (showCompactGutter) 0.72f else 1.0f
        stopOneHandedModeBtn.alpha = alpha
        switchOneHandedModeBtn.alpha = alpha
        resizeOneHandedModeBtn.alpha = alpha

        if (showCompactGutter) {
            stopOneHandedModeBtn.contentDescription = context.getString(R.string.compact_expand_full)
            switchOneHandedModeBtn.contentDescription = context.getString(R.string.compact_switch_side)
            resizeOneHandedModeBtn.contentDescription = context.getString(R.string.compact_resize)
            updateSwitchButtonSide()
        } else {
            stopOneHandedModeBtn.contentDescription = context.getString(R.string.label_stop_onehanded_mode_key)
            switchOneHandedModeBtn.contentDescription = context.getString(R.string.label_switch_onehanded_key)
            resizeOneHandedModeBtn.contentDescription = context.getString(R.string.label_resize_onehanded_key)
            updateSwitchButtonSide()
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun updateSwitchButtonSide() {
        if (!::switchOneHandedModeBtn.isInitialized) return
        val gravity = if (oneHandedModeEnabled) oneHandedGravity else Settings.getValues().mCompactGravity
        switchOneHandedModeBtn.scaleX = if (gravity == Gravity.LEFT) -1f else 1f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateViewsVisibility()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    @SuppressLint("RtlHardcoded")
    override fun onClick(view: View) {
        if (view === resizeOneHandedModeBtn) {
            KeyboardSwitcher.getInstance().toggleResizeOverlay()
            return
        }
        if (isCompactGutterActive()) {
            val switcher = KeyboardSwitcher.getInstance()
            if (view === stopOneHandedModeBtn) {
                switcher.setCompactKeyboard(false)
            } else if (view === switchOneHandedModeBtn) {
                val nextGravity = if (Settings.getValues().mCompactGravity == Gravity.LEFT) Gravity.RIGHT else Gravity.LEFT
                switcher.setCompactKeyboard(true, gravity = nextGravity)
            }
            return
        }
        if (view === stopOneHandedModeBtn) {
            keyboardActionListener?.onCodeInput(KeyCode.TOGGLE_ONE_HANDED_MODE,
                Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE,
                false /* isKeyRepeat */)
        } else if (view === switchOneHandedModeBtn) {
            keyboardActionListener?.onCodeInput(KeyCode.SWITCH_ONE_HANDED_MODE,
                Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE,
                false /* isKeyRepeat */)
        }
    }

    /** The suggestion strip and toolbar sit right above a compact or one-handed keyboard, not across the whole screen. */
    private fun alignStrip(leftMargin: Int, rightMargin: Int) {
        val strip = KeyboardSwitcher.getInstance().stripContainer ?: return
        val lp = strip.layoutParams as? MarginLayoutParams ?: return
        if (lp.leftMargin == leftMargin && lp.rightMargin == rightMargin) return
        // not during this layout pass
        post {
            lp.leftMargin = leftMargin
            lp.rightMargin = rightMargin
            strip.layoutParams = lp
        }
    }

    private fun layoutGutterButtons(isLeftGravity: Boolean, keyboardWidth: Int, keyboardHeight: Int, spareWidth: Int) {
        val minGutterPx = (40f * resources.displayMetrics.density).toInt()
        if (spareWidth < minGutterPx) {
            stopOneHandedModeBtn.layout(0, 0, 0, 0)
            switchOneHandedModeBtn.layout(0, 0, 0, 0)
            resizeOneHandedModeBtn.layout(0, 0, 0, 0)
            return
        }
        val scale = Settings.getValues().mKeyboardHeightScale
        val heightScale = if (scale < 0.8f) scale + 0.2f else 1f
        val buttonsLeft = if (isLeftGravity) keyboardWidth else 0
        val btnW = stopOneHandedModeBtn.measuredWidth.coerceAtMost(spareWidth)
        val buttonXLeft = buttonsLeft + (spareWidth - btnW) / 2
        val buttonXRight = buttonXLeft + btnW
        val buttonHeight = (heightScale * stopOneHandedModeBtn.measuredHeight).toInt()
        fun View.setLayout(yPosition: Int) {
            layout(buttonXLeft, yPosition - buttonHeight / 2, buttonXRight, yPosition + buttonHeight / 2)
        }
        stopOneHandedModeBtn.setLayout((keyboardHeight * 0.2f).toInt())
        switchOneHandedModeBtn.setLayout((keyboardHeight * 0.5f).toInt())
        resizeOneHandedModeBtn.setLayout((keyboardHeight * 0.8f).toInt())
    }

    @SuppressLint("RtlHardcoded")
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val sv = Settings.getValues()
        if (!oneHandedModeEnabled) {
            super.onLayout(changed, left, top, right, bottom)
            val keyboardView = KeyboardSwitcher.getInstance().visibleKeyboardView
            if (sv.mCompactEnabled && keyboardView != null && keyboardView.parent === this) {
                // compact docked keyboard: at the bottom, on the left, in the middle or on the right
                val spare = (right - left - keyboardView.measuredWidth).coerceAtLeast(0)
                val keyboardLeft = when (sv.mCompactGravity) {
                    Gravity.LEFT -> 0
                    Gravity.RIGHT -> spare
                    else -> spare / 2
                }
                keyboardView.layout(keyboardLeft, 0, keyboardLeft + keyboardView.measuredWidth, keyboardView.measuredHeight)
                alignStrip(keyboardLeft, spare - keyboardLeft)
                if (isCompactGutterActive()) {
                    layoutGutterButtons(sv.mCompactGravity == Gravity.LEFT, keyboardView.measuredWidth, keyboardView.measuredHeight, spare)
                }
                findViewById<KeyboardResizeOverlay>(R.id.resize_overlay)?.placeContent()
            } else {
                alignStrip(0, 0)
            }
            return
        }

        val isLeftGravity = oneHandedGravity == Gravity.LEFT
        val width = right - left
        val keyboardView = KeyboardSwitcher.getInstance().visibleKeyboardView ?: return
        val spareWidth = (width - keyboardView.measuredWidth).coerceAtLeast(0)

        val keyboardLeft = if (isLeftGravity) 0 else spareWidth
        keyboardView.layout(
                keyboardLeft,
                0,
                keyboardLeft + keyboardView.measuredWidth,
                keyboardView.measuredHeight
        )
        alignStrip(keyboardLeft, spareWidth - keyboardLeft)
        layoutGutterButtons(isLeftGravity, keyboardView.measuredWidth, keyboardView.measuredHeight, spareWidth)
        // the "Size & position" overlay covers the whole wrapper; it places its handles on the keyboard itself
        findViewById<View>(R.id.resize_overlay)?.takeIf { it.visibility != GONE }
            ?.let { it.layout(0, 0, width, it.measuredHeight) }
    }
}
