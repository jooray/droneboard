// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The emoji and clipboard views are inflated from ViewStubs after the window's insets pass. They must
 * still pick up the navigation bar as bottom padding, otherwise their bottom row (ABC, delete, ...)
 * lies behind a 3-button navigation bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LateAttachInsetsTest {
    private class LateView(context: Context, private val insets: WindowInsets) : View(context) {
        init { fitsSystemWindows = true }
        override fun getRootWindowInsets(): WindowInsets = insets
    }

    @Test fun lateAttachedViewGetsNavigationBarPadding() {
        val navBar = 126
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, navBar))
            .build()
        val view = LateView(ApplicationProvider.getApplicationContext(), insets)
        assertEquals(0, view.paddingBottom)
        view.applyWindowInsetsAfterLateAttach()
        assertEquals(navBar, view.paddingBottom)
        assertEquals(0, view.paddingTop)
    }
}
