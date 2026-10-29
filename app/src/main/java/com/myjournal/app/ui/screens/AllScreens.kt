package com.myjournal.app.ui.screens
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.myjournal.app.data.local.EntryEntity
import com.myjournal.app.ui.components.ReflectionCard
import com.myjournal.app.ui.viewmodel.JournalViewModel
import com.myjournal.app.util.BackupZipHelper
import com.myjournal.app.util.BiometricHelper
import com.myjournal.app.util.DateUtils
import com.myjournal.app.util.MediaHelper
import com.myjournal.app.util.NotificationHelper
import com.myjournal.app.worker.DailyReminderWorker
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
@Composable
fun FeedScreen(
    viewModel: JournalViewModel,
    onOpenEditor: (entryId: String?, initialMode: String, defaultDate: String) -> Unit
) {
    val context = LocalContext.current
    val mediaHelper = remember { MediaHelper(context) }
    val todayIso = remember { DateUtils.todayIso() }
    val entries by viewModel.entries.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val isPrivacyShield by viewModel.isPrivacyShieldActive.collectAsState()
    val playingEntryId by viewModel.playingEntryId.collectAsState()
    val audioProgressStep by viewModel.audioProgressStep.collectAsState()
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val feedMicPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onOpenEditor(null, "VOICE", todayIso)
        } else {
            Toast.makeText(
                context,
                "Please allow Microphone permission in Settings to record voice notes",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    DisposableEffect(Unit) {
        onDispose { mediaHelper.stopPlayback() }
    }
    val filterChips = remember(folders) {
        val base = mutableListOf("All", "Pinned")
        folders.forEach { f ->
            if (f.name !in base) base.add(f.name)
        }
        base
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "How are you feeling\ntoday?",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ActionPill(
                            icon = Icons.Default.Edit,
                            label = "Write",
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            onClick = { onOpenEditor(null, "WRITE", todayIso) },
                            modifier = Modifier.weight(1f)
                        )
                        ActionPill(
                            icon = Icons.Default.Mic,
                            label = "Voice",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    onOpenEditor(null, "VOICE", todayIso)
                                } else {
                                    feedMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        ActionPill(
                            icon = Icons.Default.Image,
                            label = "Photo",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            onClick = { onOpenEditor(null, "PHOTO", todayIso) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        // Interactive Folder & Filter Chips Bar on Feed
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(filterChips) { chip ->
                    val isSelected = selectedFilter.equals(chip, ignoreCase = true)
                    val isVault = chip.equals("Hidden Vault", ignoreCase = true) || chip.equals("Locked", ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (isVault && isPrivacyShield) {
                                BiometricHelper.authenticate(
                                    context = context,
                                    title = "Unlock Hidden Vault"
                                ) {
                                    viewModel.setPrivacyShield(false)
                                    viewModel.selectFilter(chip)
                                }
                            } else {
                                viewModel.selectFilter(chip)
                            }
                        },
                        leadingIcon = if (isVault) {
                            { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(15.dp)) }
                        } else if (chip == "Pinned") {
                            { Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(15.dp)) }
                        } else null,
                        label = { Text(chip, fontSize = 13.sp) },
                        shape = CircleShape
                    )
                }
                item {
                    AssistChip(
                        onClick = { showCreateFolderDialog = true },
                        label = { Text("+ Folder", fontSize = 13.sp) },
                        shape = CircleShape
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedFilter == "All") "Your Reflections" else "$selectedFilter (${entries.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (selectedFilter != "All") {
                    TextButton(onClick = { viewModel.selectFilter("All") }) {
                        Text("Show All", fontSize = 12.sp)
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No reflections in \"$selectedFilter\" yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(entries, key = { it.id }) { entry ->
                ReflectionCard(
                    entry = entry,
                    isPrivacyMasked = isPrivacyShield,
                    isPlayingAudio = playingEntryId == entry.id,
                    audioProgressStep = audioProgressStep,
                    onCardClick = { clicked: EntryEntity ->
                        if (isPrivacyShield && (clicked.isLocked || clicked.folderId == "folder_private")) {
                            BiometricHelper.authenticate(
                                context = context,
                                title = "Unlock Reflection"
                            ) {
                                viewModel.setPrivacyShield(false)
                                onOpenEditor(clicked.id, "WRITE", clicked.entryDate)
                            }
                        } else {
                            onOpenEditor(clicked.id, "WRITE", clicked.entryDate)
                        }
                    },
                    onPlayAudioClick = { clickedEntry: EntryEntity ->
                        val targetAudioPath = clickedEntry.audioUri
                            ?: clickedEntry.mediaUri?.takeIf { it.endsWith(".m4a") }
                        if (playingEntryId == clickedEntry.id) {
                            mediaHelper.stopPlayback()
                            viewModel.togglePlayAudio(clickedEntry.id)
                        } else {
                            if (!targetAudioPath.isNullOrBlank()) {
                                mediaHelper.playAudio(targetAudioPath) {
                                    if (playingEntryId == clickedEntry.id) {
                                        viewModel.togglePlayAudio(clickedEntry.id)
                                    }
                                }
                            }
                            viewModel.togglePlayAudio(clickedEntry.id)
                        }
                    },
                    onTogglePin = viewModel::togglePin,
                    onToggleLock = viewModel::toggleLock,
                    onToggleArchive = {
                        viewModel.toggleArchive(it)
                        Toast.makeText(context, "Archived (View in Profile)", Toast.LENGTH_SHORT).show()
                    },
                    onMoveToTrash = { id ->
                        viewModel.moveToTrash(id)
                        Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val clean = newFolderName.trim()
                    if (clean.isNotEmpty()) {
                        viewModel.createFolder(clean)
                        viewModel.selectFilter(clean)
                    }
                    newFolderName = ""
                    showCreateFolderDialog = false
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }
}
@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        modifier = modifier
            .height(42.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = contentColor
            )
        }
    }
}
@Composable
fun CalendarScreen(
    viewModel: JournalViewModel,
    onOpenEditor: (entryId: String?, initialMode: String, defaultDate: String) -> Unit
) {
    val context = LocalContext.current
    val mediaHelper = remember { MediaHelper(context) }
    val allEntries by viewModel.allActiveEntries.collectAsState()
    val isPrivacyShield by viewModel.isPrivacyShieldActive.collectAsState()
    val playingEntryId by viewModel.playingEntryId.collectAsState()
    val audioProgressStep by viewModel.audioProgressStep.collectAsState()
    val today = remember { LocalDate.now() }
    var currentYearMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDay by remember { mutableIntStateOf(today.dayOfMonth) }
    DisposableEffect(Unit) {
        onDispose { mediaHelper.stopPlayback() }
    }
    val daysInMonth = currentYearMonth.lengthOfMonth()
    if (selectedDay > daysInMonth) selectedDay = daysInMonth
    val monthPrefix = "%04d-%02d-".format(currentYearMonth.year, currentYearMonth.monthValue)
    val selectedDateIso = "%04d-%02d-%02d".format(currentYearMonth.year, currentYearMonth.monthValue, selectedDay)
    val dayEntries = allEntries.filter { it.entryDate == selectedDateIso }
    val dbActiveDays = remember(allEntries, currentYearMonth) {
        allEntries
            .filter { it.entryDate.startsWith(monthPrefix) }
            .mapNotNull { it.entryDate.removePrefix(monthPrefix).toIntOrNull() }
            .toSet()
    }
    val monthLabel = "${currentYearMonth.month.getDisplayName(TextStyle.FULL, Locale.US)} ${currentYearMonth.year}"
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = monthLabel,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row {
                                IconButton(onClick = { currentYearMonth = currentYearMonth.minusMonths(1) }) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous Month")
                                }
                                IconButton(onClick = { currentYearMonth = currentYearMonth.plusMonths(1) }) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next Month")
                                }
                            }
                        }
                        val daysOfWeek = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
                        Row(modifier = Modifier.fillMaxWidth()) {
                            daysOfWeek.forEach { day ->
                                Text(
                                    text = day,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        val firstDayOffset = currentYearMonth.atDay(1).dayOfWeek.value % 7
                        val totalCells = firstDayOffset + daysInMonth
                        val rows = (totalCells + 6) / 7
                        for (r in 0 until rows) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                for (c in 0..6) {
                                    val cellIndex = r * 7 + c
                                    val dayNumber = cellIndex - firstDayOffset + 1
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (dayNumber in 1..daysInMonth) {
                                            val isSelected = dayNumber == selectedDay
                                            val hasEntry = dayNumber in dbActiveDays
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                                                        else Color.Transparent
                                                    )
                                                    .clickable { selectedDay = dayNumber },
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Text(
                                                    text = dayNumber.toString(),
                                                    fontSize = 14.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                if (hasEntry) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(5.dp)
                                                            .clip(CircleShape)
                                                            .background(MaterialTheme.colorScheme.primary)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    text = "${currentYearMonth.month.getDisplayName(TextStyle.FULL, Locale.US)} $selectedDay, ${currentYearMonth.year}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            if (dayEntries.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No reflections on this date yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = { onOpenEditor(null, "WRITE", selectedDateIso) }) {
                                Text("+ Write Reflection for $selectedDateIso")
                            }
                        }
                    }
                }
            } else {
                items(dayEntries, key = { it.id }) { entry ->
                    ReflectionCard(
                        entry = entry,
                        isPrivacyMasked = isPrivacyShield,
                        isPlayingAudio = playingEntryId == entry.id,
                        audioProgressStep = audioProgressStep,
                        onCardClick = { clicked: EntryEntity ->
                            if (isPrivacyShield && (clicked.isLocked || clicked.folderId == "folder_private")) {
                                BiometricHelper.authenticate(context) {
                                    viewModel.setPrivacyShield(false)
                                    onOpenEditor(clicked.id, "WRITE", clicked.entryDate)
                                }
                            } else {
                                onOpenEditor(clicked.id, "WRITE", clicked.entryDate)
                            }
                        },
                        onPlayAudioClick = { clickedEntry: EntryEntity ->
                            val targetAudioPath = clickedEntry.audioUri
                                ?: clickedEntry.mediaUri?.takeIf { it.endsWith(".m4a") }
                            if (playingEntryId == clickedEntry.id) {
                                mediaHelper.stopPlayback()
                                viewModel.togglePlayAudio(clickedEntry.id)
                            } else {
                                if (!targetAudioPath.isNullOrBlank()) {
                                    mediaHelper.playAudio(targetAudioPath) {
                                        if (playingEntryId == clickedEntry.id) {
                                            viewModel.togglePlayAudio(clickedEntry.id)
                                        }
                                    }
                                }
                                viewModel.togglePlayAudio(clickedEntry.id)
                            }
                        },
                        onTogglePin = viewModel::togglePin,
                        onToggleLock = viewModel::toggleLock,
                        onToggleArchive = viewModel::toggleArchive,
                        onMoveToTrash = viewModel::moveToTrash
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { onOpenEditor(null, "WRITE", selectedDateIso) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Entry for Date")
        }
    }
}
@Composable
fun InsightsScreen(
    viewModel: JournalViewModel,
    onJumpToFeedWithFilter: (String) -> Unit
) {
    val entries by viewModel.allActiveEntries.collectAsState()
    val streak by viewModel.streakCount.collectAsState()
    val voiceCount = entries.count { it.audioDuration != null }
    val tagCounts = remember(entries) {
        entries
            .flatMap { it.tagsCsv.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
    }
    // Real Database Calculation for 7x3 Weekly Rhythm (Mon..Sun x Morning/Afternoon/Evening)
    val rhythmStats = remember(entries) {
        val grid = Array(7) { IntArray(3) }
        val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val slotNames = listOf("mornings", "afternoons", "evenings")
        entries.forEach { e ->
            val date = runCatching { LocalDate.parse(e.entryDate) }.getOrNull() ?: LocalDate.now()
            val dayIndex = date.dayOfWeek.value - 1 // 0 = Mon .. 6 = Sun
            val hour = Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault()).hour
            val slotIndex = when (hour) {
                in 5..11 -> 0  // Morning
                in 12..16 -> 1 // Afternoon
                else -> 2      // Evening
            }
            grid[dayIndex][slotIndex]++
        }
        var bestDay = 0
        var bestSlot = 0
        var maxCount = 0
        for (d in 0..6) {
            for (s in 0..2) {
                if (grid[d][s] > maxCount) {
                    maxCount = grid[d][s]
                    bestDay = d
                    bestSlot = s
                }
            }
        }
        val summary = if (maxCount == 0) {
            "Write a reflection to unlock your weekly rhythm."
        } else {
            "Your most active time is ${dayNames[bestDay]} ${slotNames[bestSlot]}."
        }
        Pair(grid, summary)
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                text = "Insights & Patterns",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "At a Glance",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatColumn(value = "\uD83D\uDD25 $streak", label = "Day Streak")
                        StatColumn(value = "\u270F\uFE0F ${entries.size}", label = "Entries")
                        StatColumn(value = "\uD83C\uDF99\uFE0F $voiceCount", label = "Voice Notes")
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    val activeDaysCount = entries.map { it.entryDate }.distinct().size
                    Text(
                        text = "Written across $activeDaysCount unique calendar days",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Weekly Rhythm (Morning / Afternoon / Evening)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val days = listOf("M", "T", "W", "T", "F", "S", "S")
                    val grid = rhythmStats.first
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        days.forEachIndexed { dayIdx, d ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = d, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(6.dp))
                                repeat(3) { slotIdx ->
                                    val count = grid[dayIdx][slotIdx]
                                    Box(
                                        modifier = Modifier
                                            .padding(vertical = 3.dp)
                                            .size(width = 36.dp, height = 14.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (count > 0) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = rhythmStats.second,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Frequently Tracked (Tap to Filter)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tagCounts) { (tag, count) ->
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { onJumpToFeedWithFilter(tag) }
                            ) {
                                Text(
                                    text = "$tag ($count)",
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            val topTag = tagCounts.firstOrNull()?.key ?: "Calm"
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Reflective Prompt",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Looking back: You've written most about $topTag recently. Try reflecting on what gave you energy and clarity today.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
@Composable
private fun StatColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun ProfileScreen(
    viewModel: JournalViewModel,
    onJumpToFeedWithFilter: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.userSettings.collectAsState()
    val trashedEntries by viewModel.trashedEntries.collectAsState()
    val archivedEntries by viewModel.archivedEntries.collectAsState()
    val allEntries by viewModel.allActiveEntries.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val storageSize by viewModel.storageSizeFormatted.collectAsState()
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showTrashDialog by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showDriveSyncDialog by remember { mutableStateOf(false) }
    // Full ZIP Export Launcher (Works for both Local Storage AND Google Drive App Document Provider!)
    val exportZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val jsonPayload = viewModel.exportBackupJson()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        BackupZipHelper.writeBackupZip(context, jsonPayload, out)
                    }
                    viewModel.updateSyncStatusLabel("Saved Backup (${viewModel.currentTimeLabel()})")
                    Toast.makeText(context, "Full ZIP Backup (Text, Checklists, Photos & Audio) saved!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // Full ZIP / JSON Restore Launcher (Works from Local Storage OR Google Drive)
    val importZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val extractedJson = context.contentResolver.openInputStream(uri)?.use { input ->
                        BackupZipHelper.readBackupZipOrJson(context, input)
                    }
                    if (!extractedJson.isNullOrBlank()) {
                        val count = viewModel.importBackupJson(extractedJson)
                        viewModel.updateSyncStatusLabel("Restored $count entries")
                        Toast.makeText(context, "Restored $count reflections (with blocks & media)!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "No valid backup data found in file", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val googleDriveSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            val account = task.result
            if (account != null) {
                viewModel.saveProfile(
                    account.displayName ?: settings.userName,
                    account.email ?: settings.userEmail
                )
                viewModel.backupToGoogleDriveNow { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        } else {
            // Automatic fallback: Open Android's Google Drive file saver directly if OAuth SHA-1 isn't registered
            Toast.makeText(
                context,
                "Opening Google Drive Picker directly...",
                Toast.LENGTH_SHORT
            ).show()
            exportZipLauncher.launch("my_journal_drive_backup_${DateUtils.todayIso()}.zip")
        }
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setDailyReminder(true)
            DailyReminderWorker.schedule(context)
            NotificationHelper.showReminderNotification(
                context,
                title = "Daily Reminders Enabled \uD83D\uDD14",
                message = "We'll gently remind you to reflect each day."
            )
        } else {
            Toast.makeText(context, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                text = "Profile & Settings",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = settings.userName.take(2).uppercase(),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = settings.userName, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = settings.userEmail,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { showEditProfileDialog = true }
                            ) {
                                Text(
                                    text = "Edit Profile",
                                    color = MaterialTheme.colorScheme.surface,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        if (settings.isPrivacyShieldActive) {
                                            BiometricHelper.authenticate(context) {
                                                viewModel.setPrivacyShield(false)
                                            }
                                        } else {
                                            viewModel.setPrivacyShield(true)
                                        }
                                    }
                            ) {
                                Text(
                                    text = if (settings.isPrivacyShieldActive) "Shield: ON" else "Shield: OFF",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Security & Privacy", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "App Lock & Biometrics", fontSize = 15.sp)
                        Switch(
                            checked = settings.isPrivacyShieldActive,
                            onCheckedChange = { wantActive ->
                                if (!wantActive) {
                                    BiometricHelper.authenticate(context) {
                                        viewModel.setPrivacyShield(false)
                                    }
                                } else {
                                    viewModel.setPrivacyShield(true)
                                }
                            }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingRow(
                        title = "Hidden / Private Folder",
                        value = "${allEntries.count { it.isLocked || it.folderId == "folder_private" }} Locked >",
                        onClick = {
                            BiometricHelper.authenticate(
                                context = context,
                                title = "Access Private Folder"
                            ) {
                                viewModel.setPrivacyShield(false)
                                onJumpToFeedWithFilter("Locked")
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingRow(
                        title = "Auto-Lock Timeout",
                        value = "${settings.autoLockTimeout} >",
                        onClick = { showTimeoutDialog = true }
                    )
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Data & Sync", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingRow(
                        title = "Google Drive Sync",
                        value = syncStatus,
                        onClick = { showDriveSyncDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingRow(
                        title = "Export / Import Full ZIP",
                        value = "Includes Photos & Voice >",
                        onClick = { showBackupDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingRow(
                        title = "Manage Storage",
                        value = "$storageSize >",
                        onClick = {
                            Toast.makeText(context, "Storage: $storageSize (${allEntries.size} active, ${trashedEntries.size} trashed)", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Preferences", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingRow(
                        title = "Appearance",
                        value = "${settings.themeMode.label} >",
                        onClick = { viewModel.cycleThemeMode() }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Notifications (Daily reminder)", fontSize = 15.sp)
                        Switch(
                            checked = settings.dailyReminder,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        viewModel.setDailyReminder(true)
                                        DailyReminderWorker.schedule(context)
                                        NotificationHelper.showReminderNotification(
                                            context,
                                            title = "Daily Reminders Enabled \uD83D\uDD14",
                                            message = "We'll gently remind you to reflect each day."
                                        )
                                    }
                                } else {
                                    viewModel.setDailyReminder(false)
                                    DailyReminderWorker.cancel(context)
                                }
                            }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingRow(
                        title = "Archived Reflections (${archivedEntries.size})",
                        value = "View / Unarchive >",
                        onClick = { showArchiveDialog = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SettingRow(
                        title = "Trash & Recycle Bin (${trashedEntries.size})",
                        value = "Auto-delete after 30 days >",
                        onClick = { showTrashDialog = true }
                    )
                }
            }
        }
    }
    if (showDriveSyncDialog) {
        val isSignedIn = viewModel.isSignedInToDrive()
        AlertDialog(
            onDismissRequest = { showDriveSyncDialog = false },
            title = { Text("Google Drive Backup & Restore") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Save or restore a complete .zip bundle (including all reflections, checklists, photos, and voice notes) directly to your Google Drive."
                    )
                    OutlinedButton(
                        onClick = {
                            showDriveSyncDialog = false
                            exportZipLauncher.launch("my_journal_drive_backup_${DateUtils.todayIso()}.zip")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("\u2601\uFE0F Save Full ZIP to Google Drive")
                    }
                    OutlinedButton(
                        onClick = {
                            showDriveSyncDialog = false
                            importZipLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("\uD83D\uDCE5 Restore ZIP from Google Drive")
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = {
                            showDriveSyncDialog = false
                            if (isSignedIn) {
                                viewModel.backupToGoogleDriveNow { msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            } else {
                                googleDriveSignInLauncher.launch(viewModel.getDriveSignInIntent())
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isSignedIn) "Sync via OAuth AppFolder" else "Connect Google OAuth (Cloud Console)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDriveSyncDialog = false }) { Text("Close") }
            }
        )
    }
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("Full ZIP Backup & Restore") },
            text = {
                Text("Export all your reflections, rich text formatting, checklists, photos, and voice notes into a single .zip archive, or restore from a backup.")
            },
            confirmButton = {
                Button(onClick = {
                    showBackupDialog = false
                    exportZipLauncher.launch("my_journal_backup_${DateUtils.todayIso()}.zip")
                }) {
                    Text("Export .ZIP")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showBackupDialog = false
                    importZipLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
                }) {
                    Text("Restore .ZIP")
                }
            }
        )
    }
    if (showEditProfileDialog) {
        var tempName by remember(settings.userName) { mutableStateOf(settings.userName) }
        var tempEmail by remember(settings.userEmail) { mutableStateOf(settings.userEmail) }
        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = { Text("Edit Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = tempEmail,
                        onValueChange = { tempEmail = it },
                        label = { Text("Email") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveProfile(tempName, tempEmail)
                    showEditProfileDialog = false
                }) { Text("Save") }
            }
        )
    }
    if (showTimeoutDialog) {
        val options = listOf("Immediate", "After 1 minute", "After 5 minutes", "After 30 minutes")
        AlertDialog(
            onDismissRequest = { showTimeoutDialog = false },
            title = { Text("Auto-Lock Timeout") },
            text = {
                Column {
                    options.forEach { option ->
                        TextButton(
                            onClick = {
                                viewModel.setAutoLockTimeout(option)
                                showTimeoutDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(option)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
    if (showArchiveDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text("Archived Reflections") },
            text = {
                if (archivedEntries.isEmpty()) {
                    Text("No archived reflections.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        archivedEntries.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.unarchive(item.id) }) {
                                    Text("Unarchive")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showArchiveDialog = false }) { Text("Close") }
            }
        )
    }
    if (showTrashDialog) {
        AlertDialog(
            onDismissRequest = { showTrashDialog = false },
            title = { Text("Trash & Recycle Bin") },
            text = {
                if (trashedEntries.isEmpty()) {
                    Text("Trash is empty.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        trashedEntries.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.restoreFromTrash(item.id) }) {
                                    Text("Restore")
                                }
                                IconButton(onClick = { viewModel.deletePermanently(item.id) }) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = "Delete Forever")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (trashedEntries.isNotEmpty()) {
                    TextButton(onClick = { viewModel.emptyTrash() }) {
                        Text("Empty Trash")
                    }
                }
                TextButton(onClick = { showTrashDialog = false }) { Text("Close") }
            }
        )
    }
}
@Composable
private fun SettingRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(text = value, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
}