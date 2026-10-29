package com.saurabh.offgridchat.repository

import android.content.Intent
import com.saurabh.offgridchat.core.BluetoothController
import com.saurabh.offgridchat.tools.BtDevice
import com.saurabh.offgridchat.tools.ChatMessage
import com.saurabh.offgridchat.tools.ConnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothRepository @Inject constructor(
    private val bt: BluetoothController
) {
    // ---- Streams ----
    fun isSupported(): Boolean = bt.isSupported
    fun bluetoothOn(): Flow<Boolean> = bt.enabled
    fun discoverable(): Flow<Boolean> = bt.discoverable
    fun scanning(): Flow<Boolean> = bt.scanning
    fun devices(): Flow<List<BtDevice>> = bt.devices
    fun connection(): Flow<ConnState> = bt.connection
    fun incomingMessages(): Flow<ChatMessage> = bt.incoming

    // ---- Intents ----
    fun enableIntent(): Intent = bt.enableIntent()
    fun discoverableIntent(): Intent = bt.discoverableIntent()
    fun settingsIntent(): Intent = bt.settingsIntent()

    // ---- Actions ----
    suspend fun tryDisable(): Boolean = withContext(Dispatchers.IO) { bt.tryDisable() }
    suspend fun startScan(): Boolean = withContext(Dispatchers.IO) { bt.startScan() }
    suspend fun stopScan() = withContext(Dispatchers.IO) { bt.stopScan() }
    suspend fun startServer() = withContext(Dispatchers.IO) { bt.startServer() }
    suspend fun connect(address: String): Boolean = bt.connect(address)
    suspend fun disconnect() = withContext(Dispatchers.IO) { bt.disconnectAll() }
    suspend fun sendMessage(text: String, targetAddress: String? = null): Boolean = bt.send(text, targetAddress)
}
