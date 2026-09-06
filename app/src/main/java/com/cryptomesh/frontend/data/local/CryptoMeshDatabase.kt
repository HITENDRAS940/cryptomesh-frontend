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
        MediaChunkEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class CryptoMeshDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao

    abstract fun securePacketDao(): SecurePacketDao

    abstract fun mediaTransferDao(): MediaTransferDao

    abstract fun mediaChunkDao(): MediaChunkDao

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
                    .addMigrations(MIGRATION_1_2)
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
    }
}
