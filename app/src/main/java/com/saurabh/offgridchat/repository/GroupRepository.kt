package com.saurabh.offgridchat.repository

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.saurabh.offgridchat.database.*
import com.saurabh.offgridchat.protocol.*
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class GroupReceiveResult(val accepted: Boolean, val inserted: GroupMessageEntity? = null, val wasRead: Boolean = false)

@Singleton
class GroupRepository @Inject constructor(private val db: AppDatabase) {
    val dao = db.groupDao()
    private val chats = db.chatDao()
    private val recoveryMutex = Mutex()
    private var recovered = false
    suspend fun recoverInterruptedAttempts() = recoveryMutex.withLock {
        if (!recovered) {
            dao.recoverInterruptedAttempts()
            recovered = true
        }
    }
    fun groups() = dao.groups()

    private fun deliveries(groupId: String, itemId: String, kind: String, payload: String,
        members: List<GroupMemberEntity>, sender: String) = members.filter { !it.left && it.nodeId != sender }.map {
        GroupDeliveryEntity(groupId, itemId, it.nodeId, UUID.randomUUID().toString(), kind, payload)
    }

    suspend fun create(name: String, local: GroupMemberEntity, selected: List<GroupMemberEntity>): String = db.withTransaction {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val members = (listOf(local) + selected).map { it.copy(groupId = id, joinedAt = now, left = false) }
        val manifest = GroupEnvelope.manifest(id, name.trim(), local.nodeId, now, members)
        GroupEnvelope.members(JSONObject(manifest)) // Validate size, identities and names before writing.
        for (member in selected) require(chats.canConnectIdentity(member.nodeId, true)) { "A selected peer is blocked or rejected" }
        dao.insertGroup(GroupEntity(id, name.trim(), local.nodeId, now, manifest))
        dao.insertMembers(members)
        val payload = GroupEnvelope.payload(GroupEnvelope.INVITE, id, id).put("manifest", JSONObject(manifest)).toString()
        dao.insertDeliveries(deliveries(id, id, GroupEnvelope.INVITE, payload, members, local.nodeId))
        id
    }

    suspend fun send(groupId: String, sender: String, text: String): String = db.withTransaction {
        val group = requireNotNull(dao.group(groupId))
        require(!group.left && text.isNotBlank() && text.length <= GroupEnvelope.MAX_TEXT)
        val members = dao.members(groupId)
        val self = members.single { it.nodeId == sender && !it.left }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        check(dao.insertMessage(GroupMessageEntity(id, groupId, sender, self.displayName, text, now, true, "")) != -1L)
        val payload = GroupEnvelope.payload(GroupEnvelope.MESSAGE, groupId, id)
            .put("text", text).put("time", now).toString()
        dao.insertDeliveries(deliveries(groupId, id, GroupEnvelope.MESSAGE, payload, members, sender))
        id
    }

    suspend fun leave(groupId: String, localNode: String) = db.withTransaction {
        val group = requireNotNull(dao.group(groupId))
        if (group.left) return@withTransaction
        val members = dao.members(groupId)
        val id = UUID.randomUUID().toString()
        val payload = GroupEnvelope.payload(GroupEnvelope.LEAVE, groupId, id).toString()
        dao.insertDeliveries(deliveries(groupId, id, GroupEnvelope.LEAVE, payload, members, localNode))
        dao.leaveMember(groupId, localNode)
        dao.leaveGroup(groupId)
    }

