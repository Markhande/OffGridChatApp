package com.saurabh.offgridchat.core

import android.annotation.SuppressLint
import com.saurabh.offgridchat.calling.VoiceCallManager
import javax.crypto.SecretKey
import android.net.Uri
import com.saurabh.offgridchat.attachments.AttachmentStore
import kotlinx.coroutines.CompletableDeferred
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.saurabh.offgridchat.crypto.CryptoManager
import com.saurabh.offgridchat.crypto.EncryptedResult
import com.saurabh.offgridchat.crypto.IdentityManager
import com.saurabh.offgridchat.database.BlockedDeviceEntity
import com.saurabh.offgridchat.database.ConnectionRequestEntity
import com.saurabh.offgridchat.database.PeerEntity
import com.saurabh.offgridchat.database.RouteEntity
import com.saurabh.offgridchat.database.MESSAGE_STATUS_DELIVERED
import com.saurabh.offgridchat.database.MESSAGE_STATUS_READ
import com.saurabh.offgridchat.database.MESSAGE_STATUS_SENDING
import com.saurabh.offgridchat.datastore.Preferences
import com.saurabh.offgridchat.protocol.MeshPacket
import com.saurabh.offgridchat.protocol.PacketType
import com.saurabh.offgridchat.protocol.SessionHandshake
import kotlinx.coroutines.CoroutineStart
import com.saurabh.offgridchat.repository.ChatRepository
import com.saurabh.offgridchat.repository.MessageDelivery
import com.saurabh.offgridchat.database.IncomingMessageResult
import com.saurabh.offgridchat.tools.APP_UUID
import com.saurabh.offgridchat.tools.BtDevice
import com.saurabh.offgridchat.tools.BtPermissions
import com.saurabh.offgridchat.tools.BtPermissions.btDevice
import com.saurabh.offgridchat.tools.ChatMessage
import com.saurabh.offgridchat.tools.ConnState
import com.saurabh.offgridchat.tools.PeerSocketState
import com.saurabh.offgridchat.database.GroupMemberEntity
import com.saurabh.offgridchat.database.GroupDeliveryEntity
import com.saurabh.offgridchat.protocol.GroupEnvelope
import com.saurabh.offgridchat.repository.GroupRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import com.saurabh.offgridchat.tools.SERVICE_NAME
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

private const val PEER_TIMEOUT_MILLIS = 45_000L

private const val ROUTE_ANNOUNCE_TTL = 8

private const val ROUTE_EXPIRY_MILLIS = 120_000L
private const val RELAY_PACKET_MAX_AGE_MILLIS = 5 * 60 * 1000L

private const val ROUTE_ANNOUNCEMENT_INTERVAL_MILLIS = 30_000L

private const val SEEN_MESSAGE_RETENTION_MILLIS =
    10 * 60 * 1000L

private const val ECDSA_SIGNATURE_ALGORITHM =
    "SHA256withECDSA"

private const val NODE_REFERENCE_PREFIX =
    "node:"

/*
 * DataInputStream.writeUTF()/readUTF() uses a 16-bit length
 * prefix. Keep a safe ceiling below the protocol maximum.
 */
private const val MAX_UTF_FRAME_BYTES =
    60_000

private class PeerConnection(
    val address: String,
    val socket: BluetoothSocket,
    val out: DataOutputStream,
    val handshake: SessionHandshake,
    val outgoing: Boolean = false
) {
    val connectedAt: Long = System.currentTimeMillis()

    @Volatile
    var nodeId: String? = null

    @Volatile
    var publicKeyHex: String? = null

    @Volatile
    var authenticated: Boolean = false

    @Volatile
    var lastActivity: Long =
        System.currentTimeMillis()

    var readJob: Job? = null
    var handshakeTimeout: Job? = null
    var voiceKey: SecretKey? = null
}

