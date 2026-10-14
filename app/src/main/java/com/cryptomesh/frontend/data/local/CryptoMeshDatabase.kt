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
        GroupEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class CryptoMeshDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao

    abstract fun securePacketDao(): SecurePacketDao

    abstract fun mediaTransferDao(): MediaTransferDao

    abstract fun mediaChunkDao(): MediaChunkDao

    abstract fun groupDao(): GroupDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
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
    }
}
