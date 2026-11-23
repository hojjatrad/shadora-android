package com.example

import android.app.Application
import com.example.data.local.ShadoraDatabase
import com.example.data.repository.ShadoraRepository
import com.example.enginemanager.EngineManager
import com.example.panelapi.ShadoraPanelClient
import com.example.panelapi.StandaloneOfflinePanelClient
import com.example.security.KeystoreCryptoManager
import com.example.subscriptionmanager.SubscriptionManager
import com.example.vpnservice.PublicIpChecker
import com.example.vpnservice.ServerLatencyTester

class ShadoraApplication : Application() {

    val database: ShadoraDatabase by lazy { ShadoraDatabase.getInstance(this) }
    val cryptoManager: KeystoreCryptoManager by lazy { KeystoreCryptoManager() }
    val repository: ShadoraRepository by lazy { ShadoraRepository(database, cryptoManager) }
    val subscriptionManager: SubscriptionManager by lazy { SubscriptionManager(repository) }
    val latencyTester: ServerLatencyTester by lazy { ServerLatencyTester() }
    val engineManager: EngineManager by lazy { EngineManager(this, repository, latencyTester) }
    val publicIpChecker: PublicIpChecker by lazy { PublicIpChecker() }
    val panelClient: ShadoraPanelClient by lazy { StandaloneOfflinePanelClient() }
}
