package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.ShadoraApplication
import com.example.core.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 3D Obsidian-Crimson Android Home Screen Widget for one-tap SHADORA VPN control.
 */
class ShadoraVpnWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        widgetScope.launch {
            try {
                updateWidgetsInternal(context.applicationContext, appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            val pendingResult = goAsync()
            widgetScope.launch {
                try {
                    handleToggleAction(context.applicationContext)
                    requestRefresh(context.applicationContext)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun handleToggleAction(context: Context) {
        val app = context.applicationContext as? ShadoraApplication ?: return
        val state = app.engineManager.connectionState.value
        val isActive = state is ConnectionState.Connected ||
            state is ConnectionState.Connecting ||
            state is ConnectionState.Reconnecting ||
            state is ConnectionState.Preparing

        if (isActive) {
            app.engineManager.disconnect("WIDGET_DISCONNECT")
            return
        }

        // If Android VPN consent hasn't been granted yet, open MainActivity cleanly to request consent
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            launchMainApp(context)
            return
        }

        val settings = app.repository.getAppSettings()
        val targetId = if (settings.selectedServerId > 0L) {
            settings.selectedServerId
        } else {
            app.repository.getAllServers().firstOrNull()?.id ?: -1L
        }

        if (targetId > 0L) {
            app.engineManager.connect(targetId)
        } else {
            launchMainApp(context)
        }
    }

    private fun launchMainApp(context: Context) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { context.startActivity(openIntent) }
    }

    companion object {
        const val ACTION_WIDGET_TOGGLE = "com.example.shadora.ACTION_WIDGET_TOGGLE_VPN"
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun requestRefresh(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, ShadoraVpnWidgetProvider::class.java))
            if (ids.isEmpty()) return
            widgetScope.launch {
                runCatching {
                    updateWidgetsInternal(appContext, manager, ids)
                }
            }
        }

        private suspend fun updateWidgetsInternal(
            context: Context,
            manager: AppWidgetManager,
            widgetIds: IntArray
        ) {
            val app = context.applicationContext as? ShadoraApplication ?: return
            val state = app.engineManager.connectionState.value
            val settings = runCatching { app.repository.getAppSettings() }.getOrNull()
            val isFa = (settings?.languageCode ?: "fa") == "fa"
            val selectedServer = runCatching {
                val sid = settings?.selectedServerId ?: -1L
                if (sid > 0L) app.repository.getServerById(sid) else app.repository.getAllServers().firstOrNull()
            }.getOrNull()

            val (statusLabel, statusColor, btnLabel, btnBgRes) = when (state) {
                is ConnectionState.Connected -> Quad(
                    if (isFa) "متصل و ایمن" else "Connected & Protected",
                    Color.parseColor("#00E676"),
                    if (isFa) "قطع اتصال" else "Disconnect",
                    R.drawable.bg_widget_btn_obsidian_3d
                )
                is ConnectionState.Connecting,
                is ConnectionState.Reconnecting,
                ConnectionState.Preparing -> Quad(
                    if (isFa) "در حال اتصال…" else "Connecting…",
                    Color.parseColor("#FFB300"),
                    if (isFa) "توقف" else "Cancel",
                    R.drawable.bg_widget_btn_obsidian_3d
                )
                is ConnectionState.Error -> Quad(
                    if (isFa) "نیازمند بررسی" else "Attention Needed",
                    Color.parseColor("#FF5252"),
                    if (isFa) "تلاش مجدد" else "Retry",
                    R.drawable.bg_widget_btn_crimson_3d
                )
                else -> Quad(
                    if (isFa) "قطع شده" else "Disconnected",
                    Color.parseColor("#A9B2C5"),
                    if (isFa) "اتصال سریع" else "Quick Connect",
                    R.drawable.bg_widget_btn_crimson_3d
                )
            }

            val serverLabel = selectedServer?.name
                ?: if (isFa) "سروری انتخاب نشده است" else "No server selected"
            val protocolLabel = selectedServer?.protocol ?: "VPN"

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPending = PendingIntent.getActivity(
                context,
                201,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val toggleIntent = Intent(context, ShadoraVpnWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_TOGGLE
            }
            val togglePending = PendingIntent.getBroadcast(
                context,
                202,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            widgetIds.forEach { widgetId ->
                val views = RemoteViews(context.packageName, R.layout.widget_shadora_vpn).apply {
                    setTextViewText(R.id.widget_status_text, statusLabel)
                    setTextColor(R.id.widget_status_text, statusColor)
                    setTextViewText(R.id.widget_server_name, serverLabel)
                    setTextViewText(R.id.widget_protocol_badge, protocolLabel)
                    setTextViewText(R.id.widget_toggle_button, btnLabel)
                    setInt(R.id.widget_toggle_button, "setBackgroundResource", btnBgRes)
                    setOnClickPendingIntent(R.id.widget_root, openAppPending)
                    setOnClickPendingIntent(R.id.widget_toggle_button, togglePending)
                }
                manager.updateAppWidget(widgetId, views)
            }
        }
    }

    private data class Quad(
        val status: String,
        val color: Int,
        val buttonText: String,
        val buttonBgRes: Int
    )
}
