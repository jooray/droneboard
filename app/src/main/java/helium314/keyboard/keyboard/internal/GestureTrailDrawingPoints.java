/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;

import helium314.keyboard.latin.common.ResizableIntArray;

/**
 * This class holds drawing points to represent a gesture trail. The gesture trail may contain
 * multiple non-contiguous gesture strokes and will be animated asynchronously from gesture input.
 * <p>
 * On the other hand, {@link GestureStrokeDrawingPoints} class holds drawing points of each gesture
 * stroke. This class holds drawing points of those gesture strokes to draw as a gesture trail.
 * Drawing points in this class will be asynchronously removed when fading out animation goes.
 */
final class GestureTrailDrawingPoints {
    public static final boolean DEBUG_SHOW_POINTS = false;
    public static final int POINT_TYPE_SAMPLED = 1;
    public static final int POINT_TYPE_INTERPOLATED = 2;

    // Keep swipe trail slightly translucent (~85% peak alpha) so keycap letters stay readable underneath.
    private static final int TRAIL_PEAK_ALPHA_SCALE = 217;
    private static final int CAP_ARC_SEGMENTS = 6;

    private static final int DEFAULT_CAPACITY = GestureStrokeDrawingPoints.PREVIEW_CAPACITY;

    // These three {@link ResizableIntArray}s should be synchronized by {@link #mEventTimes}.
    private final ResizableIntArray mXCoordinates = new ResizableIntArray(DEFAULT_CAPACITY);
    private final ResizableIntArray mYCoordinates = new ResizableIntArray(DEFAULT_CAPACITY);
    private final ResizableIntArray mEventTimes = new ResizableIntArray(DEFAULT_CAPACITY);
    private final ResizableIntArray mPointTypes = new ResizableIntArray(
            DEBUG_SHOW_POINTS ? DEFAULT_CAPACITY : 0);
    private int mCurrentStrokeId = -1;
    // The wall time of the zero value in {@link #mEventTimes}
    private long mCurrentTimeBase;
    private int mTrailStartIndex;
    private int mLastInterpolatedDrawIndex;

    // Raw live fingertip position for zero-lag trail tip tracking between sampled points.
    private boolean mHasLiveTip;
    private int mLiveTipX;
    private int mLiveTipY;
    private long mLiveTipTimeMs;

    // Reusable zero-allocation scratch buffers for direct GPU drawVertices triangle strips.
    private float[] mStrokeX = new float[128];
    private float[] mStrokeY = new float[128];
    private float[] mStrokeR = new float[128];
    private int[] mStrokeColor = new int[128];
    private float[] mStripVerts = new float[512];
    private int[] mStripColors = new int[256];
    private final float[] mCapVerts = new float[(CAP_ARC_SEGMENTS + 2) * 2];
    private final int[] mCapColors = new int[CAP_ARC_SEGMENTS + 2];

    // Use this value as imaginary zero because x-coordinates may be zero.
    private static final int DOWN_EVENT_MARKER = -128;

    private static int markAsDownEvent(final int xCoord) {
        return DOWN_EVENT_MARKER - xCoord;
    }

    private static boolean isDownEventXCoord(final int xCoordOrMark) {
        return xCoordOrMark <= DOWN_EVENT_MARKER;
    }

    private static int getXCoordValue(final int xCoordOrMark) {
        return isDownEventXCoord(xCoordOrMark)
                ? DOWN_EVENT_MARKER - xCoordOrMark : xCoordOrMark;
    }

    public void addStroke(final GestureStrokeDrawingPoints stroke, final long downTime) {
        synchronized (mEventTimes) {
            addStrokeLocked(stroke, downTime);
        }
    }

    public void setLiveTip(final int x, final int y, final long uptimeMs) {
        synchronized (mEventTimes) {
            mHasLiveTip = true;
            mLiveTipX = x;
            mLiveTipY = y;
            mLiveTipTimeMs = uptimeMs;
        }
    }

