package com.myjournal.app.data.local
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isPrivate: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
@Entity(
    tableName = "entries",
    indices = [Index("entryDate"), Index("folderId"), Index("isTrashed"), Index("isPinned")]
)
data class EntryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val previewText: String,
    val entryDate: String, // YYYY-MM-DD
    val timeLabel: String, // e.g., "2:30 PM"
    val folderId: String? = null,
    val tagsCsv: String = "",
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isLocked: Boolean = true,
    val isTrashed: Boolean = false,
    val trashedAt: Long? = null,
    val audioDuration: String? = null,
    val mediaUri: String? = null, // Photo thumbnail path
    val audioUri: String? = null, // Primary audio recording path
    val thumbnailColorHex: Long = 0xFF3A4D2C,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isSyncedToDrive: Boolean = false
)
@Entity(
    tableName = "content_blocks",
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("entryId")]
)
data class ContentBlockEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val entryId: String,
    val sortOrder: Int,
    val blockType: String, // "TEXT", "CHECKLIST", "PHOTO", "AUDIO"
    val textContent: String = "",
    val isChecked: Boolean = false,
    val mediaUri: String? = null,
    val audioDurationMillis: Long = 0L
)