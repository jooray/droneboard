/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.inputlogic;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;

import helium314.keyboard.latin.InputStyle;
import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.WordComposer;
import helium314.keyboard.latin.common.InputPointers;
import helium314.keyboard.latin.suggestions.SuggestedWords;

import java.util.ArrayDeque;

/**
 * A helper to manage deferred tasks for the input logic.
 */
class InputLogicHandler implements Handler.Callback {
    final Handler mNonUIThreadHandler;
    final LatinIME.UIHandler mLatinIMEHandler;
    final InputLogic mInputLogic;
    private final Object mLock = new Object();
    private boolean mInBatchInput; // synchronized using {@link #mLock}.
    // counts gestures, so that a result can tell whether it belongs to the gesture in progress; guarded by mLock
    private int mGestureId;
    // incremented on every reset() so a decode still running on the worker thread cannot publish into a new field; guarded by mLock
    private int mResetGeneration;
    // the last non-empty preview of the gesture in progress; guarded by mLock
    private SuggestedWords mGesturePreview;
    // the text before the cursor of the gesture in progress, taken on the UI thread; guarded by mLock
    private InputLogic.BatchContextSnapshot mBatchContext;
    // the gesture whose tail is being decoded or waits for its commit on the UI thread, 0 if none; one at a
    // time, so that the next tail is decoded with the word of this one in its context; guarded by mLock
    private int mTailInFlightGestureId;
    private long mTailInFlightSince;
    // tails of later gestures that wait for mTailInFlightGestureId to be committed; guarded by mLock
    private final ArrayDeque<DeferredTail> mDeferredTails = new ArrayDeque<>();
    // a tail that has not been committed after this long is considered lost (it never blocks later swipes)
    private static final long TAIL_IN_FLIGHT_TIMEOUT_MS = 10_000;

    private static final class DeferredTail {
        final WordComposer composer;
        final int sequenceNumber;
        final int gestureId;

        DeferredTail(final WordComposer composer, final int sequenceNumber, final int gestureId) {
            this.composer = composer;
            this.sequenceNumber = sequenceNumber;
            this.gestureId = gestureId;
        }
    }

    private static final int MSG_GET_SUGGESTED_WORDS = 1;
    private static final int MSG_UPDATE_BATCH = 2;
    private static final int MSG_TAIL_BATCH = 3;

    public InputLogicHandler(final LatinIME.UIHandler latinIMEHandler, final InputLogic inputLogic) {
        final HandlerThread handlerThread = new HandlerThread(
                InputLogicHandler.class.getSimpleName());
        handlerThread.setDaemon(true);
        handlerThread.start();
        mNonUIThreadHandler = new Handler(handlerThread.getLooper(), this);
        mLatinIMEHandler = latinIMEHandler;
        mInputLogic = inputLogic;
    }

    public void reset() {
        reset(true);
    }

    /**
     * @param hard true for a new field (or the end of input): everything queued or running is dropped. false for an
     *     input restart in the same field (many apps restart after each change of the text, e.g. right after a
     *     swiped word was committed): only stale typing suggestions and previews go, the tails of gestures still
     *     decode and commit in order, as in 0.3.0 where a restart never dropped a gesture in flight.
     */
    public void reset(final boolean hard) {
        if (!hard) {
            mNonUIThreadHandler.removeMessages(MSG_GET_SUGGESTED_WORDS);
            mNonUIThreadHandler.removeMessages(MSG_UPDATE_BATCH);
            return;
        }
        mNonUIThreadHandler.removeCallbacksAndMessages(null);
        if (mLatinIMEHandler != null) {
            mLatinIMEHandler.cancelBatchInputResults();
        }
        synchronized (mLock) {
            // the queued tails were removed above, and anything still running on the worker will be dropped
            ++mResetGeneration;
            mInBatchInput = false;
            mGesturePreview = null;
            mBatchContext = null;
            mTailInFlightGestureId = 0;
            mDeferredTails.clear();
        }
    }