    private void addStrokeLocked(final GestureStrokeDrawingPoints stroke, final long downTime) {
        final int trailSize = mEventTimes.getLength();
        stroke.appendPreviewStroke(mEventTimes, mXCoordinates, mYCoordinates, mPointTypes);
        if (mEventTimes.getLength() == trailSize) {
            return;
        }
        final int[] eventTimes = mEventTimes.getPrimitiveArray();
        final int strokeId = stroke.getGestureStrokeId();
        final int lastInterpolatedIndex = (strokeId == mCurrentStrokeId)
                ? mLastInterpolatedDrawIndex : trailSize;
        mLastInterpolatedDrawIndex = stroke.interpolateStrokeAndReturnStartIndexOfLastSegment(
                lastInterpolatedIndex, mEventTimes, mXCoordinates, mYCoordinates, mPointTypes);
        if (strokeId != mCurrentStrokeId) {
            final int elapsedTime = (int)(downTime - mCurrentTimeBase);
            for (int i = mTrailStartIndex; i < trailSize; i++) {
                // Decay the previous strokes' event times.
                eventTimes[i] -= elapsedTime;
            }
            final int[] xCoords = mXCoordinates.getPrimitiveArray();
            xCoords[trailSize] = markAsDownEvent(xCoords[trailSize]);
            mCurrentTimeBase = downTime - eventTimes[trailSize];
            mCurrentStrokeId = strokeId;
            mHasLiveTip = false;
        }
    }

    /**
     * Calculate the alpha of a gesture trail, scaled to ~85% peak opacity for keycap legibility.
     */
    private static int getAlpha(final int elapsedTime, final GestureTrailDrawingParams params) {
        final int fullAlpha = (Color.alpha(params.mTrailColor) * TRAIL_PEAK_ALPHA_SCALE) / 255;
        if (elapsedTime <= params.mFadeoutStartDelay || params.mFadeoutDuration <= 0) {
            return Math.max(0, fullAlpha);
        }
        final int decreasingAlpha = fullAlpha
                * (elapsedTime - params.mFadeoutStartDelay)
                / params.mFadeoutDuration;
        return Math.max(0, fullAlpha - decreasingAlpha);
    }

    /**
     * Calculate the width of a gesture trail.
     */
    private static float getWidth(final int elapsedTime, final GestureTrailDrawingParams params) {
        if (params.mTrailLingerDuration <= 0) {
            return params.mTrailEndWidth;
        }
        final float clampedElapsed = Math.max(0, Math.min(elapsedTime, params.mTrailLingerDuration));
        final float deltaWidth = params.mTrailStartWidth - params.mTrailEndWidth;
        return params.mTrailStartWidth - (deltaWidth * clampedElapsed) / params.mTrailLingerDuration;
    }

    private final RoundedLine mRoundedLine = new RoundedLine();
    private final Rect mRoundedLineBounds = new Rect();

    /**
     * Draw gesture trail directly on a hardware-accelerated GPU Canvas (zero offscreen Bitmap upload).
     * @return true if some gesture trails remain to be drawn
     */
    public boolean drawGestureTrailHardware(final Canvas canvas, final Paint paint,
            final GestureTrailDrawingParams params) {
        synchronized (mEventTimes) {
            return drawGestureTrailHardwareLocked(canvas, paint, params);
        }
    }

