// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.support

import java.net.URLEncoder

/**
 * Droneboard: the address of the checkout page (support-widget/spec/ask.md section 5). The app only hands
 * it to the browser; it has no network code and no INTERNET permission. No Android in this file.
 */
object SupportCheckout {
    private const val CHECKOUT = "https://hackyourself.io/support/s/droneboard"

    /** the strip on the main settings screen */
    const val REF_SETTINGS_HOME = "settings-home"
    /** the strip after an import into the personal dictionary */
    const val REF_AFTER_IMPORT = "after-import"
    /** the permanent "Support the project" rows */
    const val REF_MENU = "menu"

    private val LANGUAGE = Regex("[a-z]{2}")

    /**
     * lang, theme, ref and nothing else (no id, no key, no version). [language] is the language code the
     * settings are shown in; the checkout falls back to English itself for a language it does not have,
     * and anything that is not a two-letter code is sent as English.
     */
    fun url(ref: String, dark: Boolean, language: String?): String {
        val lang = language?.lowercase()?.takeIf { LANGUAGE.matches(it) } ?: "en"
        val theme = if (dark) "dark" else "light"
        return "$CHECKOUT?lang=$lang&theme=$theme&ref=${URLEncoder.encode(ref, "UTF-8")}"
    }
}
