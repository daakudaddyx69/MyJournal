package com.myjournal.app.ui.editor
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
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
sealed class EditorSegment {
    abstract val id: String
    class TextSegment(
        override val id: String = UUID.randomUUID().toString(),
        initialValue: TextFieldValue = TextFieldValue("")
    ) : EditorSegment() {
        var value by mutableStateOf(initialValue)
    }
    class ChecklistSegment(
        override val id: String = UUID.randomUUID().toString(),
        initialItems: List<ChecklistItemUi> = listOf(ChecklistItemUi())
    ) : EditorSegment() {
        val items: SnapshotStateList<ChecklistItemUi> = mutableStateListOf<ChecklistItemUi>().apply {
            addAll(initialItems)
        }
    }
}
data class AudioBlockUi(
    val id: String = UUID.randomUUID().toString(),
    val path: String,
    val duration: String
)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var titleValue by remember { mutableStateOf(TextFieldValue("")) }
    var titlePendingStyles by remember { mutableStateOf(setOf<FormatStyle>()) }
    var isTitleFocused by remember { mutableStateOf(false) }
    // Dynamic interleaved list of Text & Checklist segments!
    val segments = remember { mutableStateListOf<EditorSegment>(EditorSegment.TextSegment()) }
    var focusedTextSegmentId by remember { mutableStateOf<String?>(null) }
    var bodyPendingStyles by remember { mutableStateOf(setOf<FormatStyle>()) }
    var entryDate by remember { mutableStateOf(defaultDate.ifBlank { DateUtils.todayIso() }) }
    var timeLabel by remember { mutableStateOf("Just now") }
    var selectedFolderId by remember { mutableStateOf<String?>("folder_personal") }
    var isPinned by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var createdAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showFolderMenu by remember { mutableStateOf(false) }
    var folderIdRevealingTrash by remember { mutableStateOf<String?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val tags = remember { mutableStateListOf<String>() }
    val suggestedMoods = listOf("Mindfulness", "Calm", "Reflective", "Gratitude", "Energy", "Motivated", "Productive")
    var showAddTagDialog by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusTargetId by remember { mutableStateOf<String?>(null) }
    val photoPaths = remember { mutableStateListOf<String>() }
    val audioBlocks = remember { mutableStateListOf<AudioBlockUi>() }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var playingAudioPath by remember { mutableStateOf<String?>(null) }
    val activeTextSegment = remember(segments.toList(), focusedTextSegmentId) {
        segments.filterIsInstance<EditorSegment.TextSegment>()
            .firstOrNull { it.id == focusedTextSegmentId }
            ?: segments.filterIsInstance<EditorSegment.TextSegment>().lastOrNull()
    }
    val currentActiveStyles = remember(
        titleValue,
        activeTextSegment?.value,
        isTitleFocused,
        titlePendingStyles,
        bodyPendingStyles
    ) {
        if (isTitleFocused) {
            if (!titleValue.selection.collapsed) {
                RichTextHelper.getActiveStylesAtSelection(titleValue)
            } else {
                titlePendingStyles + RichTextHelper.getActiveStylesAtSelection(titleValue)
            }
        } else {
            val segVal = activeTextSegment?.value ?: TextFieldValue("")
            if (!segVal.selection.collapsed) {
                RichTextHelper.getActiveStylesAtSelection(segVal)
            } else {
                bodyPendingStyles + RichTextHelper.getActiveStylesAtSelection(segVal)
            }
        }
    }
    val multiPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                val saved = mediaHelper.copyImageToInternalStorage(uri)
                if (saved != null) {
                    photoPaths.add(saved)
                }
            }
            hasUserEdited = true
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
    LaunchedEffect(entryId) {
        if (entryId != null) {
            val existing = viewModel.getEntryById(entryId)
            val blocks = viewModel.getBlocksForEntry(entryId)
            if (existing != null) {
                val titleBlock = blocks.firstOrNull { it.blockType == "TITLE" }
                val titleAnnotated = if (titleBlock != null) {
                    RichTextHelper.fromHtml(titleBlock.textContent)
                } else {
                    RichTextHelper.fromHtml(existing.title)
                }
                titleValue = TextFieldValue(annotatedString = titleAnnotated)
                entryDate = existing.entryDate
                timeLabel = existing.timeLabel
                selectedFolderId = existing.folderId
                isPinned = existing.isPinned
                isLocked = existing.isLocked
                createdAt = existing.createdAt
                tags.clear()
                tags.addAll(existing.tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                // Reconstruct interleaved Text and Checklist segments in exact sortOrder!
                segments.clear()
                var currentChecklistSeg: EditorSegment.ChecklistSegment? = null
                var currentGroupTag: String? = null
                val bodyBlocks = blocks.filter { it.blockType == "TEXT" || it.blockType == "CHECKLIST" }
                if (bodyBlocks.isEmpty()) {
                    val fallbackAnnotated = RichTextHelper.fromHtml(existing.previewText)
                    segments.add(EditorSegment.TextSegment(initialValue = TextFieldValue(annotatedString = fallbackAnnotated)))
                } else {
                    bodyBlocks.forEach { b ->
                        if (b.blockType == "TEXT") {
                            currentChecklistSeg = null
                            currentGroupTag = null
                            val annotated = RichTextHelper.fromHtml(b.textContent)
                            segments.add(
                                EditorSegment.TextSegment(
                                    id = b.id,
                                    initialValue = TextFieldValue(annotatedString = annotated)
                                )
                            )
                        } else if (b.blockType == "CHECKLIST") {
                            val groupTag = b.mediaUri ?: "default_group"
                            val itemUi = ChecklistItemUi(id = b.id, text = b.textContent, isChecked = b.isChecked)
                            if (currentChecklistSeg != null && currentGroupTag == groupTag) {
                                currentChecklistSeg!!.items.add(itemUi)
                            } else {
                                val newSeg = EditorSegment.ChecklistSegment(initialItems = listOf(itemUi))
                                segments.add(newSeg)
                                currentChecklistSeg = newSeg
                                currentGroupTag = groupTag
                            }
                        }
                    }
                }
                if (segments.none { it is EditorSegment.TextSegment }) {
                    segments.add(EditorSegment.TextSegment())
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
        } else {
            selectedFolderId = folders.firstOrNull { !it.isPrivate }?.id
        }
        isLoaded = true
        if (entryId == null) {
            if (initialMode == "PHOTO") {
                multiPhotoPickerLauncher.launch(
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
    LaunchedEffect(focusTargetId) {
        val target = focusTargetId ?: return@LaunchedEffect
        delay(60L)
        runCatching { focusRequesters[target]?.requestFocus() }
        focusTargetId = null
    }
    // Insert a brand-new Checklist block right at the current cursor position!
    fun insertNewChecklistAtCursor() {
        val targetTextSeg = activeTextSegment
        if (targetTextSeg == null) {
            val newItem = ChecklistItemUi()
            val newCheckSeg = EditorSegment.ChecklistSegment(initialItems = listOf(newItem))
            segments.add(newCheckSeg)
            segments.add(EditorSegment.TextSegment())
            focusTargetId = newItem.id
            hasUserEdited = true
            return
        }
        val segIdx = segments.indexOf(targetTextSeg)
        val tfv = targetTextSeg.value
        val fullAnnotated = tfv.annotatedString
        val cursor = tfv.selection.start.coerceIn(0, fullAnnotated.length)
        val beforeAnnotated = fullAnnotated.subSequence(0, cursor)
        val afterAnnotated = fullAnnotated.subSequence(cursor, fullAnnotated.length)
        val newItem = ChecklistItemUi()
        val newChecklist = EditorSegment.ChecklistSegment(initialItems = listOf(newItem))
        // Trim a single trailing newline on 'before' or leading newline on 'after' so spacing stays clean
        val cleanBefore = if (beforeAnnotated.text.endsWith("\n")) {
            beforeAnnotated.subSequence(0, beforeAnnotated.length - 1)
        } else beforeAnnotated
        val cleanAfter = if (afterAnnotated.text.startsWith("\n")) {
            afterAnnotated.subSequence(1, afterAnnotated.length)
        } else afterAnnotated
        if (cleanBefore.text.isEmpty() && cleanAfter.text.isEmpty()) {
            // Current text segment is empty: place the checklist right above it
            segments.add(segIdx, newChecklist)
        } else if (cleanBefore.text.isEmpty()) {
            // Cursor is at the very start of the text block: insert checklist right before this text block
            segments.add(segIdx, newChecklist)
        } else {
            // Cursor is in the middle or after text (e.g., below "I'm krishna"):
            // Keep 'cleanBefore' above, insert the new Checklist at cursor level, and put 'cleanAfter' below!
            targetTextSeg.value = TextFieldValue(
                annotatedString = cleanBefore,
                selection = TextRange(cleanBefore.length)
            )
            segments.add(segIdx + 1, newChecklist)
            val nextSeg = segments.getOrNull(segIdx + 2)
            if (cleanAfter.text.isNotEmpty() || nextSeg !is EditorSegment.TextSegment) {
                segments.add(
                    segIdx + 2,
                    EditorSegment.TextSegment(initialValue = TextFieldValue(annotatedString = cleanAfter))
                )
            }
        }
        focusTargetId = newItem.id
        hasUserEdited = true
    }
    suspend fun performSaveNow() {
        if (!isLoaded) return
        val plainTitle = titleValue.annotatedString.text.trim()
        val previewParts = mutableListOf<String>()
        var hasAnyChecklist = false
        segments.forEach { seg ->
            when (seg) {
                is EditorSegment.TextSegment -> {
                    val t = seg.value.annotatedString.text.trim()
                    if (t.isNotEmpty()) previewParts.add(t)
                }
                is EditorSegment.ChecklistSegment -> {
                    if (seg.items.isNotEmpty()) {
                        hasAnyChecklist = true
                        val lines = seg.items.joinToString("\n") {
                            (if (it.isChecked) "\u2611 " else "\u2610 ") + it.text
                        }
                        if (lines.isNotBlank()) previewParts.add(lines)
                    }
                }
            }
        }
        val combinedPreview = previewParts.joinToString("\n")
        if (entryId == null && !hasUserEdited && plainTitle.isBlank() && combinedPreview.isBlank() && !hasAnyChecklist && photoPaths.isEmpty() && audioBlocks.isEmpty()) {
            return
        }
        val firstPlainText = segments.filterIsInstance<EditorSegment.TextSegment>()
            .map { it.value.annotatedString.text.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        val finalTitleHtml = if (plainTitle.isNotBlank()) {
            RichTextHelper.toHtml(titleValue.annotatedString)
        } else {
            when {
                firstPlainText.isNotBlank() -> firstPlainText.lineSequence().first().take(32)
                audioBlocks.isNotEmpty() -> "Voice Reflection"
                photoPaths.isNotEmpty() -> "Photo Reflection"
                hasAnyChecklist -> "Checklist Note"
                else -> "Untitled Reflection"
            }
        }
        val firstPhoto = photoPaths.firstOrNull()
        val firstAudio = audioBlocks.firstOrNull()
        val entity = EntryEntity(
            id = actualEntryId,
            title = finalTitleHtml,
            previewText = combinedPreview,
            entryDate = entryDate,
            timeLabel = if (entryId == null) viewModel.currentTimeLabel() else timeLabel,
            tagsCsv = tags.joinToString(","),
            folderId = selectedFolderId,
            audioDuration = firstAudio?.duration,
            mediaUri = firstPhoto,
            audioUri = firstAudio?.path,
            thumbnailColorHex = if (firstAudio != null) 0xFF4A7C9B else 0xFF3A4D2C,
            isLocked = isLocked,
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
                blockType = "TITLE",
                textContent = finalTitleHtml
            )
        )
        segments.forEach { seg ->
            when (seg) {
                is EditorSegment.TextSegment -> {
                    blocks.add(
                        ContentBlockEntity(
                            id = seg.id,
                            entryId = actualEntryId,
                            sortOrder = order++,
                            blockType = "TEXT",
                            textContent = RichTextHelper.toHtml(seg.value.annotatedString)
                        )
                    )
                }
                is EditorSegment.ChecklistSegment -> {
                    seg.items.forEach { item ->
                        blocks.add(
                            ContentBlockEntity(
                                id = item.id,
                                entryId = actualEntryId,
                                sortOrder = order++,
                                blockType = "CHECKLIST",
                                textContent = item.text,
                                isChecked = item.isChecked,
                                mediaUri = seg.id // Preserves separate checklist group identity!
                            )
                        )
                    }
                }
            }
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
    val segmentsSnapshot = segments.map { seg ->
        when (seg) {
            is EditorSegment.TextSegment -> seg.value.annotatedString.toString()
            is EditorSegment.ChecklistSegment -> seg.items.joinToString { "${it.id}:${it.text}:${it.isChecked}" }
        }
    }
    LaunchedEffect(
        titleValue.annotatedString,
        segmentsSnapshot,
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
    val currentFolderName = folders.firstOrNull { it.id == selectedFolderId }?.name ?: "No Folder"
    val isInHiddenVault = selectedFolderId == "folder_private"
    fun onFormatButtonClicked(style: FormatStyle) {
        if (isTitleFocused) {
            if (!titleValue.selection.collapsed) {
                titleValue = RichTextHelper.applyStyleToSelection(titleValue, style)
                hasUserEdited = true
            } else {
                titlePendingStyles = if (style in titlePendingStyles) titlePendingStyles - style else titlePendingStyles + style
            }
        } else {
            val targetSeg = activeTextSegment ?: return
            if (!targetSeg.value.selection.collapsed) {
                targetSeg.value = RichTextHelper.applyStyleToSelection(targetSeg.value, style)
                hasUserEdited = true
            } else {
                bodyPendingStyles = if (style in bodyPendingStyles) bodyPendingStyles - style else bodyPendingStyles + style
            }
        }
    }
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
                        if (isLocked) viewModel.setPrivacyShield(true)
                        hasUserEdited = true
                        Toast.makeText(
                            context,
                            if (isLocked) "Card Locked (\uD83D\uDD12 Locked Reflection)" else "Card Unlocked",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(
                            imageVector = if (isLocked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                            contentDescription = "Toggle Card Lock",
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
                        isActive = FormatStyle.BOLD in currentActiveStyles,
                        fontWeight = FontWeight.ExtraBold,
                        onClick = { onFormatButtonClicked(FormatStyle.BOLD) }
                    )
                    FormatToggleBtn(
                        label = "I",
                        isActive = FormatStyle.ITALIC in currentActiveStyles,
                        isItalic = true,
                        onClick = { onFormatButtonClicked(FormatStyle.ITALIC) }
                    )
                    FormatToggleBtn(
                        label = "U",
                        isActive = FormatStyle.UNDERLINE in currentActiveStyles,
                        isUnderline = true,
                        onClick = { onFormatButtonClicked(FormatStyle.UNDERLINE) }
                    )
                    FormatToggleBtn(
                        label = "S",
                        isActive = FormatStyle.STRIKETHROUGH in currentActiveStyles,
                        isStrike = true,
                        onClick = { onFormatButtonClicked(FormatStyle.STRIKETHROUGH) }
                    )
                    IconButton(
                        onClick = { onFormatButtonClicked(FormatStyle.HIGHLIGHT) },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (FormatStyle.HIGHLIGHT in currentActiveStyles) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                    ) {
                        Icon(Icons.Default.BorderColor, contentDescription = "Highlight", modifier = Modifier.size(18.dp))
                    }
                    VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))
                    AssistChip(
                        onClick = { insertNewChecklistAtCursor() },
                        leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Checklist") }
                    )
                    AssistChip(
                        onClick = {
                            multiPhotoPickerLauncher.launch(
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BasicTextField(
                value = titleValue,
                onValueChange = { newValue ->
                    titleValue = RichTextHelper.mergeEditWithSpans(titleValue, newValue, titlePendingStyles)
                    hasUserEdited = true
                },
                textStyle = TextStyle(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    if (titleValue.text.isEmpty()) {
                        Text(
                            text = "Title your reflection...",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    innerTextField()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isTitleFocused = it.isFocused }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    AssistChip(
                        onClick = {
                            folderIdRevealingTrash = null
                            showFolderMenu = true
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isInHiddenVault) Icons.Default.Lock else Icons.Default.Folder,
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
                        onDismissRequest = {
                            showFolderMenu = false
                            folderIdRevealingTrash = null
                        }
                    ) {
                        folders.forEach { folder ->
                            val isVaultFolder = folder.id == "folder_private"
                            val showTrashIcon = folderIdRevealingTrash == folder.id && !isVaultFolder
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            selectedFolderId = folder.id
                                            hasUserEdited = true
                                            showFolderMenu = false
                                            folderIdRevealingTrash = null
                                            if (isVaultFolder) {
                                                Toast.makeText(context, "Moved to Hidden Vault \uD83D\uDD12", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onLongClick = {
                                            if (!isVaultFolder) {
                                                folderIdRevealingTrash = if (folderIdRevealingTrash == folder.id) null else folder.id
                                            } else {
                                                Toast.makeText(context, "Hidden Vault cannot be deleted", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (folder.isPrivate) Icons.Default.Lock else Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(text = folder.name, fontSize = 14.sp)
                                }
                                if (showTrashIcon) {
                                    IconButton(
                                        onClick = {
                                            viewModel.deleteFolder(folder.id)
                                            if (selectedFolderId == folder.id) {
                                                selectedFolderId = folders.firstOrNull { it.id != folder.id && !it.isPrivate }?.id
                                                hasUserEdited = true
                                            }
                                            folderIdRevealingTrash = null
                                            Toast.makeText(context, "Deleted folder \"${folder.name}\"", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete Folder",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ Create New Folder") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                            onClick = {
                                showFolderMenu = false
                                folderIdRevealingTrash = null
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
                suggestedMoods.filter { it !in tags }.forEach { mood ->
                    AssistChip(
                        onClick = {
                            tags.add(mood)
                            hasUserEdited = true
                        },
                        label = { Text("+$mood", fontSize = 12.sp) },
                        shape = CircleShape
                    )
                }
                AssistChip(
                    onClick = { showAddTagDialog = true },
                    label = { Text("Custom Tag", fontSize = 12.sp) },
                    shape = CircleShape
                )
            }
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
            // Render Interleaved Text & Checklist Segments in Exact Order!
            val isWholeBodyEmpty = segments.size == 1 &&
                segments.firstOrNull() is EditorSegment.TextSegment &&
                (segments.first() as EditorSegment.TextSegment).value.text.isEmpty()
            segments.forEachIndexed { segIndex, segment ->
                key(segment.id) {
                    when (segment) {
                        is EditorSegment.TextSegment -> {
                            val isLastSegment = segIndex == segments.lastIndex
                            BasicTextField(
                                value = segment.value,
                                onValueChange = { newValue ->
                                    segment.value = RichTextHelper.mergeEditWithSpans(segment.value, newValue, bodyPendingStyles)
                                    hasUserEdited = true
                                },
                                textStyle = TextStyle(
                                    fontSize = 17.sp,
                                    lineHeight = 26.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = if (isLastSegment) 180.dp else 32.dp)
                                    ) {
                                        if (isWholeBodyEmpty && segIndex == 0) {
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { state ->
                                        if (state.isFocused) {
                                            isTitleFocused = false
                                            focusedTextSegmentId = segment.id
                                        }
                                    }
                            )
                        }
                        is EditorSegment.ChecklistSegment -> {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Checklist",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                        IconButton(
                                            onClick = {
                                                segments.remove(segment)
                                                if (segments.none { it is EditorSegment.TextSegment }) {
                                                    segments.add(EditorSegment.TextSegment())
                                                }
                                                hasUserEdited = true
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove Checklist",
                                                modifier = Modifier.size(15.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    segment.items.forEachIndexed { index, item ->
                                        key(item.id) {
                                            val requester = remember(item.id) {
                                                focusRequesters.getOrPut(item.id) { FocusRequester() }
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Checkbox(
                                                    checked = item.isChecked,
                                                    onCheckedChange = { checked ->
                                                        segment.items[index] = item.copy(isChecked = checked)
                                                        hasUserEdited = true
                                                    }
                                                )
                                                BasicTextField(
                                                    value = item.text,
                                                    onValueChange = { newText ->
                                                        if (newText.contains("\n")) {
                                                            val clean = newText.replace("\n", "")
                                                            segment.items[index] = item.copy(text = clean)
                                                            val newItem = ChecklistItemUi()
                                                            segment.items.add(index + 1, newItem)
                                                            focusTargetId = newItem.id
                                                        } else {
                                                            segment.items[index] = item.copy(text = newText)
                                                        }
                                                        hasUserEdited = true
                                                    },
                                                    singleLine = true,
                                                    textStyle = TextStyle(
                                                        fontSize = 16.sp,
                                                        color = if (item.isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                                        textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None
                                                    ),
                                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                                    keyboardActions = KeyboardActions(
                                                        onNext = {
                                                            val newItem = ChecklistItemUi()
                                                            segment.items.add(index + 1, newItem)
                                                            focusTargetId = newItem.id
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
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .focusRequester(requester)
                                                )
                                                IconButton(onClick = {
                                                    focusRequesters.remove(item.id)
                                                    segment.items.removeAt(index)
                                                    if (segment.items.isEmpty()) {
                                                        segments.remove(segment)
                                                    }
                                                    hasUserEdited = true
                                                }) {
                                                    Icon(Icons.Default.Close, contentDescription = "Remove item", modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            val newItem = ChecklistItemUi()
                                            segment.items.add(newItem)
                                            focusTargetId = newItem.id
                                            hasUserEdited = true
                                        }
                                    ) {
                                        Text("+ Add item")
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
            title = { Text("Add Custom Tag") },
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