    /** Called only after packet signature, identity binding and AES-GCM authentication succeeded. */
    suspend fun receive(packet: MeshPacket, plaintext: String, localNode: String,
        request: ConnectionRequestEntity, policy: ConnectionPolicy): GroupReceiveResult = db.withTransaction {
        val invalid = GroupReceiveResult(false)
        val envelope = try { JSONObject(plaintext) } catch (_: Exception) { return@withTransaction invalid }
        if (envelope.optInt("v") != 1) return@withTransaction invalid
        val groupId = envelope.optString("groupId")
        val itemId = envelope.optString("itemId")
        if (!GroupEnvelope.validId(groupId) || !GroupEnvelope.validId(itemId)) return@withTransaction invalid
        val group = dao.group(groupId)
        if (group?.left == true) return@withTransaction invalid // Durable leave tombstone; old invites cannot re-add us.
        val kind = envelope.optString("kind")
        if (kind == GroupEnvelope.INVITE) {
            val manifest = envelope.optJSONObject("manifest") ?: return@withTransaction invalid
            val members = try { GroupEnvelope.members(manifest) } catch (_: Exception) { return@withTransaction invalid }
            if (manifest.getString("groupId") != groupId || itemId != groupId ||
                manifest.getString("creator") != packet.sourceNodeId ||
                members.none { it.nodeId == localNode } ||
                members.none { it.nodeId == packet.sourceNodeId && it.publicKeyHex == packet.sourcePublicKey }) return@withTransaction invalid
            val canonical = GroupEnvelope.manifest(groupId, manifest.getString("name"), packet.sourceNodeId,
                manifest.getLong("createdAt"), members)
            if (group != null && group.manifest != canonical) return@withTransaction invalid
            for (member in members) {
                val knownKey = chats.getPeerByNodeId(member.nodeId)?.publicKeyHex
                if (knownKey != null && knownKey != member.publicKeyHex) return@withTransaction invalid
            }
            if (chats.admitIncomingIdentity(request, policy) != ConnectionAdmission.ALLOW) return@withTransaction invalid
            if (group == null) {
                dao.insertGroup(GroupEntity(groupId, manifest.getString("name"), packet.sourceNodeId,
                    manifest.getLong("createdAt"), canonical))
                dao.insertMembers(members)
            }
            return@withTransaction GroupReceiveResult(true)
        }
        if (group == null) return@withTransaction invalid
        val members = dao.members(groupId)
        if (members.none { it.nodeId == localNode && !it.left }) return@withTransaction invalid
        val sender = members.firstOrNull { it.nodeId == packet.sourceNodeId && it.publicKeyHex == packet.sourcePublicKey }
            ?: return@withTransaction invalid
        if (kind !in listOf(GroupEnvelope.MESSAGE, GroupEnvelope.LEAVE) ||
            (sender.left && kind != GroupEnvelope.LEAVE)) return@withTransaction invalid
        if (chats.admitIncomingIdentity(request, policy) != ConnectionAdmission.ALLOW) return@withTransaction invalid
        if (kind == GroupEnvelope.LEAVE) {
            dao.leaveMember(groupId, sender.nodeId)
            return@withTransaction GroupReceiveResult(true)
        }
        val text = envelope.optString("text")
        val time = envelope.optLong("time")
        if (text.isBlank() || text.length > GroupEnvelope.MAX_TEXT || time <= 0 ||
            time > System.currentTimeMillis() + 60_000) return@withTransaction invalid
        val message = GroupMessageEntity(itemId, groupId, sender.nodeId, sender.displayName, text, time, false, packet.messageId)
        if (dao.insertMessage(message) != -1L) return@withTransaction GroupReceiveResult(true, message)
        val old = dao.message(itemId) ?: return@withTransaction invalid
        if (old.fromMe || old.groupId != groupId || old.senderNodeId != sender.nodeId || old.text != text || old.timestamp != time)
            return@withTransaction invalid
        dao.updateReceipt(itemId, packet.messageId)
        GroupReceiveResult(true, wasRead = old.readLocally)
    }

    /** The wire ID binds one logical operation to one intended recipient, never the whole group. */
    suspend fun receipt(packet: MeshPacket, status: String): Boolean = db.withTransaction {
        val attempt = dao.attemptByWire(packet.ackMessageId ?: return@withTransaction false) ?: return@withTransaction false
        val delivery = dao.delivery(attempt.groupId, attempt.itemId, attempt.recipientNodeId) ?: return@withTransaction false
        val member = dao.members(delivery.groupId).firstOrNull { it.nodeId == delivery.recipientNodeId } ?: return@withTransaction false
        if (packet.sourceNodeId != member.nodeId || packet.sourcePublicKey != member.publicKeyHex ||
            chats.getBlockedDevice(member.nodeId) != null ||
            (status == MESSAGE_STATUS_READ && delivery.kind != GroupEnvelope.MESSAGE)) return@withTransaction false
        dao.receipt(delivery.groupId, delivery.itemId, delivery.recipientNodeId, status)
        true
    }
}
