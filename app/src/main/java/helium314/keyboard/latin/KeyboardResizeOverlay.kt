// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.resetFloatingSize
import helium314.keyboard.latin.settings.setFloatingSize
import helium314.keyboard.latin.utils.FloatingKeyboardUtils
import helium314.keyboard.latin.utils.ResourceUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Size & position" (Gboard's "Resize" + mode switch): an overlay over the keyboard with handles on the
 * edges and corners, a row of modes (docked, one-handed, floating, split), Reset and Done.
 *
 * It stores nothing of its own: height is the keyboard height scale, width of a docked keyboard is the
 * one-handed width and side, and a floating keyboard keeps its own size and position. All of these are
 * stored per orientation / split / fold state as before.
 */
class KeyboardResizeOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private enum class Handle(val left: Boolean, val right: Boolean, val top: Boolean, val bottom: Boolean, val description: Int) {
        TOP(false, false, true, false, R.string.resize_handle_top),
        BOTTOM(false, false, false, true, R.string.resize_handle_bottom),
        LEFT(true, false, false, false, R.string.resize_handle_left),
        RIGHT(false, true, false, false, R.string.resize_handle_right),
        TOP_LEFT(true, false, true, false, R.string.resize_handle_top_left),
        TOP_RIGHT(false, true, true, false, R.string.resize_handle_top_right),
        BOTTOM_LEFT(true, false, false, true, R.string.resize_handle_bottom_left),
        BOTTOM_RIGHT(false, true, false, true, R.string.resize_handle_bottom_right);
        val isCorner get() = (left || right) && (top || bottom)
    }

    private enum class Mode(val label: Int) {
        DOCKED(R.string.resize_mode_docked), ONE_HANDED(R.string.resize_mode_one_handed),
        FLOATING(R.string.resize_mode_floating), SPLIT(R.string.resize_mode_split)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun dpi(v: Float) = (v * density).roundToInt()

    private val handleViews = Handle.entries.associateWith { HandleView(context, it) }
    private val label = TextView(context)
    private val modeChips = Mode.entries.associateWith { TextView(context) }
    private val resetButton = TextView(context)
    private val doneButton = TextView(context)
    private val panel = LinearLayout(context)

    private val scrimPaint = Paint()
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val keyboardRect = RectF()

    val isShowing get() = visibility == VISIBLE

    init {
        setWillNotDraw(false)
        isClickable = true
        handleViews.values.forEach { addView(it) }

        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER_HORIZONTAL
        label.gravity = Gravity.CENTER
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        panel.addView(label, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dpi(8f)
        })
        val modeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        modeChips.forEach { (mode, chip) ->
            chip.text = context.getString(mode.label)
            chip.styleAsChip()
            chip.setOnClickListener { setMode(mode) }
            modeRow.addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dpi(3f); marginEnd = dpi(3f)
            })
        }
        panel.addView(modeRow)
        val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        resetButton.text = context.getString(R.string.resize_reset)
        doneButton.text = context.getString(R.string.resize_done)
        resetButton.styleAsChip()
        doneButton.styleAsChip()
        resetButton.setOnClickListener { reset() }
        doneButton.setOnClickListener { hide() }
        actionRow.addView(resetButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8f) })
        actionRow.addView(doneButton)
        panel.addView(actionRow, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(10f)
        })
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun TextView.styleAsChip() {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dpi(14f), dpi(7f), dpi(14f), dpi(7f))
        minHeight = dpi(36f)
        isClickable = true
        isFocusable = true
    }

    fun show() {
        visibility = VISIBLE
        refresh()
    }

    fun hide() {
        if (!isShowing) return
        visibility = GONE
    }

    /** Update colors, the selected mode, which handles make sense, and the size label. */
    fun refresh() {
        if (!isShowing) return
        val colors = Settings.getValues().mColors
        val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        val onAccent = colors.get(ColorType.ACTION_KEY_ICON)
        val keyBackground = colors.get(ColorType.KEY_BACKGROUND)
        val text = colors.get(ColorType.KEY_TEXT)
        scrimPaint.color = ColorUtils.setAlphaComponent(colors.get(ColorType.MAIN_BACKGROUND), 0xE0)
        outlinePaint.color = accent
        outlinePaint.strokeWidth = dp(2f)
        label.setTextColor(text)
        val mode = currentMode()
        modeChips.forEach { (m, chip) ->
            val selected = m == mode
            chip.background = pill(if (selected) accent else keyBackground)
            chip.setTextColor(if (selected) onAccent else text)
            chip.isSelected = selected
        }
        resetButton.background = pill(keyBackground)
        resetButton.setTextColor(text)
        doneButton.background = pill(accent)
        doneButton.setTextColor(onAccent)
        handleViews.values.forEach { it.color = accent }
        updateHandleVisibility(mode)
        updateLabel()
        requestLayout()
        invalidate()
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(100f)
        setColor(color)
    }

    private fun currentMode(): Mode {
        val sv = Settings.getValues()
        return when {
            sv.mIsFloatingKeyboard -> Mode.FLOATING
            sv.mIsSplitKeyboardEnabled -> Mode.SPLIT
            sv.mOneHandedModeEnabled -> Mode.ONE_HANDED
            else -> Mode.DOCKED
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun updateHandleVisibility(mode: Mode) {
        val gravity = Settings.getValues().mOneHandedModeGravity
        handleViews.forEach { (h, view) ->
            view.isVisible = when (mode) {
                Mode.FLOATING -> true
                // a docked keyboard grows upwards; its width comes from the one-handed width and side
                Mode.DOCKED -> !h.bottom || h.isCorner
                // only the edge next to the free space changes the width
                Mode.ONE_HANDED -> !(h == Handle.BOTTOM) &&
                        !(gravity == Gravity.LEFT && h.left) && !(gravity == Gravity.RIGHT && h.right)
                // split keeps the full width, only the height changes
                Mode.SPLIT -> h == Handle.TOP
            }
        }
    }

    private fun updateLabel() {
        val sv = Settings.getValues()
        val (w, h) = if (sv.mIsFloatingKeyboard) {
            val available = FloatingKeyboardUtils.availableWidth().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val docked = ResourceUtils.getDefaultKeyboardHeight(resources, sv.mShowsNumberRow).coerceAtLeast(1)
            100f * sv.mFloatingWidth / available to 100f * sv.mFloatingHeight / docked
        } else {
            100f * sv.mOneHandedModeScale to 100f * sv.mKeyboardHeightScale
        }
        label.text = context.getString(R.string.resize_size_label, w.roundToInt(), h.roundToInt())
    }

    private fun setMode(mode: Mode) {
        if (mode == currentMode()) return
        val switcher = KeyboardSwitcher.getInstance()
        val sv = Settings.getValues()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (mode != Mode.FLOATING && sv.mIsFloatingKeyboard) switcher.setFloatingKeyboard(false)
        if (mode != Mode.SPLIT && Settings.getValues().mIsSplitKeyboardEnabled) switcher.toggleSplitKeyboardMode()
        if (mode != Mode.ONE_HANDED && Settings.getValues().mOneHandedModeEnabled) switcher.setOneHandedModeEnabled(false, true)
        when (mode) {
            Mode.DOCKED -> {}
            Mode.ONE_HANDED -> switcher.setOneHandedModeEnabled(true, true)
            Mode.FLOATING -> switcher.setFloatingKeyboard(true)
            Mode.SPLIT -> if (!Settings.getValues().mIsSplitKeyboardEnabled) switcher.toggleSplitKeyboardMode()
        }
        refresh()
    }

    private fun reset() {
        val switcher = KeyboardSwitcher.getInstance()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (Settings.getValues().mIsFloatingKeyboard) {
            resetFloatingSize(context)
            switcher.reloadKeyboard()
            switcher.updateFloatingPlacement()
        } else {
            Settings.getInstance().resetCurrentSizes()
            switcher.setOneHandedModeEnabled(Settings.getValues().mOneHandedModeEnabled, true)
        }
        refresh()
    }

    // ---- dragging --------------------------------------------------------------------------------------------

    private inner class DragStart(val rawX: Float, val rawY: Float) {
        val sv = Settings.getValues()
        val floating = sv.mIsFloatingKeyboard
        val keyboardWidth = keyboardRect.width()
        val keyboardHeight = keyboardRect.height()
        val heightScale = Settings.getInstance().readCurrentHeightScale()
        val oneHanded = sv.mOneHandedModeEnabled
        val gravity = sv.mOneHandedModeGravity
        val floatingWidth = sv.mFloatingWidth
        val floatingHeight = sv.mFloatingHeight
        val position = KeyboardSwitcher.getInstance().floatingRoot?.let { FloatingKeyboardUtils.currentPosition(it) } ?: (0 to 0)
        var lastWidth = -1
        var lastHeight = -1
    }

    private var drag: DragStart? = null

    private fun onHandleTouch(handle: Handle, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = DragStart(event.rawX, event.rawY)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val d = drag ?: return true
                val dx = event.rawX - d.rawX
                val dy = event.rawY - d.rawY
                if (d.floating) resizeFloating(handle, d, dx, dy) else resizeDocked(handle, d, dx, dy)
                updateLabel()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = null
                refresh()
            }
        }
        return true
    }

    /** Gentle snapping of a width fraction to 50 %, 66 % and 100 %. */
    private fun snapWidth(fraction: Float): Float {
        for (s in floatArrayOf(0.5f, 2 / 3f, 1f)) if (abs(fraction - s) < SNAP) return s
        return fraction
    }

    @SuppressLint("RtlHardcoded")
    private fun resizeDocked(handle: Handle, d: DragStart, dx: Float, dy: Float) {
        val switcher = KeyboardSwitcher.getInstance()
        val settings = Settings.getInstance()
        var changed = false
        if (handle.top) {
            val wanted = (d.keyboardHeight - dy).coerceAtLeast(1f)
            var scale = (d.heightScale * wanted / d.keyboardHeight.coerceAtLeast(1f)).coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)
            if (abs(scale - 1f) < SNAP) scale = 1f
            val px = (scale * 1000).roundToInt()
            if (px != d.lastHeight) {
                d.lastHeight = px
                settings.writeCurrentHeightScale(scale)
                changed = true
            }
        }
        if ((handle.left || handle.right) && !d.sv.mIsSplitKeyboardEnabled) {
            val full = ResourceUtils.getDefaultKeyboardWidth(context).toFloat()
            // dragging the left edge keeps the keyboard at the right side, and the other way round
            val gravity = if (d.oneHanded) d.gravity else if (handle.left) Gravity.RIGHT else Gravity.LEFT
            val wanted = if (handle.left) d.keyboardWidth - dx else d.keyboardWidth + dx
            val fraction = snapWidth((wanted / full).coerceIn(MIN_WIDTH, 1f))
            val px = (fraction * full).roundToInt()
            if (px != d.lastWidth) {
                d.lastWidth = px
                if (fraction >= 0.995f) {
                    if (Settings.getValues().mOneHandedModeEnabled) {
                        switcher.setOneHandedModeEnabled(false, true)
                        changed = false
                    }
                } else {
                    val base = resources.getFraction(R.fraction.config_one_handed_mode_width, 1, 1)
                    settings.writeOneHandedModeScale((1 - fraction) / (1 - base))
                    if (Settings.getValues().mOneHandedModeGravity != gravity) settings.writeOneHandedModeGravity(gravity)
                    switcher.setOneHandedModeEnabled(true, true)
                    changed = false
                }
            }
        }
        if (changed) switcher.reloadKeyboard()
    }

    private fun resizeFloating(handle: Handle, d: DragStart, dx: Float, dy: Float) {
        val switcher = KeyboardSwitcher.getInstance()
        val dm = resources.displayMetrics
        val available = FloatingKeyboardUtils.availableWidth().takeIf { it > 0 } ?: dm.widthPixels
        val availableHeight = FloatingKeyboardUtils.availableHeightForKeyboard().takeIf { it > 0 } ?: dm.heightPixels
        // min size independent of orientation (as in HeliBoard), max a bit below the screen size
        val minWidth = (min(dm.widthPixels, dm.heightPixels) * 0.2f).roundToInt().coerceAtLeast(200)
        val minHeight = (minWidth * 0.5f).roundToInt()
        var width = d.floatingWidth
        var height = d.floatingHeight
        if (handle.left || handle.right) {
            val wanted = if (handle.left) d.floatingWidth - dx else d.floatingWidth + dx
            val fraction = snapWidth(wanted / available)
            width = (fraction * available).roundToInt().coerceIn(minWidth, max(minWidth, available))
        }
        if (handle.top || handle.bottom) {
            val wanted = if (handle.top) d.floatingHeight - dy else d.floatingHeight + dy
            height = wanted.roundToInt().coerceIn(minHeight, max(minHeight, (availableHeight * 0.9f).roundToInt()))
        }
        if (width == d.lastWidth && height == d.lastHeight) return
        d.lastWidth = width
        d.lastHeight = height
        // the opposite edge stays where it was
        val x = d.position.first + if (handle.left) d.floatingWidth - width else 0
        val y = d.position.second + if (handle.top) d.floatingHeight - height else 0
        setFloatingSize(context, width, height)
        switcher.reloadKeyboard()
        switcher.floatingRoot?.let { FloatingKeyboardUtils.moveTo(it, x, y) }
        switcher.updateFloatingPlacement()
    }

    // ---- layout and drawing ----------------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // as tall as the keyboard, never taller (the wrapper wraps its content)
        val keyboard = KeyboardSwitcher.getInstance().visibleKeyboardView
        val height = keyboard?.measuredHeight?.takeIf { it > 0 } ?: MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        panel.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        handleViews.forEach { (h, v) ->
            val (w, hh) = handleSize(h)
            v.measure(MeasureSpec.makeMeasureSpec(min(w, width), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(min(hh, height), MeasureSpec.EXACTLY))
        }
    }

    private fun handleSize(h: Handle): Pair<Int, Int> = when {
        h.isCorner -> dpi(40f) to dpi(40f)
        h.top || h.bottom -> dpi(88f) to dpi(32f)
        else -> dpi(32f) to dpi(88f)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val keyboard = KeyboardSwitcher.getInstance().visibleKeyboardView
        val kl = (keyboard?.left ?: 0).toFloat()
        val kr = (keyboard?.right ?: (right - left)).toFloat()
        keyboardRect.set(kl, 0f, kr, (bottom - top).toFloat())
        handleViews.forEach { (h, v) ->
            val w = v.measuredWidth
            val hh = v.measuredHeight
            val x = when {
                h.left -> kl
                h.right -> kr - w
                else -> (kl + kr - w) / 2
            }.roundToInt()
            val y = when {
                h.top -> 0
                h.bottom -> (bottom - top) - hh
                else -> ((bottom - top) - hh) / 2
            }
            v.layout(x, y, x + w, y + hh)
        }
        // the panel fits into the keyboard (a small floating keyboard scales it down)
        val pw = panel.measuredWidth
        val ph = panel.measuredHeight
        val room = keyboardRect.width() - dp(2 * 40f)
        val scale = min(1f, min(room / pw.coerceAtLeast(1), (keyboardRect.height() - dp(16f)) / ph.coerceAtLeast(1))).coerceAtLeast(0.4f)
        val px = (keyboardRect.centerX() - pw / 2f).roundToInt()
        val py = (keyboardRect.centerY() - ph / 2f).roundToInt()
        panel.layout(px, py, px + pw, py + ph)
        panel.pivotX = pw / 2f
        panel.pivotY = ph / 2f
        panel.scaleX = scale
        panel.scaleY = scale
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.drawRect(keyboardRect, scrimPaint)
        val inset = outlinePaint.strokeWidth / 2
        canvas.drawRoundRect(keyboardRect.left + inset, keyboardRect.top + inset, keyboardRect.right - inset,
            keyboardRect.bottom - inset, dp(6f), dp(6f), outlinePaint)
        super.dispatchDraw(canvas)
    }

    // keys under the overlay must not type
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = true

    /** An edge bar or an L-shaped corner, with a finger-sized touch area. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class HandleView(context: Context, val handle: Handle) : View(context) {
        var color = 0
            set(value) { field = value; paint.color = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = dp(4f)
        }
        private val path = Path()

        init {
            contentDescription = context.getString(handle.description)
            isFocusable = true
            setOnTouchListener { _, event -> onHandleTouch(handle, event) }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val m = dp(6f) // keep the stroke off the outline
            val len = dp(16f)
            path.reset()
            if (handle.isCorner) {
                val x = if (handle.left) m else w - m
                val y = if (handle.top) m else h - m
                val sx = if (handle.left) 1 else -1
                val sy = if (handle.top) 1 else -1
                path.moveTo(x, y + sy * len)
                path.lineTo(x, y)
                path.lineTo(x + sx * len, y)
            } else if (handle.top || handle.bottom) {
                val y = if (handle.top) m else h - m
                path.moveTo(w / 2 - dp(18f), y)
                path.lineTo(w / 2 + dp(18f), y)
            } else {
                val x = if (handle.left) m else w - m
                path.moveTo(x, h / 2 - dp(18f))
                path.lineTo(x, h / 2 + dp(18f))
            }
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        private const val SNAP = 0.03f
        private const val MIN_WIDTH = 0.35f
        // same range as the height setting in Appearance
        private const val MIN_HEIGHT_SCALE = 0.3f
        private const val MAX_HEIGHT_SCALE = 1.5f
    }
}
