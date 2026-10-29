package com.saurabh.offgridchat.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        PeerEntity::class,
        ConnectionRequestEntity::class,
        BlockedDeviceEntity::class,
        RouteEntity::class,
        OutgoingApproval::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        GroupMessageEntity::class,
        GroupDeliveryEntity::class,
        GroupDeliveryAttemptEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun groupDao(): GroupDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                GROUP_SCHEMA_SQL.forEach { db.execSQL(it) }
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_requests ADD COLUMN requestId TEXT")
                db.execSQL("""CREATE TABLE IF NOT EXISTS outgoing_approvals (
                    nodeId TEXT NOT NULL PRIMARY KEY, publicKeyHex TEXT NOT NULL,
                    requestId TEXT NOT NULL, timestamp INTEGER NOT NULL, status TEXT NOT NULL)
                """)
                // Old pending rows have no wire correlation and must not authorize a new session.
                db.execSQL("UPDATE connection_requests SET status = 'EXPIRED' WHERE status = 'PENDING'")
            }
        }

        /**
         * Database migration:
         *
         * version 2
         *      ↓
         * version 3
         *
         * Existing user data is preserved.
         *
         * IMPORTANT:
         * Do NOT replace this migration with destructive migration.
         */
        val MIGRATION_2_3 = object : Migration(
            2,
            3
        ) {

            override fun migrate(
                db: SupportSQLiteDatabase
            ) {

                /* =================================================
                   1. MESSAGE TABLE
                   ================================================= */

                /*
                 * New encryption metadata.
                 */
                db.execSQL(
                    """
                    ALTER TABLE messages
                    ADD COLUMN encryptionVersion INTEGER
                    """.trimIndent()
                )

                /*
                 * Delivery timestamp.
                 */
                db.execSQL(
                    """
                    ALTER TABLE messages
                    ADD COLUMN deliveredAt INTEGER
                    """.trimIndent()
                )

                /*
                 * Read timestamp.
                 */
                db.execSQL(
                    """
                    ALTER TABLE messages
                    ADD COLUMN readAt INTEGER
                    """.trimIndent()
                )

                /*
                 * Failure timestamp.
                 */
                db.execSQL(
                    """
                    ALTER TABLE messages
                    ADD COLUMN failedAt INTEGER
                    """.trimIndent()
                )

                /*
                 * Number of delivery attempts.
                 */
                db.execSQL(
                    """
                    ALTER TABLE messages
                    ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )

                /*
                 * ----------------------------------------------------------------
                 * Old versions could contain empty/default message IDs.
                 *
                 * The new schema requires messageId to be unique.
                 *
                 * Give legacy rows with empty IDs deterministic unique IDs
                 * before creating the unique index.
                 *
                 * We intentionally do NOT invent UUIDs here because SQLite
                 * migration cannot safely call Kotlin's UUID generator.
                 * These IDs are only migration identifiers.
                 * ----------------------------------------------------------------
                 */
                db.execSQL(
                    """
                    UPDATE messages
                    SET messageId = 'legacy-' || id
                    WHERE messageId = ''
                    """.trimIndent()
                )

                /*
                 * Remove duplicate message IDs created by the old
                 * double-insert path.
                 *
                 * Keep the earliest database row for each messageId.
                 */
                db.execSQL(
                    """
                    DELETE FROM messages
                    WHERE id NOT IN (
                        SELECT MIN(id)
                        FROM messages
                        GROUP BY messageId
                    )
                    """.trimIndent()
                )

                /*
                 * Old non-unique messageId index may exist.
                 */
                db.execSQL(
                    """
                    DROP INDEX IF EXISTS index_messages_messageId
                    """.trimIndent()
                )

                /*
                 * Recreate address index.
                 */
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_messages_address
                    ON messages(address)
                    """.trimIndent()
                )

                /*
                 * New unique messageId index.
                 */
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    index_messages_messageId
                    ON messages(messageId)
                    """.trimIndent()
                )

                /*
                 * Message lookup/routing/status indexes.
                 */
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_messages_nodeId
                    ON messages(nodeId)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_messages_status
                    ON messages(status)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_messages_time
                    ON messages(time)
                    """.trimIndent()
                )

                /* =================================================
                   2. CONVERSATIONS
                   ================================================= */

                /*
                 * nodeId is now a frequently-used logical identity lookup.
                 */
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_conversations_nodeId
                    ON conversations(nodeId)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_conversations_isBlocked
                    ON conversations(isBlocked)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_conversations_isApproved
                    ON conversations(isApproved)
                    """.trimIndent()
                )

                /* =================================================
                   3. PEERS
                   ================================================= */

                /*
                 * Existing columns remain unchanged.
                 *
                 * New identityVerified field.
                 *
                 * SQLite INTEGER:
                 * 0 = false
                 * 1 = true
                 */
                db.execSQL(
                    """
                    ALTER TABLE peers
                    ADD COLUMN identityVerified
                    INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_peers_address
                    ON peers(address)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_peers_fingerprint
                    ON peers(fingerprint)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_peers_isOnline
                    ON peers(isOnline)
                    """.trimIndent()
                )

                /* =================================================
                   4. CONNECTION REQUESTS
                   ================================================= */

                /*
                 * Store the requester's public identity key.
                 *
                 * Nullable so old requests remain valid.
                 */
                db.execSQL(
                    """
                    ALTER TABLE connection_requests
                    ADD COLUMN publicKeyHex TEXT
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_connection_requests_requesterNodeId
                    ON connection_requests(requesterNodeId)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_connection_requests_requesterNodeId_status
                    ON connection_requests(
                        requesterNodeId,
                        status
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_connection_requests_timestamp
                    ON connection_requests(timestamp)
                    """.trimIndent()
                )

                /* =================================================
                   5. BLOCKED DEVICES
                   ================================================= */

                /*
                 * Store the public key belonging to the blocked
                 * cryptographic identity.
                 */
                db.execSQL(
                    """
                    ALTER TABLE blocked_devices
                    ADD COLUMN publicKeyHex TEXT
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_blocked_devices_address
                    ON blocked_devices(address)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_blocked_devices_fingerprint
                    ON blocked_devices(fingerprint)
                    """.trimIndent()
                )

                /* =================================================
                   6. ROUTES
                   ================================================= */

                /*
                 * Destination public key is required by the sender
                 * to establish destination-specific E2EE.
                 */
                db.execSQL(
                    """
                    ALTER TABLE routes
                    ADD COLUMN publicKeyHex TEXT
                    """.trimIndent()
                )

                /*
                 * SQLite ALTER TABLE cannot use a Kotlin timestamp.
                 *
                 * Add a temporary/default numeric value first.
                 */
                db.execSQL(
                    """
                    ALTER TABLE routes
                    ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )

                /*
                 * Convert the newly-added field into a real expiration
                 * based on the existing route update timestamp.
                 */
                db.execSQL(
                    """
                    UPDATE routes
                    SET expiresAt = lastUpdated + 120000
                    WHERE expiresAt = 0
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_routes_nextHopAddress
                    ON routes(nextHopAddress)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    index_routes_lastUpdated
                    ON routes(lastUpdated)
                    """.trimIndent()
                )
            }
        }
    }
}
