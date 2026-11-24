/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import helium314.keyboard.keyboard.PointerTracker;
import helium314.keyboard.latin.common.CoordinateUtils;

/**
 * Draw preview graphics of multiple gesture trails during gesture input.
 */
public final class GestureTrailsDrawingPreview extends AbstractDrawingPreview implements Runnable {
    private final SparseArray<GestureTrailDrawingPoints> mGestureTrails = new SparseArray<>();
    private final GestureTrailDrawingParams mDrawingParams;
    private final Paint mGesturePaint;
    private final Paint mHardwareTrailPaint;
    private final int[] mLastCoords = CoordinateUtils.newInstance();
    private int mOffscreenWidth;
    private int mOffscreenHeight;
    private int mOffscreenOffsetY;
    private Bitmap mOffscreenBuffer;
    private final Canvas mOffscreenCanvas = new Canvas();
    private final Rect mOffscreenSrcRect = new Rect();
    private final Rect mDirtyRect = new Rect();
    private final Rect mGestureTrailBoundsRect = new Rect(); // per trail

    private DrawingPreviewPlacerView mPlacerView;
    private boolean mFrameScheduled;

    public GestureTrailsDrawingPreview(final TypedArray mainKeyboardViewAttr) {
        mDrawingParams = new GestureTrailDrawingParams(mainKeyboardViewAttr);
        final Paint gesturePaint = new Paint();
        gesturePaint.setAntiAlias(true);
        gesturePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        mGesturePaint = gesturePaint;

        final Paint hwPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hwPaint.setStyle(Paint.Style.FILL);
        hwPaint.setStrokeCap(Paint.Cap.ROUND);
        hwPaint.setStrokeJoin(Paint.Join.ROUND);
        mHardwareTrailPaint = hwPaint;
    }

    @Override
    public void setDrawingView(@NonNull final DrawingPreviewPlacerView drawingView) {
        super.setDrawingView(drawingView);
        mPlacerView = drawingView;
    }

    @Override
    public void setKeyboardViewGeometry(@NonNull final int[] originCoords, final int width,
            final int height) {
        super.setKeyboardViewGeometry(originCoords, width, height);
        mOffscreenOffsetY = (int)(height
                * GestureStrokeRecognitionPoints.EXTRA_GESTURE_TRAIL_AREA_ABOVE_KEYBOARD_RATIO);
        mOffscreenWidth = width;
        mOffscreenHeight = mOffscreenOffsetY + height;
    }

    @Override
    public void onDeallocateMemory() {
        if (mPlacerView != null) {
            mPlacerView.removeCallbacks(this);
        }
        mFrameScheduled = false;
        freeOffscreenBuffer();
    }

    private void freeOffscreenBuffer() {
        mOffscreenCanvas.setBitmap(null);
        mOffscreenCanvas.setMatrix(null);
        if (mOffscreenBuffer != null) {
            mOffscreenBuffer.recycle();
            mOffscreenBuffer = null;
        }
    }

    private void mayAllocateOffscreenBuffer() {
        if (mOffscreenBuffer != null && mOffscreenBuffer.getWidth() == mOffscreenWidth
                && mOffscreenBuffer.getHeight() == mOffscreenHeight) {
            return;
        }
        freeOffscreenBuffer();
        mOffscreenBuffer = Bitmap.createBitmap(
                mOffscreenWidth, mOffscreenHeight, Bitmap.Config.ARGB_8888);
        mOffscreenCanvas.setBitmap(mOffscreenBuffer);
        mOffscreenCanvas.translate(0, mOffscreenOffsetY);
    }

