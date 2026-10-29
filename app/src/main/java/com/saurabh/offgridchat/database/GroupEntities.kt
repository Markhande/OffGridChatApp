package com.saurabh.offgridchat.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val groupName: String,
    val creatorNodeId: String,
    val createdAt: Long,
    val manifest: String,
    val left: Boolean = false,
)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "nodeId"])
data class GroupMemberEntity(
    val groupId: String,
    val nodeId: String,
    val displayName: String,
    val publicKeyHex: String,
    val joinedAt: Long,
    val left: Boolean = false,
)

@Entity(tableName = "group_messages", indices = [Index("groupId")])
data class GroupMessageEntity(
    @PrimaryKey val messageId: String,
    val groupId: String,
    val senderNodeId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val fromMe: Boolean,
    val receiptWireId: String,
    val readLocally: Boolean = false,
)

@Entity(tableName = "group_deliveries", primaryKeys = ["groupId", "itemId", "recipientNodeId"],
    indices = [Index(value = ["wireId"], unique = true)])
data class GroupDeliveryEntity(
    val groupId: String,
    val itemId: String,
    val recipientNodeId: String,
    val wireId: String,
    val kind: String,
    val payload: String,
    val status: String = "PENDING",
)

// Additive migration only: existing direct-message, approval, and identity tables are untouched.
@Entity(tableName = "group_delivery_attempts")
data class GroupDeliveryAttemptEntity(
    @PrimaryKey val wireId: String,
    val groupId: String,
    val itemId: String,
    val recipientNodeId: String,
)

val GROUP_SCHEMA_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS chat_groups (groupId TEXT NOT NULL PRIMARY KEY, groupName TEXT NOT NULL, creatorNodeId TEXT NOT NULL, createdAt INTEGER NOT NULL, manifest TEXT NOT NULL, `left` INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS group_members (groupId TEXT NOT NULL, nodeId TEXT NOT NULL, displayName TEXT NOT NULL, publicKeyHex TEXT NOT NULL, joinedAt INTEGER NOT NULL, `left` INTEGER NOT NULL, PRIMARY KEY(groupId, nodeId))",
    "CREATE TABLE IF NOT EXISTS group_messages (messageId TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL, senderNodeId TEXT NOT NULL, senderName TEXT NOT NULL, text TEXT NOT NULL, timestamp INTEGER NOT NULL, fromMe INTEGER NOT NULL, receiptWireId TEXT NOT NULL, readLocally INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_group_messages_groupId ON group_messages(groupId)",
    "CREATE TABLE IF NOT EXISTS group_deliveries (groupId TEXT NOT NULL, itemId TEXT NOT NULL, recipientNodeId TEXT NOT NULL, wireId TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY(groupId, itemId, recipientNodeId))",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_group_deliveries_wireId ON group_deliveries(wireId)",
    "CREATE TABLE IF NOT EXISTS group_delivery_attempts (wireId TEXT NOT NULL PRIMARY KEY, groupId TEXT NOT NULL, itemId TEXT NOT NULL, recipientNodeId TEXT NOT NULL)",
)
