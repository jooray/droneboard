// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package helium314.keyboard.latin

import android.app.Application
import android.content.Context
import android.os.Build
import helium314.keyboard.keyboard.emoji.SupportedEmojis
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.dictionary.ExpandableDictionaryUtils
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.DirectBoot
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.LayoutUtilsCustom
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.upgradeToolbarPrefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class App : Application() {
    private var startupBgJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = this
        val ctx: Context = this
        DebugFlags.init(this) // needed by several other inits, so do it first
        DirectBoot.init(this) // Droneboard: minimal keyboard before the first unlock

        val scope = CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
        // other inits can be in parallel, except what depends on SubtypeSettings
        val jobs = listOf(
            scope.launch {
                SubtypeSettings.init(ctx)
                RichInputMethodManager.init(ctx)
                // Droneboard: upgrades also clean files in credential-encrypted storage, so they wait for the
                // first unlock (the process restarts then); run while locked they would be marked done without effect
                if (DirectBoot.isUnlocked(ctx)) {
                    try {
                        checkVersionUpgrade(ctx)
                    } catch (e: Throwable) {
                        Log.e("startup", "version upgrade failed", e) // a failed migration must not stop the keyboard
                    }
                }
            },
            scope.launch { FoldableUtils.init(ctx) },
            scope.launch { Settings.init(ctx) },
            // upgrade toolbar on every debug apk start because we may work on adding a new toolbar key
            scope.launch { if (BuildConfig.DEBUG) upgradeToolbarPrefs(prefs()) },
            scope.launch { transferOldPinnedClips(ctx) }, // todo: remove in a few months, maybe end 2026
            scope.launch { Defaults.initDynamicDefaults(ctx) }
        )
        runBlocking { jobs.joinAll() } // word should be finished before anything else is started

        startupBgJob = scope.launch { // do some uncritical work in background for faster startup
            // Droneboard: an exception in this coroutine kills the whole process (also the keyboard on the lock
            // screen), and none of this is needed to type, so each step fails on its own
            fun uncritical(name: String, block: () -> Unit) {
                if (!isActive) return
                try {
                    block()
                } catch (e: Throwable) {
                    runCatching { Log.e("startup", "$name failed", e) }
                }
            }
            uncritical("SupportedEmojis.load") { SupportedEmojis.load(this@App) }
            uncritical("initUpToDateDicts") { ExpandableDictionaryUtils.initUpToDateDicts(this@App) }
            uncritical("removeMissingLayouts") { LayoutUtilsCustom.removeMissingLayouts(this@App) }
            uncritical("startupLog") {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION")
                Log.i(
                    "startup", "Starting ${applicationInfo.processName} version ${packageInfo.versionName} (${
                        packageInfo.versionCode
                    }) on Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
                )
            }
        }
    }

    override fun onTerminate() {
        val job = startupBgJob
        startupBgJob = null
        if (job != null) {
            runBlocking { withTimeoutOrNull(1000L) { job.cancelAndJoin() } }
        }
        super.onTerminate()
    }

    companion object {
        // used so JniUtils can access application once
        private var app: App? = null
        fun getApp(): App? {
            val application = app
            app = null
            return application
        }
    }
}
