// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard;

import android.content.res.Resources;
import android.os.SystemClock;

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;

public class TouchpadHandler {
    private KeyboardActionListener mListener;
    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private static boolean sTouchpadModeActive = false;
    private boolean mInTouchpadMode = false;
    private boolean mHasVibrated = false;
    private boolean mIsScrolling = false;
    // Bottom-edge scroll stays suppressed until the finger first leaves the bottom 20% zone
    // (where spacebar long-press starts), preventing immediate ARROW_DOWN hijack.
    private boolean mHasLeftBottomZone = false;

    private static final float EDGE_THRESHOLD_PERCENTAGE = 0.1f;          // Screen edge threshold percentage
    private static final float BOTTOM_ESCAPE_ZONE_PERCENTAGE = 0.2f;      // Bottom 20% zone to leave before bottom-edge scroll arms
    private static final float TOUCHPAD_ACCELERATION_FACTOR_DP = 18.0f;   // Lower = more acceleration (density-independent)
    private static final float MIN_MOVE_THRESHOLD_DP = 4.0f;              // Sensitivity 100 (Very Fast)
    private static final float MAX_MOVE_THRESHOLD_DP = 24.0f;             // Sensitivity 0 (Very Slow)
    private static final float EDGE_ACCELERATION_FACTOR = 0.95f;
    private static final int MIN_EDGE_ACCELERATION_DELAY = 20;

    private long mTouchpadActivationTime;
    private int mTouchpadLastX, mTouchpadLastY;
    private long mCurrentScrollDelay;

    // Accumulators for fractional movement
    private int mTouchpadAccX = 0;
    private int mTouchpadAccY = 0;

    private static final int DIRECTION_UP = 1;
    private static final int DIRECTION_DOWN = 2;
    private static final int DIRECTION_LEFT = 3;
    private static final int DIRECTION_RIGHT = 4;
    private int mCurrentScrollDirection = 0;

    public static void setTouchpadModeActive(boolean active) {
        sTouchpadModeActive = active;
    }

