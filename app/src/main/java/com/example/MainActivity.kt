package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import com.example.ui.theme.ShadoraIcons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.LocalShadoraStrings
import com.example.core.i18n.stringsFor
import com.example.core.model.AppLanguage
import com.example.core.model.AppThemeMode
import com.example.core.model.ConnectionState
import com.example.data.local.EngineSettingsEntity
import com.example.ui.ShadoraTab
import com.example.ui.ShadoraViewModel
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.AddConfigScreen
import com.example.ui.screens.AppLockOverlay
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ServersScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SubscriptionsScreen
import com.example.ui.theme.ShadoraTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ShadoraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingShareOrViewIntent(intent)

        setContent {
            ShadoraRootApp(viewModel = viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareOrViewIntent(intent)
    }

    private fun handleIncomingShareOrViewIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    viewModel.openAddConfig(sharedText)
                } else {
                    @Suppress("DEPRECATION")
                    val streamUri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    if (streamUri != null) {
                        val content = runCatching {
                            contentResolver.openInputStream(streamUri)?.use {
                                String(it.readNBytes(2 * 1024 * 1024), Charsets.UTF_8)
                            }
                        }.getOrNull()
                        if (!content.isNullOrBlank()) {
                            viewModel.openAddConfig(content)
                        }
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val dataStr = intent.dataString
                if (!dataStr.isNullOrBlank()) {
                    viewModel.openAddConfig(dataStr)
                }
            }
        }
    }
}

