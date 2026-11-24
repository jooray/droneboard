/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import helium314.keyboard.keyboard.KeyboardTypeface;
import helium314.keyboard.keyboard.PointerTracker;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.common.CoordinateUtils;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.suggestions.SuggestedWords;

/**
 * Material 3 floating stadium pill preview text during gesture typing.
 */
public class GestureFloatingTextDrawingPreview extends AbstractDrawingPreview {
    protected static final class GesturePreviewTextParams {
        public final boolean mGesturePreviewDynamic;
        public final int mGesturePreviewTextOffset;
        public final float mGesturePreviewHorizontalPadding;
        public final float mGesturePreviewVerticalPadding;
        public final float mEdgeMarginPx;
        public final int mDisplayWidth;
        public final float mLineHeight;
        public final float mBaselineCenterOffset;

        private final TextPaint mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public GesturePreviewTextParams(final TypedArray mainKeyboardViewAttr) {
            final SettingsValues sv = Settings.getValues();
            final Colors colors = sv.mColors;
            final DisplayMetrics dm = mainKeyboardViewAttr.getResources().getDisplayMetrics();
            final float density = dm.density;

            mGesturePreviewDynamic = sv.mGestureFloatingPreviewDynamicEnabled;
            final float rawTextSize = mainKeyboardViewAttr.getDimension(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewTextSize, 19.0f * dm.scaledDensity);
            final float scaledTextSize = rawTextSize * sv.mFontSizeMultiplier;
            final int textColor = colors.get(ColorType.KEY_TEXT);
            final int previewBgColor = colors.get(ColorType.GESTURE_PREVIEW);

            mGesturePreviewTextOffset = mainKeyboardViewAttr.getDimensionPixelOffset(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewTextOffset, (int)(46.0f * density));
            mGesturePreviewHorizontalPadding = mainKeyboardViewAttr.getDimension(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewHorizontalPadding, 16.0f * density);
            mGesturePreviewVerticalPadding = mainKeyboardViewAttr.getDimension(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewVerticalPadding, 9.0f * density);
            mEdgeMarginPx = 8.0f * density;
            mDisplayWidth = dm.widthPixels;

            mTextPaint.setTextAlign(Align.CENTER);
            mTextPaint.setTextSize(scaledTextSize);
            mTextPaint.setTypeface(KeyboardTypeface.customTypeface());
            mTextPaint.setColor(textColor);

            final Paint.FontMetrics fm = mTextPaint.getFontMetrics();
            mLineHeight = fm.descent - fm.ascent;
            // Subtracting (ascent + descent)/2 from rectCenterY vertically centers tall diacritics & descenders.
            mBaselineCenterOffset = -(fm.ascent + fm.descent) * 0.5f;

            mBackgroundPaint.setStyle(Paint.Style.FILL);
            mBackgroundPaint.setColor(previewBgColor);
            mBackgroundPaint.setShadowLayer(4.0f * density, 0.0f, 1.5f * density, 0x33000000);

            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setStrokeWidth(1.0f * density);
            mStrokePaint.setColor(ColorUtils.setAlphaComponent(textColor, 34));
        }

        public TextPaint getTextPaint() {
            return mTextPaint;
        }

        public Paint getBackgroundPaint() {
            return mBackgroundPaint;
        }

        public Paint getStrokePaint() {
            return mStrokePaint;
        }
    }

    private final GesturePreviewTextParams mParams;
    private final RectF mGesturePreviewRectangle = new RectF();
    private float mPreviewTextX;
    private float mPreviewTextY;
    private SuggestedWords mSuggestedWords = SuggestedWords.getEmptyInstance();
    private final int[] mLastPointerCoords = CoordinateUtils.newInstance();
    private String mCachedRawText;
    private String mCachedDisplayText;
    private float mCachedTextWidth;

    public GestureFloatingTextDrawingPreview(final TypedArray mainKeyboardViewAttr) {
        mParams = new GesturePreviewTextParams(mainKeyboardViewAttr);
    }

    @Override
    public void onDeallocateMemory() {
        // Nothing to do here.
    }

    public void dismissGestureFloatingPreviewText() {
        setSuggestedWords(SuggestedWords.getEmptyInstance());
    }

    public void setSuggestedWords(@NonNull final SuggestedWords suggestedWords) {
        if (!isPreviewEnabled()) {
            return;
        }
        mSuggestedWords = suggestedWords;
        updatePreviewPosition(true);
    }

