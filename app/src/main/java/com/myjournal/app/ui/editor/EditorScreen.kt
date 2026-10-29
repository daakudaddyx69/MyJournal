package com.myjournal.app.ui.editor
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.myjournal.app.data.local.ContentBlockEntity
import com.myjournal.app.data.local.EntryEntity
import com.myjournal.app.ui.viewmodel.JournalViewModel
import com.myjournal.app.util.DateUtils
import com.myjournal.app.util.MediaHelper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
data class ChecklistItemUi(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val isChecked: Boolean = false
)
data class AudioBlockUi(
    val id: String = UUID.randomUUID().toString(),
    val path: String,
    val duration: String
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    entryId: String?,
    initialMode: String,
    defaultDate: String,
    viewModel: JournalViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val mediaHelper = remember { MediaHelper(context) }
    val folders by viewModel.folders.collectAsState()
    val actualEntryId = remember { entryId ?: UUID.randomUUID().toString() }
    var isLoaded by remember { mutableStateOf(false) }
    var hasUserEdited by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf("Ready") }
    // Entry State
    var title by remember { mutableStateOf("") }
    var entryDate by remember { mutableStateOf(defaultDate.ifBlank { DateUtils.todayIso() }) }
    var timeLabel by remember { mutableStateOf("Just now") }
    var selectedFolderId by remember { mutableStateOf<String?>("folder_personal") }
    var isPinned by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(true) }
    var createdAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Folder & Tag Dialogs
    var showFolderMenu by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val tags = remember { mutableStateListOf("Mindfulness", "Calm") }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }
    // WYSIWYG Rich Text State
    var richTextValue by remember { mutableStateOf(TextFieldValue("")) }
    var activeStyles by remember { mutableStateOf(setOf<FormatStyle>()) }
    // Interactive Checklists, Multiple Photos, Multiple Voice Recordings
    val checklistItems = remember { mutableStateListOf<ChecklistItemUi>() }
    val photoPaths = remember { mutableStateListOf<String>() }
    val audioBlocks = remember { mutableStateListOf<AudioBlockUi>() }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var playingAudioPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entryId) {
        if (entryId != null) {
            val existing = viewModel.getEntryById(entryId)
            val blocks = viewModel.getBlocksForEntry(entryId)
            if (existing != null) {
                title = existing.title
                entryDate = existing.entryDate
                timeLabel = existing.timeLabel
                selectedFolderId = existing.folderId ?: "folder_personal"
                isPinned = existing.isPinned
                isLocked = existing.isLocked
                createdAt = existing.createdAt
                tags.clear()
                tags.addAll(existing.tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                val textBlock = blocks.firstOrNull { it.blockType == "TEXT" }
                val annotated = if (textBlock != null) {
                    RichTextHelper.fromHtml(textBlock.textContent)
                } else {
                    RichTextHelper.fromHtml(existing.previewText)
                }
                richTextValue = TextFieldValue(annotatedString = annotated)
                checklistItems.clear()
                blocks.filter { it.blockType == "CHECKLIST" }.forEach { b ->
                    checklistItems.add(ChecklistItemUi(id = b.id, text = b.textContent, isChecked = b.isChecked))
                }
                photoPaths.clear()
                blocks.filter { it.blockType == "PHOTO" && !it.mediaUri.isNullOrBlank() }.forEach { b ->
                    photoPaths.add(b.mediaUri!!)
                }
                if (photoPaths.isEmpty() && !existing.mediaUri.isNullOrBlank() && existing.mediaUri.endsWith(".jpg")) {
                    photoPaths.add(existing.mediaUri)
                }
                audioBlocks.clear()
                blocks.filter { it.blockType == "AUDIO" && !it.mediaUri.isNullOrBlank() }.forEach { b ->
                    audioBlocks.add(AudioBlockUi(id = b.id, path = b.mediaUri!!, duration = b.textContent.ifBlank { "00:05" }))
                }
                if (audioBlocks.isEmpty() && !existing.audioUri.isNullOrBlank()) {
                    audioBlocks.add(AudioBlockUi(path = existing.audioUri, duration = existing.audioDuration ?: "00:10"))
                }
            }
        }
        isLoaded = true
    }
    suspend fun performSaveNow() {
        if (!isLoaded) return
        val plainText = richTextValue.annotatedString.text.trim()
        val checklistPreview = checklistItems.joinToString("\n") {
            (if (it.isChecked) "\u2611 " else "\u2610 ") + it.text
        }
        if (entryId == null && !hasUserEdited && title.isBlank() && plainText.isBlank() && checklistItems.isEmpty() && photoPaths.isEmpty() && audioBlocks.isEmpty()) {
            return
        }
        val combinedPreview = listOf(plainText, checklistPreview)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val finalTitle = title.ifBlank {
            when {
                plainText.isNotBlank() -> plainText.lineSequence().first().take(32)
                audioBlocks.isNotEmpty() -> "Voice Reflection"
                photoPaths.isNotEmpty() -> "Photo Reflection"
                checklistItems.isNotEmpty() -> "Checklist Note"
                else -> "Untitled Reflection"
            }
        }
        val firstPhoto = photoPaths.firstOrNull()
        val firstAudio = audioBlocks.firstOrNull()
        val entity = EntryEntity(
            id = actualEntryId,
            title = finalTitle,
            previewText = combinedPreview,
            entryDate = entryDate,
            timeLabel = if (entryId == null) viewModel.currentTimeLabel() else timeLabel,
            tagsCsv = tags.joinToString(","),
            folderId = selectedFolderId,
            audioDuration = firstAudio?.duration,
            mediaUri = firstPhoto,
            audioUri = firstAudio?.path,
            thumbnailColorHex = if (firstAudio != null) 0xFF4A7C9B else 0xFF3A4D2C,
            isLocked = isLocked || selectedFolderId == "folder_private",
            isPinned = isPinned,
            createdAt = createdAt,
            updatedAt = System.currentTimeMillis()
        )
        val blocks = mutableListOf<ContentBlockEntity>()
        var order = 0
        blocks.add(
            ContentBlockEntity(
                entryId = actualEntryId,
                sortOrder = order++,
                blockType = "TEXT",
                textContent = RichTextHelper.toHtml(richTextValue.annotatedString)
            )
        )
        checklistItems.forEach { item ->
            blocks.add(
                ContentBlockEntity(
                    id = item.id,
                    entryId = actualEntryId,
                    sortOrder = order++,
                    blockType = "CHECKLIST",
                    textContent = item.text,
                    isChecked = item.isChecked
                )
            )
        }
        photoPaths.forEach { path ->
            blocks.add(
                ContentBlockEntity(
                    entryId = actualEntryId,
                    sortOrder = order++,
                    blockType = "PHOTO",
                    mediaUri = path
                )
            )
        }
        audioBlocks.forEach { audio ->
            blocks.add(
                ContentBlockEntity(
                    id = audio.id,
                    entryId = actualEntryId,
                    sortOrder = order++,
                    blockType = "AUDIO",
                    textContent = audio.duration,
                    mediaUri = audio.path
                )
            )
        }
        viewModel.saveEntryWithBlocks(entity, blocks)
        saveStatus = "\u2713 Saved"
    }
    LaunchedEffect(
        title,
        richTextValue.annotatedString,
        checklistItems.toList(),
        photoPaths.toList(),
        audioBlocks.toList(),
        tags.toList(),
        selectedFolderId,
        isPinned,
        isLocked
    ) {
        if (!isLoaded || !hasUserEdited) return@LaunchedEffect
        saveStatus = "Saving..."
        delay(500L)
        performSaveNow()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                scope.launch {
                    withContext(NonCancellable) {
                        performSaveNow()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mediaHelper.stopAudioRecording()
            mediaHelper.stopPlayback()
        }
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val saved = mediaHelper.copyImageToInternalStorage(uri)
            if (saved != null) {
                photoPaths.add(saved)
                hasUserEdited = true
            }
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val file = mediaHelper.startAudioRecording()
            if (file != null) {
                isRecordingAudio = true
                recordingSeconds = 0
                hasUserEdited = true
            }
        } else {
            Toast.makeText(context, "Microphone permission is required for voice notes", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(isLoaded) {
        if (isLoaded && entryId == null) {
            if (initialMode == "PHOTO") {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } else if (initialMode == "VOICE") {
                val hasPerm = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) {
                    val file = mediaHelper.startAudioRecording()
                    if (file != null) {
                        isRecordingAudio = true
                        recordingSeconds = 0
                        hasUserEdited = true
                    }
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
    LaunchedEffect(isRecordingAudio) {
        while (isRecordingAudio) {
            delay(1000L)
            recordingSeconds++
        }
    }
    fun stopRecordingAndAddBlock() {
        isRecordingAudio = false
        val savedPath = mediaHelper.stopAudioRecording()
        val dur = "%02d:%02d".format(recordingSeconds / 60, maxOf(1, recordingSeconds % 60))
        if (!savedPath.isNullOrBlank()) {
            audioBlocks.add(AudioBlockUi(path = savedPath, duration = dur))
            hasUserEdited = true
        }
    }
    fun handleBackNavigation() {
        scope.launch {
            withContext(NonCancellable) {
                if (isRecordingAudio) {
                    stopRecordingAndAddBlock()
                }
                performSaveNow()
            }
            onNavigateBack()
        }
    }
    BackHandler { handleBackNavigation() }
    val currentFolderName = folders.firstOrNull { it.id == selectedFolderId }?.name ?: "Personal"
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { handleBackNavigation() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = DateUtils.formatCardHeader(entryDate, if (entryId == null) "Now" else timeLabel),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = saveStatus,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        isPinned = !isPinned
                        hasUserEdited = true
                    }) {
                        Icon(
                            imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin",
                            tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(onClick = {
                        isLocked = !isLocked
                        hasUserEdited = true
                    }) {
                        Icon(
                            imageVector = if (isLocked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                            contentDescription = "Lock",
                            tint = if (isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                    if (entryId != null) {
                        IconButton(onClick = {
                            viewModel.moveToTrash(actualEntryId)
                            Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
                            onNavigateBack()
                        }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Move to Trash")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FormatToggleBtn(
                        label = "B",
                        isActive = FormatStyle.BOLD in activeStyles,
                        fontWeight = FontWeight.ExtraBold,
                        onClick = {
                            if (!richTextValue.selection.collapsed) {
                                richTextValue = RichTextHelper.applyStyleToSelection(richTextValue, FormatStyle.BOLD)
                                hasUserEdited = true
                            } else {
                                activeStyles = if (FormatStyle.BOLD in activeStyles) activeStyles - FormatStyle.BOLD else activeStyles + FormatStyle.BOLD
                            }
                        }
                    )
                    FormatToggleBtn(
                        label = "I",
                        isActive = FormatStyle.ITALIC in activeStyles,
                        isItalic = true,
                        onClick = {
                            if (!richTextValue.selection.collapsed) {
                                richTextValue = RichTextHelper.applyStyleToSelection(richTextValue, FormatStyle.ITALIC)
                                hasUserEdited = true
                            } else {
                                activeStyles = if (FormatStyle.ITALIC in activeStyles) activeStyles - FormatStyle.ITALIC else activeStyles + FormatStyle.ITALIC
                            }
                        }
                    )
                    FormatToggleBtn(
                        label = "U",
                        isActive = FormatStyle.UNDERLINE in activeStyles,
                        isUnderline = true,
                        onClick = {
                            if (!richTextValue.selection.collapsed) {
                                richTextValue = RichTextHelper.applyStyleToSelection(richTextValue, FormatStyle.UNDERLINE)
                                hasUserEdited = true
                            } else {
                                activeStyles = if (FormatStyle.UNDERLINE in activeStyles) activeStyles - FormatStyle.UNDERLINE else activeStyles + FormatStyle.UNDERLINE
                            }
                        }
                    )
                    FormatToggleBtn(
                        label = "S",
                        isActive = FormatStyle.STRIKETHROUGH in activeStyles,
                        isStrike = true,
                        onClick = {
                            if (!richTextValue.selection.collapsed) {
                                richTextValue = RichTextHelper.applyStyleToSelection(richTextValue, FormatStyle.STRIKETHROUGH)
                                hasUserEdited = true
                            } else {
                                activeStyles = if (FormatStyle.STRIKETHROUGH in activeStyles) activeStyles - FormatStyle.STRIKETHROUGH else activeStyles + FormatStyle.STRIKETHROUGH
                            }
                        }
                    )
                    IconButton(
                        onClick = {
                            if (!richTextValue.selection.collapsed) {
                                richTextValue = RichTextHelper.applyStyleToSelection(richTextValue, FormatStyle.HIGHLIGHT)
                                hasUserEdited = true
                            } else {
                                activeStyles = if (FormatStyle.HIGHLIGHT in activeStyles) activeStyles - FormatStyle.HIGHLIGHT else activeStyles + FormatStyle.HIGHLIGHT
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (FormatStyle.HIGHLIGHT in activeStyles) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                    ) {
                        Icon(Icons.Default.BorderColor, contentDescription = "Highlight", modifier = Modifier.size(18.dp))
                    }
                    VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))
                    AssistChip(
                        onClick = {
                            checklistItems.add(ChecklistItemUi())
                            hasUserEdited = true
                        },
                        leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Checklist") }
                    )
                    AssistChip(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Photo") }
                    )
                    AssistChip(
                        onClick = {
                            if (isRecordingAudio) {
                                stopRecordingAndAddBlock()
                            } else {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPerm) {
                                    val file = mediaHelper.startAudioRecording()
                                    if (file != null) {
                                        isRecordingAudio = true
                                        recordingSeconds = 0
                                        hasUserEdited = true
                                    }
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isRecordingAudio) Icons.Default.StopCircle else Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (isRecordingAudio) Color(0xFFD9534F) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(if (isRecordingAudio) "Stop (%02d:%02d)".format(recordingSeconds / 60, recordingSeconds % 60) else "Voice")
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BasicTextField(
                value = title,
                onValueChange = {
                    title = it
                    hasUserEdited = true
                },
                textStyle = TextStyle(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    if (title.isEmpty()) {
                        Text(
                            text = "Title your reflection...",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    innerTextField()
                },
                modifier = Modifier.fillMaxWidth()
            )
            // Folder Selector + Tags Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    AssistChip(
                        onClick = { showFolderMenu = true },
                        leadingIcon = {
                            Icon(
                                imageVector = if (selectedFolderId == "folder_private") Icons.Default.Lock else Icons.Default.Folder,
                                contentDescription = "Folder",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text(currentFolderName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        shape = CircleShape
                    )
                    DropdownMenu(
                        expanded = showFolderMenu,
                        onDismissRequest = { showFolderMenu = false }
                    ) {
                        folders.forEach { folder ->
                            DropdownMenuItem(
                                text = { Text(folder.name) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (folder.isPrivate) Icons.Default.Lock else Icons.Default.FolderOpen,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    selectedFolderId = folder.id
                                    if (folder.isPrivate) isLocked = true
                                    hasUserEdited = true
                                    showFolderMenu = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Create New Folder") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                            onClick = {
                                showFolderMenu = false
                                showNewFolderDialog = true
                            }
                        )
                    }
                }
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = {
                            tags.remove(tag)
                            hasUserEdited = true
                        },
                        label = { Text(tag, fontSize = 12.sp) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                        },
                        shape = CircleShape
                    )
                }
                AssistChip(
                    onClick = { showAddTagDialog = true },
                    label = { Text("+ Tag", fontSize = 12.sp) },
                    shape = CircleShape
                )
            }
            // Render All Attached Photo Blocks
            photoPaths.forEachIndexed { index, path ->
                if (File(path).exists()) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AsyncImage(
                            model = File(path),
                            contentDescription = "Attached Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(20.dp))
                        )
                        IconButton(
                            onClick = {
                                photoPaths.removeAt(index)
                                hasUserEdited = true
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove Photo", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            // Active Voice Recording Banner
            if (isRecordingAudio) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFD9534F),
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { stopRecordingAndAddBlock() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop Recording", tint = Color.White)
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Recording Voice Note...", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(
                                    text = "%02d:%02d".format(recordingSeconds / 60, recordingSeconds % 60),
                                    fontSize = 12.sp,
                                    color = Color(0xFFD9534F)
                                )
                            }
                        }
                    }
                }
            }
            // Render All Saved Audio Blocks
            audioBlocks.forEachIndexed { index, audioItem ->
                val isPlayingThis = playingAudioPath == audioItem.path
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        if (isPlayingThis) {
                                            mediaHelper.stopPlayback()
                                            playingAudioPath = null
                                        } else {
                                            val started = mediaHelper.playAudio(audioItem.path) {
                                                playingAudioPath = null
                                            }
                                            if (started) playingAudioPath = audioItem.path
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlayingThis) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Voice Note #${index + 1}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (isPlayingThis) "Playing..." else audioItem.duration,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = {
                            if (isPlayingThis) {
                                mediaHelper.stopPlayback()
                                playingAudioPath = null
                            }
                            audioBlocks.removeAt(index)
                            hasUserEdited = true
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove Audio")
                        }
                    }
                }
            }
            if (checklistItems.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Checklist",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        checklistItems.forEachIndexed { index, item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = item.isChecked,
                                    onCheckedChange = { checked ->
                                        checklistItems[index] = item.copy(isChecked = checked)
                                        hasUserEdited = true
                                    }
                                )
                                BasicTextField(
                                    value = item.text,
                                    onValueChange = { newText ->
                                        checklistItems[index] = item.copy(text = newText)
                                        hasUserEdited = true
                                    },
                                    textStyle = TextStyle(
                                        fontSize = 16.sp,
                                        color = if (item.isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                        textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(
                                        onNext = {
                                            checklistItems.add(index + 1, ChecklistItemUi())
                                            hasUserEdited = true
                                        }
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    decorationBox = { inner ->
                                        if (item.text.isEmpty()) {
                                            Text("List item...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                        }
                                        inner()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    checklistItems.removeAt(index)
                                    hasUserEdited = true
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove item", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        TextButton(
                            onClick = {
                                checklistItems.add(ChecklistItemUi())
                                hasUserEdited = true
                            }
                        ) {
                            Text("+ Add item")
                        }
                    }
                }
            }
            BasicTextField(
                value = richTextValue,
                onValueChange = { newValue ->
                    richTextValue = RichTextHelper.mergeEditWithSpans(richTextValue, newValue, activeStyles)
                    hasUserEdited = true
                },
                textStyle = TextStyle(
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 260.dp)) {
                        if (richTextValue.text.isEmpty()) {
                            Text(
                                text = "Start writing your thoughts... Use the toolbar below for Bold, Italic, Highlight, Checklists, Photos, or Voice notes.",
                                fontSize = 17.sp,
                                lineHeight = 26.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
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
                        selectedFolderId = "folder_${clean.lowercase().replace(" ", "_")}"
                        hasUserEdited = true
                    }
                    newFolderName = ""
                    showNewFolderDialog = false
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") }
            }
        )
    }
    if (showAddTagDialog) {
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = { Text("Add Tag") },
            text = {
                OutlinedTextField(
                    value = newTagText,
                    onValueChange = { newTagText = it },
                    label = { Text("Tag name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val trimmed = newTagText.trim()
                    if (trimmed.isNotEmpty() && trimmed !in tags) {
                        tags.add(trimmed)
                        hasUserEdited = true
                    }
                    newTagText = ""
                    showAddTagDialog = false
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTagDialog = false }) { Text("Cancel") }
            }
        )
    }
}
@Composable
private fun FormatToggleBtn(
    label: String,
    isActive: Boolean,
    fontWeight: FontWeight = FontWeight.Bold,
    isItalic: Boolean = false,
    isUnderline: Boolean = false,
    isStrike: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = fontWeight,
                fontStyle = if (isItalic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                textDecoration = when {
                    isUnderline -> TextDecoration.Underline
                    isStrike -> TextDecoration.LineThrough
                    else -> TextDecoration.None
                },
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}