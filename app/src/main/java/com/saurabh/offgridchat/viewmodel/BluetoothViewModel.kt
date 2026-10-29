package com.saurabh.offgridchat.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.offgridchat.core.BluetoothController
import com.saurabh.offgridchat.crypto.DeviceIdentity
import com.saurabh.offgridchat.crypto.IdentityManager
import com.saurabh.offgridchat.database.BlockedDeviceEntity
import com.saurabh.offgridchat.database.ConnectionRequestEntity
import com.saurabh.offgridchat.database.ConversationSummary
import com.saurabh.offgridchat.database.MESSAGE_STATUS_READ
import com.saurabh.offgridchat.datastore.Preferences
import com.saurabh.offgridchat.export.ChatExporter
import com.saurabh.offgridchat.repository.ChatRepository
import com.saurabh.offgridchat.tools.BtDevice
import com.saurabh.offgridchat.tools.ChatMessage
import com.saurabh.offgridchat.tools.ConnState
import com.saurabh.offgridchat.tools.PeerReachability
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BluetoothViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    private val prefs: Preferences,
    private val identityManager: IdentityManager,
    private val btController: BluetoothController,
    private val chatExporter: ChatExporter
) : ViewModel() {
    val fileTransfers: StateFlow<Map<String, String>> = btController.fileTransfers
    fun sendFile(address: String, uri: Uri) = btController.sendFile(address, uri)
    val callState = btController.calls.state
    fun startCall(address: String) { viewModelScope.launch { btController.startVoiceCall(address) } }

    /* ============================================================
       BLUETOOTH STATE
       ============================================================ */

    val isSupported: Boolean
        get() = btController.isSupported

    val bluetoothOn: StateFlow<Boolean> =
        btController.enabled
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                false
            )

    val discoverable: StateFlow<Boolean> =
        btController.discoverable
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                false
            )

    val scanning: StateFlow<Boolean> =
        btController.scanning
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                false
            )

    val devices: StateFlow<List<BtDevice>> =
        btController.devices
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                emptyList()
            )

    val connection: StateFlow<ConnState> =
        btController.connection
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                ConnState.Idle
            )

    val socketStates = btController.socketStates

    // Routes need clock-driven expiry even when Room has no new writes. This performs no polling queries.
    val reachability: StateFlow<Map<String, PeerReachability>> = combine(
        socketStates,
        chatRepo.observeRoutes(),
        flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }
    ) { sockets, routes, now ->
        buildMap {
            for (route in routes) {
                val hop = sockets[route.nextHopAddress] ?: continue
                if (hop.authenticated && route.expiresAt > now && route.lastUpdated >= hop.connectedAt &&
                    !route.publicKeyHex.isNullOrBlank()) {
                    put(route.destinationNodeId, PeerReachability.MESH)
                    put("node:${route.destinationNodeId}", PeerReachability.MESH)
                }
            }
            for (socket in sockets.values) {
                val state = if (socket.authenticated) PeerReachability.DIRECT else PeerReachability.AUTHENTICATING
                put(socket.address, state)
                if (socket.authenticated) socket.nodeId?.let {
                    put(it, state)
                    put("node:$it", state)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /* ============================================================
       SETTINGS / PREFERENCES
       ============================================================ */

    val relayMode: StateFlow<Boolean> =
        prefs.relayModeFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                true
            )

    val requireApproval: StateFlow<Boolean> =
        prefs.requireApprovalFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                false
            )

    /* ============================================================
       ROOM DATA
       ============================================================ */

    val conversations: StateFlow<List<ConversationSummary>> =
        chatRepo.conversations()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList()
            )

    val pendingRequests: StateFlow<List<ConnectionRequestEntity>> =
        chatRepo.observePendingRequests()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList()
            )

    val blockedDevices: StateFlow<List<BlockedDeviceEntity>> =
        chatRepo.observeBlockedDevices()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList()
            )

    val messageCount: StateFlow<Int> =
        chatRepo.messageCount()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                0
            )

    val totalBytes: StateFlow<Long?> =
        chatRepo.totalBytes()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                0L
            )

    /* ============================================================
       OPEN CHAT
       ============================================================ */

    private val openAddress =
        MutableStateFlow<String?>(null)

    val messages: StateFlow<List<ChatMessage>> =
        openAddress
            .flatMapLatest { address ->

                if (
                    address == null
                ) {
                    flowOf(emptyList())
                } else {
                    chatRepo.messages(address)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList()
            )

    /* ============================================================
       IDENTITY
       ============================================================ */

    val identity: DeviceIdentity
        get() = identityManager.getIdentity()

    /* ============================================================
       INTENTS
       ============================================================ */

    fun enableIntent() =
        btController.enableIntent()

    fun discoverableIntent() =
        btController.discoverableIntent()

    fun settingsIntent() =
        btController.settingsIntent()

    /* ============================================================
       BLUETOOTH ACTIONS
       ============================================================ */

    fun startScan() {

        btController.startScan()
    }

    fun stopScan() {

        btController.stopScan()
    }

    fun startServer() {

        btController.startServer()
    }

    fun disconnect() {

        btController.disconnectAll()
    }

    fun disconnect(address: String) {
        viewModelScope.launch { btController.disconnectConversation(address) }
    }

    fun turnOff(
        onFailed: () -> Unit
    ) {

        viewModelScope.launch {

            if (
                !btController.tryDisable()
            ) {
                onFailed()
            }
        }
    }

    fun connect(
        address: String
    ) {

        if (
            address.isBlank()
        ) {
            return
        }

        viewModelScope.launch {

            // The authenticated controller saves the canonical identity/conversation.
            btController.connect(
                address
            )
        }
    }

    /* ============================================================
       SEND
       ============================================================ */

    fun send(
        address: String,
        text: String
    ) {

        if (
            address.isBlank() ||
            text.isBlank()
        ) {
            return
        }

        viewModelScope.launch {

            btController.send(
                text =
                    text,

                targetAddress =
                    address
            )
        }
    }

    /* ============================================================
       READ RECEIPTS
       ============================================================ */

    /*
    fun sendReadReceipt(
        messageId: String,
        destNodeId: String
    ) {

        if (
            messageId.isBlank() ||
            destNodeId.isBlank()
        ) {
            return
        }

        viewModelScope.launch {

            btController.sendReadReceipt(
                messageId =
                    messageId,

                destNodeId =
                    destNodeId
            )
        }
    }
     */

    /**
     * Convenience method for the chat screen.
     *
     * When the UI opens a message, the ViewModel can resolve its
     * logical peer node ID from the stored message.
     */
    fun markMessageRead(
        messageId: String
    ) {

        if (
            messageId.isBlank()
        ) {
            return
        }

        viewModelScope.launch {

            val message =
                chatRepo.getMessageById(
                    messageId
                )
                    ?: return@launch

            /*
             * Incoming message only.
             */
            if (
                message.fromMe
            ) {
                return@launch
            }

            val nodeId =
                message.nodeId
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: return@launch

            chatRepo.updateMessageStatus(
                messageId,
                MESSAGE_STATUS_READ
            )

            btController.sendReadReceipt(
                messageId =
                    messageId,

                destNodeId =
                    nodeId
            )
        }
    }

    /* ============================================================
       CHAT
       ============================================================ */

    fun openChat(
        address: String
    ) {

        if (
            address.isBlank()
        ) {
            return
        }

        openAddress.value =
            address
    }

    /* ============================================================
       SETTINGS
       ============================================================ */

    fun setRelayMode(
        enabled: Boolean
    ) {

        viewModelScope.launch {

            prefs.saveRelayMode(
                enabled
            )
        }
    }

    fun setRequireApproval(
        enabled: Boolean
    ) {

        viewModelScope.launch {

            prefs.saveRequireApproval(
                enabled
            )
        }
    }

    /* ============================================================
       CONNECTION REQUESTS
       ============================================================ */

    fun acceptRequest(
        request: ConnectionRequestEntity
    ) {

        viewModelScope.launch {

            btController.decideConnectionRequest(request.id, accepted = true)
        }
    }

    fun rejectRequest(
        request: ConnectionRequestEntity
    ) {

        viewModelScope.launch {

            btController.decideConnectionRequest(request.id, accepted = false)
        }
    }

    /* ============================================================
       BLOCKING
       ============================================================ */

    fun blockDevice(
        nodeId: String,
        address: String,
        name: String,
        fingerprint: String
    ) {

        if (
            nodeId.isBlank()
        ) {
            return
        }

        viewModelScope.launch {

            btController.blockNode(
                nodeId =
                    nodeId,

                address =
                    address,

                name =
                    name,

                fingerprint =
                    fingerprint
            )
        }
    }

    fun unblockDevice(
        nodeId: String
    ) {

        if (
            nodeId.isBlank()
        ) {
            return
        }

        viewModelScope.launch {

            btController.unblockNode(
                nodeId
            )
        }
    }

    /* ============================================================
       EXPORT / SHARING
       ============================================================ */

    fun shareToWhatsApp(
        context: Context,
        address: String? = null,
        onPackageNotFound: () -> Unit
    ) {

        viewModelScope.launch {

            chatExporter.shareToWhatsApp(
                context,
                address,
                onPackageNotFound
            )
        }
    }

    fun shareGeneral(
        context: Context,
        address: String? = null
    ) {

        viewModelScope.launch {

            chatExporter.shareGeneral(
                context,
                address
            )
        }
    }

    /* ============================================================
       DATA MANAGEMENT
       ============================================================ */

    fun clearAllData() {

        viewModelScope.launch {

            chatRepo.clearAll()
        }
    }

    /* ============================================================
       UI HELPERS
       ============================================================ */

    fun nameOf(
        address: String
    ): String {

        return conversations.value
            .firstOrNull {
                it.address == address
            }
            ?.name
            ?: devices.value
                .firstOrNull {
                    it.address == address
                }
                ?.name
            ?: address
    }
}