    private boolean drawGestureTrailHardwareLocked(final Canvas canvas, final Paint paint,
            final GestureTrailDrawingParams params) {
        final int trailSize = mEventTimes.getLength();
        if (trailSize == 0) {
            mHasLiveTip = false;
            return false;
        }

        final int[] eventTimes = mEventTimes.getPrimitiveArray();
        final int[] xCoords = mXCoordinates.getPrimitiveArray();
        final int[] yCoords = mYCoordinates.getPrimitiveArray();
        final long nowMs = SystemClock.uptimeMillis();
        final int sinceDown = (int)(nowMs - mCurrentTimeBase);

        int startIndex;
        for (startIndex = mTrailStartIndex; startIndex < trailSize; startIndex++) {
            final int elapsedTime = sinceDown - eventTimes[startIndex];
            if (elapsedTime < params.mTrailLingerDuration) {
                break;
            }
        }
        mTrailStartIndex = startIndex;

        if (startIndex < trailSize) {
            final int rgb = params.mTrailColor & 0x00FFFFFF;
            final boolean useVertices = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
            int strokeStart = startIndex;
            while (strokeStart < trailSize) {
                int strokeEnd = strokeStart + 1;
                while (strokeEnd < trailSize && !isDownEventXCoord(xCoords[strokeEnd])) {
                    strokeEnd++;
                }
                final boolean isActiveTailStroke = (strokeEnd == trailSize);
                drawSingleStrokeHardware(canvas, paint, params, rgb, useVertices,
                        eventTimes, xCoords, yCoords, strokeStart, strokeEnd,
                        sinceDown, nowMs, isActiveTailStroke);
                strokeStart = strokeEnd;
            }
            if (DEBUG_SHOW_POINTS) {
                debugDrawPoints(canvas, startIndex, trailSize, paint);
            }
        }

        final int newSize = compactArraysLocked(startIndex, trailSize);
        if (newSize == 0) {
            mHasLiveTip = false;
        }
        return newSize > 0;
    }

    private void ensureStrokeCapacity(final int needed) {
        if (mStrokeX.length >= needed) return;
        int cap = mStrokeX.length;
        while (cap < needed) cap <<= 1;
        mStrokeX = new float[cap];
        mStrokeY = new float[cap];
        mStrokeR = new float[cap];
        mStrokeColor = new int[cap];
    }

    private void ensureStripCapacity(final int numVertices) {
        if (mStripColors.length >= numVertices && mStripVerts.length >= numVertices * 2) return;
        int cap = mStripColors.length;
        while (cap < numVertices) cap <<= 1;
        mStripColors = new int[cap];
        mStripVerts = new float[cap * 2];
    }

    private void drawSingleStrokeHardware(final Canvas canvas, final Paint paint,
            final GestureTrailDrawingParams params, final int rgb, final boolean useVertices,
            final int[] eventTimes, final int[] xCoords, final int[] yCoords,
            final int strokeStart, final int strokeEnd, final int sinceDown,
            final long nowMs, final boolean isActiveTailStroke) {
        final int rawCount = strokeEnd - strokeStart + 1; // +1 for optional live fingertip point
        ensureStrokeCapacity(rawCount);

        int count = 0;
        for (int i = strokeStart; i < strokeEnd; i++) {
            final float x = getXCoordValue(xCoords[i]);
            final float y = yCoords[i];
            if (count > 0) {
                final float dx = x - mStrokeX[count - 1];
                final float dy = y - mStrokeY[count - 1];
                if (dx * dx + dy * dy < 0.25f) {
                    // Refresh radius & alpha at the coincident tip so newest timestamp wins
                    final int elapsed = sinceDown - eventTimes[i];
                    mStrokeR[count - 1] = Math.max(0.5f, getWidth(elapsed, params) * 0.5f * params.mTrailBodyRatio);
                    mStrokeColor[count - 1] = (getAlpha(elapsed, params) << 24) | rgb;
                    continue;
                }
            }
            final int elapsed = sinceDown - eventTimes[i];
            mStrokeX[count] = x;
            mStrokeY[count] = y;
            mStrokeR[count] = Math.max(0.5f, getWidth(elapsed, params) * 0.5f * params.mTrailBodyRatio);
            mStrokeColor[count] = (getAlpha(elapsed, params) << 24) | rgb;
            count++;
        }

        // Append raw fingertip live tip for zero-gap tracking when finger hasn't crossed next sample distance.
        if (isActiveTailStroke && mHasLiveTip && count > 0) {
            final int tipElapsed = (int)(nowMs - mLiveTipTimeMs);
            if (tipElapsed >= 0 && tipElapsed < params.mTrailLingerDuration) {
                final float dx = mLiveTipX - mStrokeX[count - 1];
                final float dy = mLiveTipY - mStrokeY[count - 1];
                if (dx * dx + dy * dy >= 1.0f) {
                    mStrokeX[count] = mLiveTipX;
                    mStrokeY[count] = mLiveTipY;
                    mStrokeR[count] = Math.max(0.5f, getWidth(tipElapsed, params) * 0.5f * params.mTrailBodyRatio);
                    mStrokeColor[count] = (getAlpha(tipElapsed, params) << 24) | rgb;
                    count++;
                }
            }
        }

        if (count == 0) return;

        if (count == 1) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(mStrokeColor[0]);
            canvas.drawCircle(mStrokeX[0], mStrokeY[0], mStrokeR[0], paint);
            return;
        }

