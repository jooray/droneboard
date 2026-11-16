// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.annotation.VisibleForTesting

/**
 * Droneboard: Direct Boot (Before First Unlock) gate.
 *
 * After a restart and before the user enters the PIN or password for the first time, credential-encrypted
 * storage (the app's default files, no_backup, cache, databases and default SharedPreferences) cannot be read
 * or written. The keyboard still has to work there, on the lock screen. While the user is locked, Droneboard
 * runs a minimal keyboard: plain keys, no swipe decoder, no neural or n-gram language model, no extraction of
 * assets, no user history or personal dictionary, no dictation, nothing written that holds typed text.
 *
 * Every Droneboard component checks [isUnlocked] before it touches files or other credential-encrypted state,
 * and treats "locked" as "feature off" (fail closed). The answer is cached once the user is unlocked (that does
 * not go back within a process). When the user unlocks, HeliBoard's StuffReceiver restarts the keyboard process
 * on ACTION_USER_UNLOCKED, so all features load fresh; if that ever does not happen, the next call here sees
 * the unlocked user and the features start lazily.
 */
object DirectBoot {
    private const val TAG = "DirectBoot"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var unlocked = false

    /** Tests: force the locked (true) or unlocked (false) state; null uses the system. */
    @VisibleForTesting
    @JvmStatic
    @Volatile
    var lockedOverride: Boolean? = null

    /** Remembers the application context (called from App and LatinIME onCreate). Never throws. */
    @JvmStatic
    fun init(context: Context) {
        if (appContext == null) appContext = runCatching { context.applicationContext ?: context }.getOrNull()
        if (!isUnlocked(context)) Log.i(TAG, "user is locked (direct boot): minimal keyboard until unlock")
    }

    /** True when credential-encrypted storage is available. False while locked, and when that can't be determined. */
    @JvmStatic
    fun isUnlocked(context: Context?): Boolean {
        lockedOverride?.let { return !it }
        if (unlocked) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true.also { unlocked = true }
        val ctx = context ?: appContext ?: return false
        val result = try {
            ctx.getSystemService(UserManager::class.java)?.isUserUnlocked ?: false
        } catch (e: Throwable) {
            false
        }
        if (result) unlocked = true
        return result
    }

    /** [isUnlocked] with the remembered application context. */
    @JvmStatic
    fun isUnlocked(): Boolean = isUnlocked(null)

    @JvmStatic
    fun isLocked(): Boolean = !isUnlocked(null)

    @VisibleForTesting
    @JvmStatic
    fun resetForTest() {
        unlocked = false
        lockedOverride = null
    }
}
