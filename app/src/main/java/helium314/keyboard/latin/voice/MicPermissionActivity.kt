// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import helium314.keyboard.latin.R

/**
 * An input method can't show a permission dialog itself. This invisible activity asks for the
 * microphone (and, on Android 12+, nearby devices, to name the Bluetooth headset), then closes.
 * The keyboard starts listening on the next tap. When Android no longer shows the dialog (the
 * user said no for good), it opens the app's settings page instead, with a hint what to switch on.
 */
class MicPermissionActivity : Activity() {

    companion object {
        private const val REQUEST = 1
        private const val PREFS = "dictation_permission"
        private const val ASKED = "mic_asked"

        fun permissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.RECORD_AUDIO)

        fun hasMic(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, MicPermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = permissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) finish() else requestPermissions(missing.toTypedArray(), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val mic = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val denied = mic >= 0 && grantResults.getOrNull(mic) != PackageManager.PERMISSION_GRANTED
        // credential-encrypted prefs: they throw before the first unlock, so this fails closed
        val askedBefore = runCatching {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            prefs.getBoolean(ASKED, false).also { prefs.edit().putBoolean(ASKED, true).apply() }
        }.getOrDefault(false)
        // asked before and no rationale now: the dialog won't come back, only the settings can
        // grant it (the first request is skipped, since dismissing the dialog also gives no rationale)
        if (denied && askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(this, R.string.voice_mic_permission_settings, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
