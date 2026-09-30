package io.github.chayanforyou.quickball.ui.screens.background

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.chayanforyou.quickball.R
import io.github.chayanforyou.quickball.core.QuickBallService
import io.github.chayanforyou.quickball.core.persistence.KeepAliveService
import io.github.chayanforyou.quickball.core.persistence.RecentsHelper
import io.github.chayanforyou.quickball.domain.AppPreference
import io.github.chayanforyou.quickball.ui.screens.home.components.SettingSwitchRow
import io.github.chayanforyou.quickball.ui.theme.AppCardDefaults
import io.github.chayanforyou.quickball.utils.HuaweiHelper
import io.github.chayanforyou.quickball.utils.PermissionUtils
import java.text.DateFormat
import java.util.Date

private enum class Status { OK, MISSING, UNKNOWN, NOT_NEEDED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { AppPreference.getInstance(context) }

    // Bumped on every resume so all status checks are re-evaluated.
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val accessibilityOn = remember(tick) { PermissionUtils.isAccessibilityServiceEnabled(context) }
    val serviceRunning = remember(tick) { QuickBallService.isRunning }
    val canWriteSettings = remember(tick) { PermissionUtils.canModifySystemSettings(context) }
    val batteryOk = remember(tick) { HuaweiHelper.isIgnoringBatteryOptimizations(context) }
    val lastConnected = remember(tick) { prefs.lastServiceConnectedAt }

    var excludeFromRecents by remember { mutableStateOf(prefs.isExcludeFromRecentsEnabled) }
    var keepAlive by remember { mutableStateOf(prefs.isKeepAliveNotificationEnabled) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.background_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.menu_back))
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------- Status ----------
            SectionLabel(stringResource(R.string.background_status_header))
            SectionCard {
                StatusRow(
                    title = stringResource(R.string.status_accessibility),
                    detail = when {
                        accessibilityOn && serviceRunning -> stringResource(R.string.status_running)
                        accessibilityOn -> stringResource(R.string.status_enabled_not_connected)
                        else -> stringResource(R.string.status_accessibility_off)
                    },
                    status = if (accessibilityOn) Status.OK else Status.MISSING,
                    actionLabel = if (accessibilityOn) null else stringResource(R.string.action_open),
                    onAction = { PermissionUtils.openAccessibilitySettings(context) }
                )
                StatusRow(
                    title = stringResource(R.string.status_system_settings),
                    detail = stringResource(R.string.status_system_settings_detail),
                    status = if (canWriteSettings) Status.OK else Status.MISSING,
                    actionLabel = if (canWriteSettings) null else stringResource(R.string.action_open),
                    onAction = { PermissionUtils.openSystemSettingsPermission(context) }
                )
                StatusRow(
                    title = stringResource(R.string.status_device_admin),
                    detail = stringResource(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) R.string.status_device_admin_not_needed
                        else R.string.status_device_admin_old_android
                    ),
                    status = Status.NOT_NEEDED,
                )
                StatusRow(
                    title = stringResource(R.string.status_battery),
                    detail = stringResource(if (batteryOk) R.string.status_battery_ok else R.string.status_battery_missing),
                    status = if (batteryOk) Status.OK else Status.MISSING,
                    actionLabel = stringResource(R.string.action_open),
                    onAction = { HuaweiHelper.openBatteryOptimization(context) }
                )
                StatusRow(
                    title = stringResource(R.string.status_background_launch),
                    detail = stringResource(R.string.status_background_launch_detail),
                    status = Status.UNKNOWN,
                    actionLabel = stringResource(R.string.action_open),
                    onAction = { HuaweiHelper.openAppLaunchSettings(context) }
                )
                if (lastConnected > 0) {
                    Text(
                        text = stringResource(
                            R.string.status_last_connected,
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastConnected))
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------- Behaviour switches ----------
            SectionLabel(stringResource(R.string.background_behavior_header))
            SectionCard {
                SettingSwitchRow(
                    title = stringResource(R.string.exclude_from_recents_title),
                    subtitle = stringResource(R.string.exclude_from_recents_description),
                    checked = excludeFromRecents,
                    onCheckedChange = {
                        excludeFromRecents = it
                        prefs.isExcludeFromRecentsEnabled = it
                        context.findActivity()?.let { a -> RecentsHelper.apply(a, it) }
                    }
                )
                SettingSwitchRow(
                    title = stringResource(R.string.keep_alive_title),
                    subtitle = stringResource(R.string.keep_alive_description),
                    checked = keepAlive,
                    onCheckedChange = {
                        keepAlive = it
                        prefs.isKeepAliveNotificationEnabled = it
                        KeepAliveService.syncWithPreference(context)
                    }
                )
            }

            // ---------- Guide ----------
            SectionLabel(
                stringResource(
                    if (HuaweiHelper.isHuawei) R.string.huawei_guide_header else R.string.generic_guide_header
                )
            )
            SectionCard {
                if (HuaweiHelper.isHuawei) {
                    Text(
                        text = stringResource(
                            R.string.huawei_detected,
                            "${Build.MANUFACTURER} ${Build.MODEL}",
                            if (HuaweiHelper.isHarmonyOs) "HarmonyOS" else "EMUI / Android ${Build.VERSION.RELEASE}"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                GuideStep(1, stringResource(R.string.guide_step_app_launch)) { HuaweiHelper.openAppLaunchSettings(context) }
                GuideStep(2, stringResource(R.string.guide_step_battery)) { HuaweiHelper.openBatteryOptimization(context) }
                GuideStep(3, stringResource(R.string.guide_step_recents), null)
                GuideStep(4, stringResource(R.string.guide_step_accessibility)) { PermissionUtils.openAccessibilitySettings(context) }
                GuideStep(5, stringResource(R.string.guide_step_tile), null)
                Text(
                    text = stringResource(R.string.guide_limitations),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = AppCardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) { content() }
    }
}

@Composable
private fun StatusRow(
    title: String,
    detail: String,
    status: Status,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val (icon, tint) = when (status) {
        Status.OK -> Icons.Filled.CheckCircle to Color(0xFF2E7D32)
        Status.MISSING -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
        Status.UNKNOWN -> Icons.Filled.Info to MaterialTheme.colorScheme.tertiary
        Status.NOT_NEEDED -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun GuideStep(number: Int, text: String, onOpen: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("$number.", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onOpen != null) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
