// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import android.os.UserManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.emoji.EmojiSearchIndex
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.dictionary.DictionaryFactory
import helium314.keyboard.latin.dictionary.ExpandableDictionaryUtils
import helium314.keyboard.latin.lm.NeuralLanguageModel
import helium314.keyboard.latin.lm.NgramLanguageModel
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.swipe.SwipeGestureDecoder
import helium314.keyboard.latin.utils.DirectBoot
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct boot (Before First Unlock): credential-encrypted storage is not available until the user enters the
 * PIN / password for the first time after a restart. The keyboard has to show and type there, and Droneboard's
 * own components must not touch credential-encrypted storage, nor throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class])
class DirectBootTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    /** Fails every access to credential-encrypted storage (records it), everything else goes to the app. */
    private class LockedStorageContext(base: Context) : ContextWrapper(base) {
        val accesses = mutableListOf<String>()
        private fun denied(what: String): Nothing {
            accesses.add(what)
            throw IllegalStateException("credential-encrypted storage accessed while locked: $what")
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = denied("filesDir")
        override fun getNoBackupFilesDir(): File = denied("noBackupFilesDir")
        override fun getCacheDir(): File = denied("cacheDir")
        override fun getCodeCacheDir(): File = denied("codeCacheDir")
        override fun getDataDir(): File = denied("dataDir")
        override fun getDatabasePath(name: String?): File = denied("database $name")
        override fun getExternalFilesDir(type: String?): File = denied("externalFilesDir")
        override fun getExternalCacheDir(): File = denied("externalCacheDir")
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = denied("sharedPreferences $name")
        override fun openFileOutput(name: String?, mode: Int) = denied("openFileOutput $name")
        override fun openFileInput(name: String?) = denied("openFileInput $name")
    }

    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
    }

    @Before
    fun lock() {
        DirectBoot.resetForTest()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
    }

    @After
    fun unlock() {
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        DirectBoot.resetForTest()
    }

    @Test fun followsUserManager() {
        assertFalse(DirectBoot.isUnlocked(app))
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        assertTrue(DirectBoot.isUnlocked(app))
        // unlocking does not go back within a process
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        assertTrue(DirectBoot.isUnlocked(app))
    }

    @Test fun droneboardComponentsDoNotTouchCredentialStorageWhileLocked() {
        val ctx = LockedStorageContext(app)
        SwipeGestureDecoder.init(ctx)
        SwipeGestureDecoder.preload(ctx, listOf(Locale.ENGLISH, Locale.forLanguageTag("sk")))
        assertFalse(SwipeGestureDecoder.isAvailable(ctx))
        assertTrue(SwipeGestureDecoder.loadedDictionaries(listOf("en", "sk")).isEmpty())
        // extraction fails closed, before it looks at any directory
        assertFailsWith<IllegalStateException> { SwipeGestureDecoder.extract(ctx, "en.kpd") }
        NeuralLanguageModel.preload(ctx, listOf("en"))
        assertTrue(NeuralLanguageModel.predict(listOf("good"), "", listOf(Locale.ENGLISH), 5).isEmpty())
        // the swipe ranking gets no neural model (and none starts loading) before the first unlock
        assertNull(NeuralLanguageModel.withSwipeModels(ctx, listOf("en", "sk")) { it })
        assertTrue(NgramLanguageModel.predict(listOf("good"), "mor", listOf(Locale.ENGLISH), 5).isEmpty())
        assertNull(DictionaryFactory.extractBuiltInEmojiDict(ctx, Locale.ENGLISH))
        assertTrue(EmojiSearchIndex.search(ctx, "rocket", listOf(Locale.ENGLISH)).isEmpty())
        ExpandableDictionaryUtils.initUpToDateDicts(ctx)
        // the extraction above threw on purpose; nothing else may have tried
        assertEquals(emptyList(), ctx.accesses)
    }

    /** The 0.1.14 crash: listFiles() on the locked files dir returns null, `!!` threw in App.onCreate. */
    @Test fun unreadableFilesDirDoesNotCrash() {
        DirectBoot.lockedOverride = false // even when the gate says unlocked, an unreadable dir must not throw
        val ctx = object : ContextWrapper(app) {
            override fun getFilesDir() = File("/nonexistent/direct-boot-test/files")
        }
        assertNull(ctx.filesDir.listFiles()) // what a locked credential-encrypted dir looks like
        ExpandableDictionaryUtils.initUpToDateDicts(ctx)
    }

    @Test fun keyboardStartsAndTypesWhileLocked() {
        val ime = Robolectric.setupService(LatinIME::class.java)
        val sv = Settings.getValues()
        assertTrue(sv.mIsUserLocked)
        assertFalse(sv.mUsePersonalizedDicts) // no typing history
        assertTrue(sv.mIncognitoModeEnabled) // nothing learned
        assertFalse(sv.mGestureInputEnabled) // no swipe decoder
        assertFalse(sv.mAddToPersonalDictionary)
        assertFalse(sv.mUseContactsDictionary)
        assertFalse(sv.mUseAppsDictionary)

        ShadowInputMethodService.reset()
        ShadowInputMethodService.currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val ei = EditorInfo().apply { inputType = ShadowInputMethodService.currentInputType }
        ime.mHandler.onStartInput(ei, false)
        ime.mHandler.onStartInputView(ei, false)
        shadowOf(Looper.getMainLooper()).idle()
        "1a2b".forEach { ime.onEvent(Event.createEventForCodePointFromUnknownSource(it.code)) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("1a2b", ShadowInputMethodService.text)
        // the mic key does nothing before the first unlock (no dictation, no permission screen, no crash)
        ime.onEvent(Event.createSoftwareKeypressEvent(Event.NOT_A_CODE_POINT, KeyCode.VOICE_INPUT, 0,
            Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("1a2b", ShadowInputMethodService.text)
        ime.onFinishInputView(true)
    }
}
