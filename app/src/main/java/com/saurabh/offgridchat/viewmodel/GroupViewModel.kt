package com.saurabh.offgridchat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.offgridchat.core.BluetoothController
import com.saurabh.offgridchat.crypto.IdentityManager
import com.saurabh.offgridchat.database.*
import com.saurabh.offgridchat.repository.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupViewModel @Inject constructor(
    private val repository: GroupRepository,
    private val bluetooth: BluetoothController,
    private val identity: IdentityManager,
) : ViewModel() {
    val groups = repository.groups().onStart { repository.recoverInterruptedAttempts() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val selectedGroup = MutableStateFlow("")
    val messages = selectedGroup.flatMapLatest { repository.dao.messages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val members = selectedGroup.flatMapLatest { repository.dao.observeMembers(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val deliveries = selectedGroup.flatMapLatest { repository.dao.deliveries(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _eligible = MutableStateFlow<List<GroupMemberEntity>>(emptyList())
    val eligible = _eligible.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val localNode: String get() = identity.getIdentity().nodeId

    fun open(id: String) { selectedGroup.value = id }
    fun clearError() { _error.value = null }
    fun loadEligible() = action { _eligible.value = bluetooth.eligibleGroupMembers() }
    fun create(name: String, nodes: Set<String>, onCreated: (String) -> Unit) = action {
        val eligibleNow = bluetooth.eligibleGroupMembers().filter { it.nodeId in nodes }
        require(eligibleNow.size == nodes.size && nodes.isNotEmpty()) { "Select eligible peers and try again" }
        val local = bluetooth.localGroupMember()
        val id = repository.create(name, local, eligibleNow)
        bluetooth.retryGroupDeliveries(id)
        onCreated(id)
    }
    fun send(id: String, text: String, onSaved: () -> Unit) = action {
        repository.send(id, localNode, text)
        onSaved()
        bluetooth.retryGroupDeliveries(id)
    }
    fun retry(id: String) { bluetooth.retryGroupDeliveries(id) }
    fun leave(id: String) = action {
        repository.leave(id, localNode)
        bluetooth.retryGroupDeliveries(id)
    }
    fun read(id: String, messageId: String) {
        viewModelScope.launch { bluetooth.markGroupMessageRead(id, messageId) }
    }
    private fun action(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _error.value = e.message ?: "Could not complete the group action" }
            finally { _busy.value = false }
        }
    }
}
