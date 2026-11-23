package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import com.example.ui.theme.ShadoraIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.configmanager.CountryDetector
import com.example.core.i18n.LocalShadoraStrings
import com.example.core.model.SortOrder
import com.example.core.model.VpnProtocol
import com.example.data.local.ServerEntity
import com.example.data.local.SubscriptionEntity
import com.example.ui.ServerInspectionData
import com.example.ui.components.LatencyBadge
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.Shadora3DButton
import com.example.ui.components.Shadora3DOutlinedButton
import com.example.ui.components.ShadoraCard
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.NeonCrimsonGlow

@Composable
fun ServersScreen(
    servers: List<ServerEntity>,
    subscriptions: List<SubscriptionEntity>,
    selectedServerId: Long,
    searchQuery: String,
    sortOrder: SortOrder,
    selectedCountry: String?,
    selectedProtocol: VpnProtocol?,
    selectedSubscriptionId: Long?,
    favoritesOnly: Boolean,
    isPingingAll: Boolean,
    isSmartConnecting: Boolean,
    inspectedServer: ServerInspectionData?,
    onSearchChange: (String) -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onCountryFilterChange: (String?) -> Unit,
    onProtocolFilterChange: (VpnProtocol?) -> Unit,
    onSubscriptionFilterChange: (Long?) -> Unit,
    onToggleFavoritesOnly: () -> Unit,
    onPingAll: () -> Unit,
    onSmartConnect: () -> Unit,
    onPingServer: (ServerEntity) -> Unit,
    onSelectServer: (ServerEntity) -> Unit,
    onConnectServer: (ServerEntity) -> Unit,
    onToggleFavorite: (ServerEntity) -> Unit,
    onInspectServer: (ServerEntity) -> Unit,
    onRenameServer: (Long, String) -> Unit,
    onCloseInspector: () -> Unit,
    onDeleteServer: (Long) -> Unit,
    onDeleteUnreachable: () -> Unit,
    onOpenAddConfig: () -> Unit,
    onCopySuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalShadoraStrings.current
    var pendingDeleteServer by remember { mutableStateOf<ServerEntity?>(null) }
    var showCleanUnreachableConfirm by remember { mutableStateOf(false) }

    val unreachableCount = remember(servers) { servers.count { it.latencyMs == -2 } }
    val hasManualServers = remember(servers) { servers.any { it.isManual } }

    val availableCountries = remember(servers) {
        servers.map { it.countryCode to it.countryName }.distinctBy { it.first }.sortedBy { it.second }
    }

    val filteredServers = remember(
        servers, searchQuery, sortOrder, selectedCountry, selectedProtocol, selectedSubscriptionId, favoritesOnly
    ) {
        val q = searchQuery.trim().lowercase()
        servers
            .filter { server ->
                val matchesQuery = q.isEmpty() ||
                    server.name.lowercase().contains(q) ||
                    server.host.lowercase().contains(q) ||
                    server.countryName.lowercase().contains(q) ||
                    server.protocol.lowercase().contains(q)
                val matchesCountry = selectedCountry == null || server.countryCode == selectedCountry
                val matchesProto = selectedProtocol == null || server.protocol.equals(selectedProtocol.name, ignoreCase = true)
                val matchesSub = when (selectedSubscriptionId) {
                    null -> true
                    -1L -> server.isManual
                    else -> server.subscriptionId == selectedSubscriptionId
                }
                val matchesFav = !favoritesOnly || server.isFavorite
                matchesQuery && matchesCountry && matchesProto && matchesSub && matchesFav
            }
            .let { list ->
                when (sortOrder) {
                    SortOrder.PING_ASC -> list.sortedWith(
                        compareBy<ServerEntity> { !it.isFavorite }
                            .thenBy { if (it.latencyMs > 0) it.latencyMs else Int.MAX_VALUE }
                            .thenBy { it.name.lowercase() }
                    )
                    SortOrder.NAME_ASC -> list.sortedWith(
                        compareBy<ServerEntity> { !it.isFavorite }.thenBy { it.name.lowercase() }
                    )
                    SortOrder.PROTOCOL -> list.sortedWith(
                        compareBy<ServerEntity> { !it.isFavorite }.thenBy { it.protocol }.thenBy { it.name }
                    )
                    SortOrder.RECENT -> list.sortedByDescending { it.createdAt }
                }
            }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header Title + Balanced Responsive Action Buttons
            Text(
                text = "${strings.serversTitle} (${filteredServers.size})",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Shadora3DOutlinedButton(
                    text = if (isPingingAll) strings.testingPing else strings.pingAll,
                    onClick = onPingAll,
                    icon = ShadoraIcons.NetworkPing,
                    enabled = !isPingingAll && servers.isNotEmpty(),
                    loading = isPingingAll,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ping_all_button")
                )
                Shadora3DButton(
                    text = strings.addConfigTitle,
                    onClick = onOpenAddConfig,
                    icon = Icons.Rounded.Add,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("servers_add_button")
                )
            }

            // Secondary quick actions (3D Smart Connect + Clean Unreachable if any timed out)
            if (servers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Shadora3DOutlinedButton(
                        text = if (isSmartConnecting) strings.smartConnecting else strings.smartConnectCta,
                        onClick = onSmartConnect,
                        icon = ShadoraIcons.Bolt,
                        iconTint = NeonCrimsonGlow,
                        enabled = !isSmartConnecting,
                        loading = isSmartConnecting,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("servers_smart_connect_button")
                    )
                    if (unreachableCount > 0) {
                        Shadora3DOutlinedButton(
                            text = "${strings.cleanUnreachableCta} ($unreachableCount)",
                            onClick = { showCleanUnreachableConfirm = true },
                            icon = ShadoraIcons.CleaningServices,
                            iconTint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("clean_unreachable_button")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_search_input"),
                placeholder = { Text(strings.searchServersHint) },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = strings.searchServersHint)
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Subscription / Manual Group Filter Row
            if (subscriptions.isNotEmpty() || hasManualServers) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SortChip(strings.allGroupsLabel, selectedSubscriptionId == null) {
                        onSubscriptionFilterChange(null)
                    }
                    if (hasManualServers) {
                        SortChip(strings.manualGroupLabel, selectedSubscriptionId == -1L) {
                            onSubscriptionFilterChange(if (selectedSubscriptionId == -1L) null else -1L)
                        }
                    }
                    subscriptions.forEach { sub ->
                        SortChip(
                            label = sub.name,
                            selected = selectedSubscriptionId == sub.id,
                            onClick = {
                                onSubscriptionFilterChange(if (selectedSubscriptionId == sub.id) null else sub.id)
                            }
                        )
                    }
                }
            }

            // Sort & Favorites Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SortChip(strings.sortPing, sortOrder == SortOrder.PING_ASC) { onSortChange(SortOrder.PING_ASC) }
                SortChip(strings.sortName, sortOrder == SortOrder.NAME_ASC) { onSortChange(SortOrder.NAME_ASC) }
                SortChip(strings.sortProtocol, sortOrder == SortOrder.PROTOCOL) { onSortChange(SortOrder.PROTOCOL) }
                SortChip(strings.sortRecent, sortOrder == SortOrder.RECENT) { onSortChange(SortOrder.RECENT) }
                FilterChip(
                    selected = favoritesOnly,
                    onClick = onToggleFavoritesOnly,
                    label = { Text(strings.favoriteOnly) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (favoritesOnly) Icons.Rounded.Star else ShadoraIcons.StarBorder,
                            contentDescription = strings.favoriteOnly,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            // Country Chips Row
            if (availableCountries.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SortChip(strings.allCountries, selectedCountry == null) { onCountryFilterChange(null) }
                    availableCountries.forEach { (code, name) ->
                        SortChip(
                            label = "${CountryDetector.flagForCode(code)} $name",
                            selected = selectedCountry == code,
                            onClick = { onCountryFilterChange(if (selectedCountry == code) null else code) }
                        )
                    }
                }
            }

            // Protocol Chips Row
            val usedProtocols = remember(servers) {
                servers.map { VpnProtocol.fromName(it.protocol) }.distinct()
            }
            if (usedProtocols.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SortChip(strings.allProtocols, selectedProtocol == null) { onProtocolFilterChange(null) }
                    usedProtocols.forEach { proto ->
                        SortChip(
                            label = proto.displayName,
                            selected = selectedProtocol == proto,
                            onClick = { onProtocolFilterChange(if (selectedProtocol == proto) null else proto) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Server List or Empty State
            if (filteredServers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = ShadoraIcons.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = strings.emptyServersTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = strings.emptyServersSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Shadora3DButton(
                            text = strings.addConfigTitle,
                            onClick = onOpenAddConfig,
                            icon = Icons.Rounded.Add,
                            modifier = Modifier.fillMaxWidth(0.75f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredServers, key = { it.id }) { server ->
                        ServerItemCard(
                            server = server,
                            isSelected = server.id == selectedServerId,
                            onSelect = { onSelectServer(server) },
                            onConnect = { onConnectServer(server) },
                            onPing = { onPingServer(server) },
                            onToggleFavorite = { onToggleFavorite(server) },
                            onInspect = { onInspectServer(server) },
                            onRequestDelete = { pendingDeleteServer = server }
                        )
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    val targetDelete = pendingDeleteServer
    if (targetDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteServer = null },
            title = { Text(strings.deleteServerTitle) },
            text = { Text("${strings.deleteServerConfirm}\n\n${targetDelete.name}") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteServer(targetDelete.id)
                        pendingDeleteServer = null
                    }
                ) {
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteServer = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    // Clean Unreachable Confirmation Dialog
    if (showCleanUnreachableConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanUnreachableConfirm = false },
            title = { Text(strings.cleanUnreachableTitle) },
            text = { Text("${strings.cleanUnreachableConfirm} ($unreachableCount)") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteUnreachable()
                        showCleanUnreachableConfirm = false
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(strings.delete, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanUnreachableConfirm = false }) {
                    Text(strings.cancel, maxLines = 1, softWrap = false)
                }
            }
        )
    }

    // Server Config Inspector + QR + Rename + Share Modal
    if (inspectedServer != null) {
        BackHandler(onBack = onCloseInspector)
        ServerInspectorDialog(
            data = inspectedServer,
            onRename = { newName -> onRenameServer(inspectedServer.server.id, newName) },
            onDismiss = onCloseInspector,
            onCopySuccess = onCopySuccess
        )
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = NeonCrimsonGlow
        )
    )
}

@Composable
private fun ServerItemCard(
    server: ServerEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onPing: () -> Unit,
    onToggleFavorite: () -> Unit,
    onInspect: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val strings = LocalShadoraStrings.current
    val proto = VpnProtocol.fromName(server.protocol)

    ShadoraCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("server_card_${server.id}"),
        highlighted = isSelected,
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(CountryDetector.flagForCode(server.countryCode), fontSize = 20.sp)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${server.host}:${server.port} • ${server.transport.uppercase()}/${server.security.uppercase()}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            LatencyBadge(latencyMs = server.latencyMs, timeoutLabel = strings.pingTimeout)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProtocolBadge(protocol = proto)
            if (server.isManual) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = strings.manualTag,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (server.isFavorite) Icons.Rounded.Star else ShadoraIcons.StarBorder,
                    contentDescription = strings.favoriteOnly,
                    tint = if (server.isFavorite) AmberSignal else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPing) {
                Icon(
                    imageVector = ShadoraIcons.NetworkPing,
                    contentDescription = strings.pingAll,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onInspect) {
                Icon(
                    imageVector = ShadoraIcons.Code,
                    contentDescription = strings.inspectConfigTitle,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRequestDelete) {
                Icon(
                    imageVector = ShadoraIcons.DeleteOutline,
                    contentDescription = strings.delete,
                    tint = MaterialTheme.colorScheme.error
                )
            }
            IconButton(onClick = onConnect) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = strings.tapToConnect,
                    tint = NeonCrimsonGlow
                )
            }
        }
    }
}

@Composable
private fun ServerInspectorDialog(
    data: ServerInspectionData,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
    onCopySuccess: () -> Unit
) {
    val strings = LocalShadoraStrings.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var nameDraft by remember(data.server.id, data.server.name) { mutableStateOf(data.server.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(data.server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Rename Server Field
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text(strings.renameServerTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onRename(nameDraft) },
                        enabled = nameDraft.trim().isNotEmpty() && nameDraft.trim() != data.server.name
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = strings.save,
                            tint = NeonCrimsonGlow
                        )
                    }
                }

                if (data.qrBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Image(
                            bitmap = data.qrBitmap.asImageBitmap(),
                            contentDescription = strings.qrCodeTitle,
                            modifier = Modifier
                                .size(200.dp)
                                .padding(10.dp)
                        )
                    }
                }

                // Quick Copy + Share Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(data.rawPayload))
                            onCopySuccess()
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                    ) {
                        Icon(ShadoraIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(strings.copyConfig, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, data.server.name)
                                putExtra(Intent.EXTRA_TEXT, data.rawPayload)
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(sendIntent, strings.shareConfigCta))
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(strings.shareConfigCta, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }

                Text(
                    text = strings.xrayJsonPreview,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth()
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = data.compiledEngineOutput.take(2500),
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(strings.close, maxLines = 1, softWrap = false)
            }
        }
    )
}
