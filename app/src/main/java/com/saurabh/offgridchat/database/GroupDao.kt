package com.saurabh.offgridchat.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("UPDATE group_deliveries SET status = 'FAILED' WHERE status = 'SENDING'")
    suspend fun recoverInterruptedAttempts()
    @Query("SELECT * FROM chat_groups ORDER BY createdAt DESC")
    fun groups(): Flow<List<GroupEntity>>
    @Query("SELECT * FROM chat_groups WHERE groupId = :id")
    suspend fun group(id: String): GroupEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(group: GroupEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMembers(members: List<GroupMemberEntity>)
    @Query("SELECT * FROM group_members WHERE groupId = :id ORDER BY joinedAt, nodeId")
    suspend fun members(id: String): List<GroupMemberEntity>
    @Query("SELECT * FROM group_members WHERE groupId = :id ORDER BY joinedAt, nodeId")
    fun observeMembers(id: String): Flow<List<GroupMemberEntity>>
    @Query("UPDATE group_members SET `left` = 1 WHERE groupId = :id AND nodeId = :node")
    suspend fun leaveMember(id: String, node: String)
    @Query("UPDATE chat_groups SET `left` = 1 WHERE groupId = :id")
    suspend fun leaveGroup(id: String)
    @Query("SELECT * FROM group_messages WHERE groupId = :id ORDER BY timestamp, messageId")
    fun messages(id: String): Flow<List<GroupMessageEntity>>
    @Query("SELECT * FROM group_messages WHERE messageId = :id")
    suspend fun message(id: String): GroupMessageEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: GroupMessageEntity): Long
    @Query("UPDATE group_messages SET receiptWireId = :wire WHERE messageId = :id AND fromMe = 0")
    suspend fun updateReceipt(id: String, wire: String)
    @Query("UPDATE group_messages SET readLocally = 1 WHERE messageId = :id AND fromMe = 0")
    suspend fun markRead(id: String)
    @Query("SELECT * FROM group_deliveries WHERE groupId = :id")
    fun deliveries(id: String): Flow<List<GroupDeliveryEntity>>
    @Query("SELECT * FROM group_deliveries WHERE groupId = :id AND status NOT IN ('DELIVERED', 'READ')")
    suspend fun retryable(id: String): List<GroupDeliveryEntity>
    @Query("SELECT * FROM group_delivery_attempts WHERE wireId = :id")
    suspend fun attemptByWire(id: String): GroupDeliveryAttemptEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: GroupDeliveryAttemptEntity)
    @Query("SELECT * FROM group_deliveries WHERE groupId = :groupId AND itemId = :itemId AND recipientNodeId = :nodeId")
    suspend fun delivery(groupId: String, itemId: String, nodeId: String): GroupDeliveryEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDeliveries(deliveries: List<GroupDeliveryEntity>)
    @Query("UPDATE group_deliveries SET wireId = :wire, status = 'SENDING' WHERE groupId = :groupId AND itemId = :itemId AND recipientNodeId = :nodeId AND status NOT IN ('DELIVERED', 'READ')")
    suspend fun setAttempt(groupId: String, itemId: String, nodeId: String, wire: String)
    @Transaction
    suspend fun beginAttempt(groupId: String, itemId: String, nodeId: String, wire: String) {
        val current = delivery(groupId, itemId, nodeId) ?: return
        if (current.status in listOf("DELIVERED", "READ")) return
        insertAttempt(GroupDeliveryAttemptEntity(wire, groupId, itemId, nodeId))
        setAttempt(groupId, itemId, nodeId, wire)
    }
    @Query("UPDATE group_deliveries SET status = :status WHERE wireId = :wire AND status NOT IN ('DELIVERED', 'READ')")
    suspend fun finishAttempt(wire: String, status: String)
    @Query("UPDATE group_deliveries SET status = :status WHERE groupId = :groupId AND itemId = :itemId AND recipientNodeId = :nodeId AND status != 'READ'")
    suspend fun receipt(groupId: String, itemId: String, nodeId: String, status: String)
}
