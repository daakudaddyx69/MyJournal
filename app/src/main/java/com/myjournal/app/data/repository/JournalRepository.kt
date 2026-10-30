package com.myjournal.app.data.repository
import android.content.Context
import com.myjournal.app.data.local.ContentBlockEntity
import com.myjournal.app.data.local.EntryEntity
import com.myjournal.app.data.local.FolderEntity
import com.myjournal.app.data.local.JournalDao
import com.myjournal.app.util.DateUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
@Serializable
data class BackupEntryDto(
    val id: String,
    val title: String,
    val previewText: String,
    val entryDate: String,
    val timeLabel: String,
    val tagsCsv: String,
    val folderId: String? = null,
    val audioDuration: String? = null,
    val mediaFileName: String? = null,
    val audioFileName: String? = null,
    val isLocked: Boolean = false,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
@Serializable
data class BackupBlockDto(
    val id: String,
    val entryId: String,
    val sortOrder: Int,
    val blockType: String,
    val textContent: String = "",
    val isChecked: Boolean = false,
    val mediaFileName: String? = null,
    val audioDurationMillis: Long = 0L
)
@Serializable
data class BackupFolderDto(
    val id: String,
    val name: String,
    val isPrivate: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
@Serializable
data class FullBackupBundleDto(
    val version: Int = 2,
    val entries: List<BackupEntryDto> = emptyList(),
    val blocks: List<BackupBlockDto> = emptyList(),
    val folders: List<BackupFolderDto> = emptyList()
)
@Singleton
class JournalRepository @Inject constructor(
    private val dao: JournalDao,
    @ApplicationContext private val context: Context
) {
    val activeEntries: Flow<List<EntryEntity>> = dao.observeActiveEntries()
    val trashedEntries: Flow<List<EntryEntity>> = dao.observeTrashedEntries()
    val archivedEntries: Flow<List<EntryEntity>> = dao.observeArchivedEntries()
    val folders: Flow<List<FolderEntity>> = dao.observeFolders()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    fun currentTimeLabel(): String {
        return SimpleDateFormat("h:mm a", Locale.US).format(Date())
    }
    suspend fun getEntryById(entryId: String): EntryEntity? = dao.getEntryById(entryId)
    suspend fun getBlocksForEntry(entryId: String): List<ContentBlockEntity> = dao.getBlocksForEntry(entryId)
    suspend fun saveEntryAndBlocks(entry: EntryEntity, blocks: List<ContentBlockEntity>) = dao.saveEntryWithBlocks(entry, blocks)
    suspend fun createFolder(name: String, isPrivate: Boolean = false) {
        val clean = name.trim()
        if (clean.isNotEmpty()) {
            dao.upsertFolder(
                FolderEntity(
                    id = "folder_${clean.lowercase().replace(" ", "_")}",
                    name = clean,
                    isPrivate = isPrivate
                )
            )
        }
    }
    suspend fun deleteFolder(folderId: String) {
        dao.deleteFolderTransaction(folderId)
    }
    suspend fun exportBackupJson(): String {
        val entries = dao.getAllActiveEntriesSnapshot().map {
            BackupEntryDto(
                id = it.id,
                title = it.title,
                previewText = it.previewText,
                entryDate = it.entryDate,
                timeLabel = it.timeLabel,
                tagsCsv = it.tagsCsv,
                folderId = it.folderId,
                audioDuration = it.audioDuration,
                mediaFileName = it.mediaUri?.let { path -> File(path).name },
                audioFileName = it.audioUri?.let { path -> File(path).name },
                isLocked = it.isLocked,
                isPinned = it.isPinned,
                isArchived = it.isArchived,
                createdAt = it.createdAt
            )
        }
        val activeIds = entries.map { it.id }.toSet()
        val blocks = dao.getAllBlocksSnapshot()
            .filter { it.entryId in activeIds }
            .map {
                BackupBlockDto(
                    id = it.id,
                    entryId = it.entryId,
                    sortOrder = it.sortOrder,
                    blockType = it.blockType,
                    textContent = it.textContent,
                    isChecked = it.isChecked,
                    mediaFileName = it.mediaUri?.let { path -> File(path).name },
                    audioDurationMillis = it.audioDurationMillis
                )
            }
        val folders = dao.getAllFoldersSnapshot().map {
            BackupFolderDto(
                id = it.id,
                name = it.name,
                isPrivate = it.isPrivate,
                createdAt = it.createdAt
            )
        }
        return json.encodeToString(FullBackupBundleDto(version = 2, entries = entries, blocks = blocks, folders = folders))
    }
    suspend fun importBackupJson(rawJson: String): Int {
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        val trimmed = rawJson.trim()
        if (trimmed.startsWith("{")) {
            val bundle = json.decodeFromString<FullBackupBundleDto>(trimmed)
            if (bundle.folders.isNotEmpty()) {
                dao.upsertFolders(
                    bundle.folders.map {
                        FolderEntity(id = it.id, name = it.name, isPrivate = it.isPrivate, createdAt = it.createdAt)
                    }
                )
            }
            val entities = bundle.entries.map {
                val resolvedPhoto = it.mediaFileName?.let { name -> File(mediaDir, name).absolutePath }
                val resolvedAudio = it.audioFileName?.let { name -> File(mediaDir, name).absolutePath }
                EntryEntity(
                    id = it.id,
                    title = it.title,
                    previewText = it.previewText,
                    entryDate = it.entryDate,
                    timeLabel = it.timeLabel,
                    tagsCsv = it.tagsCsv,
                    folderId = it.folderId,
                    audioDuration = it.audioDuration,
                    mediaUri = resolvedPhoto,
                    audioUri = resolvedAudio,
                    isLocked = it.isLocked,
                    isPinned = it.isPinned,
                    isArchived = it.isArchived,
                    createdAt = it.createdAt
                )
            }
            dao.upsertEntries(entities)
            val blockEntities = bundle.blocks.map {
                val resolvedMedia = it.mediaFileName?.let { name -> File(mediaDir, name).absolutePath }
                ContentBlockEntity(
                    id = it.id,
                    entryId = it.entryId,
                    sortOrder = it.sortOrder,
                    blockType = it.blockType,
                    textContent = it.textContent,
                    isChecked = it.isChecked,
                    mediaUri = resolvedMedia,
                    audioDurationMillis = it.audioDurationMillis
                )
            }
            bundle.entries.forEach { e -> dao.deleteBlocksForEntry(e.id) }
            dao.insertBlocks(blockEntities)
            return entities.size
        } else {
            val dtos = json.decodeFromString<List<BackupEntryDto>>(trimmed)
            val entities = dtos.map {
                EntryEntity(
                    id = it.id,
                    title = it.title,
                    previewText = it.previewText,
                    entryDate = it.entryDate,
                    timeLabel = it.timeLabel,
                    tagsCsv = it.tagsCsv,
                    folderId = it.folderId,
                    audioDuration = it.audioDuration,
                    isLocked = it.isLocked,
                    isPinned = it.isPinned,
                    isArchived = it.isArchived,
                    createdAt = it.createdAt
                )
            }
            dao.upsertEntries(entities)
            return entities.size
        }
    }
    suspend fun togglePin(entry: EntryEntity) = dao.setPinned(entry.id, !entry.isPinned)
    suspend fun toggleLock(entry: EntryEntity) = dao.setLocked(entry.id, !entry.isLocked)
    suspend fun toggleMoveToVault(entry: EntryEntity) {
        val currentlyInVault = entry.folderId == "folder_private"
        val fallbackFolderId = dao.getAllFoldersSnapshot().firstOrNull { !it.isPrivate }?.id
        val nextFolder = if (currentlyInVault) fallbackFolderId else "folder_private"
        dao.setVaultStatus(entry.id, entry.isLocked, nextFolder)
    }
    suspend fun toggleArchive(entry: EntryEntity) = dao.setArchived(entry.id, !entry.isArchived)
    suspend fun unarchive(entryId: String) = dao.setArchived(entryId, false)
    suspend fun moveToTrash(entryId: String) = dao.moveToTrash(entryId)
    suspend fun restoreFromTrash(entryId: String) = dao.restoreFromTrash(entryId)
    suspend fun deletePermanently(entryId: String) = dao.deletePermanently(entryId)
    suspend fun emptyTrash() = dao.emptyTrash()
    suspend fun seedInitialDataIfNeeded(shouldSeedDefaultFolders: Boolean) {
        dao.upsertFolder(FolderEntity(id = "folder_private", name = "Hidden Vault", isPrivate = true))
        if (shouldSeedDefaultFolders) {
            dao.upsertFolder(FolderEntity(id = "folder_personal", name = "Personal"))
            dao.upsertFolder(FolderEntity(id = "folder_mindfulness", name = "Mindfulness"))
        }
        if (dao.getEntryCount() == 0) {
            val today = DateUtils.todayIso()
            val seed1 = EntryEntity(
                id = "seed_1",
                title = "Finding Quiet",
                previewText = "Took ten minutes to just watch the light through the leaves without my phone...",
                entryDate = today,
                timeLabel = "2:30 PM",
                tagsCsv = "Mindfulness,Calm",
                folderId = "folder_mindfulness",
                audioDuration = "00:32",
                thumbnailColorHex = 0xFF3A4D2C,
                isLocked = false,
                createdAt = System.currentTimeMillis()
            )
            dao.saveEntryWithBlocks(
                seed1,
                listOf(
                    ContentBlockEntity(
                        entryId = "seed_1",
                        sortOrder = 0,
                        blockType = "TEXT",
                        textContent = "Took ten minutes to just watch the light through the leaves without my phone..."
                    )
                )
            )
            val seed2 = EntryEntity(
                id = "seed_2",
                title = "Morning Reflection",
                previewText = "Started the morning with deep breathing and gratitude.",
                entryDate = today,
                timeLabel = "10:15 AM",
                tagsCsv = "Personal,Calm,Motivation",
                folderId = "folder_personal",
                audioDuration = "00:32",
                thumbnailColorHex = 0xFF4A7C9B,
                isLocked = false,
                createdAt = System.currentTimeMillis() - 3600_000L
            )
            dao.saveEntryWithBlocks(
                seed2,
                listOf(
                    ContentBlockEntity(
                        entryId = "seed_2",
                        sortOrder = 0,
                        blockType = "TEXT",
                        textContent = "Started the morning with deep breathing and gratitude."
                    )
                )
            )
        }
    }
}