    private boolean drawGestureTrailsSoftware(final Canvas offscreenCanvas, final Paint paint,
            final Rect dirtyRect) {
        // Clear previous dirty rectangle.
        if (!dirtyRect.isEmpty()) {
            paint.setColor(Color.TRANSPARENT);
            paint.setStyle(Paint.Style.FILL);
            offscreenCanvas.drawRect(dirtyRect, paint);
        }
        dirtyRect.setEmpty();
        boolean needsUpdatingGestureTrail = false;
        // Draw gesture trails to offscreen buffer.
        synchronized (mGestureTrails) {
            // Trails count == fingers count that have ever been active.
            final int trailsCount = mGestureTrails.size();
            for (int index = 0; index < trailsCount; index++) {
                final GestureTrailDrawingPoints trail = mGestureTrails.valueAt(index);
                needsUpdatingGestureTrail |= trail.drawGestureTrail(offscreenCanvas, paint,
                        mGestureTrailBoundsRect, mDrawingParams);
                // {@link #mGestureTrailBoundsRect} has bounding box of the trail.
                dirtyRect.union(mGestureTrailBoundsRect);
            }
        }
        return needsUpdatingGestureTrail;
    }

    private boolean drawGestureTrailsHardware(final Canvas canvas) {
        boolean needsUpdatingGestureTrail = false;
        synchronized (mGestureTrails) {
            final int trailsCount = mGestureTrails.size();
            for (int index = 0; index < trailsCount; index++) {
                final GestureTrailDrawingPoints trail = mGestureTrails.valueAt(index);
                needsUpdatingGestureTrail |= trail.drawGestureTrailHardware(
                        canvas, mHardwareTrailPaint, mDrawingParams);
            }
        }
        return needsUpdatingGestureTrail;
    }

    private void scheduleNextFrame() {
        if (!mFrameScheduled && mPlacerView != null) {
            mFrameScheduled = true;
            mPlacerView.postOnAnimation(this);
        }
    }

    @Override
    public void run() {
        mFrameScheduled = false;
        // Update preview synchronized to the display VSync (60/90/120 Hz).
        invalidateDrawingView();
    }

    /**
     * Draws the preview
     * @param canvas The canvas where the preview is drawn.
     */
    @Override
    public void drawPreview(@NonNull final Canvas canvas) {
        if (!isPreviewEnabled()) {
            return;
        }
        if (canvas.isHardwareAccelerated()) {
            if (mOffscreenBuffer != null) {
                freeOffscreenBuffer();
            }
            if (drawGestureTrailsHardware(canvas)) {
                scheduleNextFrame();
            }
            return;
        }

        // Software canvas fallback (non-hardware-accelerated windows / tests).
        mayAllocateOffscreenBuffer();
        final boolean needsUpdatingGestureTrail = drawGestureTrailsSoftware(
                mOffscreenCanvas, mGesturePaint, mDirtyRect);
        if (needsUpdatingGestureTrail) {
            scheduleNextFrame();
        }
        // Transfer offscreen buffer to screen.
        if (!mDirtyRect.isEmpty()) {
            mOffscreenSrcRect.set(mDirtyRect);
            mOffscreenSrcRect.offset(0, mOffscreenOffsetY);
            canvas.drawBitmap(mOffscreenBuffer, mOffscreenSrcRect, mDirtyRect, null);
        }
    }

    /**
     * Set the position of the preview.
     * @param tracker The new location of the preview is based on the points in PointerTracker.
     */
    @Override
    public void setPreviewPosition(@NonNull final PointerTracker tracker) {
        if (!isPreviewEnabled()) {
            return;
        }
        GestureTrailDrawingPoints trail;
        synchronized (mGestureTrails) {
            trail = mGestureTrails.get(tracker.mPointerId);
            if (trail == null) {
                trail = new GestureTrailDrawingPoints();
                mGestureTrails.put(tracker.mPointerId, trail);
            }
        }
        trail.addStroke(tracker.getGestureStrokeDrawingPoints(), tracker.getDownTime());
        tracker.getLastCoordinates(mLastCoords);
        trail.setLiveTip(CoordinateUtils.x(mLastCoords), CoordinateUtils.y(mLastCoords),
                SystemClock.uptimeMillis());

        invalidateDrawingView();
    }
}