@Singleton
@SuppressLint("MissingPermission")
class BluetoothController @Inject constructor(
    @ApplicationContext context: Context,
    private val chatRepo: ChatRepository,
    private val messageDelivery: MessageDelivery,
    private val connectionApproval: com.saurabh.offgridchat.repository.ConnectionApproval,
    private val identityManager: IdentityManager,
    private val cryptoManager: CryptoManager,
    private val prefs: Preferences,
    private val groups: GroupRepository,
    private val attachments: AttachmentStore,
    val calls: VoiceCallManager
) {
    private val fileAcks = ConcurrentHashMap<String, Pair<String, CompletableDeferred<Unit>>>()
    val fileTransfers = MutableStateFlow<Map<String, String>>(emptyMap())

    fun sendFile(address: String, uri: Uri) {
        if (fileTransfers.value[address]?.startsWith("Sending") == true ||
            fileTransfers.value[address] == "Preparing file…") return
        fileTransfers.update { it + (address to "Preparing file…") }
        scope.launch(Dispatchers.IO) {
            try {
                val attachment = attachments.prepare(uri)
                val destination = resolveDestinationNodeId(address) ?: error("Peer is disconnected.")
                val conversation = resolveConversationAddress(address, destination)
                val finalId = UUID.randomUUID().toString()
                val sent = messageDelivery.send(
                    ChatMessage(messageId = finalId, address = conversation, nodeId = destination,
                        text = attachment.message(), fromMe = true, time = System.currentTimeMillis(),
                        status = MESSAGE_STATUS_SENDING), EncryptedResult.CURRENT_VERSION
                ) {
                    attachments.file(attachment).inputStream().use { input ->
                        val count = AttachmentStore.chunkCount(attachment.size)
                        for (index in 0 until count) {
                            val size = minOf(AttachmentStore.CHUNK_BYTES.toLong(),
                                attachment.size - index.toLong() * AttachmentStore.CHUNK_BYTES).toInt()
                            val bytes = ByteArray(size)
                            java.io.DataInputStream(input).readFully(bytes)
                            val payload = attachment.json().put("index", index)
                                .put("data", Base64.getEncoder().encodeToString(bytes)).toString()
                            fileTransfers.update { it + (address to "Sending ${attachment.name} · ${index * 100 / count}%") }
                            if (!send(payload, address, PacketType.FILE_CHUNK,
                                    if (index == count - 1) finalId else UUID.randomUUID().toString())) return@send false
                        }
                    }
                    true
                }
                check(sent) { "Transfer interrupted. Reconnect and select the file to retry." }
                fileTransfers.update { it + (address to "File delivered") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fileTransfers.update { it + (address to (e.message ?: "Could not send file.")) }
            }
        }
    }

    private val app =
        context.applicationContext

    private val adapter: BluetoothAdapter? =
        (
                app.getSystemService(
                    Context.BLUETOOTH_SERVICE
                ) as? BluetoothManager
                )?.adapter

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO
        )

    val isSupported: Boolean
        get() = adapter != null

    private val _enabled =
        MutableStateFlow(
            adapter?.isEnabled == true
        )

    val enabled: StateFlow<Boolean> =
        _enabled.asStateFlow()

    private val _discoverable =
        MutableStateFlow(
            readDiscoverable()
        )

    val discoverable: StateFlow<Boolean> =
        _discoverable.asStateFlow()

    private val _scanning =
        MutableStateFlow(false)

    val scanning: StateFlow<Boolean> =
        _scanning.asStateFlow()

    private val _devices =
        MutableStateFlow<List<BtDevice>>(
            emptyList()
        )

    val devices: StateFlow<List<BtDevice>> =
        _devices.asStateFlow()

    private val _conn =
        MutableStateFlow<ConnState>(
            ConnState.Idle
        )

    val connection: StateFlow<ConnState> =
        _conn.asStateFlow()

    private val _incoming =
        MutableSharedFlow<ChatMessage>(
            extraBufferCapacity = 64
        )

    val incoming: SharedFlow<ChatMessage> =
        _incoming.asSharedFlow()

    private var probeJob: Job? = null

    private var serverJob: Job? = null

    private var heartbeatJob: Job? = null

    private var routeAnnouncementJob: Job? = null

    private var serverSocket:
            BluetoothServerSocket? = null

    /**
     * One independent connection per Bluetooth address.
     */
    private val activePeers =
        ConcurrentHashMap<
                String,
                PeerConnection
                >()

    /** Live sockets, published independently of the last connection attempt/event. */
    private val _socketStates = MutableStateFlow<Map<String, PeerSocketState>>(emptyMap())
    val socketStates = _socketStates.asStateFlow()

    @Synchronized
    private fun publishSocketStates() {
        _socketStates.value =
            activePeers.values.filter { it.socket.isConnected }.associate { peer ->
                peer.address to PeerSocketState(
                    peer.address,
                    peer.nodeId,
                    peer.authenticated,
                    peer.connectedAt
                )
            }
    }

    private fun routeNextHop(route: RouteEntity): PeerConnection? =
        activePeers[route.nextHopAddress]?.takeIf {
            it.authenticated && it.socket.isConnected &&
                    route.expiresAt > System.currentTimeMillis() && route.lastUpdated >= it.connectedAt
        }

    /** Room's messageId constraint separately provides persistent DATA duplicate protection. */
    private val seenMessageIds =
        ConcurrentHashMap<String, Long>()

    private val connectionLocks = ConcurrentHashMap<String, Mutex>()
    private val groupDeliveryLocks = ConcurrentHashMap<String, Mutex>()
    private val reconnectSuppressed = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var shareOnlineStatus = false
    @Volatile
    private var relayEnabled = false

    private fun relayPacketIsFresh(packet: MeshPacket): Boolean =
        System.currentTimeMillis() - packet.timestamp <
                if (packet.packetType == PacketType.ROUTE_ANNOUNCE) ROUTE_EXPIRY_MILLIS else RELAY_PACKET_MAX_AGE_MILLIS

    private val presenceMutex = Mutex()
    private val remotePresence = mutableMapOf<String, Pair<String, Long>>()

    private suspend fun recordPresence(nodeId: String, viaAddress: String, online: Boolean) =
        presenceMutex.withLock {
            if (online) remotePresence[nodeId] =
                viaAddress to android.os.SystemClock.elapsedRealtime()
            else remotePresence.remove(nodeId)
            chatRepo.updatePeerPresence(nodeId, online)
        }

    private suspend fun expirePresence() = presenceMutex.withLock {
        val cutoff = android.os.SystemClock.elapsedRealtime() - 60_000
        val expired = remotePresence.filterValues { it.second < cutoff }.keys.toList()
        for (nodeId in expired) {
            remotePresence.remove(nodeId)
            chatRepo.updatePeerPresence(nodeId, false)
        }
    }

    private fun startReconnectLoop() = scope.launch {
        combine(
            prefs.autoReconnectFlow,
            enabled
        ) { auto, on -> auto && on }.collectLatest { recover ->
            if (!recover) return@collectLatest
            coroutineScope {
                val retries = mutableMapOf<String, Job>()
                while (isActive) {
                    if (BtPermissions.granted(app)) {
                        startServer()
                        val eligible = chatRepo.reconnectablePeers().filter {
                            BluetoothAdapter.checkBluetoothAddress(it.address) && it.address !in reconnectSuppressed
                        }.associateBy { it.address }
                        retries.keys.toList().filter { it !in eligible }
                            .forEach { retries.remove(it)?.cancel() }
                        for ((address, peer) in eligible) {
                            if (retries[address]?.isActive == true) continue
                            retries[address] = launch {
                                // Stagger the two ends, but either end can recover on its own.
                                val initialBackoff =
                                    if (identityManager.getIdentity().nodeId < peer.nodeId) 5_000L else 20_000L
                                var backoff = initialBackoff
                                while (isActive) {
                                    delay((backoff + Random.nextLong(0, 2_000)).milliseconds)
                                    if (activePeers[address] != null) {
                                        if (activePeers[address]?.authenticated == true) backoff =
                                            initialBackoff
                                        continue
                                    }
                                    if (address in reconnectSuppressed ||
                                        chatRepo.reconnectablePeers()
                                            .none { it.nodeId == peer.nodeId && it.address == address }
                                    ) break
                                    connectPeer(address, automatic = true)
                                    backoff = (backoff * 2).coerceAtMost(120_000L)
                                }
                            }
                        }
                    } else {
                        retries.values.forEach { it.cancel() }
                        retries.clear()
                    }
                    delay(3_000.milliseconds)
                }
            }
        }
    }

    private val receiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {

                when (intent.action) {

                    BluetoothAdapter.ACTION_STATE_CHANGED -> {

                        val state =
                            intent.getIntExtra(
                                BluetoothAdapter.EXTRA_STATE,
                                BluetoothAdapter.ERROR
                            )

                        _enabled.value =
                            state ==
                                    BluetoothAdapter.STATE_ON

                        if (state == BluetoothAdapter.STATE_ON) startServer()

                        if (
                            state ==
                            BluetoothAdapter.STATE_TURNING_OFF ||
                            state ==
                            BluetoothAdapter.STATE_OFF
                        ) {

                            probeJob?.cancel()

                            stopServer()

                            disconnectAll(allowReconnect = true)

                            seenMessageIds.clear()

                            _scanning.value =
                                false

                            _discoverable.value =
                                false

                            _devices.value =
                                emptyList()

                            scope.launch {
                                chatRepo.clearRoutes()
                            }
                        }
                    }

                    BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {

                        val mode =
                            intent.getIntExtra(
                                BluetoothAdapter.EXTRA_SCAN_MODE,
                                BluetoothAdapter.ERROR
                            )

                        _discoverable.value =
                            mode ==
                                    BluetoothAdapter
                                        .SCAN_MODE_CONNECTABLE_DISCOVERABLE
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        _scanning.value = true
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {

                        _scanning.value = false

                        probeServices()
                    }

                    BluetoothDevice.ACTION_FOUND -> {

                        val device =
                            intent.btDevice()
                                ?: return

                        val rssi =
                            intent.getShortExtra(
                                BluetoothDevice.EXTRA_RSSI,
                                Short.MIN_VALUE
                            ).toInt()

                        upsert(
                            device,
                            rssi.takeUnless {
                                it ==
                                        Short.MIN_VALUE.toInt()
                            }
                        )
                    }

                    BluetoothDevice.ACTION_UUID -> {

                        val device =
                            intent.btDevice()
                                ?: return

                        @Suppress("DEPRECATION")
                        val uuids =
                            intent.getParcelableArrayExtra(
                                BluetoothDevice.EXTRA_UUID
                            )
                                ?.filterIsInstance<ParcelUuid>()
                                .orEmpty()

                        markCapable(
                            device.address,
                            uuids.any {
                                it.uuid == APP_UUID
                            }
                        )
                    }
                }
            }
        }

    init {
        calls.bindTransport { node, payload, audio -> sendVoicePacket(node, payload, audio) }
        scope.launch {
            prefs.relayModeFlow.collectLatest { relayEnabled = it }
        }
        scope.launch {
            prefs.allowNewConnectionsFlow.collectLatest { allowed ->
                if (!allowed) {
                    chatRepo.expireUnapprovedRequests()
                    for (peer in activePeers.values) {
                        val nodeId = peer.nodeId
                            ?: continue // Identity must be proved before applying trust.
                        if (!chatRepo.canConnectIdentity(
                                nodeId,
                                false,
                                respectRemoteDecision = peer.outgoing
                            )
                        ) {
                            connectionApproval.latest(nodeId)?.let { request ->
                                if (request.status == com.saurabh.offgridchat.database.REQUEST_STATUS_EXPIRED) {
                                    request.requestId?.let {
                                        sendApprovalResponse(
                                            nodeId,
                                            it,
                                            request.status
                                        )
                                    }
                                }
                            }
                            disconnectPeer(peer)
                        }
                    }
                }
            }
        }
        scope.launch {
            // Persisted presence cannot establish that a peer is still online after restart.
            presenceMutex.withLock { chatRepo.clearPresence() }
            prefs.onlineStatusFlow.collectLatest { allowed ->
                shareOnlineStatus = allowed
                // Reuse routing announcements: absent online status withdraws the previous claim.
                // Routing itself stays available even while presence sharing is disabled.
                announceLocalRoute()
            }
        }
        scope.launch {
            while (isActive) {
                connectionApproval.expire()
                delay(10_000.milliseconds)
            }
        }

        val filter =
            IntentFilter().apply {

                addAction(
                    BluetoothAdapter.ACTION_STATE_CHANGED
                )

                addAction(
                    BluetoothAdapter.ACTION_SCAN_MODE_CHANGED
                )

                addAction(
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED
                )

                addAction(
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED
                )

                addAction(
                    BluetoothDevice.ACTION_FOUND
                )

                addAction(
                    BluetoothDevice.ACTION_UUID
                )
            }

        ContextCompat.registerReceiver(
            app,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        startHeartbeatLoop()

        startRouteAnnouncementLoop()
        startReconnectLoop()
    }

    /* ============================================================
       SYSTEM
       ============================================================ */

    private fun readDiscoverable(): Boolean =
        try {

            adapter?.scanMode ==
                    BluetoothAdapter
                        .SCAN_MODE_CONNECTABLE_DISCOVERABLE

        } catch (_: SecurityException) {

            false
        }

    fun enableIntent(): Intent =
        Intent(
            BluetoothAdapter.ACTION_REQUEST_ENABLE
        )

    fun discoverableIntent(
        seconds: Int = 300
    ): Intent =
        Intent(
            BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE
        )
            .putExtra(
                BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                seconds
            )

    fun settingsIntent(): Intent =
        Intent(
            Settings.ACTION_BLUETOOTH_SETTINGS
        )

    fun tryDisable(): Boolean =
        try {

            @Suppress("DEPRECATION")
            adapter?.disable() == true

        } catch (_: SecurityException) {

            false
        }

    /* ============================================================
       HEARTBEAT / PRESENCE
       ============================================================ */

    private fun startHeartbeatLoop() {

        heartbeatJob?.cancel()

        heartbeatJob =
            scope.launch {

                while (isActive) {

                    delay(
                        20_000.milliseconds
                    )

                    val now =
                        System.currentTimeMillis()

                    val onlineAllowed =
                        prefs.onlineStatusFlow.first()

                    cleanupSeenMessageIds(now)
                    expirePresence()

                    for (
                    peer in activePeers.values
                    ) {

                        val inactiveFor =
                            now -
                                    peer.lastActivity

                        if (
                            inactiveFor >
                            PEER_TIMEOUT_MILLIS
                        ) {

                            peer.nodeId?.let {
                                chatRepo.updatePeerPresence(
                                    it,
                                    false
                                )
                            }

                            disconnectPeer(peer)

                            continue
                        }

                        if (
                            !peer.authenticated
                        ) {
                            continue
                        }

                        /*
                         * Presence publication is optional.
                         *
                         * When disabled, no PRESENCE_PING is
                         * published by the heartbeat loop.
                         */
                        if (!onlineAllowed) {
                            continue
                        }

                        val destination =
                            peer.nodeId
                                ?: continue

                        val identity =
                            identityManager.getIdentity()

                        val ping =
                            MeshPacket(
                                sourceNodeId =
                                    identity.nodeId,

                                destinationNodeId =
                                    destination,

                                previousHopId =
                                    identity.nodeId,

                                packetType =
                                    PacketType.PRESENCE_PING,

                                sourceName =
                                    localAdvertisedName(),

                                sourcePublicKey =
                                    identity.publicKeyHex
                            )

                        sendPacketToPeer(
                            peer,
                            signPacket(ping)
                        )
                    }
                }
            }
    }

    private fun startRouteAnnouncementLoop() {

        routeAnnouncementJob?.cancel()

        routeAnnouncementJob =
            scope.launch {

                delay(
                    1_000.milliseconds
                )

                while (isActive) {

                    announceLocalRoute()

                    delay(
                        ROUTE_ANNOUNCEMENT_INTERVAL_MILLIS
                            .milliseconds
                    )
                }
            }
    }

    private suspend fun announceLocalRoute() {

        val identity =
            identityManager.getIdentity()

        val packet =
            MeshPacket(
                sourceNodeId =
                    identity.nodeId,

                destinationNodeId =
                    MeshPacket.BROADCAST_NODE_ID,

                previousHopId =
                    identity.nodeId,

                ttl =
                    ROUTE_ANNOUNCE_TTL,

                packetType =
                    PacketType.ROUTE_ANNOUNCE,

                status = if (shareOnlineStatus) "ONLINE" else null,

                sourceName =
                    localAdvertisedName(),

                sourcePublicKey =
                    identity.publicKeyHex
            )

        broadcastPacket(
            signPacket(packet)
        )
    }

    /* ============================================================
       DISCOVERY
       ============================================================ */

    fun startScan(): Boolean {

        val bluetoothAdapter =
            adapter
                ?: return false

        if (
            !bluetoothAdapter.isEnabled ||
            !BtPermissions.granted(app)
        ) {
            return false
        }

        probeJob?.cancel()

        if (
            bluetoothAdapter.isDiscovering
        ) {
            bluetoothAdapter.cancelDiscovery()
        }

        _devices.value =
            bluetoothAdapter.bondedDevices
                .orEmpty()
                .map {
                    BtDevice(
                        it.name ?: "Unknown device",
                        it.address
                    )
                }

        return bluetoothAdapter.startDiscovery()
    }

    fun stopScan() {

        val bluetoothAdapter =
            adapter
                ?: return

        try {

            if (
                bluetoothAdapter.isDiscovering
            ) {
                bluetoothAdapter.cancelDiscovery()
            }

        } catch (_: SecurityException) {

            _devices.value =
                emptyList()
        }
    }

    private fun upsert(
        device: BluetoothDevice,
        rssi: Int?
    ) {

        val name =
            try {
                device.name
            } catch (_: SecurityException) {
                null
            }

        _devices.update { list ->

            val index =
                list.indexOfFirst {
                    it.address ==
                            device.address
                }

            if (index >= 0) {

                list.toMutableList()
                    .also { mutable ->

                        mutable[index] =
                            mutable[index].copy(
                                name =
                                    name
                                        ?: mutable[index].name,

                                rssi =
                                    rssi
                                        ?: mutable[index].rssi
                            )
                    }

            } else {

                list +
                        BtDevice(
                            name
                                ?: "Unknown device",
                            device.address,
                            rssi
                        )
            }
        }
    }

    private fun markCapable(
        address: String,
        capable: Boolean
    ) {

        _devices.update { list ->

            list.map { device ->

                if (
                    device.address ==
                    address
                ) {

                    device.copy(
                        capable =
                            capable
                    )

                } else {
                    device
                }
            }
        }
    }

    private fun probeServices() {

        val bluetoothAdapter =
            adapter
                ?: return

        probeJob?.cancel()

        probeJob =
            scope.launch {

                for (
                device in
                _devices.value.filter {
                    it.capable == null
                }
                ) {

                    if (!isActive) {
                        return@launch
                    }

                    runCatching {

                        bluetoothAdapter
                            .getRemoteDevice(
                                device.address
                            )
                            .fetchUuidsWithSdp()
                    }

                    delay(
                        2_000.milliseconds
                    )
                }

                _devices.update { list ->

                    list.map { device ->

                        if (
                            device.capable == null
                        ) {
                            device.copy(
                                capable = false
                            )
                        } else {
                            device
                        }
                    }
                }
            }
    }

    /* ============================================================
       SERVER
       ============================================================ */

    @Synchronized
    fun startServer() {

        val bluetoothAdapter =
            adapter
                ?: return

        if (
            serverJob?.isActive == true ||
            !bluetoothAdapter.isEnabled ||
            !BtPermissions.granted(app)
        ) {
            return
        }

        serverJob =
            scope.launch {
                var listeningSocket: BluetoothServerSocket? = null

                try {

                    val socket =
                        bluetoothAdapter
                            .listenUsingRfcommWithServiceRecord(
                                SERVICE_NAME,
                                APP_UUID
                            )

                    listeningSocket = socket
                    serverSocket = socket

                    while (isActive) {

                        val client =
                            socket.accept()

                        handleSocket(
                            client
                        )
                    }

                } catch (
                    _: SecurityException
                ) {

                    _conn.value =
                        ConnState.Failed(
                            "Bluetooth permission is not granted"
                        )

                } catch (
                    _: IOException
                ) {
                    // Expected when server socket is closed.
                } finally {

                    runCatching {
                        listeningSocket?.close()
                    }

                    if (serverSocket === listeningSocket) serverSocket = null
                }
            }
    }

    @Synchronized
    fun stopServer() {

        serverJob?.cancel()

        runCatching {
            serverSocket?.close()
        }

        serverSocket = null
    }

    /* ============================================================
       CLIENT
       ============================================================ */

    suspend fun connect(address: String): Boolean {
        reconnectSuppressed.remove(address)
        return connectPeer(address, automatic = false)
    }

    private suspend fun connectPeer(address: String, automatic: Boolean): Boolean =
        connectionLocks.computeIfAbsent(address) { Mutex() }.withLock {
            if (activePeers[address] != null) true else connectSocket(address, automatic)
        }

    private suspend fun connectSocket(
        address: String,
        automatic: Boolean
    ): Boolean =
        withContext(Dispatchers.IO) {

            val bluetoothAdapter =
                adapter
                    ?: return@withContext false

            if (
                !BluetoothAdapter.checkBluetoothAddress(address) || !BtPermissions.granted(app) || !bluetoothAdapter.isEnabled
            ) {
                return@withContext false
            }

            try {

                val allowNew = prefs.allowNewConnectionsFlow.first()
                val knownNode = chatRepo.knownNodeAtAddress(address)
                if ((knownNode == null && !allowNew) ||
                    (knownNode != null && !chatRepo.canConnectIdentity(knownNode, allowNew))
                ) {
                    return@withContext false
                }

                _conn.value =
                    ConnState.Connecting(
                        address
                    )

                if (
                    bluetoothAdapter.isDiscovering
                ) {
                    bluetoothAdapter.cancelDiscovery()
                }

                /*
                 * Before identity authentication, only the
                 * transport address can be checked.
                 */
                if (
                    chatRepo.isBlockedByAddress(
                        address
                    )
                ) {

                    _conn.value =
                        ConnState.Failed(
                            "Device blocked"
                        )

                    return@withContext false
                }

                val socket =
                    bluetoothAdapter
                        .getRemoteDevice(address)
                        .createRfcommSocketToServiceRecord(
                            APP_UUID
                        )

                try {
                    val connected = withTimeoutOrNull(15_000.milliseconds) {
                        suspendCancellableCoroutine { continuation ->
                            val dial = scope.launch {
                                try {
                                    socket.connect()
                                    continuation.resume(Unit)
                                } catch (e: Exception) {
                                    continuation.resumeWithException(e)
                                }
                            }
                            continuation.invokeOnCancellation {
                                runCatching { socket.close() }
                                dial.cancel()
                            }
                        }
                        true
                    } ?: false
                    if (!connected || (automatic && (!prefs.autoReconnectFlow.first() ||
                                address in reconnectSuppressed ||
                                chatRepo.reconnectablePeers().none { it.address == address }))
                    ) {
                        socket.close()
                        return@withContext false
                    }
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    throw e
                }

                handleSocket(
                    socket, outgoing = true
                )

                true

            } catch (
                _: IOException
            ) {

                _conn.value =
                    ConnState.Failed(
                        "Could not connect. Is OffGrid open on the other phone?"
                    )

                false

            } catch (
                _: SecurityException
            ) {

                _conn.value =
                    ConnState.Failed(
                        "Bluetooth permission is not granted"
                    )

                false
            }
        }

    /* ============================================================
       CONNECTION
       ============================================================ */

    private suspend fun localAdvertisedName(): String =
        com.saurabh.offgridchat.tools.PeerNames.resolve(
            android.os.Build.MODEL, prefs.userNameFlow.first(),
            try {
                adapter?.name
            } catch (_: SecurityException) {
                null
            }
        )

    private suspend fun handleSocket(socket: BluetoothSocket, outgoing: Boolean = false) {
        val address = socket.remoteDevice.address
        if (chatRepo.isDeviceBlocked(null, address)) {
            runCatching { socket.close() }
            return
        }
        val identity = identityManager.getIdentity()
        // If both ends dial at once, both keep the socket initiated by the lower node ID.
        // A completed authenticated connection is always retained.
        val knownNode =
            chatRepo.knownNodeAtAddress(address) ?: chatRepo.getConversation(address)?.nodeId
        val existing = activePeers[address]
        if (existing != null && (existing.authenticated ||
                    (knownNode != null && existing.outgoing == (identity.nodeId < knownNode)))
        ) {
            runCatching { socket.close() }
            return
        }
        val output = try {
            DataOutputStream(socket.outputStream)
        } catch (_: IOException) {
            runCatching { socket.close() }
            return
        }
        val peer = PeerConnection(
            address, socket, output,
            SessionHandshake(identity.nodeId, identity.publicKeyHex), outgoing
        )
        val remoteName = try {
            socket.remoteDevice.name
        } catch (_: SecurityException) {
            null
        } ?: address
        // Assign jobs before publishing the connection. A replacement reader owns only its socket.
        peer.readJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val input = DataInputStream(socket.inputStream)
                while (isActive) {
                    val packet = MeshPacket.textToPacket(input.readUTF()) ?: continue
                    if (processIncomingPacket(packet, peer, remoteName)) {
                        peer.lastActivity = System.currentTimeMillis()
                    }
                }
            } catch (_: IOException) {
                // Socket closed or remote peer disconnected.
            } catch (_: SecurityException) {
                // Permission revoked.
            } finally {
                disconnectPeer(peer)
            }
        }
        peer.handshakeTimeout = scope.launch(start = CoroutineStart.LAZY) {
            delay(SessionHandshake.TIMEOUT_MILLIS.milliseconds)
            if (!peer.authenticated) disconnectPeer(peer)
        }
        var replaced: PeerConnection? = null
        val selected = activePeers.compute(address) { _, current ->
            if (current != null && (current.authenticated ||
                        (knownNode != null && current.outgoing == (identity.nodeId < knownNode)))
            ) current
            else peer.also { replaced = current }
        }
        if (selected !== peer) {
            disconnectPeer(peer)
            return
        }
        replaced?.let { disconnectPeer(it) }
        publishSocketStates()
        _conn.value = ConnState.Connecting(address)
        peer.handshakeTimeout?.start()
        // Write HELLO before starting our reader so a PROOF cannot overtake our HELLO.
        if (!sendPacketToPeer(
                peer,
                signPacket(peer.handshake.hello().copy(sourceName = localAdvertisedName())),
                allowUnauthenticated = true
            )
        ) {
            disconnectPeer(peer)
            return
        }
        peer.readJob?.start()
    }

    private suspend fun processIncomingPacket(
        packet: MeshPacket,
        peer: PeerConnection,
        peerName: String
    ): Boolean {

        val peerAddress = peer.address
        if (activePeers[peerAddress] !== peer) return false

        val myIdentity =
            identityManager.getIdentity()

        if (
            !packet.isStructurallyValid()
        ) {
            return false
        }

        /*
         * Application packets must have:
         *
         * source public key
         * source Node ID bound to that key
         * ECDSA signature
         */
        if (
            packet.sourcePublicKey.isNullOrBlank() ||
            packet.signature.isNullOrBlank()
        ) {
            return false
        }

        if (
            !packet.isNodeIdBoundToPublicKey()
        ) {
            return false
        }

        if (
            !verifyPacketSignature(packet)
        ) {
            return false
        }

        /*
         * Before this socket is authenticated, the first valid
         * packet must belong directly to this Bluetooth peer.
         *
         * This prevents an unauthenticated socket from claiming
         * a remote source identity.
         */
        if (!peer.authenticated) {

            when (peer.handshake.receive(packet)) {
                SessionHandshake.Result.RESPONSE_REQUIRED -> {
                    return sendPacketToPeer(
                        peer,
                        signPacket(peer.handshake.proof().copy(sourceName = localAdvertisedName())),
                        allowUnauthenticated = true
                    )
                }

                SessionHandshake.Result.REJECTED -> {
                    disconnectPeer(peer)
                    return false
                }

                SessionHandshake.Result.AUTHENTICATED -> Unit
            }

            if (
                packet.sourceNodeId !=
                packet.previousHopId
            ) {
                return false
            }

            peer.nodeId =
                packet.sourceNodeId

            peer.publicKeyHex =
                packet.sourcePublicKey

            /*
             * Cryptographic identity blocking.
             */
            if (
                chatRepo.isDeviceBlocked(
                    nodeId =
                        peer.nodeId,

                    address =
                        peerAddress
                )
            ) {

                disconnectPeer(peer)

                return false
            }

            /*
             * If we already know this nodeId but its public key
             * changed, treat this as an identity conflict.
             */
            val existingPeer =
                chatRepo.getPeerByNodeId(
                    peer.nodeId!!
                )

            if (!chatRepo.canConnectIdentity(
                    packet.sourceNodeId, prefs.allowNewConnectionsFlow.first(),
                    respectRemoteDecision = peer.outgoing
                )
            ) {
                disconnectPeer(peer)
                return false
            }

            if (
                existingPeer != null &&
                existingPeer.publicKeyHex !=
                peer.publicKeyHex
            ) {

                disconnectPeer(peer)

                return false
            }

            val fingerprint =
                IdentityManager
                    .computeFingerprintShort(
                        packet.sourcePublicKey
                    )

            // A peer that previously refused us can initiate a fresh authenticated connection.
            // Retire only their old remote decision; our own blocks/rejections were checked above.
            if (!peer.outgoing) chatRepo.reopenRemoteApprovalOnIncomingConnection(packet.sourceNodeId)

            val resolvedPeerName = com.saurabh.offgridchat.tools.PeerNames.resolve(
                peerAddress, packet.sourceName, peerName,
                chatRepo.getPeerByNodeId(packet.sourceNodeId)?.name
            )
            chatRepo.upsertPeer(
                PeerEntity(
                    nodeId =
                        peer.nodeId!!,

                    address =
                        peerAddress,

                    name =
                        resolvedPeerName,

                    fingerprint =
                        fingerprint,

                    publicKeyHex =
                        peer.publicKeyHex!!,

                    lastSeen =
                        System.currentTimeMillis(),

                    isOnline =
                        false,

                    isApproved =
                        existingPeer
                            ?.isApproved
                            ?: false,

                    isBlocked =
                        false,

                    identityVerified = existingPeer?.identityVerified ?: false
                )
            )

            if (activePeers[peerAddress] !== peer) return false
            peer.authenticated = true
            peer.handshakeTimeout?.cancel()
            recordDirectRoute(
                peer
            )
            chatRepo.saveConversation(
                address = chatRepo.getConversationByNodeId(packet.sourceNodeId)?.address
                    ?: peer.address,
                name = resolvedPeerName, nodeId = packet.sourceNodeId,
                fingerprint = fingerprint, isOnline = false
            )
            _conn.value = ConnState.Connected(peer.address, resolvedPeerName)
            publishSocketStates()
            announceLocalRoute()
            scope.launch { requestRemoteApproval(packet.sourceNodeId, packet.sourcePublicKey) }
            return true

        } else {

            /*
             * Once a Bluetooth socket is authenticated, every
             * packet arriving on it must identify that same
             * immediate previous hop.
             */
            if (
                packet.previousHopId !=
                peer.nodeId
            ) {
                return false
            }

            /*
             * A direct peer must not suddenly present another
             * public identity.
             */
            if (
                packet.sourceNodeId ==
                packet.previousHopId &&
                packet.sourcePublicKey !=
                peer.publicKeyHex
            ) {

                disconnectPeer(peer)

                return false
            }
        }

        peer.lastActivity =
            System.currentTimeMillis()

        if (!chatRepo.canConnectIdentity(
                peer.nodeId ?: return false, prefs.allowNewConnectionsFlow.first(),
                respectRemoteDecision = false
            )
        ) {
            disconnectPeer(peer)
            return false
        }

        /*
         * Check logical identity block again after authentication.
         */
        peer.nodeId?.let { nodeId ->

            if (
                chatRepo.isBlocked(nodeId)
            ) {

                disconnectPeer(peer)

                return false
            }
        }

        /*
         * Suppress already-forwarded protocol messages.
         *
         * Signature validation happens BEFORE this check, so a
         * tampered packet cannot poison the message-ID cache.
         */
        // Source blocking applies through relays as well as on a direct socket.
        if (chatRepo.isBlocked(packet.sourceNodeId)) return false
        if (packet.sourceNodeId == myIdentity.nodeId) return false
        // Relayed traffic and route advertisements cannot be replayed indefinitely after cache eviction.
        if ((packet.sourceNodeId != peer.nodeId || packet.packetType == PacketType.ROUTE_ANNOUNCE) &&
            !relayPacketIsFresh(packet)
        ) return false
        if (packet.packetType == PacketType.ROUTE_ANNOUNCE && packet.ttl !in 1..ROUTE_ANNOUNCE_TTL) return false

        if (packet.packetType == PacketType.CALL_SIGNAL || packet.packetType == PacketType.CALL_AUDIO)
            return receiveVoicePacket(peer, packet, peerName)

        val localData =
            (packet.packetType == PacketType.DATA || packet.packetType == PacketType.FILE_CHUNK || packet.packetType == PacketType.CONNECT_REQUEST ||
                    packet.packetType == PacketType.GROUP_DATA) &&
                    packet.destinationNodeId == myIdentity.nodeId
        // Local DATA is deduplicated only after successful decryption, in Room.
        // Rejected DATA must neither poison a memory cache nor earn an ACK on retry.
        val alreadySeen = !localData && seenMessageIds.putIfAbsent(
            "${packet.sourceNodeId}:${packet.messageId}", System.currentTimeMillis()
        ) != null

        // Relay/control duplicates are ignored. Local DATA must reach the Room check below.
        if (
            alreadySeen
        ) {

            return true
        }

        val senderName = com.saurabh.offgridchat.tools.PeerNames.resolve(
            logicalConversationAddress(packet.sourceNodeId), packet.sourceName,
            if (packet.sourceNodeId == peer.nodeId) peerName.takeUnless { it == peerAddress } else null,
            chatRepo.getConversationByNodeId(packet.sourceNodeId)?.name,
            chatRepo.getPeerByNodeId(packet.sourceNodeId)?.name
        )

        val senderFingerprint =
            packet.sourcePublicKey.let {
                IdentityManager
                    .computeFingerprintShort(it)
            }

        /*
         * Keep a conversation entry for a known logical identity.
         *
         * For a direct peer, preserve the actual Bluetooth address.
         * For a remote mesh source, use node:<nodeId>.
         */
        val directSource =
            packet.sourceNodeId ==
                    peer.nodeId

        val conversationAddress =
            chatRepo.getConversationByNodeId(packet.sourceNodeId)?.address ?: if (directSource) {
                peerAddress
            } else {
                logicalConversationAddress(
                    packet.sourceNodeId
                )
            }

        val admissionRequest = ConnectionRequestEntity(
            requesterNodeId = packet.sourceNodeId, address = conversationAddress,
            name = senderName, fingerprint = senderFingerprint,
            publicKeyHex = packet.sourcePublicKey, timestamp = packet.timestamp
        )

        // Relays forward the protected envelope without reading membership or storing a group message.
        if (packet.packetType == PacketType.GROUP_DATA) {
            if (packet.destinationNodeId != myIdentity.nodeId) return relayForwardIfNeeded(
                packet,
                peerAddress
            )
            return receiveGroupPacket(packet, admissionRequest)
        }

        chatRepo.saveConversation(
            address =
                conversationAddress,

            name =
                senderName,

            nodeId =
                packet.sourceNodeId,

            fingerprint =
                senderFingerprint
        )

        return when (
            packet.packetType
        ) {
            PacketType.GROUP_DATA, PacketType.CALL_SIGNAL, PacketType.CALL_AUDIO -> false // Handled above.
            PacketType.AUTH_HELLO,
            PacketType.AUTH_PROOF -> false

            /* ====================================================
               DATA
               ==================================================== */

            PacketType.DATA, PacketType.FILE_CHUNK -> {

                /*
                 * Not our destination -> RELAY.
                 *
                 * Do NOT decrypt.
                 */
                if (
                    packet.destinationNodeId !=
                    myIdentity.nodeId
                ) {

                    return relayForwardIfNeeded(
                        packet,
                        peerAddress
                    )
                }

                /*
                 * Destination DATA must be encrypted.
                 *
                 * MeshPacket validation already requires this,
                 * but keep an explicit security check.
                 */
                if (
                    !packet.encrypted ||
                    packet.encryptionVersion == null
                ) {
                    return false
                }

                // Authenticate the encrypted content before checking persistent duplicates.
                val encryptedResult =
                    EncryptedResult.fromJson(
                        packet.payload
                    )
                        ?: return false

                if (packet.encryptionVersion != encryptedResult.v) return false

                val senderPublicKey =
                    identityManager.decodePublicKey(
                        packet.sourcePublicKey
                    )

                val ourPrivateKey =
                    identityManager
                        .getIdentityPrivateKey()

                val ourPublicKey =
                    identityManager
                        .getIdentityPublicKey()

                /*
                 * Destination-specific E2EE:
                 *
                 * sender A + destination D
                 *
                 * Both sides derive the same key from the same
                 * two public identities.
                 */
                val sessionKey =
                    try {

                        cryptoManager.deriveSessionKey(
                            ourPrivateKey,
                            ourPublicKey,
                            senderPublicKey
                        )

                    } catch (_: Exception) {

                        /*
                         * No plaintext fallback.
                         */
                        return false
                    }

                val plaintext =
                    try {

                        cryptoManager.decryptPayload(
                            encryptedResult,
                            sessionKey,
                            aadData =
                                packet.authenticatedHeader()
                        )

                    } catch (_: Exception) {

                        /*
                         * Authentication failed:
                         * payload modified / wrong key / wrong AAD.
                         */
                        return false
                    }

                val messageText = if (packet.packetType == PacketType.FILE_CHUNK) {
                    if (chatRepo.admitIncomingIdentity(admissionRequest, prefs.connectionPolicyFlow.first()) !=
                        com.saurabh.offgridchat.protocol.ConnectionAdmission.ALLOW) return false
                    val completed = try { attachments.receive(packet.sourceNodeId, plaintext) }
                        catch (_: Exception) { return false }
                    if (completed == null) {
                        sendDeliveryAck(packet)
                        return true
                    }
                    completed.message()
                } else plaintext

                val message =
                    ChatMessage(
                        messageId =
                            packet.messageId,

                        address =
                            conversationAddress,

                        nodeId =
                            packet.sourceNodeId,

                        text =
                            messageText,

                        fromMe =
                            false,

                        time =
                            packet.timestamp,

                        status =
                            MESSAGE_STATUS_DELIVERED
                    )

                val result = chatRepo.persistIncomingMessage(
                    message, encryptedResult.v,
                    prefs.connectionPolicyFlow.first(), admissionRequest
                )
                if (result != IncomingMessageResult.INSERTED && result != IncomingMessageResult.DUPLICATE)
                    return false

                if (result == IncomingMessageResult.INSERTED) {

                    _incoming.emit(
                        message
                    )
                }

                /*
                 * ACK only after the message was accepted/persisted.
                 */
                sendDeliveryAck(
                    packet
                )

                true
            }

            /* ====================================================
               ACK
               ==================================================== */

            PacketType.ACK -> {

                if (
                    packet.destinationNodeId !=
                    myIdentity.nodeId
                ) {

                    return relayForwardIfNeeded(
                        packet,
                        peerAddress
                    )
                }

                val acknowledgedMessageId =
                    packet.ackMessageId
                        ?: return false

                fileAcks[acknowledgedMessageId]?.let { (destination, completion) ->
                    if (destination != packet.sourceNodeId) return false
                    chatRepo.getMessageById(acknowledgedMessageId)?.let {
                        if (it.fromMe && it.nodeId == destination)
                            chatRepo.updateMessageStatus(acknowledgedMessageId, MESSAGE_STATUS_DELIVERED)
                    }
                    completion.complete(Unit)
                    return true
                }

                if (groups.receipt(packet, MESSAGE_STATUS_DELIVERED)) return true

                /*
                 * Only the actual destination peer of our outgoing
                 * message is allowed to acknowledge it.
                 *
                 * This prevents an arbitrary authenticated node from
                 * turning another message into DELIVERED.
                 */
                val originalMessage =
                    chatRepo.getMessageById(
                        acknowledgedMessageId
                    )
                        ?: return false

                if (
                    !originalMessage.fromMe
                ) {
                    return false
                }

                if (
                    originalMessage.nodeId !=
                    packet.sourceNodeId
                ) {
                    return false
                }

                chatRepo.updateMessageStatus(
                    acknowledgedMessageId,
                    MESSAGE_STATUS_DELIVERED
                )

                true
            }

            /* ====================================================
               READ RECEIPT
               ==================================================== */

            PacketType.READ_RECEIPT -> {

                if (
                    packet.destinationNodeId !=
                    myIdentity.nodeId
                ) {

                    return relayForwardIfNeeded(
                        packet,
                        peerAddress
                    )
                }

                val readMessageId =
                    packet.ackMessageId
                        ?: return false

                if (groups.receipt(packet, MESSAGE_STATUS_READ)) return true

                val originalMessage =
                    chatRepo.getMessageById(
                        readMessageId
                    )
                        ?: return false

                /*
                 * Only the peer we sent the message to may mark
                 * that message as READ.
                 */
                if (
                    !originalMessage.fromMe
                ) {
                    return false
                }

                if (
                    originalMessage.nodeId !=
                    packet.sourceNodeId
                ) {
                    return false
                }

                chatRepo.updateMessageStatus(
                    readMessageId,
                    MESSAGE_STATUS_READ
                )

                true
            }

            /* ====================================================
               PRESENCE PING
               ==================================================== */

            PacketType.PRESENCE_PING -> {
                if (packet.destinationNodeId != myIdentity.nodeId)
                    return relayForwardIfNeeded(packet, peerAddress)
                recordPresence(packet.sourceNodeId, peerAddress, true)

                val onlineAllowed =
                    prefs.onlineStatusFlow.first()

                if (
                    onlineAllowed
                ) {

                    val identity =
                        identityManager
                            .getIdentity()

                    val pong =
                        MeshPacket(
                            sourceNodeId =
                                identity.nodeId,

                            destinationNodeId =
                                packet.sourceNodeId,

                            previousHopId =
                                identity.nodeId,

                            packetType =
                                PacketType.PRESENCE_PONG,

                            sourceName =
                                localAdvertisedName(),

                            sourcePublicKey =
                                identity.publicKeyHex
                        )

                    sendPacketToDestination(
                        signPacket(pong)
                    )
                }

                true
            }

            /* ====================================================
               PRESENCE PONG
               ==================================================== */

            PacketType.PRESENCE_PONG -> {

                if (
                    packet.destinationNodeId !=
                    myIdentity.nodeId
                ) {

                    return relayForwardIfNeeded(
                        packet,
                        peerAddress
                    )
                }

                recordPresence(packet.sourceNodeId, peerAddress, true)

                true
            }

            /* ====================================================
               CONNECTION REQUEST
               ==================================================== */

            PacketType.CONNECT_REQUEST -> {

                if (
                    packet.destinationNodeId !=
                    myIdentity.nodeId
                ) {

                    return relayForwardIfNeeded(
                        packet,
                        peerAddress
                    )
                }

                /*
                 * If this identity is already blocked, don't
                 * create a pending request.
                 */
                if (
                    chatRepo.isBlocked(
                        packet.sourceNodeId
                    )
                ) {
                    return false
                }

                val decision = connectionApproval.receive(
                    admissionRequest.copy(requestId = packet.ackMessageId ?: packet.messageId),
                    prefs.connectionPolicyFlow.first()
                )
                sendApprovalResponse(
                    packet.sourceNodeId,
                    packet.ackMessageId ?: packet.messageId,
                    decision
                )
            }

            /* ====================================================
               CONNECTION RESPONSE
               ==================================================== */

            PacketType.CONNECT_RESPONSE -> {

                if (
                    packet.destinationNodeId !=
                    myIdentity.nodeId
                ) {

                    return relayForwardIfNeeded(
                        packet,
                        peerAddress
                    )
                }

                connectionApproval.response(
                    packet.sourceNodeId, packet.sourcePublicKey,
                    packet.ackMessageId ?: return false, packet.status ?: return false
                )
            }

            /* ====================================================
               ROUTE ANNOUNCEMENT
               ==================================================== */

            PacketType.ROUTE_ANNOUNCE -> {

                /*
                 * We never accept our own route announcement back.
                 */
                if (packet.sourceNodeId == myIdentity.nodeId) {
                    return false
                }

                val destinationPublicKey = packet.sourcePublicKey
                recordPresence(packet.sourceNodeId, peerAddress, packet.status == "ONLINE")

                val now = System.currentTimeMillis()

                val calculatedHopCount =
                    (ROUTE_ANNOUNCE_TTL - packet.ttl).coerceAtLeast(0) + 1

                val existingRoute =
                    chatRepo.getRoute(
                        packet.sourceNodeId
                    )

                val existingValid =
                    existingRoute != null &&
                            routeNextHop(existingRoute) != null

                val shouldReplace =
                    !existingValid ||
                            existingRoute.hopCount >
                            calculatedHopCount ||
                            (existingRoute.nextHopAddress == peerAddress && existingRoute.hopCount == calculatedHopCount)

                if (
                    shouldReplace
                ) {

                    chatRepo.upsertRoute(
                        RouteEntity(
                            destinationNodeId =
                                packet.sourceNodeId,

                            nextHopAddress =
                                peerAddress,

                            hopCount =
                                calculatedHopCount,

                            publicKeyHex =
                                destinationPublicKey,

                            lastUpdated =
                                now,

                            expiresAt =
                                minOf(now, packet.timestamp) +
                                        ROUTE_EXPIRY_MILLIS
                        )
                    )

                }
                // Every fresh, unseen announcement propagates, including periodic refreshes
                // and presence withdrawals. Route replacement is a separate local decision.
                relayForwardIfNeeded(packet, peerAddress)

                true
            }
        }
    }

    /* ============================================================
       DELIVERY ACK
       ============================================================ */

    private suspend fun sendDeliveryAck(
        originalPacket: MeshPacket
    ) {

        val identity =
            identityManager.getIdentity()

        val ack =
            MeshPacket(
                sourceNodeId =
                    identity.nodeId,

                destinationNodeId =
                    originalPacket.sourceNodeId,

                previousHopId =
                    identity.nodeId,

                packetType =
                    PacketType.ACK,

                ackMessageId =
                    originalPacket.messageId,

                status =
                    MESSAGE_STATUS_DELIVERED,

                sourceName =
                    localAdvertisedName(),

                sourcePublicKey =
                    identity.publicKeyHex
            )

        sendPacketToDestination(
            signPacket(ack), allowFlooding = originalPacket.packetType != PacketType.GROUP_DATA
        )
    }

    /* ============================================================
       ROUTING / RELAY
       ============================================================ */

    private suspend fun relayForwardIfNeeded(
        packet: MeshPacket,
        incomingAddress: String
    ): Boolean {

        if (!prefs.relayModeFlow.first()) return false
        if (!relayPacketIsFresh(packet)) return false
        val localNodeId = identityManager.getIdentity().nodeId
        if (packet.sourceNodeId == localNodeId || packet.destinationNodeId == localNodeId) return false
        val incoming = activePeers[incomingAddress] ?: return false
        if (!incoming.authenticated ||
            !chatRepo.canConnectIdentity(incoming.nodeId ?: return false, false) ||
            !chatRepo.canConnectIdentity(packet.sourceNodeId, true)
        ) return false
        if (packet.destinationNodeId != MeshPacket.BROADCAST_NODE_ID &&
            !chatRepo.canConnectIdentity(packet.destinationNodeId, true)
        ) return false

        if (
            packet.ttl <= 1
        ) {
            return false
        }

        /*
         * Broadcast/control packets:
         * use controlled flooding.
         */
        if (
            packet.destinationNodeId ==
            MeshPacket.BROADCAST_NODE_ID
        ) {

            val identity =
                identityManager.getIdentity()

            val forwarded =
                packet.copyForForward(
                    identity.nodeId
                )
                    ?: return false

            return broadcastPacket(
                forwarded,
                excludeAddress =
                    incomingAddress
            )
        }

        val identity =
            identityManager.getIdentity()

        val forwarded =
            packet.copyForForward(
                identity.nodeId
            )
                ?: return false

        if (packet.packetType == PacketType.GROUP_DATA) {
            activePeers.values.firstOrNull {
                it.authenticated && it.socket.isConnected && it.nodeId == packet.destinationNodeId &&
                        it.address != incomingAddress
            }?.let { return sendPacketToPeer(it, forwarded) }
        }

        /*
         * 1. Prefer persisted route.
         */
        val route =
            chatRepo.getRoute(
                packet.destinationNodeId
            )

        if (
            route != null &&
            route.nextHopAddress !=
            incomingAddress
        ) {

            val nextHop = routeNextHop(route)

            if (
                nextHop != null &&
                nextHop.authenticated
            ) {

                return sendPacketToPeer(
                    nextHop,
                    forwarded
                )
            }
        }

        /*
         * 2. No route:
         * exact destination remains untouched.
         *
         * Use controlled flooding as route-discovery fallback.
         */
        if (packet.packetType == PacketType.GROUP_DATA) return false
        return broadcastPacket(
            forwarded,
            excludeAddress =
                incomingAddress
        )
    }

    private suspend fun recordDirectRoute(
        peer: PeerConnection
    ) {

        val nodeId =
            peer.nodeId
                ?: return

        val publicKey =
            peer.publicKeyHex
                ?: return

        val now =
            System.currentTimeMillis()

        chatRepo.upsertRoute(
            RouteEntity(
                destinationNodeId =
                    nodeId,

                nextHopAddress =
                    peer.address,

                hopCount =
                    1,

                publicKeyHex =
                    publicKey,

                lastUpdated =
                    now,

                expiresAt =
                    now +
                            ROUTE_EXPIRY_MILLIS
            )
        )
    }

    /* ============================================================
       SEND
       ============================================================ */

    suspend fun startVoiceCall(address: String) {
        val node = resolveDestinationNodeId(address) ?: return
        val name = chatRepo.getConversationByNodeId(node)?.name ?: address
        calls.start(node, name)
    }

    private fun voiceKey(peer: PeerConnection): SecretKey = synchronized(peer) {
        peer.voiceKey ?: cryptoManager.deriveSessionKey(identityManager.getIdentityPrivateKey(),
            identityManager.getIdentityPublicKey(), identityManager.decodePublicKey(checkNotNull(peer.publicKeyHex)))
            .also { peer.voiceKey = it }
    }

    /** Voice never floods or relays; it uses the authenticated peer's existing direct socket. */
    private suspend fun sendVoicePacket(node: String, payload: String, audio: Boolean): Boolean = withContext(Dispatchers.IO) {
        val peer = activePeers.values.firstOrNull { it.authenticated && it.nodeId == node } ?: return@withContext false
        if (!chatRepo.canConnectIdentity(node, false)) return@withContext false
        try {
            val identity = identityManager.getIdentity()
            val header = MeshPacket(sourceNodeId = identity.nodeId, destinationNodeId = node,
                previousHopId = identity.nodeId, ttl = 1,
                packetType = if (audio) PacketType.CALL_AUDIO else PacketType.CALL_SIGNAL,
                sourcePublicKey = identity.publicKeyHex, encrypted = true, encryptionVersion = EncryptedResult.CURRENT_VERSION)
            val encrypted = cryptoManager.encryptPayload(payload, voiceKey(peer), header.authenticatedHeader())
            sendPacketToPeer(peer, signPacket(header.copy(payload = encrypted.toJson())))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    private suspend fun receiveVoicePacket(peer: PeerConnection, packet: MeshPacket, name: String): Boolean {
        if (!peer.authenticated || packet.sourceNodeId != peer.nodeId ||
            packet.destinationNodeId != identityManager.getIdentity().nodeId || packet.ttl != 1 ||
            kotlin.math.abs(System.currentTimeMillis() - packet.timestamp) > 60_000 ||
            !chatRepo.canConnectIdentity(packet.sourceNodeId, false, respectRemoteDecision = false)) return false
        val encrypted = EncryptedResult.fromJson(packet.payload) ?: return false
        if (!packet.encrypted || packet.encryptionVersion != encrypted.v) return false
        val plaintext = try { cryptoManager.decryptPayload(encrypted, voiceKey(peer), packet.authenticatedHeader()) }
            catch (_: Exception) { return false }
        if (packet.packetType == PacketType.CALL_AUDIO) calls.receiveAudio(packet.sourceNodeId, plaintext)
        else calls.receiveSignal(packet.sourceNodeId,
            chatRepo.getConversationByNodeId(packet.sourceNodeId)?.name ?: name, plaintext)
        return true
    }

    suspend fun send(
        text: String,
        targetAddress: String? = null,
        packetType: PacketType = PacketType.DATA,
        outgoingId: String = UUID.randomUUID().toString()
    ): Boolean =
        withContext(Dispatchers.IO) {

            if (
                text.isBlank()
            ) {
                return@withContext false
            }

            val identity =
                identityManager.getIdentity()

            val target =
                targetAddress
                    ?.trim()
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                    ?: return@withContext false

            val destinationNodeId =
                resolveDestinationNodeId(
                    target
                )
                    ?: return@withContext false

            if (
                destinationNodeId ==
                identity.nodeId
            ) {
                return@withContext false
            }

            val destinationPublicKey =
                resolveDestinationPublicKey(
                    destinationNodeId
                )
                    ?: return@withContext false

            if (!chatRepo.canConnectIdentity(
                    destinationNodeId,
                    prefs.allowNewConnectionsFlow.first()
                )
            ) {
                return@withContext false
            }

            val ourPrivateKey =
                identityManager
                    .getIdentityPrivateKey()

            val ourPublicKey =
                identityManager
                    .getIdentityPublicKey()

            val messageId = outgoingId

            val timestamp =
                System.currentTimeMillis()

            /*
             * Construct immutable metadata before encryption.
             *
             * authenticatedHeader() becomes GCM AAD.
             */
            val header =
                MeshPacket(
                    messageId =
                        messageId,

                    sourceNodeId =
                        identity.nodeId,

                    destinationNodeId =
                        destinationNodeId,

                    previousHopId =
                        identity.nodeId,

                    ttl =
                        MeshPacket.DEFAULT_TTL,

                    packetType =
                        packetType,

                    timestamp =
                        timestamp,

                    payload =
                        "",

                    sourceName =
                        localAdvertisedName(),

                    sourcePublicKey =
                        identity.publicKeyHex,

                    encrypted =
                        true,

                    encryptionVersion =
                        EncryptedResult.CURRENT_VERSION
                )

            val destinationKey =
                try {

                    identityManager
                        .decodePublicKey(
                            destinationPublicKey
                        )

                } catch (_: Exception) {

                    return@withContext false
                }

            val sessionKey =
                try {

                    cryptoManager.deriveSessionKey(
                        ourPrivateKey,
                        ourPublicKey,
                        destinationKey
                    )

                } catch (_: Exception) {

                    /*
                     * NEVER send plaintext if key derivation fails.
                     */
                    return@withContext false
                }

            val encryptedResult =
                try {

                    cryptoManager.encryptPayload(
                        plaintext =
                            text,

                        sessionKey =
                            sessionKey,

                        aadData =
                            header.authenticatedHeader()
                    )

                } catch (_: Exception) {

                    /*
                     * NEVER send plaintext if encryption fails.
                     */
                    return@withContext false
                }

            val encryptedPacket =
                header.copy(
                    payload =
                        encryptedResult.toJson()
                )

            val signedPacket =
                try {

                    signPacket(
                        encryptedPacket
                    )

                } catch (_: Exception) {

                    /*
                     * No unsigned DATA packet.
                     */
                    return@withContext false
                }

            val conversationAddress =
                resolveConversationAddress(
                    target,
                    destinationNodeId
                )

            if (packetType == PacketType.FILE_CHUNK) {
                val completion = CompletableDeferred<Unit>()
                fileAcks[messageId] = destinationNodeId to completion
                try {
                    if (!requestRemoteApproval(destinationNodeId, destinationPublicKey)) return@withContext false
                    repeat(3) {
                        if (sendPacketToDestination(signedPacket) &&
                            withTimeoutOrNull(15_000) { completion.await(); true } == true) return@withContext true
                    }
                    return@withContext false
                } finally {
                    fileAcks.remove(messageId)
                }
            }

            messageDelivery.send(
                ChatMessage(
                    messageId =
                        messageId,

                    address =
                        conversationAddress,

                    nodeId =
                        destinationNodeId,

                    text =
                        text,

                    fromMe =
                        true,

                    time =
                        timestamp,

                    status = MESSAGE_STATUS_SENDING
                ),
                encryptionVersion = encryptedResult.v
            ) {
                requestRemoteApproval(destinationNodeId, destinationPublicKey) &&
                        sendPacketToDestination(signedPacket)
            }
        }

    /** Known signed identities only; group membership never grants transport approval. */
    suspend fun eligibleGroupMembers(): List<GroupMemberEntity> = withContext(Dispatchers.IO) {
        chatRepo.conversations().first().mapNotNull { conversation ->
            val node = conversation.nodeId ?: return@mapNotNull null
            if (node == identityManager.getIdentity().nodeId ||
                !chatRepo.canConnectIdentity(node, prefs.allowNewConnectionsFlow.first())
            ) return@mapNotNull null
            val key = resolveDestinationPublicKey(node) ?: return@mapNotNull null
            if (!GroupEnvelope.identityMatches(node, key)) return@mapNotNull null
            GroupMemberEntity("", node, conversation.name.take(120).ifBlank { node }, key, 0)
        }.distinctBy { it.nodeId }
    }

    suspend fun localGroupMember(): GroupMemberEntity {
        val me = identityManager.getIdentity()
        return GroupMemberEntity("", me.nodeId, localAdvertisedName().take(120), me.publicKeyHex, 0)
    }

    /** Uses this controller's existing lifetime; leaving a screen does not cancel delivery. */
    fun retryGroupDeliveries(groupId: String) {
        scope.launch {
            groupDeliveryLocks.computeIfAbsent(groupId) { Mutex() }.withLock {
                groups.recoverInterruptedAttempts()
                val pending = groups.dao.retryable(groupId)
                coroutineScope {
                    pending.groupBy { it.recipientNodeId }.values.forEach { deliveries ->
                        launch {
                            // Install the creator's invitation before subsequent messages on each link.
                            deliveries.sortedBy { if (it.kind == GroupEnvelope.INVITE) 0 else 1 }
                                .forEach {
                                    transmitGroupDelivery(it)
                                }
                        }
                    }
                }
            }
        }
    }

    private fun hasGroupPath(node: String): Boolean =
        activePeers.values.any { it.authenticated && it.socket.isConnected && it.nodeId == node }

    private suspend fun groupPathAvailable(node: String): Boolean =
        hasGroupPath(node) || chatRepo.getRoute(node)?.let { routeNextHop(it) != null } == true

    private suspend fun transmitGroupDelivery(candidate: GroupDeliveryEntity) {
        val old =
            groups.dao.delivery(candidate.groupId, candidate.itemId, candidate.recipientNodeId)
                ?: return
        if (old.status in listOf(MESSAGE_STATUS_DELIVERED, MESSAGE_STATUS_READ)) return
        val wireId = UUID.randomUUID()
            .toString() // A retry is a fresh routed packet, but retains the logical itemId.
        groups.dao.beginAttempt(old.groupId, old.itemId, old.recipientNodeId, wireId)
        var sent = false
        try {
            val group = groups.dao.group(old.groupId) ?: return
            val member = groups.dao.members(old.groupId)
                .firstOrNull { it.nodeId == old.recipientNodeId && !it.left } ?: return
            if (group.left && old.kind != GroupEnvelope.LEAVE) return
            if (!chatRepo.canConnectIdentity(
                    member.nodeId,
                    prefs.allowNewConnectionsFlow.first()
                ) ||
                !groupPathAvailable(member.nodeId)
            ) return
            if (!requestRemoteApproval(member.nodeId, member.publicKeyHex)) return
            // Recheck policy/path after approval waiting, before any group ciphertext is sent.
            if (!chatRepo.canConnectIdentity(
                    member.nodeId,
                    prefs.allowNewConnectionsFlow.first()
                ) ||
                !groupPathAvailable(member.nodeId) ||
                (groups.dao.group(old.groupId)?.left == true && old.kind != GroupEnvelope.LEAVE)
            ) return
            val identity = identityManager.getIdentity()
            val header = MeshPacket(
                messageId = wireId, sourceNodeId = identity.nodeId,
                destinationNodeId = member.nodeId, previousHopId = identity.nodeId,
                packetType = PacketType.GROUP_DATA, encrypted = true,
                encryptionVersion = EncryptedResult.CURRENT_VERSION,
                sourceName = localAdvertisedName(), sourcePublicKey = identity.publicKeyHex
            )
            val key = cryptoManager.deriveSessionKey(
                identityManager.getIdentityPrivateKey(),
                identityManager.getIdentityPublicKey(),
                identityManager.decodePublicKey(member.publicKeyHex)
            )
            val encrypted = cryptoManager.encryptPayload(
                old.payload,
                key,
                aadData = header.authenticatedHeader()
            )
            sent = sendPacketToDestination(
                signPacket(header.copy(payload = encrypted.toJson())),
                allowFlooding = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Remain retryable; never claim delivery after crypto, admission, or socket failure.
        } finally {
            withContext(NonCancellable) {
                groups.dao.finishAttempt(wireId, if (sent) "SENT" else "FAILED")
            }
        }
    }

    private suspend fun receiveGroupPacket(
        packet: MeshPacket,
        request: ConnectionRequestEntity
    ): Boolean {
        val plaintext = try {
            val encrypted = EncryptedResult.fromJson(packet.payload) ?: return false
            if (!packet.encrypted || encrypted.v != packet.encryptionVersion) return false
            val key = cryptoManager.deriveSessionKey(
                identityManager.getIdentityPrivateKey(),
                identityManager.getIdentityPublicKey(),
                identityManager.decodePublicKey(packet.sourcePublicKey ?: return false)
            )
            cryptoManager.decryptPayload(encrypted, key, aadData = packet.authenticatedHeader())
        } catch (_: Exception) {
            return false
        }
        val result = groups.receive(
            packet, plaintext, identityManager.getIdentity().nodeId,
            request, prefs.connectionPolicyFlow.first()
        )
        if (!result.accepted) return false
        result.inserted?.let {
            _incoming.emit(
                ChatMessage(
                    messageId = it.messageId, address = "group:${it.groupId}",
                    nodeId = it.senderNodeId, text = it.text, fromMe = false, time = it.timestamp,
                    status = MESSAGE_STATUS_DELIVERED
                )
            )
        }
        sendDeliveryAck(packet)
        if (result.wasRead) sendGroupReadReceipt(packet.messageId, packet.sourceNodeId)
        return true
    }

    suspend fun markGroupMessageRead(groupId: String, messageId: String) {
        val message = groups.dao.message(messageId) ?: return
        if (message.fromMe || message.groupId != groupId || groups.dao.group(groupId)?.left != false) return
        groups.dao.markRead(messageId)
        sendGroupReadReceipt(message.receiptWireId, message.senderNodeId)
    }

    private suspend fun sendGroupReadReceipt(wireId: String, destination: String) {
        if (!prefs.readReceiptsFlow.first() ||
            !chatRepo.canConnectIdentity(destination, prefs.allowNewConnectionsFlow.first())
        ) return
        val identity = identityManager.getIdentity()
        sendPacketToDestination(
            signPacket(
                MeshPacket(
                    sourceNodeId = identity.nodeId,
                    destinationNodeId = destination,
                    previousHopId = identity.nodeId,
                    packetType = PacketType.READ_RECEIPT,
                    ackMessageId = wireId,
                    status = MESSAGE_STATUS_READ,
                    sourceName = localAdvertisedName(),
                    sourcePublicKey = identity.publicKeyHex
                )
            ), allowFlooding = false
        )
    }

    private suspend fun resolveDestinationNodeId(
        targetAddress: String
    ): String? {

        if (
            targetAddress.isBlank()
        ) {
            return null
        }

        if (
            targetAddress.startsWith(
                NODE_REFERENCE_PREFIX
            )
        ) {

            return targetAddress
                .removePrefix(
                    NODE_REFERENCE_PREFIX
                )
                .takeIf {
                    it.isNotBlank()
                }
        }

        if (
            targetAddress.startsWith(
                "node_"
            )
        ) {
            return targetAddress
        }

        /*
         * Direct authenticated peer or an existing conversation.
         */
        activePeers[
            targetAddress
        ]?.nodeId
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                return it
            }

        /*
         * This helper now correctly resolves both:
         *
         * conversation address -> nodeId         *  -> PeerEntity
         */
        chatRepo.getConversation(targetAddress)?.nodeId?.takeIf { it.isNotBlank() }
            ?.let { return it }

        chatRepo
            .getPeerForConversation(
                targetAddress
            )
            ?.nodeId
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                return it
            }

        return null
    }

    private suspend fun resolveDestinationPublicKey(
        destinationNodeId: String
    ): String? {

        if (
            destinationNodeId.isBlank()
        ) {
            return null
        }

        /*
         * 1. Persisted route.
         */
        chatRepo
            .getRoute(
                destinationNodeId
            )
            ?.publicKeyHex
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                return it
            }

        /*
         * 2. Direct active peer.
         */
        activePeers.values
            .firstOrNull {
                it.authenticated &&
                        it.nodeId ==
                        destinationNodeId
            }
            ?.publicKeyHex
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                return it
            }

        /*
         * 3. Peer database.
         */
        return chatRepo
            .getPeerByNodeId(
                destinationNodeId
            )
            ?.publicKeyHex
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private suspend fun resolveConversationAddress(
        requestedAddress: String,
        destinationNodeId: String
    ): String {

        /*
         * If a real conversation already exists for this node,
         * keep its original local address.
         */
        chatRepo
            .getConversationByNodeId(
                destinationNodeId
            )
            ?.address
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                return it
            }

        /*
         * Otherwise preserve the caller's target.
         */
        return requestedAddress
    }

    private fun logicalConversationAddress(
        nodeId: String
    ): String =
        "$NODE_REFERENCE_PREFIX$nodeId"

    /* ============================================================
       READ RECEIPTS
       ============================================================ */

    suspend fun sendReadReceipt(
        messageId: String,
        destNodeId: String
    ) = withContext(Dispatchers.IO) {

        if (
            messageId.isBlank() ||
            destNodeId.isBlank()
        ) {
            return@withContext
        }

        val allowed =
            prefs.readReceiptsFlow.first()

        if (!allowed) {
            return@withContext
        }

        /*
         * Only send a receipt for a real incoming message.
         */
        val localMessage =
            chatRepo.getMessageById(
                messageId
            )
                ?: return@withContext

        if (
            localMessage.fromMe
        ) {
            return@withContext
        }

        if (
            localMessage.nodeId !=
            destNodeId
        ) {
            return@withContext
        }

        val identity =
            identityManager.getIdentity()

        val packet =
            MeshPacket(
                messageId =
                    UUID.randomUUID()
                        .toString(),

                sourceNodeId =
                    identity.nodeId,

                destinationNodeId =
                    destNodeId,

                previousHopId =
                    identity.nodeId,

                packetType =
                    PacketType.READ_RECEIPT,

                ackMessageId =
                    messageId,

                status =
                    MESSAGE_STATUS_READ,

                sourceName =
                    localAdvertisedName(),

                sourcePublicKey =
                    identity.publicKeyHex
            )

        sendPacketToDestination(
            signPacket(packet)
        )
    }

    /* ============================================================
       CONNECTION REQUEST
       ============================================================ */

    fun outgoingApproval(nodeId: String) = connectionApproval.observe(nodeId)

    private suspend fun requestRemoteApproval(nodeId: String, key: String): Boolean =
        connectionApproval.awaitRemoteApproval(nodeId, key) { request ->
            val identity = identityManager.getIdentity()
            sendPacketToDestination(
                signPacket(
                    MeshPacket(
                        ackMessageId = request.requestId, timestamp = request.timestamp,
                        sourceNodeId = identity.nodeId, destinationNodeId = nodeId,
                        previousHopId = identity.nodeId, packetType = PacketType.CONNECT_REQUEST,
                        sourceName = localAdvertisedName(), sourcePublicKey = identity.publicKeyHex
                    )
                )
            )
        }

    suspend fun decideConnectionRequest(id: Long, accepted: Boolean): Boolean {
        val decision =
            connectionApproval.decide(id, accepted, prefs.allowNewConnectionsFlow.first())
                ?: return false
        decision.requestId?.let {
            sendApprovalResponse(decision.requesterNodeId, it, decision.status)
        }
        // The decision is durable; a retry of its request resends a lost response.
        return true
    }

    private suspend fun sendApprovalResponse(
        nodeId: String,
        requestId: String,
        status: String
    ): Boolean {
        val identity = identityManager.getIdentity()
        return sendPacketToDestination(
            signPacket(
                MeshPacket(
                    sourceNodeId = identity.nodeId, destinationNodeId = nodeId,
                    previousHopId = identity.nodeId, packetType = PacketType.CONNECT_RESPONSE,
                    ackMessageId = requestId, status = status,
                    sourceName = localAdvertisedName(), sourcePublicKey = identity.publicKeyHex
                )
            )
        )
    }
    /* ============================================================
       BLOCKING
       ============================================================ */

    suspend fun blockNode(
        nodeId: String,
        address: String,
        name: String,
        fingerprint: String,
        publicKeyHex: String? = null
    ) {

        if (
            nodeId.isBlank()
        ) {
            return
        }

        val storedPeer = chatRepo.getPeerByNodeId(nodeId)
        val conversation = chatRepo.getConversationByNodeId(nodeId)
        val request = connectionApproval.latest(nodeId)
        val identityKey = storedPeer?.publicKeyHex ?: request?.publicKeyHex ?: publicKeyHex
        val blockedAddress = storedPeer?.address ?: conversation?.address ?: address
        val blockedName = com.saurabh.offgridchat.tools.PeerNames.resolve(
            blockedAddress, storedPeer?.name, conversation?.name, request?.name, name
        )
        val blockedFingerprint = identityKey?.let { IdentityManager.computeFingerprintShort(it) }
            ?: fingerprint.ifBlank { conversation?.fingerprint ?: nodeId }

        chatRepo.blockDevice(
            BlockedDeviceEntity(
                nodeId =
                    nodeId,

                address =
                    blockedAddress,

                name =
                    blockedName,

                fingerprint =
                    blockedFingerprint,

                publicKeyHex =
                    identityKey
            )
        )

        try {
            request?.requestId?.let {
                sendApprovalResponse(
                    nodeId,
                    it,
                    com.saurabh.offgridchat.database.REQUEST_STATUS_BLOCKED
                )
            }
        } finally {
            // A conversation address can be stale or logical (node:...). Never disconnect a relay
            // just because it transported the request; close sockets belonging to the blocked identity.
            activePeers.values.filter { it.nodeId == nodeId }.forEach { disconnectPeer(it) }
        }

        /*
         * Remove its route immediately.
         */
        chatRepo.deleteRoute(
            nodeId
        )
    }

    suspend fun unblockNode(
        nodeId: String
    ) {

        if (
            nodeId.isBlank()
        ) {
            return
        }

        chatRepo.unblockDevice(
            nodeId
        )
    }

    /* ============================================================
       TRANSPORT
       ============================================================ */

    private suspend fun sendPacketToDestination(
        packet: MeshPacket,
        allowFlooding: Boolean = true
    ): Boolean {

        if (chatRepo.isBlocked(packet.destinationNodeId) &&
            !(packet.packetType == PacketType.CONNECT_RESPONSE &&
                    packet.status == com.saurabh.offgridchat.database.REQUEST_STATUS_BLOCKED &&
                    !packet.ackMessageId.isNullOrBlank())
        ) return false

        if (
            packet.destinationNodeId ==
            MeshPacket.BROADCAST_NODE_ID
        ) {

            return broadcastPacket(
                packet
            )
        }

        /*
         * Direct authenticated peer.
         */
        val directPeer =
            activePeers.values
                .firstOrNull {
                    it.authenticated &&
                            it.nodeId ==
                            packet.destinationNodeId
                }

        if (
            directPeer != null
        ) {

            return sendPacketToPeer(
                directPeer,
                packet
            )
        }

        /*
         * Persisted route.
         */
        val route =
            chatRepo.getRoute(
                packet.destinationNodeId
            )

        if (
            route != null
        ) {

            val nextHop = routeNextHop(route)

            if (
                nextHop != null &&
                nextHop.authenticated
            ) {

                return sendPacketToPeer(
                    nextHop,
                    packet
                )
            }
        }

        /*
         * Unknown route:
         *
         * preserve exact destination and use controlled flooding
         * as discovery fallback.
         *
         * The DATA packet itself remains encrypted.
         */
        return allowFlooding && broadcastPacket(packet)
    }

    private suspend fun sendPacketToPeer(
        peer: PeerConnection,
        packet: MeshPacket,
        allowUnauthenticated: Boolean = false
    ): Boolean {

        if (activePeers[peer.address] !== peer) return false
        val forwarding = packet.sourceNodeId != identityManager.getIdentity().nodeId
        if (forwarding && (!relayEnabled || !relayPacketIsFresh(packet) ||
                    !chatRepo.canConnectIdentity(peer.nodeId ?: return false, false))
        ) return false

        if (!peer.authenticated && (!allowUnauthenticated ||
                    packet.packetType !in setOf(PacketType.AUTH_HELLO, PacketType.AUTH_PROOF))
        ) {
            return false
        }

        if (
            !packet.isStructurallyValid()
        ) {
            return false
        }

        val wireData =
            try {

                packet.toJson()
                    .toByteArray(
                        StandardCharsets.UTF_8
                    )

            } catch (_: Exception) {

                return false
            }

        if (
            wireData.size >
            MAX_UTF_FRAME_BYTES
        ) {
            return false
        }

        return try {

            synchronized(
                peer.out
            ) {
                if (forwarding && (!relayEnabled || !relayPacketIsFresh(packet))) return false

                // Check again at the actual write, including packets queued before a toggle.
                if (packet.sourceNodeId == identityManager.getIdentity().nodeId) {
                    if (packet.packetType in setOf(
                            PacketType.PRESENCE_PING,
                            PacketType.PRESENCE_PONG
                        ) &&
                        !shareOnlineStatus
                    ) return false
                    if (packet.packetType == PacketType.ROUTE_ANNOUNCE &&
                        packet.status != if (shareOnlineStatus) "ONLINE" else null
                    ) return false
                }

                peer.out.writeUTF(
                    String(
                        wireData,
                        StandardCharsets.UTF_8
                    )
                )

                peer.out.flush()
            }

            peer.lastActivity =
                System.currentTimeMillis()

            true

        } catch (
            _: IOException
        ) {

            disconnectPeer(peer)

            false
        }
    }

    private suspend fun broadcastPacket(
        packet: MeshPacket,
        excludeAddress: String? = null
    ): Boolean {

        var sentAny =
            false

        for (
        peer in activePeers.values
        ) {

            if (
                peer.address ==
                excludeAddress
            ) {
                continue
            }

            if (
                !peer.authenticated
            ) {
                continue
            }

            if (
                sendPacketToPeer(
                    peer,
                    packet
                )
            ) {
                sentAny =
                    true
            }
        }

        return sentAny
    }

    /* ============================================================
       CRYPTOGRAPHIC SIGNATURE
       ============================================================ */

    private fun signPacket(
        packet: MeshPacket
    ): MeshPacket {

        val identity =
            identityManager.getIdentity()

        val privateKey =
            identityManager
                .getIdentityPrivateKey()

        val signer =
            Signature.getInstance(
                ECDSA_SIGNATURE_ALGORITHM
            )

        signer.initSign(
            privateKey
        )

        signer.update(
            signatureInput(packet)
        )

        val signatureBytes =
            signer.sign()

        val signatureBase64 =
            Base64.getEncoder()
                .encodeToString(
                    signatureBytes
                )

        return packet.copy(
            sourcePublicKey =
                identity.publicKeyHex,

            signature =
                signatureBase64
        )
    }

    private fun verifyPacketSignature(
        packet: MeshPacket
    ): Boolean {

        val publicKeyBase64 =
            packet.sourcePublicKey
                ?: return false

        val signatureBase64 =
            packet.signature
                ?: return false

        return try {

            val publicKey =
                identityManager
                    .decodePublicKey(
                        publicKeyBase64
                    )

            val signatureBytes =
                Base64.getDecoder()
                    .decode(
                        signatureBase64
                    )

            val verifier =
                Signature.getInstance(
                    ECDSA_SIGNATURE_ALGORITHM
                )

            verifier.initVerify(
                publicKey
            )

            verifier.update(
                signatureInput(packet)
            )

            verifier.verify(
                signatureBytes
            )

        } catch (_: Exception) {

            false
        }
    }

    private fun signatureInput(
        packet: MeshPacket
    ): ByteArray {

        /*
         * previousHopId and ttl are intentionally excluded.
         *
         * They are valid relay-modified routing fields.
         *
         * Everything else, including encrypted payload, is
         * covered by the original sender's signature.
         */
        return packet.signatureInput()
    }

    /* ============================================================
       DISCONNECT / CLEANUP
       ============================================================ */

    @Synchronized
    fun disconnectPeer(
        address: String
    ) {
        activePeers[address]?.let { disconnectPeer(it) }
    }

    suspend fun disconnectConversation(address: String) {
        val nodeId = resolveDestinationNodeId(address) ?: return
        // Never disconnect a relay (and its other chats) to disconnect a logical mesh destination.
        activePeers.values.filter { it.nodeId == nodeId }.forEach {
            reconnectSuppressed.add(it.address)
            disconnectPeer(it)
        }
    }

    private fun disconnectPeer(peer: PeerConnection) {
        val removed = activePeers.remove(peer.address, peer)
        if (removed) {
            publishSocketStates()
            peer.nodeId?.let { calls.peerDisconnected(it) }
        }
        peer.readJob?.cancel()
        peer.handshakeTimeout?.cancel()
        runCatching { peer.socket.close() }
        if (removed && activePeers.isEmpty()) _conn.value = ConnState.Idle
        if (removed) scope.launch {
            presenceMutex.withLock {
                if (activePeers[peer.address] == null) {
                    val lost =
                        remotePresence.filterValues { it.first == peer.address }.keys.toMutableSet()
                    peer.nodeId?.let { lost.add(it) }
                    for (nodeId in lost) {
                        remotePresence.remove(nodeId)
                        chatRepo.updatePeerPresence(nodeId, false)
                    }
                }
            }
        }
    }

    fun disconnectAll(allowReconnect: Boolean = false) {
        if (!allowReconnect) reconnectSuppressed.addAll(activePeers.keys)

        for (
        address in
        activePeers.keys.toList()
        ) {

            disconnectPeer(
                address
            )
        }
    }

    private fun cleanupSeenMessageIds(
        now: Long
    ) {

        for (
        entry in
        seenMessageIds.entries
        ) {

            if (
                now -
                entry.value >
                SEEN_MESSAGE_RETENTION_MILLIS
            ) {

                seenMessageIds.remove(
                    entry.key,
                    entry.value
                )
            }
        }
    }

    fun release() {
        heartbeatJob?.cancel()
        routeAnnouncementJob?.cancel()
        probeJob?.cancel()
        serverJob?.cancel()
        runCatching {
            app.unregisterReceiver(
                receiver
            )
        }
        stopScan()
        stopServer()
        disconnectAll()
        seenMessageIds.clear()
        scope.launch {
            chatRepo.clearRoutes()
        }
        scope.cancel()
    }
}
