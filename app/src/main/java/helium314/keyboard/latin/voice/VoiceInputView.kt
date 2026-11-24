// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The listening panel that covers the keys while dictating, like Gboard's: a big mic/stop orb in the
 * middle that pulses with the voice, what to do next above it, live elapsed timer and active microphone
 * below it. Tapping the orb stops (or retries on error); the keyboard button in the corner cancels.
 */
@SuppressLint("ViewConstructor")
class VoiceInputView(context: Context, private val actions: Actions) : FrameLayout(context) {

    interface Actions {
        fun onMicTapped()
        fun onCancelTapped()
        fun onInstallTapped()
        fun onHelpTapped()
        fun onOpenOsttTapped()
    }

    enum class Mode {
        STARTING, WAITING_BLUETOOTH, LISTENING, TRANSCRIBING, ERROR,
        /** Offline Speech to Text is not installed. */
        MISSING_OSTT,
        /** Installed, but its recognition service is off or too old. */
        OSTT_UNAVAILABLE,
        /** It needs a speech model or a permission: the user has to open it once. */
        OSTT_NEEDS_SETUP;

        /** An explanation with buttons instead of the mic. */
        val info get() = this == MISSING_OSTT || this == OSTT_UNAVAILABLE || this == OSTT_NEEDS_SETUP

        /** Active states that warrant orb animation. */
        val animated get() = this == STARTING || this == WAITING_BLUETOOTH || this == LISTENING || this == TRANSCRIBING
    }

    /** What the buttons under the explanation or error do. */
    enum class Button { INSTALL, OPEN_OSTT, HELP, RETRY }

