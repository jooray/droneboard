// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings as KeyboardSettings
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.screens.OsttStatusCard
import helium314.keyboard.settings.screens.checkOsttStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TOTAL_SETUP_STEPS = 4

@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit,
    openLanguages: () -> Unit = close,
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    fun determineStep(): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) -> 0
        !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) -> 2
        else -> 3
    }
    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    // Droneboard: the picker was opened at least once and the keyboard is still not selected (shows what to do next)
    var pickerOpened by rememberSaveable { mutableStateOf(false) }
    val inSeparateProfile = remember {
        val um = ctx.getSystemService(Context.USER_SERVICE) as? UserManager
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching { um?.isManagedProfile == true }.getOrDefault(false))
            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runCatching { um?.isProfile == true }.getOrDefault(false))
    }
    // Poll off the main thread for IME selection, but always mutate Compose state on the main thread.
    LaunchedEffect(step) {
        if (step == 2) {
            while (step == 2) {
                val selected = withContext(Dispatchers.IO) {
                    UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)
                }
                if (selected) {
                    step = 3
                    break
                }
                delay(100)
            }
        }
    }

    val useWideLayout = isWideScreen()
    val appName = stringResource(ctx.applicationInfo.labelRes)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        step = determineStep()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = if (step <= 2) Alignment.Center else Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (step > 0) {
                    WizardProgressHeader(step = step, totalSteps = TOTAL_SETUP_STEPS)
                }
                if (useWideLayout && step <= 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(0.42f)) {
                            WizardTitleBlock(step = step, appName = appName)
                        }
                        Box(Modifier.weight(0.58f)) {
                            WizardStepContent(
                                step = step,
                                appName = appName,
                                pickerOpened = pickerOpened,
                                inSeparateProfile = inSeparateProfile,
                                onStart = { step = 1 },
                                onEnableInSettings = {
                                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                        .addCategory(Intent.CATEGORY_DEFAULT)
                                    launcher.launch(intent)
                                },
                                onSwitchIme = {
                                    pickerOpened = true
                                    imm.showInputMethodPicker()
                                },
                                onStepChange = { step = it },
                                onOpenLanguages = openLanguages,
                                onClose = close,
                                onFinish = finish,
                            )
                        }
                    }
                } else {
                    if (step <= 2) {
                        WizardTitleBlock(step = step, appName = appName)
                    }
                    WizardStepContent(
                        step = step,
                        appName = appName,
                        pickerOpened = pickerOpened,
                        inSeparateProfile = inSeparateProfile,
                        onStart = { step = 1 },
                        onEnableInSettings = {
                            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                .addCategory(Intent.CATEGORY_DEFAULT)
                            launcher.launch(intent)
                        },
                        onSwitchIme = {
                            pickerOpened = true
                            imm.showInputMethodPicker()
                        },
                        onStepChange = { step = it },
                        onOpenLanguages = openLanguages,
                        onClose = close,
                        onFinish = finish,
                    )
                }
            }
        }
    }
}

@Composable
private fun WizardProgressHeader(step: Int, totalSteps: Int) {
    val clampedStep = step.coerceIn(1, totalSteps)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.setup_wizard_step_progress, clampedStep, totalSteps),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        LinearProgressIndicator(
            progress = { clampedStep.toFloat() / totalSteps.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WizardTitleBlock(step: Int, appName: String) {
    val titleRes = if (step == 0) R.string.setup_welcome_title else R.string.setup_steps_title
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (step == 0) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(titleRes, appName),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = if (step == 0) TextAlign.Center else TextAlign.Start,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth()
        )
        if (step == 0) {
            Text(
                text = if (JniUtils.sHaveGestureLib) stringResource(R.string.setup_welcome_subtitle)
                else stringResource(R.string.app_slogan),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WizardStepContent(
    step: Int,
    appName: String,
    pickerOpened: Boolean,
    inSeparateProfile: Boolean,
    onStart: () -> Unit,
    onEnableInSettings: () -> Unit,
    onSwitchIme: () -> Unit,
    onStepChange: (Int) -> Unit,
    onOpenLanguages: () -> Unit,
    onClose: () -> Unit,
    onFinish: () -> Unit,
) {
    when (step) {
        0 -> Step0(onClick = onStart)
        1 -> WizardActionStep(
            title = stringResource(R.string.setup_step1_title, appName),
            instruction = stringResource(R.string.setup_step1_instruction, appName),
            actionText = stringResource(R.string.setup_step1_action),
            icon = painterResource(R.drawable.ic_setup_key),
            hint = stringResource(R.string.setup_step1_hint, appName),
            action = onEnableInSettings,
        )
        2 -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WizardActionStep(
                title = stringResource(R.string.setup_step2_title, appName),
                instruction = stringResource(R.string.setup_step2_instruction, appName),
                actionText = stringResource(R.string.setup_step2_action),
                icon = painterResource(R.drawable.ic_setup_select),
                hint = if (pickerOpened) stringResource(R.string.setup_step2_retry, appName)
                else stringResource(R.string.setup_step2_hint, appName),
                action = onSwitchIme,
            )
            // Droneboard: in a work profile (Island, Shelter, a company profile) or another separate
            // profile, the picker opened above belongs to the main profile, while this wizard waits for
            // the keyboard of its own profile, which Android sets in the system settings.
            if (inSeparateProfile) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.setup_step2_profile, appName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        OutlinedButton(
                            onClick = onEnableInSettings,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_setup_key),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.setup_step2_profile_action))
                        }
                    }
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.setup_step2_skip))
            }
        }
        3 -> Step3LanguagesAndTyping(
            onOpenLanguages = onOpenLanguages,
            onSkip = onClose,
            onNext = { onStepChange(4) }
        )
        else -> Step4TryDroneboard(
            onBack = { onStepChange(3) },
            onOpenSettings = onClose,
            onFinish = onFinish
        )
    }
}