private data class NavItemSpec(
    val tab: ShadoraTab,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@Composable
fun ShadoraRootApp(viewModel: ShadoraViewModel) {
    val context = LocalContext.current
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val history by viewModel.connectionHistory.collectAsStateWithLifecycle()
    val engineSettingsList by viewModel.engineSettingsList.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val traffic by viewModel.trafficSnapshot.collectAsStateWithLifecycle()
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val showAddConfig by viewModel.showAddConfigScreen.collectAsStateWithLifecycle()
    val pendingDraft by viewModel.pendingImportDraft.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val isPingingAll by viewModel.isPingingAll.collectAsStateWithLifecycle()
    val refreshingSubIds by viewModel.refreshingSubIds.collectAsStateWithLifecycle()
    val searchQuery by viewModel.serverSearchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.serverSortOrder.collectAsStateWithLifecycle()
    val selectedCountry by viewModel.selectedCountryFilter.collectAsStateWithLifecycle()
    val selectedProtocol by viewModel.selectedProtocolFilter.collectAsStateWithLifecycle()
    val selectedSubscription by viewModel.selectedSubscriptionFilter.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()
    val inspectedServer by viewModel.inspectedServer.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val isSmartConnecting by viewModel.isSmartConnecting.collectAsStateWithLifecycle()
    val isCheckingIp by viewModel.isCheckingIp.collectAsStateWithLifecycle()
    val publicIpResult by viewModel.publicIpResult.collectAsStateWithLifecycle()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsStateWithLifecycle()
    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingConnectServerId by remember { mutableLongStateOf(-1L) }
    var hasAttemptedAutoConnect by remember { mutableStateOf(false) }
    var hasPromptedNotificationPerm by remember { mutableStateOf(false) }
    var lastBackgroundedAt by remember { mutableLongStateOf(0L) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Non-blocking: live speed notification displays when granted */ }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (pendingConnectServerId > 0L) {
                viewModel.connectServer(pendingConnectServerId)
            } else {
                viewModel.connectSelectedServer()
            }
        } else {
            viewModel.notifyVpnPermissionDenied()
        }
        pendingConnectServerId = -1L
    }

    fun requestVpnConnect(serverId: Long? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appSettings.notificationsEnabled &&
            !hasPromptedNotificationPerm &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            hasPromptedNotificationPerm = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            pendingConnectServerId = serverId ?: -1L
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            if (serverId != null) viewModel.connectServer(serverId)
            else viewModel.connectSelectedServer()
        }
    }

    // Lifecycle-aware App Lock auto-re-lock after >8s in background (avoids re-locking during system dialogs)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    lastBackgroundedAt = System.currentTimeMillis()
                }
                Lifecycle.Event.ON_START -> {
                    val elapsedBg = System.currentTimeMillis() - lastBackgroundedAt
                    if (lastBackgroundedAt > 0L && elapsedBg > 8_000L) {
                        viewModel.lockAppIfEnabled()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-Connect on app launch when enabled, unlocked, consented, and a server is available
    LaunchedEffect(appSettings.autoConnect, appSettings.appLockEnabled, isAppUnlocked, servers.size) {
        val lockCleared = !appSettings.appLockEnabled || appSettings.appLockPinHash.isBlank() || isAppUnlocked
        if (!hasAttemptedAutoConnect &&
            appSettings.autoConnect &&
            lockCleared &&
            servers.isNotEmpty() &&
            connectionState is ConnectionState.Disconnected &&
            VpnService.prepare(context) == null
        ) {
            hasAttemptedAutoConnect = true
            viewModel.connectSelectedServer()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val language = AppLanguage.fromCode(appSettings.languageCode)
    val themeMode = runCatching { AppThemeMode.valueOf(appSettings.themeMode) }
        .getOrDefault(AppThemeMode.OBSIDIAN_DARK)
    val strings = stringsFor(language)
    val layoutDirection = if (language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    val selectedServer = remember(servers, appSettings.selectedServerId) {
        servers.firstOrNull { it.id == appSettings.selectedServerId } ?: servers.firstOrNull()
    }
    val xraySettings = remember(engineSettingsList) {
        engineSettingsList.firstOrNull { it.engineId == "xray" } ?: EngineSettingsEntity(engineId = "xray")
    }

    val navItems = listOf(
        NavItemSpec(ShadoraTab.HOME, strings.navHome, ShadoraIcons.Shield, ShadoraIcons.ShieldOutlined, "nav_home"),
        NavItemSpec(ShadoraTab.SERVERS, strings.navServers, ShadoraIcons.Dns, ShadoraIcons.DnsOutlined, "nav_servers"),
        NavItemSpec(ShadoraTab.SUBSCRIPTIONS, strings.navSubscriptions, ShadoraIcons.CloudSync, ShadoraIcons.CloudSyncOutlined, "nav_subscriptions"),
        NavItemSpec(ShadoraTab.SETTINGS, strings.navSettings, Icons.Rounded.Settings, Icons.Outlined.Settings, "nav_settings"),
        NavItemSpec(ShadoraTab.ABOUT, strings.navAbout, Icons.Rounded.Info, Icons.Outlined.Info, "nav_about")
    )

    // Handle back press on non-Home tabs to return to Home
    BackHandler(enabled = !showAddConfig && currentTab != ShadoraTab.HOME) {
        viewModel.selectTab(ShadoraTab.HOME)
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalShadoraStrings provides strings
    ) {
        ShadoraTheme(themeMode = themeMode) {
            val requiresAppLock = appSettings.appLockEnabled &&
                appSettings.appLockPinHash.isNotBlank() &&
                !isAppUnlocked
            if (requiresAppLock) {
                AppLockOverlay(
                    storedPinHash = appSettings.appLockPinHash,
                    biometricEnabled = appSettings.appLockBiometricEnabled,
                    onUnlocked = viewModel::unlockApp
                )
            } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavigationRail = maxWidth >= 600.dp

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    bottomBar = {
                        if (!useNavigationRail && !showAddConfig) {
                            Column {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                )
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 0.dp
                                ) {
                                    navItems.forEach { item ->
                                        val selected = currentTab == item.tab
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = { viewModel.selectTab(item.tab) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            ),
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                                    contentDescription = item.label
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = item.label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            modifier = Modifier.testTag(item.testTag)
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (useNavigationRail && !showAddConfig) {
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surface
                            ) {
                                navItems.forEach { item ->
                                    val selected = currentTab == item.tab
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = { viewModel.selectTab(item.tab) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                                contentDescription = item.label
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = item.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        modifier = Modifier.testTag(item.testTag)
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            val activeRouteKey = if (showAddConfig) "ADD_CONFIG" else currentTab.name
                            AnimatedContent(
                                targetState = activeRouteKey,
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(210, easing = FastOutSlowInEasing)) +
                                        slideInVertically(animationSpec = tween(210, easing = FastOutSlowInEasing)) { it / 28 })
                                        .togetherWith(fadeOut(animationSpec = tween(150)))
                                },
                                label = "shadora_screen_transition"
                            ) { routeKey ->
                                if (routeKey == "ADD_CONFIG") {
                                    AddConfigScreen(
                                        draftText = pendingDraft,
                                        isImporting = isImporting,
                                        onDraftChange = viewModel::updateImportDraft,
                                        detectProtocol = viewModel::detectProtocolPreview,
                                        isSubscriptionUrl = viewModel::isSubscriptionUrlDraft,
                                        onImportSubmit = { viewModel.importConfigsFromText(it) },
                                        onToast = viewModel::emitToast,
                                        onClose = viewModel::closeAddConfig
                                    )
                                } else {
                                    when (ShadoraTab.valueOf(routeKey)) {
                                    ShadoraTab.HOME -> HomeScreen(
                                        connectionState = connectionState,
                                        traffic = traffic,
                                        selectedServer = selectedServer,
                                        appSettings = appSettings,
                                        hasServers = servers.isNotEmpty(),
                                        isSmartConnecting = isSmartConnecting,
                                        isCheckingIp = isCheckingIp,
                                        publicIpResult = publicIpResult,
                                        onToggleConnect = {
                                            if (connectionState is ConnectionState.Connected ||
                                                connectionState is ConnectionState.Connecting ||
                                                connectionState is ConnectionState.Reconnecting
                                            ) {
                                                viewModel.disconnect()
                                            } else {
                                                requestVpnConnect(selectedServer?.id)
                                            }
                                        },
                                        onSmartConnect = {
                                            viewModel.findFastestServerForSmartConnect { fastestId ->
                                                requestVpnConnect(fastestId)
                                            }
                                        },
                                        onCheckPublicIp = viewModel::checkPublicIp,
                                        onOpenServers = { viewModel.selectTab(ShadoraTab.SERVERS) },
                                        onOpenAddConfig = { viewModel.openAddConfig() },
                                        onToggleLanguage = viewModel::toggleLanguage
                                    )

                                    ShadoraTab.SERVERS -> ServersScreen(
                                        servers = servers,
                                        subscriptions = subscriptions,
                                        selectedServerId = selectedServer?.id ?: -1L,
                                        searchQuery = searchQuery,
                                        sortOrder = sortOrder,
                                        selectedCountry = selectedCountry,
                                        selectedProtocol = selectedProtocol,
                                        selectedSubscriptionId = selectedSubscription,
                                        favoritesOnly = favoritesOnly,
                                        isPingingAll = isPingingAll,
                                        isSmartConnecting = isSmartConnecting,
                                        inspectedServer = inspectedServer,
                                        onSearchChange = viewModel::updateSearchQuery,
                                        onSortChange = viewModel::updateSortOrder,
                                        onCountryFilterChange = viewModel::updateCountryFilter,
                                        onProtocolFilterChange = viewModel::updateProtocolFilter,
                                        onSubscriptionFilterChange = viewModel::updateSubscriptionFilter,
                                        onToggleFavoritesOnly = viewModel::toggleFavoritesOnly,
                                        onPingAll = viewModel::pingAllServers,
                                        onSmartConnect = {
                                            viewModel.findFastestServerForSmartConnect { fastestId ->
                                                viewModel.selectTab(ShadoraTab.HOME)
                                                requestVpnConnect(fastestId)
                                            }
                                        },
                                        onPingServer = viewModel::pingSingleServer,
                                        onSelectServer = { viewModel.selectServer(it.id) },
                                        onConnectServer = {
                                            viewModel.selectTab(ShadoraTab.HOME)
                                            requestVpnConnect(it.id)
                                        },
                                        onToggleFavorite = viewModel::toggleServerFavorite,
                                        onInspectServer = { viewModel.inspectServer(it.id) },
                                        onRenameServer = viewModel::renameServer,
                                        onCloseInspector = viewModel::closeServerInspector,
                                        onDeleteServer = viewModel::deleteServer,
                                        onDeleteUnreachable = viewModel::deleteUnreachableServers,
                                        onOpenAddConfig = { viewModel.openAddConfig() },
                                        onCopySuccess = { viewModel.emitToast(strings.copiedNotice) }
                                    )

                                    ShadoraTab.SUBSCRIPTIONS -> SubscriptionsScreen(
                                        subscriptions = subscriptions,
                                        refreshingIds = refreshingSubIds,
                                        onAddSubscription = viewModel::addSubscription,
                                        onEditSubscription = viewModel::updateSubscriptionDetails,
                                        onRefreshSubscription = viewModel::refreshSubscription,
                                        onRefreshAll = viewModel::refreshAllSubscriptions,
                                        onDeleteSubscription = viewModel::deleteSubscription
                                    )

                                    ShadoraTab.SETTINGS -> SettingsScreen(
                                        appSettings = appSettings,
                                        xraySettings = xraySettings,
                                        history = history,
                                        installedApps = installedApps,
                                        diagnosticLogs = diagnosticLogs,
                                        onLanguageChange = viewModel::setLanguage,
                                        onThemeChange = viewModel::setThemeMode,
                                        onDnsSave = viewModel::updateDns,
                                        onIpv6Change = viewModel::setIpv6Enabled,
                                        onBypassIranChange = viewModel::setBypassIranDomains,
                                        onBypassLanChange = viewModel::setBypassLanCidrs,
                                        onSaveCustomRouting = viewModel::saveCustomRoutingDomains,
                                        onSplitModeChange = viewModel::setSplitTunnelMode,
                                        onLoadInstalledApps = viewModel::loadLauncherAppsForSplitTunnel,
                                        onToggleSplitApp = viewModel::toggleSplitTunnelPackage,
                                        onAutoConnectChange = viewModel::setAutoConnect,
                                        onAutoFailoverChange = viewModel::setAutoFailover,
                                        onEnableAppLockPin = viewModel::enableAppLockWithPin,
                                        onDisableAppLock = viewModel::disableAppLock,
                                        onAppLockBiometricChange = viewModel::setAppLockBiometric,
                                        onKillSwitchChange = viewModel::setKillSwitch,
                                        onNotificationsChange = viewModel::setNotificationsEnabled,
                                        onEngineSettingsChange = viewModel::updateXrayEngineSettings,
                                        onClearHistory = viewModel::clearHistory,
                                        onExportBackupJson = viewModel::generateBackupJson,
                                        onImportBackupJson = viewModel::restoreBackupFromJson,
                                        onOpenDiagnosticLogs = viewModel::refreshDiagnosticLogs,
                                        onClearDiagnosticLogs = viewModel::clearDiagnosticLogs,
                                        onExportDiagnosticLogsText = viewModel::exportDiagnosticLogsText,
                                        onToast = viewModel::emitToast,
                                        onSavedNotice = { viewModel.emitToast(strings.save) }
                                    )

                                    ShadoraTab.ABOUT -> AboutScreen(
                                        language = language,
                                        diagnostics = viewModel.engineDiagnostics
                                    )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
