package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import com.example.ui.theme.ShadoraIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalShadoraStrings
import com.example.core.model.AppLanguage
import com.example.core.model.AppThemeMode
import com.example.core.model.SplitTunnelMode
import com.example.core.util.FormatUtils
import com.example.data.local.AppSettingsEntity
import com.example.data.local.ConnectionHistoryEntity
import com.example.data.local.EngineSettingsEntity
import com.example.security.SafeLogEntry
import com.example.ui.InstalledAppItem
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.security.AppLockManager
import com.example.ui.components.Shadora3DButton
import com.example.ui.components.Shadora3DOutlinedButton
import com.example.ui.components.ShadoraCard
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.NeonCrimsonGlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    appSettings: AppSettingsEntity,
    xraySettings: EngineSettingsEntity,
    history: List<ConnectionHistoryEntity>,
    installedApps: List<InstalledAppItem>,
    diagnosticLogs: List<SafeLogEntry>,
    onLanguageChange: (AppLanguage) -> Unit,
    onThemeChange: (AppThemeMode) -> Unit,
    onDnsSave: (String, String) -> Unit,
    onIpv6Change: (Boolean) -> Unit,
    onBypassIranChange: (Boolean) -> Unit,
    onBypassLanChange: (Boolean) -> Unit,
    onSaveCustomRouting: (directCsv: String, blockCsv: String) -> Unit,
    onSplitModeChange: (SplitTunnelMode) -> Unit,
    onLoadInstalledApps: () -> Unit,
    onToggleSplitApp: (String) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onAutoFailoverChange: (Boolean) -> Unit,
    onEnableAppLockPin: (String) -> Unit,
    onDisableAppLock: () -> Unit,
    onAppLockBiometricChange: (Boolean) -> Unit,
    onKillSwitchChange: (Boolean) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onEngineSettingsChange: (mtu: Int, mux: Boolean, allowInsecure: Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onExportBackupJson: suspend () -> String,
    onImportBackupJson: (String) -> Unit,
    onOpenDiagnosticLogs: () -> Unit,
    onClearDiagnosticLogs: () -> Unit,
    onExportDiagnosticLogsText: () -> String,
    onToast: (String) -> Unit,
    onSavedNotice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalShadoraStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var primaryDnsDraft by remember(appSettings.primaryDns) { mutableStateOf(appSettings.primaryDns) }
    var secondaryDnsDraft by remember(appSettings.secondaryDns) { mutableStateOf(appSettings.secondaryDns) }
    var customDirectDraft by remember(appSettings.customDirectDomainsCsv) { mutableStateOf(appSettings.customDirectDomainsCsv) }
    var customBlockDraft by remember(appSettings.customBlockDomainsCsv) { mutableStateOf(appSettings.customBlockDomainsCsv) }
    var mtuSlider by remember(xraySettings.mtu) { mutableFloatStateOf(xraySettings.mtu.toFloat()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onNotificationsChange(granted)
    }

    // Zero-permission SAF Backup Export
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = onExportBackupJson()
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
                if (ok) onSavedNotice()
            }
        }
    }

    // Zero-permission SAF Backup Restore
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val raw = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    String(input.readNBytes(2 * 1024 * 1024), Charsets.UTF_8)
                }
            }.getOrNull()
            if (!raw.isNullOrBlank()) {
                onImportBackupJson(raw)
            }
        }
    }

    val selectedPackages = remember(appSettings.splitTunnelPackagesCsv) {
        appSettings.splitTunnelPackagesCsv
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    val currentLang = AppLanguage.fromCode(appSettings.languageCode)
    val currentTheme = runCatching { AppThemeMode.valueOf(appSettings.themeMode) }
        .getOrDefault(AppThemeMode.OBSIDIAN_DARK)
    val currentSplitMode = runCatching { SplitTunnelMode.valueOf(appSettings.splitTunnelMode) }
        .getOrDefault(SplitTunnelMode.ALL_APPS)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = strings.settingsTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            // 1. General & Appearance
            SectionHeader(strings.sectionGeneral)
            ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                Text(strings.languageLabel, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = currentLang == AppLanguage.FA,
                        onClick = { onLanguageChange(AppLanguage.FA) },
                        label = { Text("فارسی (RTL)", maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("lang_fa_chip")
                    )
                    FilterChip(
                        selected = currentLang == AppLanguage.EN,
                        onClick = { onLanguageChange(AppLanguage.EN) },
                        label = { Text("English (LTR)", maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("lang_en_chip")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(strings.themeLabel, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = currentTheme == AppThemeMode.OBSIDIAN_DARK,
                        onClick = { onThemeChange(AppThemeMode.OBSIDIAN_DARK) },
                        label = { Text(strings.themeObsidian, maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = currentTheme == AppThemeMode.SILVER_LIGHT,
                        onClick = { onThemeChange(AppThemeMode.SILVER_LIGHT) },
                        label = { Text(strings.themeSilver, maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = currentTheme == AppThemeMode.SYSTEM,
                        onClick = { onThemeChange(AppThemeMode.SYSTEM) },
                        label = { Text(strings.themeSystem, maxLines = 1, softWrap = false) }
                    )
                }
            }

            // 2. Network, Anti-Leak DNS & Split Tunneling
            SectionHeader(strings.sectionNetwork)
            ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                Text(strings.dnsPresetLabel, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = primaryDnsDraft == "1.1.1.1",
                        onClick = {
                            primaryDnsDraft = "1.1.1.1"
                            secondaryDnsDraft = "1.0.0.1"
                            onDnsSave("1.1.1.1", "1.0.0.1")
                        },
                        label = { Text("Cloudflare", maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = primaryDnsDraft == "8.8.8.8",
                        onClick = {
                            primaryDnsDraft = "8.8.8.8"
                            secondaryDnsDraft = "8.8.4.4"
                            onDnsSave("8.8.8.8", "8.8.4.4")
                        },
                        label = { Text("Google", maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = primaryDnsDraft == "9.9.9.9",
                        onClick = {
                            primaryDnsDraft = "9.9.9.9"
                            secondaryDnsDraft = "149.112.112.112"
                            onDnsSave("9.9.9.9", "149.112.112.112")
                        },
                        label = { Text("Quad9", maxLines = 1, softWrap = false) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = primaryDnsDraft,
                        onValueChange = { primaryDnsDraft = it },
                        label = { Text(strings.primaryDnsLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = secondaryDnsDraft,
                        onValueChange = { secondaryDnsDraft = it },
                        label = { Text(strings.secondaryDnsLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Shadora3DOutlinedButton(
                    text = strings.save,
                    onClick = {
                        onDnsSave(primaryDnsDraft, secondaryDnsDraft)
                        onSavedNotice()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_dns_button")
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                ToggleSettingRow(
                    title = strings.ipv6RoutingLabel,
                    description = strings.ipv6RoutingDesc,
                    checked = appSettings.ipv6Enabled,
                    onCheckedChange = onIpv6Change,
                    testTag = "ipv6_toggle"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(strings.splitTunnelLabel, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = currentSplitMode == SplitTunnelMode.ALL_APPS,
                        onClick = { onSplitModeChange(SplitTunnelMode.ALL_APPS) },
                        label = { Text(strings.splitModeAll, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = currentSplitMode == SplitTunnelMode.BYPASS_SELECTED,
                        onClick = { onSplitModeChange(SplitTunnelMode.BYPASS_SELECTED) },
                        label = { Text(strings.splitModeBypass, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = currentSplitMode == SplitTunnelMode.ONLY_SELECTED,
                        onClick = { onSplitModeChange(SplitTunnelMode.ONLY_SELECTED) },
                        label = { Text(strings.splitModeOnly, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                    )
                }

                if (currentSplitMode != SplitTunnelMode.ALL_APPS) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Shadora3DOutlinedButton(
                        text = "${strings.selectAppsCta} (${String.format(strings.selectedAppsCount, selectedPackages.size)})",
                        onClick = {
                            onLoadInstalledApps()
                            showAppPicker = true
                        },
                        icon = ShadoraIcons.Apps,
                        iconTint = NeonCrimsonGlow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("select_split_apps_button")
                    )
                }
            }

            // 2.5 Smart Routing Rules (Iran Bypass, LAN Bypass, Custom Direct & Block)
            SectionHeader(strings.sectionSmartRouting)
            ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                ToggleSettingRow(
                    title = strings.bypassIranLabel,
                    description = strings.bypassIranDesc,
                    checked = appSettings.bypassIranDomains,
                    onCheckedChange = onBypassIranChange,
                    testTag = "bypass_iran_toggle"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                ToggleSettingRow(
                    title = strings.bypassLanLabel,
                    description = strings.bypassLanDesc,
                    checked = appSettings.bypassLanCidrs,
                    onCheckedChange = onBypassLanChange,
                    testTag = "bypass_lan_toggle"
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = customDirectDraft,
                    onValueChange = { customDirectDraft = it },
                    label = { Text(strings.customDirectLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text(strings.customDirectHint) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_direct_domains_input")
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customBlockDraft,
                    onValueChange = { customBlockDraft = it },
                    label = { Text(strings.customBlockLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text(strings.customBlockHint) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_block_domains_input")
                )
                Spacer(modifier = Modifier.height(10.dp))
                Shadora3DOutlinedButton(
                    text = strings.saveRoutingCta,
                    onClick = { onSaveCustomRouting(customDirectDraft, customBlockDraft) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_routing_button")
                )
            }

            // 3. Security, Auto-Failover & App Lock
            SectionHeader(strings.sectionSecurity)
            ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                ToggleSettingRow(
                    title = strings.autoConnectLabel,
                    description = strings.autoConnectDesc,
                    checked = appSettings.autoConnect,
                    onCheckedChange = onAutoConnectChange,
                    testTag = "auto_connect_toggle"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                ToggleSettingRow(
                    title = strings.autoFailoverLabel,
                    description = strings.autoFailoverDesc,
                    checked = appSettings.autoFailoverEnabled,
                    onCheckedChange = onAutoFailoverChange,
                    testTag = "auto_failover_toggle"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                ToggleSettingRow(
                    title = strings.killSwitchLabel,
                    description = strings.killSwitchDesc,
                    checked = appSettings.killSwitchEnabled,
                    onCheckedChange = onKillSwitchChange,
                    testTag = "kill_switch_toggle"
                )
                Spacer(modifier = Modifier.height(10.dp))
                Shadora3DOutlinedButton(
                    text = strings.openSystemVpnSettings,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        }
                    },
                    icon = ShadoraIcons.OpenInNew,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                // App Lock (Biometric + 3D Numeric PIN)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = if (appSettings.appLockEnabled) EmeraldSignal else NeonCrimsonGlow,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.appLockLabel, style = MaterialTheme.typography.titleSmall)
                        Text(
                            strings.appLockDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (appSettings.appLockEnabled) {
                    ToggleSettingRow(
                        title = strings.appLockBiometricLabel,
                        description = strings.appLockBiometricDesc,
                        checked = appSettings.appLockBiometricEnabled,
                        onCheckedChange = onAppLockBiometricChange,
                        testTag = "app_lock_biometric_toggle"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Shadora3DOutlinedButton(
                            text = strings.changePinCta,
                            onClick = { showPinSetupDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("change_pin_button")
                        )
                        Shadora3DOutlinedButton(
                            text = strings.disableLockCta,
                            onClick = onDisableAppLock,
                            iconTint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("disable_app_lock_button")
                        )
                    }
                } else {
                    Shadora3DButton(
                        text = strings.setPinCta,
                        onClick = { showPinSetupDialog = true },
                        icon = Icons.Rounded.Lock,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("enable_app_lock_button")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                ToggleSettingRow(
                    title = strings.notificationsLabel,
                    description = strings.notificationsDesc,
                    checked = appSettings.notificationsEnabled,
                    onCheckedChange = { enable ->
                        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onNotificationsChange(enable)
                        }
                    },
                    testTag = "notifications_toggle"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ShadoraIcons.Widgets,
                        contentDescription = null,
                        tint = NeonCrimsonGlow,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.widgetTipTitle, style = MaterialTheme.typography.titleSmall)
                        Text(
                            strings.widgetTipDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. Engine Tuning & Connection History
            SectionHeader(strings.sectionEngine)
            ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(strings.mtuLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${mtuSlider.toInt()} bytes",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = NeonCrimsonGlow
                    )
                }
                Slider(
                    value = mtuSlider,
                    onValueChange = { mtuSlider = it },
                    onValueChangeFinished = {
                        onEngineSettingsChange(mtuSlider.toInt(), xraySettings.muxEnabled, xraySettings.allowInsecureTls)
                    },
                    valueRange = 1280f..1500f,
                    steps = 21
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ToggleSettingRow(
                    title = strings.muxLabel,
                    description = "Xray Outbound Multiplexing (concurrency=8)",
                    checked = xraySettings.muxEnabled,
                    onCheckedChange = {
                        onEngineSettingsChange(mtuSlider.toInt(), it, xraySettings.allowInsecureTls)
                    },
                    testTag = "mux_toggle"
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ToggleSettingRow(
                    title = strings.allowInsecureTlsLabel,
                    description = strings.allowInsecureTlsWarning,
                    checked = xraySettings.allowInsecureTls,
                    onCheckedChange = {
                        onEngineSettingsChange(mtuSlider.toInt(), xraySettings.muxEnabled, it)
                    },
                    testTag = "allow_insecure_toggle"
                )
                if (xraySettings.allowInsecureTls) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Icon(ShadoraIcons.WarningAmber, contentDescription = null, tint = AmberSignal, modifier = Modifier.size(16.dp))
                        Text(strings.allowInsecureTlsWarning, style = MaterialTheme.typography.bodySmall, color = AmberSignal)
                    }
                }
            }

            // Connection History Card
            ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.connectionHistoryTitle, style = MaterialTheme.typography.titleMedium)
                    if (history.isNotEmpty()) {
                        TextButton(onClick = onClearHistory) {
                            Icon(ShadoraIcons.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(strings.clearHistory)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (history.isEmpty()) {
                    Text(
                        text = strings.noHistoryYet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    history.take(8).forEachIndexed { idx, item ->
                        if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.serverName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(
                                    text = "${item.protocol} • ${FormatUtils.formatTimestamp(item.startedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = FormatUtils.formatBytes(item.bytesDown + item.bytesUp),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // 5. Backup, Restore & Safe Diagnostic Logs
            SectionHeader(strings.sectionBackupLogs)
            ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = strings.backupDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Shadora3DOutlinedButton(
                        text = strings.exportBackupCta,
                        onClick = { exportBackupLauncher.launch("shadora-backup.json") },
                        icon = ShadoraIcons.Upload,
                        iconTint = NeonCrimsonGlow,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_backup_button")
                    )
                    Shadora3DOutlinedButton(
                        text = strings.importBackupCta,
                        onClick = { importBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        icon = ShadoraIcons.Download,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import_backup_button")
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Shadora3DOutlinedButton(
                    text = strings.openLogsCta,
                    onClick = {
                        onOpenDiagnosticLogs()
                        showLogsDialog = true
                    },
                    icon = ShadoraIcons.BugReport,
                    iconTint = NeonCrimsonGlow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_diagnostic_logs_button")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showAppPicker) {
        BackHandler { showAppPicker = false }
        SplitTunnelAppPickerDialog(
            apps = installedApps,
            selectedPackages = selectedPackages,
            onToggle = onToggleSplitApp,
            onDismiss = { showAppPicker = false }
        )
    }

    if (showLogsDialog) {
        BackHandler { showLogsDialog = false }
        SafeDiagnosticLogsDialog(
            logs = diagnosticLogs,
            onClear = onClearDiagnosticLogs,
            onExportText = onExportDiagnosticLogsText,
            onCopied = { onToast(strings.copiedNotice) },
            onDismiss = { showLogsDialog = false }
        )
    }

    if (showPinSetupDialog) {
        BackHandler { showPinSetupDialog = false }
        PinSetupDialog(
            onDismiss = { showPinSetupDialog = false },
            onConfirmPin = { pin ->
                onEnableAppLockPin(pin)
                showPinSetupDialog = false
            }
        )
    }
}

@Composable
private fun PinSetupDialog(
    onDismiss: () -> Unit,
    onConfirmPin: (String) -> Unit
) {
    val strings = LocalShadoraStrings.current
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.createPinTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pin1,
                    onValueChange = { value ->
                        if (value.length <= 6 && value.all { it.isDigit() }) {
                            pin1 = value
                            error = null
                        }
                    },
                    label = { Text(strings.pinPlaceholder) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pin_setup_input_1")
                )
                OutlinedTextField(
                    value = pin2,
                    onValueChange = { value ->
                        if (value.length <= 6 && value.all { it.isDigit() }) {
                            pin2 = value
                            error = null
                        }
                    },
                    label = { Text(strings.confirmPinTitle) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pin_setup_input_2")
                )
                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        !AppLockManager.isValidPin(pin1) || pin1.length > 6 -> error = strings.pinInvalidError
                        pin1 != pin2 -> error = strings.pinMismatchError
                        else -> onConfirmPin(pin1)
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("pin_setup_confirm_button")
            ) {
                Text(strings.save, maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel, maxLines = 1, softWrap = false)
            }
        }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = NeonCrimsonGlow,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun ToggleSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun SplitTunnelAppPickerDialog(
    apps: List<InstalledAppItem>,
    selectedPackages: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalShadoraStrings.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    LaunchedEffect(Unit) { /* list loaded on open */ }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.selectAppsCta) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(strings.searchServersHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    items(filtered, key = { it.packageName }) { appItem ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(appItem.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    appItem.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Checkbox(
                                checked = appItem.packageName in selectedPackages,
                                onCheckedChange = { onToggle(appItem.packageName) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(strings.close) }
        }
    )
}

@Composable
private fun SafeDiagnosticLogsDialog(
    logs: List<SafeLogEntry>,
    onClear: () -> Unit,
    onExportText: () -> String,
    onCopied: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalShadoraStrings.current
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.diagnosticLogsTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.logsSanitizedNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (logs.isEmpty()) {
                    Text(
                        text = strings.noLogsYet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(logs) { entry ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "${entry.level}/${entry.tag} • ${FormatUtils.formatTimestamp(entry.timestampMillis)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = NeonCrimsonGlow
                                )
                                Text(
                                    text = entry.message,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val text = onExportText()
                    if (text.isNotBlank()) {
                        clipboard.setText(AnnotatedString(text))
                        onCopied()
                    }
                },
                enabled = logs.isNotEmpty(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(ShadoraIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text(strings.copyLogsCta, maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (logs.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text(strings.clearLogsCta, maxLines = 1, softWrap = false)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(strings.close, maxLines = 1, softWrap = false)
                }
            }
        }
    )
}
