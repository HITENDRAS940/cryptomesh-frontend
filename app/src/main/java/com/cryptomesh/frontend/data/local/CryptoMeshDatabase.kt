package com.cryptomesh.frontend.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LocalIdentityEntity::class,
        SecurePacketEntity::class,
        MediaTransferEntity::class,
        MediaChunkEntity::class,
        GroupEntity::class,
        DopLocalPreKeyEntity::class,
        DopCachedPreKeyRecordEntity::class,
        DopSessionEntity::class,
        DopReplayLogEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class CryptoMeshDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao

    abstract fun securePacketDao(): SecurePacketDao

    abstract fun mediaTransferDao(): MediaTransferDao

    abstract fun mediaChunkDao(): MediaChunkDao

    abstract fun groupDao(): GroupDao

    abstract fun dopDao(): DopDao

    companion object {
        @Volatile
        private var instance: CryptoMeshDatabase? = null

        fun getInstance(context: Context): CryptoMeshDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CryptoMeshDatabase::class.java,
                    "cryptomesh.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_transfers (
                        transferId TEXT NOT NULL PRIMARY KEY,
                        peerDeviceId TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        mediaKind TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        chunkSizeBytes INTEGER NOT NULL,
                        totalChunks INTEGER NOT NULL,
                        fileSha256Base64 TEXT NOT NULL,
                        encryptedTransferKeyBase64 TEXT NOT NULL,
                        sourceUri TEXT,
                        outputPath TEXT,
                        status TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        expiresAtEpochMillis INTEGER NOT NULL,
                        lastUpdatedEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_chunks (
                        transferId TEXT NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        packetId TEXT,
                        offsetBytes INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        chunkSha256Base64 TEXT NOT NULL,
                        encryptedPath TEXT,
                        plaintextPath TEXT,
                        status TEXT NOT NULL,
                        lastUpdatedEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(transferId, chunkIndex),
                        FOREIGN KEY(transferId) REFERENCES media_transfers(transferId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_transfers_peerDeviceId " +
                        "ON media_transfers(peerDeviceId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_transfers_direction " +
                        "ON media_transfers(direction)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_transfers_status " +
                        "ON media_transfers(status)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_media_transfers_expiresAtEpochMillis " +
                        "ON media_transfers(expiresAtEpochMillis)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_chunks_transferId " +
                        "ON media_chunks(transferId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_chunks_status " +
                        "ON media_chunks(status)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_chunks_packetId " +
                        "ON media_chunks(packetId)"
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mesh_groups (
                        groupId TEXT NOT NULL PRIMARY KEY,
                        groupName TEXT NOT NULL,
                        isBroadcastChannel INTEGER NOT NULL,
                        creatorDeviceId TEXT NOT NULL,
                        groupKeyBase64 TEXT NOT NULL,
                        membersCsv TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        lastActivityEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mesh_groups_isBroadcastChannel " +
                        "ON mesh_groups(isBroadcastChannel)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mesh_groups_createdAtEpochMillis " +
                        "ON mesh_groups(createdAtEpochMillis)"
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dop_local_prekeys` (
                        `preKeyId` TEXT NOT NULL,
                        `epochId` INTEGER NOT NULL,
                        `preKeyType` TEXT NOT NULL,
                        `publicKeyBase64` TEXT NOT NULL,
                        `wrappedPrivateKeyBase64` TEXT,
                        `lifecycleState` TEXT NOT NULL,
                        `usageCount` INTEGER NOT NULL,
                        `firstUsedAtEpochMillis` INTEGER,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `expiresAtEpochMillis` INTEGER NOT NULL,
                        `retiredAtEpochMillis` INTEGER,
                        PRIMARY KEY(`preKeyId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_local_prekeys_epochId` " +
                        "ON `dop_local_prekeys` (`epochId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_local_prekeys_lifecycleState` " +
                        "ON `dop_local_prekeys` (`lifecycleState`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_local_prekeys_expiresAtEpochMillis` " +
                        "ON `dop_local_prekeys` (`expiresAtEpochMillis`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dop_cached_prekey_records` (
                        `recipientDeviceId` TEXT NOT NULL,
                        `recordId` TEXT NOT NULL,
                        `recipientDisplayName` TEXT NOT NULL,
                        `recipientSigningPublicKeyBase64` TEXT NOT NULL,
                        `recipientIdentityFingerprintHex` TEXT NOT NULL,
                        `epochId` INTEGER NOT NULL,
                        `signedPreKeyId` TEXT NOT NULL,
                        `serializedRecordJson` TEXT NOT NULL,
                        `recordDigestHex` TEXT NOT NULL,
                        `isOwnRecord` INTEGER NOT NULL,
                        `sourcePeerDeviceId` TEXT,
                        `validFromEpochMillis` INTEGER NOT NULL,
                        `expiresAtEpochMillis` INTEGER NOT NULL,
                        `cachedAtEpochMillis` INTEGER NOT NULL,
                        `lastAccessedAtEpochMillis` INTEGER NOT NULL,
                        `consumedOneTimePreKeyIdsCsv` TEXT NOT NULL,
                        PRIMARY KEY(`recipientDeviceId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_cached_prekey_records_epochId` " +
                        "ON `dop_cached_prekey_records` (`epochId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_cached_prekey_records_expiresAtEpochMillis` " +
                        "ON `dop_cached_prekey_records` (`expiresAtEpochMillis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_cached_prekey_records_lastAccessedAtEpochMillis` " +
                        "ON `dop_cached_prekey_records` (`lastAccessedAtEpochMillis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_cached_prekey_records_isOwnRecord` " +
                        "ON `dop_cached_prekey_records` (`isOwnRecord`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dop_sessions` (
                        `sessionId` TEXT NOT NULL,
                        `peerDeviceId` TEXT NOT NULL,
                        `peerDisplayName` TEXT NOT NULL,
                        `peerSigningPublicKeyBase64` TEXT NOT NULL,
                        `peerIdentityFingerprintHex` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `recordId` TEXT NOT NULL,
                        `epochId` INTEGER NOT NULL,
                        `signedPreKeyId` TEXT NOT NULL,
                        `oneTimePreKeyId` TEXT,
                        `transcriptHashHex` TEXT NOT NULL,
                        `wrappedSessionKeyBase64` TEXT NOT NULL,
                        `wrappedConfirmationKeyBase64` TEXT NOT NULL,
                        `initiationPacketId` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `confirmedAtEpochMillis` INTEGER,
                        `lastUsedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_sessions_peerDeviceId` " +
                        "ON `dop_sessions` (`peerDeviceId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_sessions_state` " +
                        "ON `dop_sessions` (`state`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_sessions_initiationPacketId` " +
                        "ON `dop_sessions` (`initiationPacketId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_sessions_lastUsedAtEpochMillis` " +
                        "ON `dop_sessions` (`lastUsedAtEpochMillis`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dop_replay_log` (
                        `replayKey` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `packetId` TEXT NOT NULL,
                        `senderDeviceId` TEXT NOT NULL,
                        `signedPreKeyId` TEXT NOT NULL,
                        `oneTimePreKeyId` TEXT,
                        `transcriptHashHex` TEXT NOT NULL,
                        `receivedAtEpochMillis` INTEGER NOT NULL,
                        `expiresAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`replayKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_replay_log_sessionId` " +
                        "ON `dop_replay_log` (`sessionId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_replay_log_packetId` " +
                        "ON `dop_replay_log` (`packetId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dop_replay_log_expiresAtEpochMillis` " +
                        "ON `dop_replay_log` (`expiresAtEpochMillis`)"
                )
            }
        }
    }
}