    public void disableTouchpadMode() {
        if (!mInTouchpadMode) return;
        stopEdgeScrolling();
        stopHapticRunnable();
        mInTouchpadMode = false;
        mHasLeftBottomZone = false;
        mTouchpadAccX = 0;
        mTouchpadAccY = 0;
        sTouchpadModeActive = false;
        if (mListener != null) {
            mListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_OFF);
        }
        mListener = null;
    }

    public void enableTouchpadMove(int x, int y, KeyboardActionListener listener) {
        if (!sTouchpadModeActive) return;

        // Initialize
        if (!mInTouchpadMode) {
            mListener = listener;
            mInTouchpadMode = true;
            mHasVibrated = false;
            mHasLeftBottomZone = false;
            mTouchpadAccX = 0;
            mTouchpadAccY = 0;
            mTouchpadLastX = x;
            mTouchpadLastY = y;
            mTouchpadActivationTime = SystemClock.elapsedRealtime();
            mListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_ON);
            SettingsValues sv = Settings.getValues();
            mHandler.postDelayed(mHapticRunnable, sv.mKeyLongpressTimeout);
            return;
        }

        onMove(x, y);
    }

    /** start trackpad mode right away, e.g. after a long press (which already gave haptic feedback) */
    public void startTouchpadNow(int x, int y, KeyboardActionListener listener) {
        mListener = listener;
        mInTouchpadMode = true;
        mHasVibrated = true;
        mHasLeftBottomZone = false;
        mTouchpadAccX = 0;
        mTouchpadAccY = 0;
        mTouchpadLastX = x;
        mTouchpadLastY = y;
        // no debounce: the long press timeout has already passed
        mTouchpadActivationTime = SystemClock.elapsedRealtime() - Settings.getValues().mKeyLongpressTimeout;
        mListener.onCustomRequest(KeyboardActionListener.CustomAction.TOUCHPAD_ON);
        mListener.onCustomRequest(KeyboardActionListener.CustomAction.PERFORM_HAPTIC);
    }

    private float getDisplayDensity() {
        final MainKeyboardView view = KeyboardSwitcher.getInstance().getMainKeyboardView();
        if (view != null) {
            return view.getResources().getDisplayMetrics().density;
        }
        return Resources.getSystem().getDisplayMetrics().density;
    }

    private void onMove(int x, int y) {
        SettingsValues sv = Settings.getValues();

        // Debounce
        if (SystemClock.elapsedRealtime() - mTouchpadActivationTime < sv.mKeyLongpressTimeout) {
            mTouchpadLastX = x;
            mTouchpadLastY = y;
            return;
        }

        // Edge Scrolling
        if (sv.mTouchpadEdgeScroll && handleEdgeScrolling(x, y)) {
            return;
        }

        // In touchpad mode - track both horizontal and vertical movement for 2D cursor control
        int deltaX = x - mTouchpadLastX;
        int deltaY = y - mTouchpadLastY;

        mTouchpadLastX = x;
        mTouchpadLastY = y;

        final float density = getDisplayDensity();
        final float accelerationFactorPx = Math.max(1.0f, TOUCHPAD_ACCELERATION_FACTOR_DP * density);

        if (Math.abs(deltaX) > Math.abs(deltaY)) {
            // Horizontal move, X only
            float accFactorX = 1.0f + (Math.abs(deltaX) / accelerationFactorPx);
            mTouchpadAccX += (int) (deltaX * accFactorX);
            mTouchpadAccY = 0;
        } else {
            // Vertical move, Y only
            float accFactorY = 1.0f + (Math.abs(deltaY) / accelerationFactorPx);
            mTouchpadAccY += (int) (deltaY * accFactorY);
            mTouchpadAccX = 0;
        }

        // Calculate density-independent dynamic threshold based on sensitivity setting (0-100):
        // 0 -> 24dp (Very Slow), 50 -> 14dp (Default), 100 -> 4dp (Very Fast)
        int sensitivity = Math.max(0, Math.min(100, Settings.getInstance().getCurrent().mTouchpadSensitivity));
        float thresholdDp = MAX_MOVE_THRESHOLD_DP - (sensitivity / 100.0f) * (MAX_MOVE_THRESHOLD_DP - MIN_MOVE_THRESHOLD_DP);
        int moveThreshold = Math.max(1, Math.round(thresholdDp * density));

        // Handle horizontal movement with accumulator
        while (Math.abs(mTouchpadAccX) >= moveThreshold) {
            boolean positive = mTouchpadAccX > 0;
            int direction = positive ? KeyCode.ARROW_RIGHT : KeyCode.ARROW_LEFT;
            mListener.onCodeInput(direction, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
            mTouchpadAccX -= (positive ? moveThreshold : -moveThreshold);
        }

        // Handle vertical movement with accumulator
        while (Math.abs(mTouchpadAccY) >= moveThreshold) {
            boolean positive = mTouchpadAccY > 0;
            int direction = positive ? KeyCode.ARROW_DOWN : KeyCode.ARROW_UP;
            mListener.onCodeInput(direction, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);
            mTouchpadAccY -= (positive ? moveThreshold : -moveThreshold);
        }
    }

    private final Runnable mHapticRunnable = () -> {
        if (!mHasVibrated && mListener != null) {
            mListener.onCustomRequest(KeyboardActionListener.CustomAction.PERFORM_HAPTIC);
            mHasVibrated = true;
        }
    };

    private void stopHapticRunnable() {
        mHasVibrated = false;
        mHandler.removeCallbacks(mHapticRunnable);
    }

    private final Runnable mScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsScrolling && mListener != null) {
                int keyCode = getKeyCodeForDirection(mCurrentScrollDirection);

                mListener.onCodeInput(keyCode, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false);

                mCurrentScrollDelay = Math.max(MIN_EDGE_ACCELERATION_DELAY, (long) (mCurrentScrollDelay * EDGE_ACCELERATION_FACTOR));

                mHandler.postDelayed(this, mCurrentScrollDelay);
            }
        }
    };

    private boolean handleEdgeScrolling(int x, int y) {
        Keyboard currentKeyboard = KeyboardSwitcher.getInstance().getKeyboard();
        if (currentKeyboard == null) return false;

        int keyboardHeight = currentKeyboard.mBaseHeight;
        int keyboardWidth = currentKeyboard.mBaseWidth;
        if (keyboardHeight <= 0 || keyboardWidth <= 0) return false;

        int thresholdX = (int) (keyboardWidth * EDGE_THRESHOLD_PERCENTAGE);
        int thresholdY = (int) (keyboardHeight * EDGE_THRESHOLD_PERCENTAGE);
        int bottomEscapeY = (int) (keyboardHeight * (1.0f - BOTTOM_ESCAPE_ZONE_PERCENTAGE));
        if (y < bottomEscapeY) {
            mHasLeftBottomZone = true;
        }

        if (y <= thresholdY) {
            mCurrentScrollDirection = DIRECTION_UP;
            startEdgeScrolling();
            return true;
        } else if (mHasLeftBottomZone && y >= (keyboardHeight - thresholdY)) {
            mCurrentScrollDirection = DIRECTION_DOWN;
            startEdgeScrolling();
            return true;
        } else if (x <= thresholdX) {
            mCurrentScrollDirection = DIRECTION_LEFT;
            startEdgeScrolling();
            return true;
        } else if (x >= (keyboardWidth - thresholdX)) {
            mCurrentScrollDirection = DIRECTION_RIGHT;
            startEdgeScrolling();
            return true;
        } else {
            stopEdgeScrolling();
            return false;
        }
    }

    private void startEdgeScrolling() {
        if (!mIsScrolling) {
            mIsScrolling = true;
            mCurrentScrollDelay = getBaseScrollDelay();
            mHandler.removeCallbacks(mScrollRunnable);
            mHandler.post(mScrollRunnable);
        }
    }

    private void stopEdgeScrolling() {
        mIsScrolling = false;
        mHandler.removeCallbacks(mScrollRunnable);
    }

    private long getBaseScrollDelay() {
        int sensitivity = Settings.getInstance().getCurrent().mTouchpadSensitivity;

        // Calculates the base scroll delay based on user sensitivity (range: 0-100).
        // Maps sensitivity 0 to 300ms (slowest) and 100 to 50ms (fastest).
        return 300 - (long) (sensitivity * 2.5f);
    }

    private int getKeyCodeForDirection(int direction) {
        return switch (direction) {
            case DIRECTION_UP -> KeyCode.ARROW_UP;
            case DIRECTION_DOWN -> KeyCode.ARROW_DOWN;
            case DIRECTION_LEFT -> KeyCode.ARROW_LEFT;
            case DIRECTION_RIGHT -> KeyCode.ARROW_RIGHT;
            default -> KeyCode.UNSPECIFIED;
        };
    }
}
