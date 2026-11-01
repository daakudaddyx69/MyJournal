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
import com.myjournal.app.ui.editor.RichTextHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppThemeMode.DARK)
    val folders: StateFlow<List<FolderEntity>> = repository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _playingEntryId = MutableStateFlow<String?>(null)
    val playingEntryId: StateFlow<String?> = _playingEntryId.asStateFlow()
    private val _audioProgressStep = MutableStateFlow(0)
    val audioProgressStep: StateFlow<Int> = _audioProgressStep.asStateFlow()
    private var audioJob: Job? = null
    private val _syncStatus = MutableStateFlow("Connect Google Drive for cloud backup >")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()
    private var lastBackgroundTimestamp: Long = 0L
    private var suppressAutoLockUntil: Long = 0L
    val allActiveEntries: StateFlow<List<EntryEntity>> = repository.activeEntries
        .map { list -> list.filter { it.folderId != "folder_private" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val vaultEntriesCount: StateFlow<Int> = repository.activeEntries
        .map { list -> list.count { it.folderId == "folder_private" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val trashedEntries: StateFlow<List<EntryEntity>> = repository.trashedEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archivedEntries: StateFlow<List<EntryEntity>> = repository.archivedEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val greetingText: StateFlow<String> = userSettings.map { settings ->
        val name = settings.userName.trim().ifBlank { "Rockstar" }
        val hour = LocalTime.now().hour
        val timeGreeting = when (hour) {
            in 0..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        "$timeGreeting, $name."
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Hello, Rockstar.")
    val streakCount: StateFlow<Int> = repository.activeEntries.map { list ->
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
    val storageSizeFormatted: StateFlow<String> = repository.activeEntries.map {
        val mediaDir = File(context.filesDir, "media")
        val mediaBytes = if (mediaDir.exists()) mediaDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val dbFile = context.getDatabasePath("my_journal.db")
        val dbBytes = if (dbFile.exists()) dbFile.length() else 0L
        val totalMb = (mediaBytes + dbBytes) / (1024.0 * 1024.0)
        String.format(Locale.US, "%.2f MB (Photos & Audio)", totalMb)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0.10 MB")
    // On This Day Resurfacing Memories (strictly excludes Hidden Vault & today's own entries)
    val onThisDayMemories: StateFlow<List<Pair<String, EntryEntity>>> = combine(
        repository.activeEntries,
        folders
    ) { list, folderList ->
        val privateIds = folderList.filter { it.isPrivate }.map { it.id }.toSet() + "folder_private"
        val today = LocalDate.now()
        list.filter { it.folderId !in privateIds }
            .mapNotNull { entry ->
                val date = runCatching { LocalDate.parse(entry.entryDate) }.getOrNull() ?: return@mapNotNull null
                if (!date.isBefore(today)) return@mapNotNull null
                val daysBetween = ChronoUnit.DAYS.between(date, today)
                val monthsBetween = ChronoUnit.MONTHS.between(date, today)
                val yearsBetween = ChronoUnit.YEARS.between(date, today)
                val label = when {
                    date.month == today.month && date.dayOfMonth == today.dayOfMonth && yearsBetween >= 1 ->
                        if (yearsBetween == 1L) "1 Year Ago Today" else "$yearsBetween Years Ago Today"
                    date.dayOfMonth == today.dayOfMonth && monthsBetween >= 1 ->
                        if (monthsBetween == 1L) "1 Month Ago Today" else "$monthsBetween Months Ago Today"
                    daysBetween == 7L -> "1 Week Ago"
                    daysBetween == 14L -> "2 Weeks Ago"
                    daysBetween == 30L -> "30 Days Ago"
                    else -> null
                }
                if (label != null) label to entry else null
            }
            .sortedByDescending { it.second.entryDate }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entries: StateFlow<List<EntryEntity>> = combine(
        _searchQuery,
        _selectedFilter,
        repository.activeEntries,
        folders
    ) { query, filter, list, folderList ->
        val folderMap = folderList.associateBy { it.id }
        val isViewingVault = filter.equals("Hidden Vault", ignoreCase = true)
        val cleanQuery = query.trim()
        list.filter { entry ->
            val folder = entry.folderId?.let { folderMap[it] }
            val folderName = folder?.name.orEmpty()
            val isInHiddenVault = entry.folderId == "folder_private" || folder?.isPrivate == true
            val plainTitle = RichTextHelper.fromHtml(entry.title).text
            val plainPreview = RichTextHelper.fromHtml(entry.previewText).text
            val matchesQuery = cleanQuery.isBlank() ||
                plainTitle.contains(cleanQuery, ignoreCase = true) ||
                plainPreview.contains(cleanQuery, ignoreCase = true) ||
                entry.previewText.contains(cleanQuery, ignoreCase = true) ||
                entry.tagsCsv.contains(cleanQuery, ignoreCase = true) ||
                folderName.contains(cleanQuery, ignoreCase = true)
            if (isViewingVault) {
                isInHiddenVault && matchesQuery
            } else {
                if (isInHiddenVault) {
                    false
                } else {
                    val matchesFilter = when (filter) {
                        "All" -> true
                        "Pinned" -> entry.isPinned
                        else -> {
                            val matchesTag = entry.tagsCsv.split(",").any { it.trim().equals(filter, ignoreCase = true) }
                            val matchesFolder = folderName.equals(filter, ignoreCase = true)
                            matchesTag || matchesFolder
                        }
                    }
                    matchesQuery && matchesFilter
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init {
        viewModelScope.launch {
            val initialSettings = prefs.settingsFlow.first()
            repository.seedInitialDataIfNeeded(shouldSeedDefaultFolders = !initialSettings.hasSeededDefaultFolders)
            if (!initialSettings.hasSeededDefaultFolders) {
                prefs.markDefaultFoldersSeeded()
            }
            prefs.settingsFlow.collect { s ->
                if (s.googleAccountEmail.isNotBlank() && _syncStatus.value.startsWith("Connect")) {
                    _syncStatus.value = "Connected (${s.googleAccountEmail}) >"
                }
            }
        }
    }
    fun suppressAutoLockTemporarily(durationMillis: Long = 25_000L) {
        suppressAutoLockUntil = SystemClock.elapsedRealtime() + durationMillis
    }
    fun completeOnboarding(name: String) {
        viewModelScope.launch {
            prefs.setOnboardingCompleted(name.trim().ifBlank { "Rockstar" })
        }
    }
    fun connectGoogleEmail(email: String) {
        viewModelScope.launch {
            val clean = email.trim()
            if (clean.isNotBlank()) {
                prefs.setGoogleAccountEmail(clean)
                _syncStatus.value = "Connected ($clean) >"
            }
        }
    }
    fun isSignedInToDrive(): Boolean = driveSyncHelper.getSignedInAccount() != null
    fun getDriveSignInIntent(): Intent = driveSyncHelper.getSignInIntent()
    fun updateSyncStatusLabel(label: String) { _syncStatus.value = label }
    fun signOutFromCloud() {
        viewModelScope.launch {
            prefs.setGoogleAccountEmail("")
            _syncStatus.value = "Connect Google Drive for cloud backup >"
        }
    }
    fun onAppBackgrounded() {
        val now = SystemClock.elapsedRealtime()
        if (now < suppressAutoLockUntil) return
        lastBackgroundTimestamp = now
        if (_selectedFilter.value.equals("Hidden Vault", ignoreCase = true)) {
            _selectedFilter.value = "All"
        }
        if (userSettings.value.autoLockTimeout == "Immediate") {
            setPrivacyShield(true)
        }
    }
    fun onAppForegrounded() {
        val now = SystemClock.elapsedRealtime()
        if (now < suppressAutoLockUntil) {
            suppressAutoLockUntil = 0L
            return
        }
        if (lastBackgroundTimestamp == 0L) return
        val elapsedMillis = now - lastBackgroundTimestamp
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
    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            repository.deleteFolder(folderId)
            _selectedFilter.value = "All"
        }
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
        viewModelScope.launch { prefs.updateProfile(name.trim(), email.trim()) }
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
    fun toggleMoveToVault(entry: EntryEntity) = viewModelScope.launch { repository.toggleMoveToVault(entry) }
    fun toggleArchive(entry: EntryEntity) = viewModelScope.launch { repository.toggleArchive(entry) }
    fun unarchive(entryId: String) = viewModelScope.launch { repository.unarchive(entryId) }
    fun moveToTrash(entryId: String) = viewModelScope.launch { repository.moveToTrash(entryId) }
    fun restoreFromTrash(entryId: String) = viewModelScope.launch { repository.restoreFromTrash(entryId) }
    fun deletePermanently(entryId: String) = viewModelScope.launch { repository.deletePermanently(entryId) }
    fun emptyTrash() = viewModelScope.launch { repository.emptyTrash() }
}