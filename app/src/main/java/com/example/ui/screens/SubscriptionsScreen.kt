package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import com.example.ui.theme.ShadoraIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalShadoraStrings
import com.example.core.model.RefreshIntervalHours
import com.example.core.util.FormatUtils
import com.example.data.local.SubscriptionEntity
import com.example.subscriptionmanager.SubscriptionAutoRefreshScheduler
import com.example.ui.components.Shadora3DButton
import com.example.ui.components.Shadora3DOutlinedButton
import com.example.ui.components.ShadoraCard
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.NeonCrimsonGlow

@Composable
fun SubscriptionsScreen(
    subscriptions: List<SubscriptionEntity>,
    refreshingIds: Set<Long>,
    onAddSubscription: (name: String, url: String, intervalHours: Int) -> Unit,
    onEditSubscription: (id: Long, name: String, url: String, intervalHours: Int) -> Unit,
    onRefreshSubscription: (Long) -> Unit,
    onRefreshAll: () -> Unit,
    onDeleteSubscription: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalShadoraStrings.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSub by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var pendingDeleteSub by remember { mutableStateOf<SubscriptionEntity?>(null) }

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
            Text(
                text = strings.subscriptionsTitle,
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
                    text = strings.refreshAll,
                    onClick = onRefreshAll,
                    icon = Icons.Rounded.Refresh,
                    enabled = subscriptions.isNotEmpty() && refreshingIds.isEmpty(),
                    loading = refreshingIds.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("refresh_all_subs_button")
                )
                Shadora3DButton(
                    text = strings.addSubscription,
                    onClick = { showAddDialog = true },
                    icon = Icons.Rounded.Add,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("add_subscription_button")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Security & Manual Preservation Notice
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ShadoraIcons.Security,
                        contentDescription = null,
                        tint = EmeraldSignal,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = strings.httpsOnlyHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.manualPreservedNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (subscriptions.isEmpty()) {
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
                            imageVector = ShadoraIcons.CloudSync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = strings.emptySubsTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = strings.emptySubsSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Shadora3DButton(
                            text = strings.addSubscription,
                            onClick = { showAddDialog = true },
                            icon = Icons.Rounded.Add,
                            modifier = Modifier.fillMaxWidth(0.75f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(subscriptions, key = { it.id }) { sub ->
                        SubscriptionCard(
                            sub = sub,
                            isRefreshing = sub.id in refreshingIds,
                            onEdit = { editingSub = sub },
                            onRefresh = { onRefreshSubscription(sub.id) },
                            onDelete = { pendingDeleteSub = sub }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        SubscriptionEditorDialog(
            title = strings.addSubscription,
            confirmLabel = strings.add,
            initialName = "",
            initialUrl = "https://",
            initialIntervalHours = RefreshIntervalHours.EVERY_24H.hours,
            onDismiss = { showAddDialog = false },
            onSave = { name, url, interval ->
                onAddSubscription(name, url, interval)
                showAddDialog = false
            }
        )
    }

    val targetEdit = editingSub
    if (targetEdit != null) {
        SubscriptionEditorDialog(
            title = strings.editSubscriptionTitle,
            confirmLabel = strings.save,
            initialName = targetEdit.name,
            initialUrl = targetEdit.url,
            initialIntervalHours = targetEdit.refreshIntervalHours,
            onDismiss = { editingSub = null },
            onSave = { name, url, interval ->
                onEditSubscription(targetEdit.id, name, url, interval)
                editingSub = null
            }
        )
    }

    val targetDelete = pendingDeleteSub
    if (targetDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteSub = null },
            title = { Text(strings.deleteSubTitle) },
            text = { Text("${strings.deleteSubConfirm}\n\n${targetDelete.name}") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSubscription(targetDelete.id)
                        pendingDeleteSub = null
                    }
                ) {
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSub = null }) { Text(strings.cancel) }
            }
        )
    }
}

@Composable
private fun SubscriptionCard(
    sub: SubscriptionEntity,
    isRefreshing: Boolean,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalShadoraStrings.current
    val nextSyncMillis = remember(sub.lastUpdatedAt, sub.refreshIntervalHours, sub.createdAt) {
        SubscriptionAutoRefreshScheduler.nextRefreshMillis(sub)
    }

    ShadoraCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sub.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sub.url,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = strings.editSubscriptionTitle,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = strings.refreshNow,
                            tint = NeonCrimsonGlow
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = ShadoraIcons.DeleteOutline,
                        contentDescription = strings.delete,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = String.format(strings.serversCountFormat, sub.serverCount),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${strings.lastUpdatedLabel}: " +
                    if (sub.lastUpdatedAt > 0L) FormatUtils.formatTimestamp(sub.lastUpdatedAt)
                    else strings.neverUpdated,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (nextSyncMillis != null || sub.expireTimestamp > 0L) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (nextSyncMillis != null) {
                    Text(
                        text = "${strings.nextAutoRefreshLabel}: ${FormatUtils.formatTimestamp(nextSyncMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (sub.expireTimestamp > 0L) {
                    Text(
                        text = "${strings.expiresAtLabel}: ${FormatUtils.formatTimestamp(sub.expireTimestamp)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldSignal
                    )
                }
            }
        }

        if (sub.totalBytes > 0L) {
            val used = (sub.uploadBytes + sub.downloadBytes).coerceAtLeast(0L)
            val ratio = (used.toFloat() / sub.totalBytes.toFloat()).coerceIn(0f, 1f)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth(),
                color = NeonCrimsonGlow
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${strings.quotaLabel}: ${FormatUtils.formatBytes(used)} / ${FormatUtils.formatBytes(sub.totalBytes)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!sub.lastError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = ErrorRed.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(ShadoraIcons.ErrorOutline, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(16.dp))
                    Text(
                        text = sub.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionEditorDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialUrl: String,
    initialIntervalHours: Int,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, intervalHours: Int) -> Unit
) {
    val strings = LocalShadoraStrings.current
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var intervalHours by remember { mutableIntStateOf(initialIntervalHours) }

    val intervals = listOf(
        RefreshIntervalHours.MANUAL to strings.intervalManual,
        RefreshIntervalHours.EVERY_6H to strings.interval6h,
        RefreshIntervalHours.EVERY_12H to strings.interval12h,
        RefreshIntervalHours.EVERY_24H to strings.interval24h,
        RefreshIntervalHours.EVERY_72H to strings.interval72h
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.subNameLabel) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sub_name_input")
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(strings.subUrlLabel) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sub_url_input")
                )
                Text(strings.subIntervalLabel, style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    intervals.forEach { (item, label) ->
                        FilterChip(
                            selected = intervalHours == item.hours,
                            onClick = { intervalHours = item.hours },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, url, intervalHours) },
                enabled = url.trim().length > 8,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("save_subscription_button")
            ) {
                Text(confirmLabel, maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel, maxLines = 1, softWrap = false) }
        }
    )
}
