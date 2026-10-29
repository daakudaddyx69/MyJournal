package com.myjournal.app.ui.viewmodel
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myjournal.app.data.local.ContentBlockEntity
import com.myjournal.app.data.local.EntryEntity
import com.myjournal.app.data.local.FolderEntity
import com.myjournal.app.data.preferences.UserPreferencesRepository
import com.myjournal.app.data.preferences.UserSettings
import com.myjournal.app.data.repository.JournalRepository
import com.myjournal.app.data.sync.GoogleDriveSyncHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
enum class AppThemeMode(val label: String) {
    SYSTEM("System Default"),
    LIGHT("Warm Sand Light"),
    DARK("Espresso Dark")
}
@HiltViewModel
class JournalViewModel @Inject constructor(
    private val repository: JournalRepository,
    private val prefs: UserPreferencesRepository,
    private val driveSyncHelper: GoogleDriveSyncHelper,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _selectedFilter = MutableStateFlow("All")
    val selectedFilter: StateFlow<String> = _selectedFilter.asStateFlow()
    val userSettings: StateFlow<UserSettings> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())
    val isPrivacyShieldActive: StateFlow<Boolean> = userSettings
        .map { it.isPrivacyShieldActive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val themeMode: StateFlow<AppThemeMode> = userSettings
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppThemeMode.LIGHT)
    val folders: StateFlow<List<FolderEntity>> = repository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _playingEntryId = MutableStateFlow<String?>(null)
    val playingEntryId: StateFlow<String?> = _playingEntryId.asStateFlow()
    private val _audioProgressStep = MutableStateFlow(0)
    val audioProgressStep: StateFlow<Int> = _audioProgressStep.asStateFlow()
    private var audioJob: Job? = null
    private val _syncStatus = MutableStateFlow("Backup / Restore on Drive >")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()
    private var lastBackgroundTimestamp: Long = 0L
    val allActiveEntries: StateFlow<List<EntryEntity>> = repository.activeEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trashedEntries: StateFlow<List<EntryEntity>> = repository.trashedEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedEntries: StateFlow<List<EntryEntity>> = repository.archivedEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // True Consecutive Day Streak Calculation (Walks backward from Today or Yesterday)
    val streakCount: StateFlow<Int> = allActiveEntries.map { list ->
        val activeDates = list.mapNotNull {
            runCatching { LocalDate.parse(it.entryDate) }.getOrNull()
        }.toSet()
        if (activeDates.isEmpty()) return@map 0
        val today = LocalDate.now()
        var cursor = when {
            today in activeDates -> today
            today.minusDays(1) in activeDates -> today.minusDays(1)
            else -> return@map 0
        }
        var streak = 0
        while (cursor in activeDates) {
            streak++
            cursor = cursor.minusDays(1)
        }
        streak
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)
    val storageSizeFormatted: StateFlow<String> = allActiveEntries.map {
        val mediaDir = File(context.filesDir, "media")
        val mediaBytes = if (mediaDir.exists()) mediaDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val dbFile = context.getDatabasePath("my_journal.db")
        val dbBytes = if (dbFile.exists()) dbFile.length() else 0L
        val totalMb = (mediaBytes + dbBytes) / (1024.0 * 1024.0)
        String.format(Locale.US, "%.2f MB (Photos & Audio)", totalMb)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0.10 MB")
    val entries: StateFlow<List<EntryEntity>> = combine(
        _searchQuery,
        _selectedFilter,
        repository.activeEntries,
        folders,
        isPrivacyShieldActive
    ) { query, filter, list, folderList, shieldActive ->
        val folderMap = folderList.associateBy { it.id }
        list.filter { entry ->
            val folder = entry.folderId?.let { folderMap[it] }
            val folderName = folder?.name.orEmpty()
            val isPrivateFolder = folder?.isPrivate == true || entry.folderId == "folder_private"
            // Hide "Hidden Vault" entries from the general "All" feed when Privacy Shield is active
            if (shieldActive && isPrivateFolder && filter == "All") {
                return@filter false
            }
            val matchesQuery = query.isBlank() ||
                entry.title.contains(query, ignoreCase = true) ||
                entry.previewText.contains(query, ignoreCase = true) ||
                entry.tagsCsv.contains(query, ignoreCase = true) ||
                folderName.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                "All" -> true
                "Pinned" -> entry.isPinned
                "Locked" -> entry.isLocked || isPrivateFolder
                "Hidden Vault" -> isPrivateFolder
                else -> {
                    val matchesTag = entry.tagsCsv.split(",").any { it.trim().equals(filter, ignoreCase = true) }
                    val matchesFolder = folderName.equals(filter, ignoreCase = true)
                    matchesTag || matchesFolder
                }
            }
            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init {
        viewModelScope.launch {
            repository.seedInitialDataIfNeeded()
            val account = driveSyncHelper.getSignedInAccount()
            if (account != null) {
                _syncStatus.value = "Connected (${account.email})"
            }
        }
    }
    fun isSignedInToDrive(): Boolean = driveSyncHelper.getSignedInAccount() != null
    fun getDriveSignInIntent(): Intent = driveSyncHelper.getSignInIntent()
    fun updateSyncStatusLabel(label: String) { _syncStatus.value = label }
    fun backupToGoogleDriveNow(onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            _syncStatus.value = "Uploading backup to Google Drive..."
            val jsonPayload = repository.exportBackupJson()
            val res = driveSyncHelper.uploadBackupJsonToDrive(jsonPayload)
            res.onSuccess { email ->
                val time = repository.currentTimeLabel()
                _syncStatus.value = "Synced to Drive ($time)"
                onResult("Backed up to Google Drive ($email)!")
            }.onFailure { err ->
                _syncStatus.value = "Use Direct Drive Save \u00B7 Tap >"
                onResult("OAuth note: ${err.message}")
            }
        }
    }
    fun restoreFromGoogleDriveNow(onResult: (String) -> Unit = {}) {
        viewModelScope.launch {
            _syncStatus.value = "Downloading from Google Drive..."
            val res = driveSyncHelper.downloadBackupJsonFromDrive()
            res.onSuccess { jsonPayload ->
                val count = repository.importBackupJson(jsonPayload)
                _syncStatus.value = "Restored $count entries from Drive"
                onResult("Restored $count reflections from Google Drive!")
            }.onFailure { err ->
                _syncStatus.value = "Restore failed \u00B7 Tap >"
                onResult("Drive restore failed: ${err.message}")
            }
        }
    }
    fun onAppBackgrounded() {
        lastBackgroundTimestamp = SystemClock.elapsedRealtime()
        if (userSettings.value.autoLockTimeout == "Immediate") {
            setPrivacyShield(true)
        }
    }
    fun onAppForegrounded() {
        if (lastBackgroundTimestamp == 0L) return
        val elapsedMillis = SystemClock.elapsedRealtime() - lastBackgroundTimestamp
        val timeoutMillis = when (userSettings.value.autoLockTimeout) {
            "Immediate" -> 0L
            "After 1 minute" -> 60_000L
            "After 5 minutes" -> 5 * 60_000L
            "After 30 minutes" -> 30 * 60_000L
            else -> 0L
        }
        if (elapsedMillis >= timeoutMillis) {
            setPrivacyShield(true)
        }
    }
    fun currentTimeLabel(): String = repository.currentTimeLabel()
    suspend fun getEntryById(entryId: String): EntryEntity? = repository.getEntryById(entryId)
    suspend fun getBlocksForEntry(entryId: String): List<ContentBlockEntity> = repository.getBlocksForEntry(entryId)
    suspend fun saveEntryWithBlocks(entry: EntryEntity, blocks: List<ContentBlockEntity>) = repository.saveEntryAndBlocks(entry, blocks)
    fun createFolder(name: String, isPrivate: Boolean = false) {
        viewModelScope.launch { repository.createFolder(name, isPrivate) }
    }
    suspend fun exportBackupJson(): String = repository.exportBackupJson()
    suspend fun importBackupJson(rawJson: String): Int = repository.importBackupJson(rawJson)
    fun updateSearchQuery(query: String) { _searchQuery.value = query }
    fun selectFilter(filter: String) { _selectedFilter.value = filter }
    fun setPrivacyShield(active: Boolean) {
        viewModelScope.launch { prefs.setPrivacyShield(active) }
    }
    fun togglePrivacyShield() {
        viewModelScope.launch { prefs.setPrivacyShield(!isPrivacyShieldActive.value) }
    }
    fun cycleThemeMode() {
        val next = when (themeMode.value) {
            AppThemeMode.LIGHT -> AppThemeMode.DARK
            AppThemeMode.DARK -> AppThemeMode.SYSTEM
            AppThemeMode.SYSTEM -> AppThemeMode.LIGHT
        }
        viewModelScope.launch { prefs.setThemeMode(next) }
    }
    fun saveProfile(name: String, email: String) {
        viewModelScope.launch { prefs.updateProfile(name, email) }
    }
    fun setAutoLockTimeout(timeout: String) {
        viewModelScope.launch { prefs.setAutoLockTimeout(timeout) }
    }
    fun setDailyReminder(enabled: Boolean) {
        viewModelScope.launch { prefs.setDailyReminder(enabled) }
    }
    fun togglePlayAudio(entryId: String) {
        if (_playingEntryId.value == entryId) {
            audioJob?.cancel()
            _playingEntryId.value = null
            _audioProgressStep.value = 0
        } else {
            audioJob?.cancel()
            _playingEntryId.value = entryId
            _audioProgressStep.value = 0
            audioJob = viewModelScope.launch {
                for (step in 1..16) {
                    _audioProgressStep.value = step
                    delay(400L)
                }
                _playingEntryId.value = null
                _audioProgressStep.value = 0
            }
        }
    }
    fun togglePin(entry: EntryEntity) = viewModelScope.launch { repository.togglePin(entry) }
    fun toggleLock(entry: EntryEntity) = viewModelScope.launch { repository.toggleLock(entry) }
    fun toggleArchive(entry: EntryEntity) = viewModelScope.launch { repository.toggleArchive(entry) }
    fun unarchive(entryId: String) = viewModelScope.launch { repository.unarchive(entryId) }
    fun moveToTrash(entryId: String) = viewModelScope.launch { repository.moveToTrash(entryId) }
    fun restoreFromTrash(entryId: String) = viewModelScope.launch { repository.restoreFromTrash(entryId) }
    fun deletePermanently(entryId: String) = viewModelScope.launch { repository.deletePermanently(entryId) }
    fun emptyTrash() = viewModelScope.launch { repository.emptyTrash() }
}