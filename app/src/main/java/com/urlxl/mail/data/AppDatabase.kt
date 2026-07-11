package com.urlxl.mail.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        EmailEntity::class,
        FolderEntity::class,
        ContactEntity::class,
        PendingContactChangeEntity::class,
        DeviceContactLinkEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao
    abstract fun folderDao(): FolderDao
    abstract fun contactDao(): ContactDao
    abstract fun pendingContactChangeDao(): PendingContactChangeDao
    abstract fun deviceContactLinkDao(): DeviceContactLinkDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `device_contact_links` (" +
                        "`uid` TEXT NOT NULL, `rawContactId` INTEGER NOT NULL, " +
                        "`deviceUpdatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`uid`))",
                )
            }
        }
    }
}
