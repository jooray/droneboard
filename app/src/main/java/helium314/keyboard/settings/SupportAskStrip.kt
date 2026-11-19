// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import helium314.keyboard.compat.locale
import helium314.keyboard.latin.R
import helium314.keyboard.latin.support.SupportAsk
import helium314.keyboard.latin.support.SupportCheckout
import helium314.keyboard.settings.preferences.Preference

/**
 * Droneboard: the support ask (support-widget/spec/ask.md, the plain variant with the app's own Material
 * tokens). A strip inside a settings screen: never a dialog, never in the keyboard, takes no focus.
 *
 * Whether it shows is decided once per visit, when the screen is really in front (resumed), and the ask
 * is counted at that moment. It then stays until one of its three buttons is used; the decision survives
 * a rotation and a visit to a sub-screen, so one ask is never counted twice.
 */
@Composable
fun SupportAskStrip(ref: String, modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) return
    val ctx = LocalContext.current
    var visible: Boolean? by rememberSaveable { mutableStateOf(null) }
    if (visible == null) {
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.withResumed { }
            val ask = SupportAsk.shouldAsk(ctx)
            if (ask) SupportAsk.onShown(ctx)
            visible = ask
        }
    }
    if (visible != true) return // nothing is reserved in the layout

    val dark = isSystemInDarkTheme() // what Theme() of the settings follows
    val language = LocalConfiguration.current.locale().language
    val alpha = remember { Animatable(if (animationsEnabled(ctx)) 0f else 1f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(160)) }
    SupportAskPlate(
        onSupport = { visible = false; SupportAsk.support(ctx, ref, dark, language) },
        onLater = { visible = false },
        onNever = { visible = false; SupportAsk.never(ctx) },
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportAskPlate(onSupport: () -> Unit, onLater: () -> Unit, onNever: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val label = stringResource(R.string.support_ask_yes)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(colors.surfaceContainer, shape)
            .border(1.dp, colors.outlineVariant, shape)
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                paneTitle = label
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // wraps, never truncated; grows with the font scale
        Text(stringResource(R.string.support_ask_text), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // outlined, never the filled button: the ask is not the loudest thing on the screen
            OutlinedButton(
                onClick = onSupport,
                modifier = Modifier.heightIn(min = 48.dp),
                border = BorderStroke(1.dp, colors.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
            ) { Text(label) }
            TextButton(
                onClick = onLater,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
            ) { Text(stringResource(R.string.support_ask_later)) }
        }
        TextButton(
            onClick = onNever,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            Text(
                stringResource(R.string.support_ask_never),
                style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
            )
        }
    }
}

/** The permanent "Support the project" row: only opens the checkout, changes nothing about the asking. */
@Composable
fun SupportMenuPreference(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val language = LocalConfiguration.current.locale().language
    Preference(
        name = stringResource(R.string.support_menu),
        onClick = { SupportAsk.open(ctx, SupportCheckout.REF_MENU, dark, language) },
        modifier = modifier,
        icon = R.drawable.ic_settings_support,
    )
}

/** false when the system's animations are switched off (animator duration scale 0): then there is no fade */
private fun animationsEnabled(context: Context): Boolean = runCatching {
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}.getOrDefault(true)
