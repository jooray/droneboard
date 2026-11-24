/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.widget.RelativeLayout;

import androidx.core.graphics.ColorUtils;

import helium314.keyboard.latin.common.CoordinateUtils;

import java.util.ArrayList;

public final class DrawingPreviewPlacerView extends RelativeLayout {
    private static final String TOUCHPAD_WATERMARK_TEXT = "←  ↕  →";

    private final int[] mKeyboardViewOrigin = CoordinateUtils.newInstance();
    private int mKeyboardWidth;
    private int mKeyboardHeight;

    private final ArrayList<AbstractDrawingPreview> mPreviews = new ArrayList<>();

    private boolean mTouchpadWatermarkVisible;
    private Paint mWatermarkBgPaint;
    private Paint mWatermarkTextPaint;
    private final RectF mWatermarkRect = new RectF();

    public DrawingPreviewPlacerView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    public void setHardwareAcceleratedDrawingEnabled(final boolean enabled) {
        // Draw previews directly onto the hardware-accelerated window canvas without forcing an
        // extra full-window LAYER_TYPE_HARDWARE FBO copy on every 120 Hz gesture-trail frame.
        setLayerType(LAYER_TYPE_NONE, null);
    }

    public void addPreview(final AbstractDrawingPreview preview) {
        if (!mPreviews.contains(preview)) {
            mPreviews.add(preview);
        }
    }

    public void setKeyboardViewGeometry(final int[] originCoords, final int width,
            final int height) {
        CoordinateUtils.copy(mKeyboardViewOrigin, originCoords);
        mKeyboardWidth = width;
        mKeyboardHeight = height;
        final int count = mPreviews.size();
        for (int i = 0; i < count; i++) {
            mPreviews.get(i).setKeyboardViewGeometry(originCoords, width, height);
        }
    }

    public void setTouchpadWatermarkVisible(final boolean visible, final int textColor, final int bgColor) {
        if (mTouchpadWatermarkVisible == visible && !visible) {
            return;
        }
        mTouchpadWatermarkVisible = visible;
        if (visible) {
            final float density = getResources().getDisplayMetrics().density;
            if (mWatermarkTextPaint == null) {
                mWatermarkTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                mWatermarkTextPaint.setTextAlign(Paint.Align.CENTER);
                mWatermarkTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
            }
            mWatermarkTextPaint.setTextSize(22f * density);
            mWatermarkTextPaint.setColor(ColorUtils.setAlphaComponent(textColor, 175));
            if (mWatermarkBgPaint == null) {
                mWatermarkBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                mWatermarkBgPaint.setStyle(Paint.Style.FILL);
            }
            mWatermarkBgPaint.setColor(ColorUtils.setAlphaComponent(bgColor, 160));
        }
        invalidate();
    }

    public void deallocateMemory() {
        mTouchpadWatermarkVisible = false;
        final int count = mPreviews.size();
        for (int i = 0; i < count; i++) {
            mPreviews.get(i).onDeallocateMemory();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        deallocateMemory();
    }

    @Override
    public void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        final int originX = CoordinateUtils.x(mKeyboardViewOrigin);
        final int originY = CoordinateUtils.y(mKeyboardViewOrigin);
        canvas.translate(originX, originY);
        final int count = mPreviews.size();
        for (int i = 0; i < count; i++) {
            mPreviews.get(i).drawPreview(canvas);
        }
        if (mTouchpadWatermarkVisible && mKeyboardWidth > 0 && mKeyboardHeight > 0
                && mWatermarkTextPaint != null && mWatermarkBgPaint != null) {
            final float density = getResources().getDisplayMetrics().density;
            final float cx = mKeyboardWidth * 0.5f;
            final float cy = mKeyboardHeight * 0.5f;
            final float textWidth = mWatermarkTextPaint.measureText(TOUCHPAD_WATERMARK_TEXT);
            final Paint.FontMetrics fm = mWatermarkTextPaint.getFontMetrics();
            final float hPad = 18f * density;
            final float vPad = 10f * density;
            final float halfW = textWidth * 0.5f + hPad;
            final float halfH = (fm.descent - fm.ascent) * 0.5f + vPad;
            mWatermarkRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
            canvas.drawRoundRect(mWatermarkRect, halfH, halfH, mWatermarkBgPaint);
            final float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(TOUCHPAD_WATERMARK_TEXT, cx, baseline, mWatermarkTextPaint);
        }
        canvas.translate(-originX, -originY);
    }
}
