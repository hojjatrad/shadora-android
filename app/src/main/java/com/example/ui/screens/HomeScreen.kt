package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import com.example.ui.theme.ShadoraIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.configmanager.CountryDetector
import com.example.core.i18n.LocalShadoraStrings
import com.example.core.model.AppLanguage
import com.example.core.model.ConnectionState
import com.example.core.model.TrafficSnapshot
import com.example.core.model.VpnProtocol
import com.example.core.util.FormatUtils
import com.example.data.local.AppSettingsEntity
import com.example.data.local.ServerEntity
import com.example.ui.components.LatencyBadge
import com.example.ui.components.OrbitalConnectButton
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.Shadora3DButton
import com.example.ui.components.Shadora3DKeyContainer
import com.example.ui.components.Shadora3DOutlinedButton
import com.example.ui.components.ShadoraCard
import com.example.ui.components.TrafficSparkline
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCrimsonGlow
import com.example.ui.theme.OutfitFamily
import com.example.vpnservice.PublicIpCheckResult

@Composable
fun HomeScreen(
    connectionState: ConnectionState,
    traffic: TrafficSnapshot,
    selectedServer: ServerEntity?,
    appSettings: AppSettingsEntity,
    hasServers: Boolean,
    isSmartConnecting: Boolean,
    isCheckingIp: Boolean,
    publicIpResult: PublicIpCheckResult?,
    onToggleConnect: () -> Unit,
    onSmartConnect: () -> Unit,
    onCheckPublicIp: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenAddConfig: () -> Unit,
    onToggleLanguage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalShadoraStrings.current
    val isPersian = AppLanguage.fromCode(appSettings.languageCode) == AppLanguage.FA
    val isConnected = connectionState is ConnectionState.Connected

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            NeonCrimson.copy(alpha = if (isConnected) 0.19f else 0.09f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.24f),
                        radius = size.maxDimension * 0.58f
                    )
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            // Luxury Top Brand Bar with 3D Tactile Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_shadora_icon),
                        contentDescription = strings.appTitle,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(13.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.appTitle,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = OutfitFamily,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.6.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = strings.tagline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Shadora3DKeyContainer(
                        onClick = onToggleLanguage,
                        modifier = Modifier.testTag("home_language_toggle")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = ShadoraIcons.Language,
                                contentDescription = strings.languageLabel,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isPersian) "EN" else "فا",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Shadora3DKeyContainer(
                        onClick = onOpenAddConfig,
                        accentCrimson = true,
                        modifier = Modifier
                            .testTag("home_add_config_button")
                            .size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = strings.addConfigTitle,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Live Connection Status Pill
            StatusBannerPill(connectionState = connectionState, isPersian = isPersian)

            // Centerpiece 3D Sculpted Orbital Reactor Button
            OrbitalConnectButton(
                connectionState = connectionState,
                actionHint = if (isConnected) strings.tapToDisconnect else strings.tapToConnect,
                onClick = onToggleConnect,
                modifier = Modifier.padding(vertical = 2.dp)
            )

            // Full-Text Honest Error / Native Core Notice Card
            AnimatedVisibility(
                visible = connectionState is ConnectionState.Error,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val err = connectionState as? ConnectionState.Error
                if (err != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = ErrorRed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = ShadoraIcons.ErrorOutline,
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = if (isPersian) err.messageFa else err.messageEn,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Engine Mode & Network Info when connected
            AnimatedVisibility(visible = connectionState is ConnectionState.Connected) {
                val conn = connectionState as? ConnectionState.Connected
                if (conn != null) {
                    ShadoraCard(
                        modifier = Modifier.fillMaxWidth(),
                        highlighted = true
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (conn.isFullNativeTunnel) EmeraldSignal else AmberSignal)
                                )
                                Text(
                                    text = conn.engineModeLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = conn.localAddress,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = ShadoraIcons.Wifi,
                                    contentDescription = strings.networkLabel,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${strings.networkLabel}: ${conn.underlyingNetwork}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = ShadoraIcons.Dns,
                                    contentDescription = strings.dnsLabel,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = conn.dnsServers.firstOrNull() ?: appSettings.primaryDns,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Active Server Selector Card
            ShadoraCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("active_server_card"),
                onClick = if (selectedServer != null) onOpenServers else onOpenAddConfig
            ) {
                Text(
                    text = strings.activeServer,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (selectedServer != null) {
                    val proto = VpnProtocol.fromName(selectedServer.protocol)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = CountryDetector.flagForCode(selectedServer.countryCode),
                                        fontSize = 22.sp
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedServer.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ProtocolBadge(protocol = proto)
                                    LatencyBadge(
                                        latencyMs = selectedServer.latencyMs,
                                        timeoutLabel = "—"
                                    )
                                }
                            }
                        }
                        Icon(
                            imageVector = ShadoraIcons.SwapHoriz,
                            contentDescription = strings.selectServerCta,
                            tint = NeonCrimsonGlow
                        )
                    }
                } else {
                    // Clean stacked responsive layout so button text always sits completely inside its frame on any phone width
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = ShadoraIcons.Public,
                                contentDescription = strings.noServerSelected,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = strings.noServerSelected,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Shadora3DButton(
                            text = strings.addFirstConfigCta,
                            onClick = onOpenAddConfig,
                            icon = Icons.Rounded.Add,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("home_add_first_config_button")
                        )
                    }
                }
            }

            // Quick Pro Tools Row: 3D Smart Connect + 3D Public IP Check
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Shadora3DOutlinedButton(
                    text = if (isSmartConnecting) strings.smartConnecting else strings.smartConnectCta,
                    onClick = onSmartConnect,
                    icon = ShadoraIcons.Bolt,
                    iconTint = NeonCrimsonGlow,
                    enabled = hasServers && !isSmartConnecting,
                    loading = isSmartConnecting,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_smart_connect_button")
                )

                Shadora3DOutlinedButton(
                    text = if (isCheckingIp) strings.checkingIp else strings.checkPublicIpCta,
                    onClick = onCheckPublicIp,
                    icon = ShadoraIcons.Radar,
                    iconTint = EmeraldSignal,
                    enabled = !isCheckingIp,
                    loading = isCheckingIp,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_check_ip_button")
                )
            }

            if (publicIpResult != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, EmeraldSignal.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = CountryDetector.flagForCode(publicIpResult.countryCode),
                                fontSize = 18.sp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.publicIpLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Text(
                                    text = publicIpResult.ip,
                                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Text(
                            text = "${publicIpResult.countryCode} • ${publicIpResult.latencyMs}ms",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = EmeraldSignal,
                            maxLines = 1
                        )
                    }
                }
            }

            // Telemetry 2x2 Grid (Duration, Download, Upload, Total Data)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TelemetryTile(
                    icon = ShadoraIcons.Schedule,
                    label = strings.durationLabel,
                    value = FormatUtils.formatDuration(traffic.elapsedSeconds),
                    accent = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                TelemetryTile(
                    icon = ShadoraIcons.DataUsage,
                    label = strings.totalDataLabel,
                    value = FormatUtils.formatBytes(traffic.totalRxBytes + traffic.totalTxBytes),
                    accent = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TelemetryTile(
                    icon = ShadoraIcons.ArrowDownward,
                    label = strings.downloadLabel,
                    value = FormatUtils.formatSpeed(traffic.rxBytesPerSec),
                    accent = NeonCrimsonGlow,
                    modifier = Modifier.weight(1f)
                )
                TelemetryTile(
                    icon = ShadoraIcons.ArrowUpward,
                    label = strings.uploadLabel,
                    value = FormatUtils.formatSpeed(traffic.txBytesPerSec),
                    accent = EmeraldSignal,
                    modifier = Modifier.weight(1f)
                )
            }

            // Live Throughput Sparkline Card
            if (isConnected) {
                ShadoraCard(modifier = Modifier.fillMaxWidth()) {
                    TrafficSparkline(
                        rxHistory = traffic.rxHistory,
                        txHistory = traffic.txHistory
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StatusBannerPill(
    connectionState: ConnectionState,
    isPersian: Boolean
) {
    val strings = LocalShadoraStrings.current
    val (label, dotColor) = when (connectionState) {
        ConnectionState.Disconnected -> strings.statusDisconnected to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.Preparing -> strings.statusPreparing to AmberSignal
        is ConnectionState.Connecting -> "${strings.statusConnecting} (${connectionState.stage})" to AmberSignal
        is ConnectionState.Connected -> strings.statusConnected to EmeraldSignal
        is ConnectionState.Reconnecting -> "${strings.statusReconnecting} #${connectionState.attempt}" to AmberSignal
        ConnectionState.Disconnecting -> strings.statusDisconnecting to AmberSignal
        is ConnectionState.Error -> (if (isPersian) connectionState.messageFa else connectionState.messageEn) to ErrorRed
    }

    Surface(
        modifier = Modifier.testTag("connection_status_pill"),
        shape = RoundedCornerShape(50),
        color = dotColor.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TelemetryTile(
    icon: ImageVector,
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    ShadoraCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