        if (!useVertices) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 1; i < count; i++) {
                paint.setStrokeWidth((mStrokeR[i - 1] + mStrokeR[i]));
                paint.setColor(mStrokeColor[i]);
                canvas.drawLine(mStrokeX[i - 1], mStrokeY[i - 1], mStrokeX[i], mStrokeY[i], paint);
            }
            paint.setStyle(Paint.Style.FILL);
            return;
        }

        // Direct GPU triangle-strip rasterization: zero overlapping joints, smooth per-vertex alpha & width taper.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setAlpha(255);

        ensureStripCapacity(count * 2);
        int vertIndex = 0; // count of vertices emitted into current sub-strip
        float prevTx = 0f;
        float prevTy = 0f;

        for (int i = 0; i < count; i++) {
            float tx;
            float ty;
            boolean splitBefore = false;
            if (i == 0) {
                final float dx = mStrokeX[1] - mStrokeX[0];
                final float dy = mStrokeY[1] - mStrokeY[0];
                final float len = (float)Math.hypot(dx, dy);
                tx = len > 1e-4f ? dx / len : 1f;
                ty = len > 1e-4f ? dy / len : 0f;
            } else if (i == count - 1) {
                final float dx = mStrokeX[i] - mStrokeX[i - 1];
                final float dy = mStrokeY[i] - mStrokeY[i - 1];
                final float len = (float)Math.hypot(dx, dy);
                tx = len > 1e-4f ? dx / len : prevTx;
                ty = len > 1e-4f ? dy / len : prevTy;
            } else {
                final float dx1 = mStrokeX[i] - mStrokeX[i - 1];
                final float dy1 = mStrokeY[i] - mStrokeY[i - 1];
                final float l1 = (float)Math.hypot(dx1, dy1);
                final float t1x = l1 > 1e-4f ? dx1 / l1 : prevTx;
                final float t1y = l1 > 1e-4f ? dy1 / l1 : prevTy;

                final float dx2 = mStrokeX[i + 1] - mStrokeX[i];
                final float dy2 = mStrokeY[i + 1] - mStrokeY[i];
                final float l2 = (float)Math.hypot(dx2, dy2);
                final float t2x = l2 > 1e-4f ? dx2 / l2 : t1x;
                final float t2y = l2 > 1e-4f ? dy2 / l2 : t1y;

                final float dot = t1x * t2x + t1y * t2y;
                if (dot < -0.35f) {
                    // Sharp hairpin reversal: finish current strip cleanly along incoming tangent,
                    // then start a fresh strip along outgoing tangent to prevent bowtie twists.
                    final float nx1 = -t1y * mStrokeR[i];
                    final float ny1 = t1x * mStrokeR[i];
                    emitPair(vertIndex, mStrokeX[i], mStrokeY[i], nx1, ny1, mStrokeColor[i]);
                    vertIndex += 2;
                    if (vertIndex >= 4) {
                        canvas.drawVertices(Canvas.VertexMode.TRIANGLE_STRIP, vertIndex * 2,
                                mStripVerts, 0, null, 0, mStripColors, 0, null, 0, 0, paint);
                    }
                    vertIndex = 0;
                    tx = t2x;
                    ty = t2y;
                    splitBefore = true;
                } else {
                    final float sx = t1x + t2x;
                    final float sy = t1y + t2y;
                    final float sl = (float)Math.hypot(sx, sy);
                    tx = sl > 1e-4f ? sx / sl : t2x;
                    ty = sl > 1e-4f ? sy / sl : t2y;
                }
            }

            final float r = mStrokeR[i];
            final float nx = -ty * r;
            final float ny = tx * r;
            emitPair(vertIndex, mStrokeX[i], mStrokeY[i], nx, ny, mStrokeColor[i]);
            vertIndex += 2;
            prevTx = tx;
            prevTy = ty;

            // Semicircular front cap at the fingertip (non-overlapping with body strip).
            if (i == count - 1) {
                if (vertIndex >= 4) {
                    canvas.drawVertices(Canvas.VertexMode.TRIANGLE_STRIP, vertIndex * 2,
                            mStripVerts, 0, null, 0, mStripColors, 0, null, 0, 0, paint);
                }
                if (r >= 1.5f) {
                    drawSemicircleCap(canvas, paint, mStrokeX[i], mStrokeY[i], r, tx, ty, mStrokeColor[i]);
                }
            } else if (splitBefore && r >= 1.5f) {
                // Subtle rounded cap at hairpin apex.
                drawSemicircleCap(canvas, paint, mStrokeX[i], mStrokeY[i], r, -tx, -ty, mStrokeColor[i]);
            }
        }
    }

    private void emitPair(final int vertIndex, final float cx, final float cy,
            final float nx, final float ny, final int color) {
        final int f = vertIndex * 2;
        mStripVerts[f] = cx + nx;
        mStripVerts[f + 1] = cy + ny;
        mStripVerts[f + 2] = cx - nx;
        mStripVerts[f + 3] = cy - ny;
        mStripColors[vertIndex] = color;
        mStripColors[vertIndex + 1] = color;
    }

    private void drawSemicircleCap(final Canvas canvas, final Paint paint,
            final float cx, final float cy, final float radius,
            final float tx, final float ty, final int color) {
        final float baseAngle = (float)Math.atan2(ty, tx) - (float)(Math.PI * 0.5);
        mCapVerts[0] = cx;
        mCapVerts[1] = cy;
        mCapColors[0] = color;
        for (int s = 0; s <= CAP_ARC_SEGMENTS; s++) {
            final float angle = baseAngle + (float)(Math.PI * s / CAP_ARC_SEGMENTS);
            final int v = s + 1;
            mCapVerts[v * 2] = cx + (float)Math.cos(angle) * radius;
            mCapVerts[v * 2 + 1] = cy + (float)Math.sin(angle) * radius;
            mCapColors[v] = color;
        }
        canvas.drawVertices(Canvas.VertexMode.TRIANGLE_FAN, (CAP_ARC_SEGMENTS + 2) * 2,
                mCapVerts, 0, null, 0, mCapColors, 0, null, 0, 0, paint);
    }

    private int compactArraysLocked(final int startIndex, final int trailSize) {
        final int newSize = trailSize - startIndex;
        if (newSize < startIndex) {
            final int[] eventTimes = mEventTimes.getPrimitiveArray();
            final int[] xCoords = mXCoordinates.getPrimitiveArray();
            final int[] yCoords = mYCoordinates.getPrimitiveArray();
            final int[] pointTypes = mPointTypes.getPrimitiveArray();
            mTrailStartIndex = 0;
            if (newSize > 0) {
                System.arraycopy(eventTimes, startIndex, eventTimes, 0, newSize);
                System.arraycopy(xCoords, startIndex, xCoords, 0, newSize);
                System.arraycopy(yCoords, startIndex, yCoords, 0, newSize);
                if (DEBUG_SHOW_POINTS) {
                    System.arraycopy(pointTypes, startIndex, pointTypes, 0, newSize);
                }
            }
            mEventTimes.setLength(newSize);
            mXCoordinates.setLength(newSize);
            mYCoordinates.setLength(newSize);
            if (DEBUG_SHOW_POINTS) {
                mPointTypes.setLength(newSize);
            }
            mLastInterpolatedDrawIndex = Math.max(mLastInterpolatedDrawIndex - startIndex, 0);
        }
        return newSize;
    }

    /**
     * Draw gesture trail (software canvas fallback).
     */
    public boolean drawGestureTrail(final Canvas canvas, final Paint paint,
            final Rect outBoundsRect, final GestureTrailDrawingParams params) {
        synchronized (mEventTimes) {
            return drawGestureTrailLocked(canvas, paint, outBoundsRect, params);
        }
    }

    private boolean drawGestureTrailLocked(final Canvas canvas, final Paint paint,
            final Rect outBoundsRect, final GestureTrailDrawingParams params) {
        // Initialize bounds rectangle.
        outBoundsRect.setEmpty();
        final int trailSize = mEventTimes.getLength();
        if (trailSize == 0) {
            return false;
        }

        final int[] eventTimes = mEventTimes.getPrimitiveArray();
        final int[] xCoords = mXCoordinates.getPrimitiveArray();
        final int[] yCoords = mYCoordinates.getPrimitiveArray();
        final int sinceDown = (int)(SystemClock.uptimeMillis() - mCurrentTimeBase);
        int startIndex;
        for (startIndex = mTrailStartIndex; startIndex < trailSize; startIndex++) {
            final int elapsedTime = sinceDown - eventTimes[startIndex];
            // Skip too old trail points.
            if (elapsedTime < params.mTrailLingerDuration) {
                break;
            }
        }
        mTrailStartIndex = startIndex;

        if (startIndex < trailSize) {
            paint.setColor(params.mTrailColor);
            paint.setStyle(Paint.Style.FILL);
            final RoundedLine roundedLine = mRoundedLine;
            int p1x = getXCoordValue(xCoords[startIndex]);
            int p1y = yCoords[startIndex];
            final int lastTime = sinceDown - eventTimes[startIndex];
            float r1 = getWidth(lastTime, params) / 2.0f;
            for (int i = startIndex + 1; i < trailSize; i++) {
                final int elapsedTime = sinceDown - eventTimes[i];
                final int p2x = getXCoordValue(xCoords[i]);
                final int p2y = yCoords[i];
                final float r2 = getWidth(elapsedTime, params) / 2.0f;
                // Draw trail line only when the current point isn't a down point.
                if (!isDownEventXCoord(xCoords[i])) {
                    final float body1 = r1 * params.mTrailBodyRatio;
                    final float body2 = r2 * params.mTrailBodyRatio;
                    final Path path = roundedLine.makePath(p1x, p1y, body1, p2x, p2y, body2);
                    if (!path.isEmpty()) {
                        roundedLine.getBounds(mRoundedLineBounds);
                        if (params.mTrailShadowEnabled) {
                            final float shadow2 = r2 * params.mTrailShadowRatio;
                            paint.setShadowLayer(shadow2, 0.0f, 0.0f, params.mTrailColor);
                            final int shadowInset = -(int)Math.ceil(shadow2);
                            mRoundedLineBounds.inset(shadowInset, shadowInset);
                        }
                        // Take union for the bounds.
                        outBoundsRect.union(mRoundedLineBounds);
                        final int alpha = getAlpha(elapsedTime, params);
                        paint.setAlpha(alpha);
                        canvas.drawPath(path, paint);
                    }
                }
                p1x = p2x;
                p1y = p2y;
                r1 = r2;
            }
            if (DEBUG_SHOW_POINTS) {
                debugDrawPoints(canvas, startIndex, trailSize, paint);
            }
        }

        final int newSize = compactArraysLocked(startIndex, trailSize);
        return newSize > 0;
    }

    private void debugDrawPoints(final Canvas canvas, final int startIndex, final int endIndex,
            final Paint paint) {
        final int[] xCoords = mXCoordinates.getPrimitiveArray();
        final int[] yCoords = mYCoordinates.getPrimitiveArray();
        final int[] pointTypes = mPointTypes.getPrimitiveArray();
        // {@link Paint} that is zero width stroke and anti alias off draws exactly 1 pixel.
        paint.setAntiAlias(false);
        paint.setStrokeWidth(0);
        for (int i = startIndex; i < endIndex; i++) {
            final int pointType = pointTypes[i];
            if (pointType == POINT_TYPE_INTERPOLATED) {
                paint.setColor(Color.RED);
            } else if (pointType == POINT_TYPE_SAMPLED) {
                paint.setColor(0xFFA000FF);
            } else {
                paint.setColor(Color.GREEN);
            }
            canvas.drawPoint(getXCoordValue(xCoords[i]), yCoords[i], paint);
        }
        paint.setAntiAlias(true);
    }
}