    /**
     * Handle a message.
     * @see android.os.Handler.Callback#handleMessage(android.os.Message)
     */
    // Called on the Non-UI handler thread by the Handler code.
    @Override
    public boolean handleMessage(final Message msg) {
        if (msg.what == MSG_GET_SUGGESTED_WORDS
                || msg.what == MSG_UPDATE_BATCH
                || msg.what == MSG_TAIL_BATCH) {
            ((Runnable) msg.obj).run();
        }
        return true;
    }

    // Called on the UI thread by InputLogic.
    public void onStartBatchInput() {
        // the context of the whole gesture, for the previews and the tail (the text does not change meanwhile,
        // except when the word of an earlier gesture is committed, see onTailBatchInputCommitted)
        final InputLogic.BatchContextSnapshot context = mInputLogic != null
                ? mInputLogic.captureContextForBatchInput(null) : null;
        synchronized (mLock) {
            mInBatchInput = true;
            ++mGestureId;
            mGesturePreview = null;
            mBatchContext = context;
        }
        mNonUIThreadHandler.removeMessages(MSG_UPDATE_BATCH);
        if (mInputLogic != null) {
            mInputLogic.prefetchNeuralForBatchInput(context);
        }
    }

    /** Whether a gesture started after the one whose tail is in flight. Called on the UI thread when it commits. */
    boolean hasNewerGestureThanTailInFlight() {
        synchronized (mLock) {
            // and that gesture still goes on, or waits with its tail (not when it was canceled)
            return mTailInFlightGestureId != 0 && mTailInFlightGestureId != mGestureId
                    && (mInBatchInput || !mDeferredTails.isEmpty());
        }
    }

    /** Whether [gestureId] is still the tail expected to commit (0 = direct/untagged call). */
    boolean isTailExpected(final int gestureId) {
        if (gestureId == 0) return true;
        synchronized (mLock) {
            return mTailInFlightGestureId == gestureId;
        }
    }

    void onTailBatchInputCommitted() {
        onTailBatchInputCommitted(0);
    }

    /**
     * The tail in flight has been committed (or turned out empty). Called on the UI thread. The context of the
     * gesture in progress is taken again so that it contains the committed word, and the next waiting tail is
     * sent to the decoder with a fresh context.
     */
    void onTailBatchInputCommitted(final int gestureId) {
        final boolean refresh;
        final DeferredTail next;
        final int resetGeneration;
        synchronized (mLock) {
            if (gestureId != 0 && mTailInFlightGestureId != gestureId) return;
            mTailInFlightGestureId = 0;
            refresh = mInBatchInput;
            next = mDeferredTails.poll();
            resetGeneration = mResetGeneration;
            if (next != null) markTailInFlightLocked(next.gestureId);
        }
        if (mInputLogic == null) return;
        if (refresh || next != null) {
            final InputLogic.BatchContextSnapshot context = mInputLogic.captureContextForBatchInput(null);
            if (refresh) {
                synchronized (mLock) {
                    if (mInBatchInput && mResetGeneration == resetGeneration) mBatchContext = context;
                }
            }
            if (next != null) {
                mInputLogic.refreshDeferredBatchCapsMode(next.composer);
                sendTail(next.composer, context, next.sequenceNumber, next.gestureId, resetGeneration);
            }
        }
    }

    private void markTailInFlightLocked(final int gestureId) {
        mTailInFlightGestureId = gestureId;
        mTailInFlightSince = SystemClock.uptimeMillis();
    }

    private void sendTail(final WordComposer composer, final InputLogic.BatchContextSnapshot context,
            final int sequenceNumber, final int gestureId, final int resetGeneration) {
        sendBatchTask(composer, context, sequenceNumber, true, gestureId, resetGeneration);
    }

    public boolean isInBatchInput() {
        synchronized (mLock) {
            return mInBatchInput;
        }
    }

