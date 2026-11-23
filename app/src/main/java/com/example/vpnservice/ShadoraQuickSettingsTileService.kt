package com.example.vpnservice

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import com.example.R
import com.example.ShadoraApplication
import com.example.core.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android Quick Settings Tile for one-tap SHADORA VPN toggle directly from the
 * system notification shade (supported on Android 7.0+ / API 24+).
 */
class ShadoraQuickSettingsTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val app = application as? ShadoraApplication ?: return
        stateJob?.cancel()
        stateJob = tileScope.launch {
            app.engineManager.connectionState.collect { state ->
                renderTileState(state)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val app = application as? ShadoraApplication ?: return
        val currentState = app.engineManager.connectionState.value

        if (currentState is ConnectionState.Connected ||
            currentState is ConnectionState.Connecting ||
            currentState is ConnectionState.Reconnecting
        ) {
            tileScope.launch {
                app.engineManager.disconnect("QUICK_TILE_DISCONNECT")
            }
            return
        }

        // If Android VPN consent hasn't been granted yet, open MainActivity cleanly
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            openMainActivityFromTile()
            return
        }

        tileScope.launch(Dispatchers.IO) {
            val settings = app.repository.getAppSettings()
            val targetId = if (settings.selectedServerId > 0L) {
                settings.selectedServerId
            } else {
                app.repository.getAllServers().firstOrNull()?.id ?: -1L
            }
            if (targetId <= 0L) {
                openMainActivityFromTile()
            } else {
                app.engineManager.connect(targetId)
            }
        }
    }

    private fun openMainActivityFromTile() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                202,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun renderTileState(state: ConnectionState) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.qs_tile_label)
        when (state) {
            is ConnectionState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = state.serverName
                }
            }
            is ConnectionState.Connecting,
            is ConnectionState.Reconnecting,
            ConnectionState.Preparing -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.vpn_status_connecting)
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.vpn_status_disconnected)
                }
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }
}
