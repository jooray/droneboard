// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The listening panel that covers the keys while dictating, like Gboard's: a big mic in the
 * middle that pulses with the voice, what to do next above it, and which microphone records
 * below it. Tapping the mic stops; the keyboard button in the corner cancels.
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
    }

    /** What the buttons under the explanation do. */
    enum class Button { INSTALL, OPEN_OSTT, HELP }

    private val colors = Settings.getValues().mColors
    private val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
    /** Readable on the accent whatever the theme: themes pair a pale accent with a pale icon color. */
    private val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.45) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
    private val textColor = colors.get(ColorType.KEY_TEXT)

    private val orb = Orb(context)
    private val title = TextView(context)
    private val detail = TextView(context)
    private val primary = TextView(context)
    private val secondary = TextView(context)
    private val buttons = LinearLayout(context)
    private val cancel = ImageButton(context)

    var mode = Mode.STARTING
        private set

    init {
        isClickable = true // the keys underneath get nothing
        colors.setBackground(this, ColorType.MAIN_BACKGROUND)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        title.apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        detail.apply {
            setTextColor(ColorUtils.setAlphaComponent(textColor, 170))
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

        column.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(24), dp(16), dp(24), dp(4))
        })
        column.addView(orb, LinearLayout.LayoutParams(dp(120), dp(120)))
        column.addView(detail, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(24), dp(4), dp(24), dp(12))
        })
        column.addView(buttons, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(18), dp(4), dp(18), dp(12))
        })
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

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

    /**
     * [primaryButton] and [secondaryButton] show under the detail; in the [Mode.info] modes the
     * explanation and the buttons replace the mic.
     */
    fun show(mode: Mode, title: CharSequence, detail: CharSequence?, primaryButton: Button? = null, secondaryButton: Button? = null) {
        this.mode = mode
        this.title.text = title
        this.detail.text = detail ?: ""
        this.detail.visibility = if (detail.isNullOrEmpty()) INVISIBLE else VISIBLE
        this.detail.maxLines = if (mode.info || primaryButton != null) 4 else 2
        bind(primary, primaryButton)
        bind(secondary, secondaryButton)
        buttons.visibility = if (primaryButton != null || secondaryButton != null) VISIBLE else GONE
        orb.visibility = if (mode.info) GONE else VISIBLE
        orb.setMode(mode)
    }

    private fun bind(view: TextView, button: Button?) {
        view.visibility = if (button == null) GONE else VISIBLE
        if (button == null) return
        view.text = context.getString(when (button) {
            Button.INSTALL -> R.string.voice_install_ostt
            Button.OPEN_OSTT -> R.string.voice_open_ostt
            Button.HELP -> R.string.voice_dictation_help
        })
        view.setOnClickListener {
            when (button) {
                Button.INSTALL -> actions.onInstallTapped()
                Button.OPEN_OSTT -> actions.onOpenOsttTapped()
                Button.HELP -> actions.onHelpTapped()
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        orb.stop()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** The mic: a filled circle, a ring that follows the voice level, and a slow breathing halo. */
    private inner class Orb(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icon = ContextCompat.getDrawable(context, R.drawable.ic_keyboard_dictation_mic)!!.mutate()
        private var level = 0f
        private var shown = 0f
        private var phase = 0f
        private var orbMode = Mode.STARTING
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
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

        fun setMode(m: Mode) {
            orbMode = m
            if (!animator.isStarted) animator.start()
            invalidate()
        }

        fun setLevel(peak: Int) {
            // perceptual scale: speech sits around 0.3..0.8
            level = min(1f, sqrt(peak / 32767f) * 1.4f)
        }

        fun stop() = animator.cancel()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val base = min(width, height) / 2f * 0.62f
            val listening = orbMode == Mode.LISTENING
            val dim = orbMode == Mode.TRANSCRIBING || orbMode == Mode.STARTING || orbMode == Mode.WAITING_BLUETOOTH
            // breathing halo
            val breath = 0.5f - 0.5f * kotlin.math.cos(phase * 2 * Math.PI.toFloat())
            halo.color = ColorUtils.setAlphaComponent(accent, if (listening) 50 else 35)
            canvas.drawCircle(cx, cy, base * (1.12f + 0.12f * breath), halo)
            // voice ring
            if (listening) {
                halo.color = ColorUtils.setAlphaComponent(accent, 90)
                canvas.drawCircle(cx, cy, base * (1.05f + 0.55f * shown), halo)
            }
            fill.color = if (dim) ColorUtils.setAlphaComponent(accent, 150) else accent
            canvas.drawCircle(cx, cy, base, fill)
            val s = (base * 0.9f).toInt()
            icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            icon.draw(canvas)
        }
    }
}