    /**
     * Fetch suggestions corresponding to an update of a batch input.
     * @param batchPointers the updated pointers, including the part that was passed last time.
     * @param sequenceNumber the sequence number associated with this batch input.
     * @param isTailBatchInput true if this is the end of a batch input, false if it's an update.
     */
    // This method can be called from any thread and will see to it that the correct threads
    // are used for parts that require it. This method will send a message to the Non-UI handler
    // thread to pull suggestions, and get the inlined callback to get called on the Non-UI
    // handler thread. If this is the end of a batch input, the callback will then proceed to
    // send a message to the UI handler in LatinIME so that showing suggestions can be done on
    // the UI thread.
    private void updateBatchInput(final InputPointers batchPointers,
            final int sequenceNumber, final boolean isTailBatchInput) {
        final int gestureId;
        final int resetGeneration;
        final InputPointers pointersSnapshot;
        final WordComposer batchComposerSnapshot;
        final InputLogic.BatchContextSnapshot contextSnapshot;
        synchronized (mLock) {
            if (!mInBatchInput) {
                // Batch input has ended or canceled while the message was being delivered.
                return;
            }
            // Purge any queued beam=32 preview so a stale preview never delays a newer preview
            // or the final finger-up TAIL_BATCH commit.
            mNonUIThreadHandler.removeMessages(MSG_UPDATE_BATCH);
            gestureId = mGestureId;
            resetGeneration = mResetGeneration;
            pointersSnapshot = new InputPointers(Math.max(1, batchPointers.getPointerSize()));
            pointersSnapshot.copy(batchPointers);
            if (isTailBatchInput && mInputLogic != null && mInputLogic.mWordComposer != null) {
                mInputLogic.mWordComposer.setBatchInputPointers(pointersSnapshot);
            }
            batchComposerSnapshot = mInputLogic != null
                    ? mInputLogic.snapshotWordComposerForBatch(pointersSnapshot)
                    : null;
            contextSnapshot = mBatchContext;
            if (isTailBatchInput) {
                if (mTailInFlightGestureId != 0
                        && SystemClock.uptimeMillis() - mTailInFlightSince > TAIL_IN_FLIGHT_TIMEOUT_MS) {
                    // its commit is lost: queue this tail behind any waiting ones and release only the head
                    // so queued tails still decode one at a time instead of overtaking each other
                    mDeferredTails.add(new DeferredTail(batchComposerSnapshot, sequenceNumber, gestureId));
                    final DeferredTail first = mDeferredTails.poll();
                    markTailInFlightLocked(first.gestureId);
                    sendTail(first.composer, contextSnapshot, first.sequenceNumber, first.gestureId, resetGeneration);
                    return;
                }
                if (mTailInFlightGestureId != 0) {
                    // the word of an earlier gesture is not committed yet: decode this tail once it is, so
                    // that its context contains that word
                    mDeferredTails.add(new DeferredTail(batchComposerSnapshot, sequenceNumber, gestureId));
                    return;
                }
                markTailInFlightLocked(gestureId);
            }
        }
        sendBatchTask(batchComposerSnapshot, contextSnapshot, sequenceNumber, isTailBatchInput, gestureId, resetGeneration);
    }

    private void sendBatchTask(final WordComposer batchComposerSnapshot,
            final InputLogic.BatchContextSnapshot contextSnapshot, final int sequenceNumber,
            final boolean isTailBatchInput, final int gestureId, final int resetGeneration) {
        final int what = isTailBatchInput ? MSG_TAIL_BATCH : MSG_UPDATE_BATCH;
        final Runnable task = () -> {
            if (mInputLogic == null) return;
            mInputLogic.getSuggestedWordsForBatchInput(
                    batchComposerSnapshot,
                    isTailBatchInput ? InputStyle.TAIL_BATCH : InputStyle.UPDATE_BATCH,
                    contextSnapshot,
                    sequenceNumber,
                    suggestedWords -> showGestureSuggestionsWithPreviewVisuals(
                            suggestedWords, isTailBatchInput, gestureId, resetGeneration));
        };
        mNonUIThreadHandler.obtainMessage(what, task).sendToTarget();
    }

