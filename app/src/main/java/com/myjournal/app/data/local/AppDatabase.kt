package com.myjournal.app.data.local
import androidx.room.Database
import androidx.room.RoomDatabase
@Database(
    entities = [
        EntryEntity::class,
        ContentBlockEntity::class,
        FolderEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao
}