    @Override
    public void setPreviewPosition(@NonNull final PointerTracker tracker) {
        setPreviewPositionInternal(tracker, true);
    }

    public void setPreviewPositionNoInvalidate(@NonNull final PointerTracker tracker) {
        setPreviewPositionInternal(tracker, false);
    }

    private void setPreviewPositionInternal(@NonNull final PointerTracker tracker,
            final boolean invalidate) {
        if (!isPreviewEnabled()) {
            return;
        }
        tracker.getLastCoordinates(mLastPointerCoords);
        updatePreviewPosition(invalidate);
    }

    /**
     * Draws gesture preview text
     * @param canvas The canvas where preview text is drawn.
     */
    @Override
    public void drawPreview(@NonNull final Canvas canvas) {
        if (!isPreviewEnabled() || TextUtils.isEmpty(mCachedDisplayText)) {
            return;
        }
        final float pillRadius = mGesturePreviewRectangle.height() * 0.5f;
        canvas.drawRoundRect(
                mGesturePreviewRectangle, pillRadius, pillRadius, mParams.getBackgroundPaint());
        canvas.drawRoundRect(
                mGesturePreviewRectangle, pillRadius, pillRadius, mParams.getStrokePaint());
        canvas.drawText(mCachedDisplayText, mPreviewTextX, mPreviewTextY, mParams.getTextPaint());
    }

    /**
     * Updates gesture preview text position based on mLastPointerCoords.
     */
    protected void updatePreviewPosition() {
        updatePreviewPosition(true);
    }

    private void updatePreviewPosition(final boolean invalidate) {
        if (mSuggestedWords.isEmpty() || TextUtils.isEmpty(mSuggestedWords.getWord(0))) {
            mCachedRawText = null;
            mCachedDisplayText = null;
            mCachedTextWidth = 0.0f;
            if (invalidate) {
                invalidateDrawingView();
            }
            return;
        }
        final String rawText = mSuggestedWords.getWord(0);
        final float hPad = mParams.mGesturePreviewHorizontalPadding;
        final float vPad = mParams.mGesturePreviewVerticalPadding;
        final float maxPillWidth = Math.max(hPad * 2.0f + 1.0f,
                mParams.mDisplayWidth - mParams.mEdgeMarginPx * 2.0f);
        final float maxTextWidth = Math.max(1.0f, maxPillWidth - hPad * 2.0f);

        if (!rawText.equals(mCachedRawText)) {
            mCachedRawText = rawText;
            final TextPaint textPaint = mParams.getTextPaint();
            final float measured = textPaint.measureText(rawText);
            if (measured > maxTextWidth) {
                mCachedDisplayText = TextUtils.ellipsize(
                        rawText, textPaint, maxTextWidth, TextUtils.TruncateAt.END).toString();
                mCachedTextWidth = Math.min(maxTextWidth, textPaint.measureText(mCachedDisplayText));
            } else {
                mCachedDisplayText = rawText;
                mCachedTextWidth = measured;
            }
        }

        final float rectWidth = Math.min(maxPillWidth, mCachedTextWidth + hPad * 2.0f);
        final float rectHeight = mParams.mLineHeight + vPad * 2.0f;

        final float minX = mParams.mEdgeMarginPx;
        final float maxX = Math.max(minX, mParams.mDisplayWidth - mParams.mEdgeMarginPx - rectWidth);
        final float rectX = mParams.mGesturePreviewDynamic
                ? Math.min(Math.max(CoordinateUtils.x(mLastPointerCoords) - rectWidth * 0.5f, minX), maxX)
                : (mParams.mDisplayWidth - rectWidth) * 0.5f;
        final float rectY = mParams.mGesturePreviewDynamic
                ? CoordinateUtils.y(mLastPointerCoords) - mParams.mGesturePreviewTextOffset - rectHeight
                : -mParams.mGesturePreviewTextOffset - rectHeight;
        mGesturePreviewRectangle.set(rectX, rectY, rectX + rectWidth, rectY + rectHeight);

        mPreviewTextX = mGesturePreviewRectangle.centerX();
        mPreviewTextY = mGesturePreviewRectangle.centerY() + mParams.mBaselineCenterOffset;
        if (invalidate) {
            invalidateDrawingView();
        }
    }
}