    private val colors = Settings.getValues().mColors
    private val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
    /** Readable on the accent whatever the theme: themes pair a pale accent with a pale icon color. */
    private val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.45) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
    private val textColor = colors.get(ColorType.KEY_TEXT)

    private val scrollView = ScrollView(context)
    private val orb = Orb(context)
    private val title = TextView(context)
    private val timer = TextView(context)
    private val detail = TextView(context)
    private val primary = TextView(context)
    private val secondary = TextView(context)
    private val buttons = LinearLayout(context)
    private val cancel = ImageButton(context)

    private var listeningStartElapsedMs = 0L
    private val timerTick = object : Runnable {
        override fun run() {
            if (mode != Mode.LISTENING || visibility != VISIBLE || listeningStartElapsedMs <= 0L) return
            updateTimerText()
            postDelayed(this, 500L)
        }
    }

    var mode = Mode.STARTING
        private set

    init {
        isClickable = true // the keys underneath get nothing
        colors.setBackground(this, ColorType.MAIN_BACKGROUND)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        title.apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        timer.apply {
            setTextColor(ColorUtils.setAlphaComponent(textColor, 205))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            maxLines = 1
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        detail.apply {
            setTextColor(ColorUtils.setAlphaComponent(textColor, 175))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        primary.apply {
            setTextColor(onAccent)
            styleButton(this)
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(accent) }
        }
        secondary.apply {
            setTextColor(textColor)
            styleButton(this)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), ColorUtils.setAlphaComponent(textColor, 110))
            }
        }
        buttons.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = GONE
            addView(primary, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
            addView(secondary, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        orb.setOnClickListener { actions.onMicTapped() }
        orb.contentDescription = context.getString(R.string.voice_stop)

        // 52dp horizontal margins keep title clear of the 48x48dp top-start cancel button even on 2-line titles.
        column.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(52), dp(8), dp(52), dp(4))
        })
        column.addView(orb, LinearLayout.LayoutParams(dp(88), dp(88)))
        column.addView(timer, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(24), dp(2), dp(24), 0)
        })
        column.addView(detail, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(24), dp(2), dp(24), dp(8))
        })
        column.addView(buttons, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(18), dp(4), dp(18), dp(10))
        })

        // Wrap content in a fill-viewport ScrollView so info/error states and buttons never clip in landscape,
        // 0.85x height, or 1.3x font scale, while vertically centering cleanly when everything fits.
        scrollView.apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        cancel.apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_keyboard_dictation_back))
            setColorFilter(textColor)
            background = null
            contentDescription = context.getString(R.string.voice_cancel)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { actions.onCancelTapped() }
        }
        addView(cancel, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableHeight > 0) {
            val targetOrb = min(dp(96), (availableHeight * 0.48f).toInt()).coerceAtLeast(dp(52))
            val lp = orb.layoutParams
            if (lp.width != targetOrb || lp.height != targetOrb) {
                lp.width = targetOrb
                lp.height = targetOrb
                orb.layoutParams = lp
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * [primaryButton] and [secondaryButton] show under the detail; in the [Mode.info] modes the
     * explanation and the buttons replace the mic.
     */
    fun show(
        mode: Mode,
        title: CharSequence,
        detail: CharSequence?,
        primaryButton: Button? = null,
        secondaryButton: Button? = null,
        urgent: Boolean = false,
    ) {
        this.mode = mode
        val desiredLiveRegion = if (mode == Mode.ERROR || urgent) {
            ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        } else {
            ACCESSIBILITY_LIVE_REGION_POLITE
        }
        if (this.title.accessibilityLiveRegion != desiredLiveRegion) {
            this.title.accessibilityLiveRegion = desiredLiveRegion
        }
        // Only update title text when it actually changes so TalkBack live regions don't repeat on every amplitude tick.
        if (this.title.text?.toString() != title.toString()) {
            this.title.text = title
        }
        val detailStr = detail ?: ""
        if (this.detail.text?.toString() != detailStr.toString()) {
            this.detail.text = detailStr
        }
        this.detail.visibility = if (detail.isNullOrEmpty()) GONE else VISIBLE
        this.detail.maxLines = if (mode.info || primaryButton != null) 4 else 2
        bind(primary, primaryButton)
        bind(secondary, secondaryButton)
        buttons.visibility = if (primaryButton != null || secondaryButton != null) VISIBLE else GONE
        orb.visibility = if (mode.info) GONE else VISIBLE

        when (mode) {
            Mode.LISTENING, Mode.STARTING, Mode.WAITING_BLUETOOTH -> {
                orb.isClickable = true
                orb.contentDescription = context.getString(R.string.voice_stop)
            }
            Mode.ERROR -> {
                orb.isClickable = true
                orb.contentDescription = context.getString(R.string.voice_try_again)
            }
            Mode.TRANSCRIBING -> {
                orb.isClickable = false
                orb.contentDescription = context.getString(R.string.voice_transcribing)
            }
            else -> {
                orb.isClickable = false
                orb.contentDescription = null
            }
        }

        if (mode == Mode.LISTENING) {
            if (listeningStartElapsedMs <= 0L) {
                listeningStartElapsedMs = SystemClock.elapsedRealtime()
            }
            timer.visibility = VISIBLE
            updateTimerText()
            removeCallbacks(timerTick)
            postDelayed(timerTick, 500L)
        } else {
            removeCallbacks(timerTick)
            timer.visibility = GONE
            if (mode != Mode.TRANSCRIBING) {
                listeningStartElapsedMs = 0L
            }
        }

        orb.setMode(mode)
    }

    /** Keep elapsed timer synced with DictationController across orientation changes. */
    fun setListeningStart(elapsedRealtimeMs: Long) {
        listeningStartElapsedMs = elapsedRealtimeMs
        if (mode == Mode.LISTENING && elapsedRealtimeMs > 0L) {
            updateTimerText()
        }
    }

    private fun updateTimerText() {
        val elapsedSec = ((SystemClock.elapsedRealtime() - listeningStartElapsedMs).coerceAtLeast(0L) / 1000L).toInt()
        val minutes = elapsedSec / 60
        val seconds = elapsedSec % 60
        timer.text = String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun bind(view: TextView, button: Button?) {
        view.visibility = if (button == null) GONE else VISIBLE
        if (button == null) return
        view.text = context.getString(when (button) {
            Button.INSTALL -> R.string.voice_install_ostt
            Button.OPEN_OSTT -> R.string.voice_open_ostt
            Button.HELP -> R.string.voice_dictation_help
            Button.RETRY -> R.string.voice_try_again
        })
        view.setOnClickListener {
            when (button) {
                Button.INSTALL -> actions.onInstallTapped()
                Button.OPEN_OSTT -> actions.onOpenOsttTapped()
                Button.HELP -> actions.onHelpTapped()
                Button.RETRY -> actions.onMicTapped()
            }
        }
    }

    private fun styleButton(view: TextView) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        view.gravity = Gravity.CENTER
        view.maxLines = 2
        view.ellipsize = TextUtils.TruncateAt.END
        view.minHeight = dp(48)
        val pad = dp(12)
        view.setPadding(pad, dp(10), pad, dp(10))
    }

    /** 0..32767 peak of the last block. */
    fun setLevel(peak: Int) = orb.setLevel(peak)

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            removeCallbacks(timerTick)
            orb.stop()
        } else {
            orb.syncAnimationState()
            if (mode == Mode.LISTENING && listeningStartElapsedMs > 0L) {
                removeCallbacks(timerTick)
                postDelayed(timerTick, 500L)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(timerTick)
        orb.stop()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** The mic/stop orb: filled circle, voice-reactive ring, stop square when listening, progress arc when transcribing. */
    private inner class Orb(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onAccent }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(3).toFloat()
            color = accent
        }
        private val arcBounds = RectF()
        private val stopRect = RectF()
        private val icon = ContextCompat.getDrawable(context, R.drawable.ic_keyboard_dictation_mic)!!.mutate()
        private var level = 0f
        private var shown = 0f
        private var phase = 0f
        private var orbMode = Mode.STARTING
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                // ease the level so the ring glides instead of jumping
                shown += (level - shown) * 0.25f
                invalidate()
            }
        }

        init {
            isClickable = true
            icon.setTint(onAccent)
        }

        private fun animatorsEnabled(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

        fun setMode(m: Mode) {
            orbMode = m
            syncAnimationState()
            invalidate()
        }

        fun syncAnimationState() {
            val shouldAnimate = orbMode.animated
                    && this@VoiceInputView.visibility == VISIBLE
                    && visibility == VISIBLE
                    && animatorsEnabled()
            if (shouldAnimate) {
                if (!animator.isStarted) animator.start()
            } else {
                if (animator.isStarted) animator.cancel()
            }
        }

        fun setLevel(peak: Int) {
            // perceptual scale: speech sits around 0.3..0.8
            level = min(1f, sqrt(peak / 32767f) * 1.4f)
            if (!animatorsEnabled()) {
                shown = level
                invalidate()
            }
        }

        fun stop() {
            if (animator.isStarted) animator.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val base = min(width, height) / 2f * 0.62f
            val listening = orbMode == Mode.LISTENING
            val transcribing = orbMode == Mode.TRANSCRIBING
            val dim = transcribing || orbMode == Mode.STARTING || orbMode == Mode.WAITING_BLUETOOTH

            if (orbMode.animated) {
                val breath = if (animatorsEnabled()) {
                    0.5f - 0.5f * kotlin.math.cos(phase * 2 * Math.PI.toFloat())
                } else 0.5f
                halo.color = ColorUtils.setAlphaComponent(accent, if (listening) 50 else 32)
                canvas.drawCircle(cx, cy, base * (1.12f + 0.12f * breath), halo)
            }
            // voice level ring
            if (listening) {
                halo.color = ColorUtils.setAlphaComponent(accent, 90)
                canvas.drawCircle(cx, cy, base * (1.05f + 0.52f * shown), halo)
            }
            // rotating indeterminate arc around the orb while transcribing
            if (transcribing) {
                val ringRadius = base * 1.18f
                arcBounds.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
                val startAngle = if (animatorsEnabled()) phase * 360f - 90f else -90f
                canvas.drawArc(arcBounds, startAngle, 95f, false, progressPaint)
            }

            fill.color = if (dim) ColorUtils.setAlphaComponent(accent, 150) else accent
            canvas.drawCircle(cx, cy, base, fill)

            if (listening) {
                // Active recording shows a clear Stop square glyph inside the orb.
                val halfSide = base * 0.31f
                val corner = halfSide * 0.44f
                stopRect.set(cx - halfSide, cy - halfSide, cx + halfSide, cy + halfSide)
                canvas.drawRoundRect(stopRect, corner, corner, stopPaint)
            } else {
                val s = (base * 0.9f).toInt()
                icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
                icon.draw(canvas)
            }
        }
    }
}
