package com.myjournal.app.data.local
import androidx.room.*
import kotlinx.coroutines.flow.Flow
@Dao
interface JournalDao {
    @Query("""
        SELECT * FROM entries 
        WHERE isTrashed = 0 AND isArchived = 0
        ORDER BY isPinned DESC, createdAt DESC
    """)
    fun observeActiveEntries(): Flow<List<EntryEntity>>
    @Query("SELECT * FROM entries WHERE isTrashed = 1 ORDER BY trashedAt DESC")
    fun observeTrashedEntries(): Flow<List<EntryEntity>>
    @Query("SELECT * FROM entries WHERE isArchived = 1 AND isTrashed = 0 ORDER BY updatedAt DESC")
    fun observeArchivedEntries(): Flow<List<EntryEntity>>
    @Query("SELECT * FROM entries WHERE id = :entryId LIMIT 1")
    suspend fun getEntryById(entryId: String): EntryEntity?
    @Query("SELECT * FROM entries WHERE isTrashed = 0")
    suspend fun getAllActiveEntriesSnapshot(): List<EntryEntity>
    @Query("SELECT * FROM content_blocks ORDER BY sortOrder ASC")
    suspend fun getAllBlocksSnapshot(): List<ContentBlockEntity>
    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    suspend fun getAllFoldersSnapshot(): List<FolderEntity>
    @Query("SELECT * FROM content_blocks WHERE entryId = :entryId ORDER BY sortOrder ASC")
    suspend fun getBlocksForEntry(entryId: String): List<ContentBlockEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: EntryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<EntryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<ContentBlockEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(folders: List<FolderEntity>)
    @Query("DELETE FROM content_blocks WHERE entryId = :entryId")
    suspend fun deleteBlocksForEntry(entryId: String)
    @Transaction
    suspend fun saveEntryWithBlocks(entry: EntryEntity, blocks: List<ContentBlockEntity>) {
        upsertEntry(entry)
        deleteBlocksForEntry(entry.id)
        insertBlocks(blocks)
    }
    @Query("UPDATE entries SET isPinned = :pinned, updatedAt = :timestamp WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, timestamp: Long = System.currentTimeMillis())
    @Query("UPDATE entries SET isArchived = :archived, updatedAt = :timestamp WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, timestamp: Long = System.currentTimeMillis())
    @Query("UPDATE entries SET isLocked = :locked, updatedAt = :timestamp WHERE id = :id")
    suspend fun setLocked(id: String, locked: Boolean, timestamp: Long = System.currentTimeMillis())
    @Query("UPDATE entries SET isLocked = :locked, folderId = :folderId, updatedAt = :timestamp WHERE id = :id")
    suspend fun setVaultStatus(id: String, locked: Boolean, folderId: String?, timestamp: Long = System.currentTimeMillis())
    @Query("UPDATE entries SET isTrashed = 1, trashedAt = :timestamp WHERE id = :id")
    suspend fun moveToTrash(id: String, timestamp: Long = System.currentTimeMillis())
    @Query("UPDATE entries SET isTrashed = 0, trashedAt = null WHERE id = :id")
    suspend fun restoreFromTrash(id: String)
    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deletePermanently(id: String)
    @Query("DELETE FROM entries WHERE isTrashed = 1")
    suspend fun emptyTrash()
    @Query("DELETE FROM entries WHERE isTrashed = 1 AND trashedAt < :cutoffMillis")
    suspend fun purgeOldTrash(cutoffMillis: Long)
    @Query("SELECT COUNT(*) FROM entries")
    suspend fun getEntryCount(): Int
    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    fun observeFolders(): Flow<List<FolderEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: FolderEntity)
    @Query("UPDATE entries SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolderFromEntries(folderId: String)
    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun deleteFolderById(folderId: String)
    @Transaction
    suspend fun deleteFolderTransaction(folderId: String) {
        if (folderId != "folder_private") {
            clearFolderFromEntries(folderId)
            deleteFolderById(folderId)
        }
    }
}