package helium314.keyboard.latin.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Settings

object FloatingKeyboardUtils {
    private val TAG = this::class.java.simpleName
    private val windowFrame = Rect()
    private var extraHeight = 0f

    /** Space the floating keyboard keeps from the screen edges when placed by default. */
    private const val DEFAULT_MARGIN_DP = 16

    @JvmStatic
    fun setFloating(view: View?) {
        view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        updateWindowFrame(view)
        val (x, y) = readPosition(view.context, maxX, maxY)
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "place floating view at $x, $y, width ${Settings.getValues().mFloatingWidth}, height $actualFloatingHeight")
        ViewLayoutUtils.placeViewAt(view, x, y, Settings.getValues().mFloatingWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        val container = view.findViewById<View>(R.id.float_handle_container) ?: return
        styleHandle(view)
        if (container.isVisible)
            return
        container.isVisible = true
        view.findViewById<ImageView>(R.id.drag_handle)?.setDragListener(view)
    }

    @JvmStatic
    fun disableFloating(view: View?) {
        val lp = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) return // not floating
        if (DebugFlags.DEBUG_ENABLED)
            Log.d(TAG, "disable floating view")
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.leftMargin = 0
        lp.topMargin = 0
        view.findViewById<View>(R.id.float_handle_container)?.isGone = true
        view.findViewById<View>(R.id.dock_hint)?.isGone = true
    }

    @JvmStatic
    fun getFloatingHandleHeight(resources: Resources) = resources.getDimension(R.dimen.config_floating_handle_height)

    /** The area the floating keyboard can be placed in (window frame minus nav bar), refreshed from [view]. */
    private fun updateWindowFrame(view: View) {
        view.getWindowVisibleDisplayFrame(windowFrame)
        // On Android 15+ (edge-to-edge) getWindowVisibleDisplayFrame includes the area behind the navbar, so we remove it here
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val navBarBottom = ViewCompat.getRootWindowInsets(view)
                ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            windowFrame.bottom -= navBarBottom
        }
        extraHeight = getSuggestionStripHeight(view.resources) + getFloatingHandleHeight(view.resources)
    }

    private val availableWidth get() = windowFrame.right - windowFrame.left
    private val availableHeight get() = windowFrame.bottom - windowFrame.top
    private val maxX get() = availableWidth - Settings.getValues().mFloatingWidth
    private val maxY get() = availableHeight - extraHeight.toInt() - actualFloatingHeight

    /** Width of the area the floating keyboard lives in, for the resize overlay (0 before the first placement). */
    @JvmStatic
    fun availableWidth(): Int = availableWidth

    @JvmStatic
    fun availableHeightForKeyboard(): Int = (availableHeight - extraHeight).toInt()

    @JvmStatic
    fun readPosition(context: Context, maxX: Int, maxY: Int): Pair<Int, Int> {
        // got a crash report where max was < 0 -> probably some crap like visibleDisplayFrame size 0
        val maxX = maxX.coerceAtLeast(0)
        val maxY = maxY.coerceAtLeast(0)
        val dm = context.resources.displayMetrics
        val width = dm.widthPixels
        val margin = (DEFAULT_MARGIN_DP * dm.density).toInt()
        // default: bottom right, where the right thumb is (Gboard floats near the bottom too)
        val defaultX = (width - Settings.getValues().mFloatingWidth - margin).coerceAtLeast(0)
        val defaultY = (dm.heightPixels - actualFloatingHeight - 3 * getFloatingHandleHeight(context.resources).toInt()
                - context.resources.getDimension(R.dimen.config_suggestions_strip_height).toInt() - 2 * margin).coerceAtLeast(0)
        val prefs = context.prefs()
        val x = prefs.getInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, defaultX)
        val y = prefs.getInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, defaultY)
        if (x > maxX || y > maxY)
            savePosition(context, x.coerceAtMost(maxX), y.coerceAtMost(maxY))
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /** Current position of the floating keyboard (left/top margin in its window). */
    @JvmStatic
    fun currentPosition(view: View): Pair<Int, Int> {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return 0 to 0
        return lp.leftMargin to lp.topMargin
    }

    /** Move the floating keyboard (used by the resize overlay when the left or top edge is dragged). */
    @JvmStatic
    fun moveTo(view: View, x: Int, y: Int) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        lp.leftMargin = x.coerceIn(0, maxX.coerceAtLeast(0))
        lp.topMargin = y.coerceIn(0, maxY.coerceAtLeast(0))
        savePosition(view.context, lp.leftMargin, lp.topMargin)
        view.layoutParams = lp
    }

    private fun savePosition(context: Context, x: Int, y: Int) {
        val width = context.resources.displayMetrics.widthPixels
        context.prefs().edit {
            putInt(Settings.PREF_FLOATING_POS_X_PREFIX + width, x)
            putInt(Settings.PREF_FLOATING_POS_Y_PREFIX + width, y)
        }
    }

    private fun getSuggestionStripHeight(resources: Resources) =
        if (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN) 0
        else resources.getDimension(R.dimen.config_suggestions_strip_height).toInt()

    private fun styleHandle(view: View) {
        val colors = Settings.getValues().mColors
        view.findViewById<ImageView>(R.id.drag_handle)?.let { colors.setColor(it, ColorType.KEY_HINT_TEXT) }
        view.findViewById<TextView>(R.id.dock_hint)?.let {
            it.setTextColor(colors.get(ColorType.ACTION_KEY_ICON))
            it.background = GradientDrawable().apply {
                cornerRadius = 100 * view.resources.displayMetrics.density
                setColor(colors.get(ColorType.ACTION_KEY_BACKGROUND))
            }
        }
    }

    /**
     * Drag the pill to move the keyboard. Pulling it against the bottom edge shows "Release to dock";
     * releasing there docks the keyboard (Gboard: "Drag here to exit floating mode").
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.setDragListener(view: View) {
        var startX = 0f
        var startY = 0f
        var positionX = 0f
        var positionY = 0f
        var wantedY = 0f
        var inDockZone = false
        val dockHint = view.findViewById<View>(R.id.dock_hint)
        val dockZone = 24 * resources.displayMetrics.density
        fun setDockZone(inside: Boolean) {
            if (inside == inDockZone) return
            inDockZone = inside
            dockHint?.isVisible = inside
            view.findViewById<View>(R.id.drag_handle)?.alpha = if (inside) 0f else 1f
            if (inside) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    val lp = view.layoutParams as ViewGroup.MarginLayoutParams
                    positionX = lp.leftMargin.toFloat()
                    positionY = lp.topMargin.toFloat()
                    wantedY = positionY
                    updateWindowFrame(view)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    startX = event.rawX
                    startY = event.rawY
                    val lp = view.layoutParams as ViewGroup.MarginLayoutParams
                    val maxYf = maxY.coerceAtLeast(0).toFloat()
                    // the keyboard stops at the bottom edge; pulling on past it arms docking
                    wantedY = (wantedY + dy).coerceIn(0f, maxYf + 3 * dockZone)
                    positionX = (positionX + dx).coerceIn(0f, maxX.coerceAtLeast(0).toFloat())
                    positionY = wantedY.coerceAtMost(maxYf)
                    setDockZone(wantedY > maxYf + dockZone / 2)
                    lp.leftMargin = positionX.toInt()
                    lp.topMargin = positionY.toInt()
                    savePosition(context, lp.leftMargin, lp.topMargin)
                    view.layoutParams = lp // to update immediately
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dock = inDockZone && event.actionMasked == MotionEvent.ACTION_UP
                    setDockZone(false)
                    if (dock) {
                        // forget the dragged-to-bottom position so the next float starts where it was placed by default
                        context.prefs().edit {
                            val width = context.resources.displayMetrics.widthPixels
                            remove(Settings.PREF_FLOATING_POS_X_PREFIX + width)
                            remove(Settings.PREF_FLOATING_POS_Y_PREFIX + width)
                        }
                        KeyboardSwitcher.getInstance().setFloatingKeyboard(false)
                    }
                    true
                }
                else -> false
            }
        }
    }

    // mFloatingHeight does not consider whether everything or only the toolbar is shown
    private val actualFloatingHeight get() = if (Settings.getInstance().readShowToolbarOnly()) 0 else Settings.getValues().mFloatingHeight
}