    /**
     * The result of one decoding step of the gesture [gestureId]: shown as a preview, and committed when it is
     * the tail. When the decoder found nothing, the last preview of the same gesture is used instead. Nothing
     * else may stand in for it: not what the strip showed before the gesture (next-word predictions, which a
     * swipe that could not be read used to commit), and not the preview of an earlier gesture.
     */
    // Runs on the Non-UI thread, no matter what thread updateBatchInput was originally called on.
    void showGestureSuggestionsWithPreviewVisuals(final SuggestedWords suggestedWordsForBatchInput,
            final boolean isTailBatchInput, final int gestureId) {
        final int resetGeneration;
        synchronized (mLock) {
            resetGeneration = mResetGeneration;
        }
        showGestureSuggestionsWithPreviewVisuals(suggestedWordsForBatchInput, isTailBatchInput, gestureId, resetGeneration);
    }

    void showGestureSuggestionsWithPreviewVisuals(final SuggestedWords suggestedWordsForBatchInput,
            final boolean isTailBatchInput, final int gestureId, final int resetGeneration) {
        final SuggestedWords suggestedWordsToShowSuggestions;
        synchronized (mLock) {
            if (resetGeneration != mResetGeneration) return; // reset() ran while this decode was in flight
            // a result that belongs to an earlier gesture (its tail was still being decoded when the next
            // swipe began) is still shown and committed, but leaves the state of the running gesture alone
            final boolean current = gestureId == mGestureId;
            if (!current && !isTailBatchInput) return; // a late preview of a finished gesture: nothing to show
            if (!suggestedWordsForBatchInput.isEmpty()) {
                suggestedWordsToShowSuggestions = suggestedWordsForBatchInput;
                if (current && !isTailBatchInput) mGesturePreview = suggestedWordsForBatchInput;
            } else if (current && mGesturePreview != null) {
                suggestedWordsToShowSuggestions = mGesturePreview;
            } else {
                suggestedWordsToShowSuggestions = suggestedWordsForBatchInput;
            }
            if (current && isTailBatchInput) {
                mInBatchInput = false;
                mGesturePreview = null;
            }
        }
        mLatinIMEHandler.showGesturePreviewAndSetSuggestions(suggestedWordsToShowSuggestions, isTailBatchInput);
        if (isTailBatchInput) {
            // The following call schedules onEndBatchInputInternal
            // to be called on the UI thread.
            mLatinIMEHandler.showTailBatchInputResult(suggestedWordsToShowSuggestions, gestureId);
        }
    }

    /**
     * Update a batch input.
     * <p>
     * This fetches suggestions and updates the suggestion strip and the floating text preview.
     *
     * @param batchPointers the updated batch pointers.
     * @param sequenceNumber the sequence number associated with this batch input.
     */
    // Called on the UI thread by InputLogic.
    public void onUpdateBatchInput(final InputPointers batchPointers,
            final int sequenceNumber) {
        updateBatchInput(batchPointers, sequenceNumber, false);
    }

    /**
     * Cancel a batch input.
     * <p>
     * Note that as opposed to updateTailBatchInput, we do the UI side of this immediately on the
     * same thread, rather than get this to call a method in LatinIME. This is because
     * canceling a batch input does not necessitate the long operation of pulling suggestions.
     */
    // Called on the UI thread by InputLogic.
    public void onCancelBatchInput() {
        synchronized (mLock) {
            mInBatchInput = false;
        }
        mNonUIThreadHandler.removeMessages(MSG_UPDATE_BATCH);
    }

    /**
     * Trigger an update for a tail batch input.
     * <p>
     * A tail batch input is the last update for a gesture, the one that is triggered after the
     * user lifts their finger. This method schedules fetching suggestions on the non-UI thread,
     * then when the suggestions are computed it comes back on the UI thread to update the
     * suggestion strip, commit the first suggestion, and dismiss the floating text preview.
     *
     * @param batchPointers the updated batch pointers.
     * @param sequenceNumber the sequence number associated with this batch input.
     */
    // Called on the UI thread by InputLogic.
    public void updateTailBatchInput(final InputPointers batchPointers,
            final int sequenceNumber) {
        updateBatchInput(batchPointers, sequenceNumber, true);
    }

    public void getSuggestedWords(final Runnable callback) {
        mNonUIThreadHandler.obtainMessage(MSG_GET_SUGGESTED_WORDS, callback).sendToTarget();
    }
}
