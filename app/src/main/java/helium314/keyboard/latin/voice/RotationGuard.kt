// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Keeps a dictation alive while the phone is rotated. Normally a hidden keyboard ends the recording (the user left).
 * A rotation also hides the keyboard for a moment: Android builds the keyboard's views again and the app's text
 * field lets go of the keyboard and takes it back, in no fixed order and sometimes twice. So for [TIMEOUT_MS] after
 * a rotation, a hidden keyboard only takes the listening panel off the screen; the recording goes on, and the panel
 * comes back when the keyboard does. If the keyboard is still hidden when the time is up, the user really left, and
 * the recording ends as it does without a rotation.
 *
 * Only the decisions live here, free of Android, for the tests; [DictationController] owns the timer.
 */
class RotationGuard {
    enum class Finish {
        /** End the recording; it is still written down. */
        STOP,
        /** Leave the recording alone. */
        KEEP,
    }

    companion object {
        /** An app that rebuilds its whole screen on rotation can take a second or two to ask for the keyboard again. */
        const val TIMEOUT_MS = 3000L
    }

    private var rotating = false
    private var hidden = false

    /** The screen turned while a dictation runs; the caller starts its timer of [TIMEOUT_MS]. */
    fun onOrientationChange() {
        rotating = true
        hidden = false
    }

    /** The keyboard was hidden. The panel is taken down either way; this says what happens to the recording. */
    fun onFinishInputView(): Finish {
        if (!rotating) return Finish.STOP
        hidden = true
        return Finish.KEEP
    }

    /**
     * The keyboard is shown (again). The rotation is not over with that: the app often hides the keyboard once more
     * right after the first time it is shown, so only the timer ends it.
     */
    fun onStartInputView() {
        hidden = false
    }

    /** [TIMEOUT_MS] after the rotation: [Finish.STOP] when the keyboard was hidden and never came back. */
    fun onTimeout(): Finish {
        val stop = rotating && hidden
        reset()
        return if (stop) Finish.STOP else Finish.KEEP
    }

    /** The dictation ended or was cancelled. */
    fun reset() {
        rotating = false
        hidden = false
    }
}
