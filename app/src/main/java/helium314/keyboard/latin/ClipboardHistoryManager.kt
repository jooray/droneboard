// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.TextView
import android.widget.LinearLayout
import android.view.Gravity
import android.util.TypedValue
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.isValidNumber
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private var clipboardDao: ClipboardDao? = null
    private var tempPrimaryClip = false

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(latinIME)
        if (latinIME.mSettings.current.mClipboardHistoryEnabled)
            fetchPrimaryClip()
    }

    fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        // Make sure we read clipboard content only if history settings is set
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
            dontShowCurrentSuggestion = false
        }
    }

    // todo for later
    //  care about other clip items than first?
    private fun fetchPrimaryClip() {
        if (tempPrimaryClip) return // avoid updating history
        val clipData = clipboardManager.primaryClip ?: return
        if (clipData.itemCount == 0) return
        val clipItem = clipData.getItemAt(0) ?: return
        val description = clipData.description ?: return
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        // Droneboard: what the copying app marked as sensitive (password managers do) never enters the
        // history, so it is in neither the clipboard view nor a backup; it can still be pasted from the chip
        if (ClipboardManagerCompat.getClipSensitivity(description) == true) return

        if (description.hasMimeType("text/*")) {
            val content = clipItem.coerceToText(latinIME)
            if (TextUtils.isEmpty(content)) return
            clipboardDao?.addClip(timeStamp, false, content.toString())
        } else if (maySaveFromUri(clipItem.uri, latinIME)) {
            clipboardDao?.addClipUri(timeStamp, false, clipItem.uri, description, latinIME)
        }
    }

    fun getPrimaryClipIfText(): String? {
        if (tempPrimaryClip) return null // avoid updating history
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        return if (clipData.description?.hasMimeType("text/*") == true)
            clipItem.coerceToText(latinIME).toString().takeIf { it.isNotEmpty() }
        else null
    }

    // fallback method because in some apps there is no supported mime type and commitContend does nothing,
    // but KeyEvent.KEYCODE_PASTE for pasting from primary clip works fine
    // (actually we do change the primary clip, but (try to) revert immediately)
    fun pasteWithoutChangingClips(content: InputContentInfoCompat) {
        Log.d(TAG, "trying fallback pasting with system clipboard")
        val primaryClip = clipboardManager.primaryClip
        val tempClip = ClipData(content.description, ClipData.Item(content.contentUri))
        tempPrimaryClip = true
        clipboardManager.setPrimaryClip(tempClip)
        latinIME.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD_PASTE, 0,
            Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        tempPrimaryClip = false
        if (primaryClip == null)
            return
        // we need to wait a little before switching back to the original primary clip
        // a. it can happen that we switch back before the pasting has started, in that case we only past the primary clip
        // b. if we switch while the clip is pasted, it might crash the app (tested with joplin and logseq)
        // todo: replacing the current primary clip is far from ideal, try finding a different way
        GlobalScope.launch {
            delay(500)
            try {
                clipboardManager.setPrimaryClip(primaryClip)
            } catch (e: Exception) {
                Log.i(TAG, "could not go back to old primary clip", e)
                // happens wen the clip was a file
                // try to find it in out clipboard entries
                val clip = clipboardDao?.getAll()?.firstOrNull { it.timeStamp == ClipboardManagerCompat.getClipTimestamp(primaryClip) }
                if (clip?.filename != null)
                    clipboardManager.setPrimaryClip(ClipData(
                        ClipDescription(clip.text, clip.mimeTypes?.toTypedArray()),
                        ClipData.Item(clip.getContentUri(latinIME))
                    ))
                else if (clip != null)
                    clipboardManager.setPrimaryClip(ClipData(
                        ClipDescription("", arrayOf("text/*")),
                        ClipData.Item(clip.text)
                    ))
            }
        }
    }

    fun toggleClipPinned(id: Long) {
        clipboardDao?.togglePinned(id)
    }

    fun clearHistory() {
        clipboardDao?.clearNonPinned()
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun canRemove(index: Int) = clipboardDao?.isPinned(index) == false

    fun removeEntry(index: Int) {
        if (canRemove(index))
            clipboardDao?.deleteClipAt(index)
    }

    fun sortHistoryEntries() {
        clipboardDao?.sort()
    }

    // We do not want to update history while user is visualizing it, so we check retention only
    // when history is about to be shown
    fun prepareClipboardHistory() = clipboardDao?.clearOldClips(true)

    fun getHistorySize() = clipboardDao?.count() ?: 0

    fun getHistoryEntry(position: Int) = clipboardDao?.getAt(position)

    fun getHistoryEntryContent(id: Long) = clipboardDao?.get(id)

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        clipboardDao?.listener = listener
    }

    private fun isClipSensitive(inputType: Int): Boolean {
        ClipboardManagerCompat.getClipSensitivity(clipboardManager.primaryClip?.description)?.let { return it }
        return InputTypeUtils.isPasswordInputType(inputType)
    }

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        // maybe no need to create a new view
        // but a cache has to consider a few possible changes, so better don't implement without need
        clipboardSuggestionView = null

        // get the content, or return null
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (dontShowCurrentSuggestion) return null
        if (parent == null) return null
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        val hasText = clipData.description?.hasMimeType("text/*") == true
        val hasImage = clipData.description?.hasMimeType("image/*") == true && clipItem.uri != null
        if (!hasText && !hasImage) return null
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        if (System.currentTimeMillis() - timeStamp > RECENT_TIME_MILLIS) return null
        val content = clipItem.coerceToText(latinIME)

        // create the view
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val colors = latinIME.mSettings.current.mColors
        val density = latinIME.resources.displayMetrics.density
        val iconSizePx = (15f * density).toInt()
        val chipHeightPx = (32f * density).toInt()
        val chipPadPx = (10f * density).toInt()
        val chipGapPx = (6f * density).toInt()

        val textView = binding.clipboardSuggestionText
        val mainChip = binding.clipboardSuggestionMainChip
        val chipsRow = binding.clipboardSuggestionChips
        val clipIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.PASTE.name, latinIME)?.apply {
            setBounds(0, 0, iconSizePx, iconSizePx)
            colors.setColor(this, ColorType.CLIPBOARD_SUGGESTION_ICON)
        }
        textView.setCompoundDrawablesRelative(clipIcon, null, null, null)
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        if (hasText) {
            if (TextUtils.isEmpty(content)) return null
            if (InputTypeUtils.isNumberInputType(inputType) && !content.isValidNumber()) return null
            val shortenedContent = content.take(200) // for display only
            KeyboardTypeface.applyToTextView(textView)
            textView.text = (if (isClipSensitive(inputType)) "*".repeat(shortenedContent.length) else shortenedContent)
        }
        val onClickListener = View.OnClickListener {
            dontShowCurrentSuggestion = true
            if (hasText) latinIME.onTextInput(content.toString())
            else latinIME.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD_PASTE, 0,
                Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
            latinIME.setNeutralSuggestionStrip()
        }
        mainChip.setOnClickListener(onClickListener)
        textView.setOnClickListener(onClickListener)

        // Droneboard quick clips: an e-mail address, a link or a one-time code from the copied text get their own pills
        val quickClips = if (hasText && !isClipSensitive(inputType) && !InputTypeUtils.isNumberInputType(inputType))
            QuickClips.extract(content) else emptyList()
        val quickClipViews = quickClips.mapIndexed { index, clip ->
            val semanticIcon = makeQuickClipIcon(clip.type, iconSizePx, colors, density)
            TextView(latinIME).apply {
                text = QuickClips.label(clip)
                contentDescription = clip.text
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textView.textSize)
                typeface = textView.typeface
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                isHapticFeedbackEnabled = false
                isSoundEffectsEnabled = false
                compoundDrawablePadding = (5f * density).toInt()
                setCompoundDrawablesRelative(semanticIcon, null, null, null)
                setPaddingRelative(chipPadPx, 0, chipPadPx, 0)
                background = makeChipPillDrawable(colors, density)
                setTextColor(colors.get(ColorType.KEY_TEXT))
                setOnClickListener {
                    dontShowCurrentSuggestion = true
                    latinIME.onTextInput(clip.text)
                    AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
                    binding.root.isGone = true
                    latinIME.setNeutralSuggestionStrip()
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, chipHeightPx).apply {
                    marginEnd = chipGapPx
                }
                chipsRow.addView(this, index, lp)
            }
        }
        textView.maxWidth = ((if (quickClipViews.isNotEmpty()) 180f else 260f) * density).toInt()
        mainChip.background = makeChipPillDrawable(colors, density)

        if (hasImage) {
            if (InputTypeUtils.isNumberInputType(inputType)) return null
            val imageView = binding.clipboardSuggestionImage
            imageView.isVisible = true
            try {
                imageView.setImageURI(clipItem.uri)
            } catch (e: Exception) {
                Log.w(TAG, "error setting clipboard image", e) // happens with SecurityException: Permission Denial
                return null
            }
            imageView.setOnClickListener(onClickListener)
        }

        val closeButton = binding.clipboardSuggestionClose
        closeButton.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.CLOSE_HISTORY.name, latinIME))
        closeButton.setOnClickListener { removeClipboardSuggestion() }

        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)

        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private fun makeChipPillDrawable(colors: Colors, density: Float): Drawable {
        val fillColor = colors.get(ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        val strokeColor = ColorUtils.setAlphaComponent(colors.get(ColorType.KEY_TEXT), 38)
        val rippleColor = ColorUtils.setAlphaComponent(colors.get(ColorType.KEY_TEXT), 28)
        val shape = GradientDrawable().apply {
            cornerRadius = 16f * density
            setColor(fillColor)
            setStroke((1f * density).toInt().coerceAtLeast(1), strokeColor)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null)
    }

    private fun makeQuickClipIcon(type: QuickClips.Type, sizePx: Int, colors: Colors, density: Float): Drawable? {
        val iconColor = colors.get(ColorType.CLIPBOARD_SUGGESTION_ICON)
        val drawable: Drawable? = when (type) {
            QuickClips.Type.LINK -> ContextCompat.getDrawable(latinIME, R.drawable.ic_link)?.mutate()?.also {
                colors.setColor(it, ColorType.CLIPBOARD_SUGGESTION_ICON)
            }
            QuickClips.Type.EMAIL -> SemanticGlyphDrawable(SemanticGlyphDrawable.Kind.EMAIL, iconColor, density)
            QuickClips.Type.CODE -> SemanticGlyphDrawable(SemanticGlyphDrawable.Kind.OTP, iconColor, density)
        }
        drawable?.setBounds(0, 0, sizePx, sizePx)
        return drawable
    }

    /** Compact vector-drawn semantic icons for email and OTP Quick Clip chips. */
    private class SemanticGlyphDrawable(
        private val kind: Kind,
        color: Int,
        density: Float
    ) : Drawable() {
        enum class Kind { EMAIL, OTP }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rect = RectF()
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val pad = w * 0.12f
            when (kind) {
                Kind.EMAIL -> {
                    rect.set(b.left + pad, b.top + h * 0.2f, b.right - pad, b.bottom - h * 0.2f)
                    canvas.drawRoundRect(rect, w * 0.1f, w * 0.1f, paint)
                    path.reset()
                    path.moveTo(rect.left + w * 0.04f, rect.top + h * 0.06f)
                    path.lineTo(rect.centerX(), rect.centerY() + h * 0.05f)
                    path.lineTo(rect.right - w * 0.04f, rect.top + h * 0.06f)
                    canvas.drawPath(path, paint)
                }
                Kind.OTP -> {
                    // Crisp "#" hash / pin glyph for one-time passcodes
                    val x1 = b.left + w * 0.36f
                    val x2 = b.left + w * 0.66f
                    val y1 = b.top + h * 0.35f
                    val y2 = b.top + h * 0.65f
                    canvas.drawLine(x1 + w * 0.04f, b.top + pad, x1 - w * 0.04f, b.bottom - pad, paint)
                    canvas.drawLine(x2 + w * 0.04f, b.top + pad, x2 - w * 0.04f, b.bottom - pad, paint)
                    canvas.drawLine(b.left + pad, y1, b.right - pad, y1, paint)
                    canvas.drawLine(b.left + pad, y2, b.right - pad, y2, paint)
                }
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun removeClipboardSuggestion() {
        dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            // clipboard view is shown ->
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    companion object {
        private val TAG = "ClipboardHistoryManager"

        // avoid showing the current suggestion because it has been dismissed or pasted
        private var dontShowCurrentSuggestion: Boolean = false

        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes (for clipboard suggestions)

        private fun maySaveFromUri(uri: Uri?, context: Context): Boolean {
            val maxSize = context.prefs().getInt(Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT, Defaults.PREF_CLIPBOARD_FILES_SIZE_LIMIT)
            val saveUriData = context.prefs().getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES)
            if (uri == null || !saveUriData) return false
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null).use {
                    if (it?.moveToFirst() != true) return false
                    val size = it.getLong(0)
                    return size <= maxSize * 1000000 // maxSize is megabytes
                }
            } catch (e: Exception) {
                Log.w(TAG, "error checking clip size", e) // happens with SecurityException: Permission Denial
                return false
            }
        }
    }
}
