package com.saurabh.offgridchat.protocol

import com.saurabh.offgridchat.database.GroupMemberEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Versioned application payload inside the existing signed, per-destination encrypted packet. */
object GroupEnvelope {
    const val INVITE = "INVITE"
    const val MESSAGE = "MESSAGE"
    const val LEAVE = "LEAVE"
    const val MAX_MEMBERS = 16
    const val MAX_TEXT = 4000

    fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun identityMatches(node: String, key: String): Boolean = key.length <= 1024 &&
        MeshPacket(sourceNodeId = node, destinationNodeId = node, previousHopId = node,
            packetType = PacketType.GROUP_DATA, sourcePublicKey = key).isNodeIdBoundToPublicKey()

    fun manifest(id: String, name: String, creator: String, createdAt: Long, members: List<GroupMemberEntity>): String =
        JSONObject().put("groupId", id).put("name", name).put("creator", creator).put("createdAt", createdAt)
            .put("members", JSONArray().apply {
                members.sortedBy { it.nodeId }.forEach {
                    put(JSONObject().put("node", it.nodeId).put("name", it.displayName).put("key", it.publicKeyHex))
                }
            }).toString()

    fun members(manifest: JSONObject): List<GroupMemberEntity> {
        val id = manifest.getString("groupId")
        require(validId(id))
        require(manifest.getString("name").isNotBlank() && manifest.getString("name").length <= 80)
        val created = manifest.getLong("createdAt")
        require(created > 0 && created <= System.currentTimeMillis() + 60_000)
        val array = manifest.getJSONArray("members")
        require(array.length() in 2..MAX_MEMBERS)
        val members = (0 until array.length()).map {
            val member = array.getJSONObject(it)
            val node = member.getString("node")
            val name = member.getString("name")
            val key = member.getString("key")
            require(name.isNotBlank() && name.length <= 120 && identityMatches(node, key))
            GroupMemberEntity(id, node, name, key, created)
        }
        require(members.distinctBy { it.nodeId }.size == members.size)
        require(members.any { it.nodeId == manifest.getString("creator") })
        return members
    }

    fun payload(kind: String, groupId: String, itemId: String): JSONObject =
        JSONObject().put("v", 1).put("kind", kind).put("groupId", groupId).put("itemId", itemId)
}