@Composable
private fun WizardActionStep(
    title: String,
    instruction: String,
    actionText: String,
    icon: Painter,
    hint: String? = null,
    action: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = instruction,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = action,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = actionText,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            NextScreenIcon()
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun Step0(onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.setup_welcome_image),
            contentDescription = null,
            modifier = Modifier.size(180.dp)
        )
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        ) {
            Text(
                text = stringResource(R.string.setup_start_action),
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Step3LanguagesAndTyping(
    onOpenLanguages: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0) {
        // triggers recomposition when subtype or autocorrect preferences change
    }
    var autocorrect by rememberSaveable {
        mutableStateOf(prefs.getBoolean(KeyboardSettings.PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION))
    }
    var osttStatus by remember { mutableStateOf(checkOsttStatus(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        osttStatus = checkOsttStatus(ctx)
    }

    val enabledSubtypes = runCatching { SubtypeSettings.getEnabledSubtypes(true) }.getOrDefault(emptyList())
    val fullSupportSubtypes: List<InputMethodSubtype> = remember {
        runCatching {
            val seenKeys = HashSet<String>()
            SubtypeSettings.getAllAvailableSubtypes()
                .filter { PredictionEngines.of(ctx, it.locale()).isFullSupport }
                .filter { seenKeys.add(it.locale().toLanguageTag()) }
                .sortedBy { it.displayName() }
        }.getOrDefault(emptyList())
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.setup_step3_languages_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.setup_step3_languages_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Languages & layouts card (QWERTY / QWERTZ + swipe support)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.setup_step3_languages_card_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.setup_step3_languages_card_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (fullSupportSubtypes.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        fullSupportSubtypes.forEach { subtype ->
                            val isEnabled = enabledSubtypes.any { it.locale() == subtype.locale() }
                            FilterChip(
                                selected = isEnabled,
                                onClick = {
                                    if (isEnabled) {
                                        if (enabledSubtypes.size > 1) {
                                            enabledSubtypes.filter { it.locale() == subtype.locale() }.forEach {
                                                SubtypeSettings.removeEnabledSubtype(ctx, it)
                                            }
                                        }
                                    } else {
                                        SubtypeSettings.addEnabledSubtype(prefs, subtype)
                                    }
                                },
                                label = { Text(subtype.displayName()) }
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onOpenLanguages,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.sym_keyboard_language_switch),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.setup_step3_configure_languages))
                }
            }
        }

        // Autocorrect default-off toggle
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setup_step3_autocorrect_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.setup_step3_autocorrect_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autocorrect,
                    onCheckedChange = {
                        autocorrect = it
                        prefs.edit { putBoolean(KeyboardSettings.PREF_AUTO_CORRECTION, it) }
                    }
                )
            }
        }

        // Optional Offline Dictation (OSTT) live status card
        OsttStatusCard(status = osttStatus)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.setup_action_skip))
            }
            Button(
                onClick = onNext,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.setup_action_next))
            }
        }
    }
}

@Composable
private fun Step4TryDroneboard(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onFinish: () -> Unit,
) {
    var sampleText by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.setup_step4_try_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.setup_step4_try_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = sampleText,
            onValueChange = { sampleText = it },
            placeholder = { Text(stringResource(R.string.setup_step4_try_placeholder)) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.setup_step4_tips_title),
                    style = MaterialTheme.typography.titleSmall
                )
                ShortcutTipRow(
                    title = stringResource(R.string.setup_tip_trackpad_title),
                    detail = stringResource(R.string.setup_tip_trackpad_detail)
                )
                ShortcutTipRow(
                    title = stringResource(R.string.setup_tip_delete_title),
                    detail = stringResource(R.string.setup_tip_delete_detail)
                )
                ShortcutTipRow(
                    title = stringResource(R.string.setup_tip_edit_title),
                    detail = stringResource(R.string.setup_tip_edit_detail)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_setup_check),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.setup_finish_action))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.setup_action_back))
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.setup_step3_action))
                }
            }
        }
    }
}

@Composable
private fun ShortcutTipRow(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}, {})
        }
    }
}

@Preview(
    device = "spec:orientation=landscape,width=780dp,height=400dp"
)
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}, {})
        }
    }
}
