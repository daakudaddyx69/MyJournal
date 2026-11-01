package com.myjournal.app.ui.editor
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
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
import com.myjournal.app.ui.components.FullScreenPhotoLightbox
import com.myjournal.app.ui.viewmodel.JournalViewModel
import com.myjournal.app.util.DateUtils
import com.myjournal.app.util.MediaHelper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
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
private sealed class SegmentSnapshot {
    data class TextSnap(val id: String, val html: String, val cursor: Int) : SegmentSnapshot()
    data class ChecklistSnap(val id: String, val items: List<ChecklistItemUi>) : SegmentSnapshot()
}
private data class EditorHistorySnapshot(
    val titleHtml: String,
    val titleCursor: Int,
    val segments: List<SegmentSnapshot>
)
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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val mediaHelper = remember { MediaHelper(context) }
    val folders by viewModel.folders.collectAsState()
    val actualEntryId = remember { entryId ?: UUID.randomUUID().toString() }
    var isLoaded by remember { mutableStateOf(false) }
    var hasUserEdited by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf("Ready") }
    // Existing reflections open in distraction-free Reading Mode; new reflections start in Editing Mode
    var isEditingMode by remember { mutableStateOf(entryId == null) }
    var titleValue by remember { mutableStateOf(TextFieldValue("")) }
    var titlePendingStyles by remember { mutableStateOf(setOf<FormatStyle>()) }
    var isTitleFocused by remember { mutableStateOf(false) }
    val segments = remember { mutableStateListOf<EditorSegment>(EditorSegment.TextSegment()) }
    var focusedTextSegmentId by remember { mutableStateOf<String?>(null) }
    var bodyPendingStyles by remember { mutableStateOf(setOf<FormatStyle>()) }
    // Undo / Redo History Ring Buffer
    val undoStack = remember { mutableStateListOf<EditorHistorySnapshot>() }
    val redoStack = remember { mutableStateListOf<EditorHistorySnapshot>() }
    var isRestoringHistory by remember { mutableStateOf(false) }
    fun captureCurrentSnapshot(): EditorHistorySnapshot {
        val tHtml = RichTextHelper.toHtml(titleValue.annotatedString)
        val segSnaps = segments.map { seg ->
            when (seg) {
                is EditorSegment.TextSegment -> SegmentSnapshot.TextSnap(
                    id = seg.id,
                    html = RichTextHelper.toHtml(seg.value.annotatedString),
                    cursor = seg.value.selection.start
                )
                is EditorSegment.ChecklistSegment -> SegmentSnapshot.ChecklistSnap(
                    id = seg.id,
                    items = seg.items.toList()
                )
            }
        }
        return EditorHistorySnapshot(
            titleHtml = tHtml,
            titleCursor = titleValue.selection.start,
            segments = segSnaps
        )
    }
    fun applySnapshot(snap: EditorHistorySnapshot) {
        isRestoringHistory = true
        val restoredTitle = RichTextHelper.fromHtml(snap.titleHtml)
        titleValue = TextFieldValue(
            annotatedString = restoredTitle,
            selection = TextRange(snap.titleCursor.coerceIn(0, restoredTitle.length))
        )
        segments.clear()
        snap.segments.forEach { s ->
            when (s) {
                is SegmentSnapshot.TextSnap -> {
                    val ann = RichTextHelper.fromHtml(s.html)
                    segments.add(
                        EditorSegment.TextSegment(
                            id = s.id,
                            initialValue = TextFieldValue(
                                annotatedString = ann,
                                selection = TextRange(s.cursor.coerceIn(0, ann.length))
                            )
                        )
                    )
                }
                is SegmentSnapshot.ChecklistSnap -> {
                    segments.add(
                        EditorSegment.ChecklistSegment(
                            id = s.id,
                            initialItems = s.items
                        )
                    )
                }
            }
        }
        if (segments.none { it is EditorSegment.TextSegment }) {
            segments.add(EditorSegment.TextSegment())
        }
        hasUserEdited = true
        scope.launch {
            delay(80L)
            isRestoringHistory = false
        }
    }
    fun recordUndoStateNow() {
        val current = captureCurrentSnapshot()
        if (undoStack.lastOrNull() != current) {
            undoStack.add(current)
            if (undoStack.size > 25) undoStack.removeAt(0)
            redoStack.clear()
        }
    }
    fun performUndo() {
        if (undoStack.size <= 1) return
        val current = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(current)
        val target = undoStack.last()
        applySnapshot(target)
    }
    fun performRedo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(next)
        applySnapshot(next)
    }
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
    var lightboxInitialIndex by remember { mutableStateOf<Int?>(null) }
    val audioBlocks = remember { mutableStateListOf<AudioBlockUi>() }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var playingAudioPath by remember { mutableStateOf<String?>(null) }
    var playbackProgressBars by remember { mutableIntStateOf(0) }
    LaunchedEffect(playingAudioPath) {
        if (playingAudioPath != null) {
            playbackProgressBars = 1
            while (playingAudioPath != null) {
                delay(300L)
                playbackProgressBars = if (playbackProgressBars < 28) playbackProgressBars + 1 else 1
            }
        } else {
            playbackProgressBars = 10
        }
    }
    val formattedCleanDate = remember(entryDate) {
        runCatching {
            val parsed = LocalDate.parse(entryDate)
            parsed.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
        }.getOrDefault(entryDate)
    }
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
        undoStack.clear()
        redoStack.clear()
        undoStack.add(captureCurrentSnapshot())
        isLoaded = true
        if (entryId == null) {
            if (initialMode == "PHOTO") {
                viewModel.suppressAutoLockTemporarily()
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
                    viewModel.suppressAutoLockTemporarily()
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
    fun insertNewChecklistAtCursor() {
        recordUndoStateNow()
        isEditingMode = true
        val targetTextSeg = activeTextSegment
        if (targetTextSeg == null) {
            val newItem = ChecklistItemUi()
            val newCheckSeg = EditorSegment.ChecklistSegment(initialItems = listOf(newItem))
            segments.add(newCheckSeg)
            segments.add(EditorSegment.TextSegment())
            focusTargetId = newItem.id
            hasUserEdited = true
            recordUndoStateNow()
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
        val cleanBefore = if (beforeAnnotated.text.endsWith("\n")) {
            beforeAnnotated.subSequence(0, beforeAnnotated.length - 1)
        } else beforeAnnotated
        val cleanAfter = if (afterAnnotated.text.startsWith("\n")) {
            afterAnnotated.subSequence(1, afterAnnotated.length)
        } else afterAnnotated
        if (cleanBefore.text.isEmpty() && cleanAfter.text.isEmpty()) {
            segments.add(segIdx, newChecklist)
        } else if (cleanBefore.text.isEmpty()) {
            segments.add(segIdx, newChecklist)
        } else {
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
        recordUndoStateNow()
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
                                mediaUri = seg.id
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
            is EditorSegment.TextSegment -> RichTextHelper.toHtml(seg.value.annotatedString)
            is EditorSegment.ChecklistSegment -> seg.items.joinToString { "${it.id}:${it.text}:${it.isChecked}" }
        }
    }
    // Debounced Undo Snapshot + Auto-Save
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
        delay(450L)
        if (!isRestoringHistory) {
            recordUndoStateNow()
        }
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
    fun exitEditModeToReadingView() {
        focusManager.clearFocus()
        keyboardController?.hide()
        isEditingMode = false
        scope.launch {
            withContext(NonCancellable) {
                if (isRecordingAudio) stopRecordingAndAddBlock()
                performSaveNow()
            }
        }
    }
    fun handleBackNavigation() {
        if (isEditingMode && entryId != null) {
            exitEditModeToReadingView()
        } else {
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
    }
    BackHandler { handleBackNavigation() }
    val currentFolderName = folders.firstOrNull { it.id == selectedFolderId }?.name
    val isInHiddenVault = selectedFolderId == "folder_private"
    fun onFormatButtonClicked(style: FormatStyle) {
        isEditingMode = true
        if (isTitleFocused) {
            if (!titleValue.selection.collapsed) {
                recordUndoStateNow()
                titleValue = RichTextHelper.applyStyleToSelection(titleValue, style)
                hasUserEdited = true
                recordUndoStateNow()
            } else {
                titlePendingStyles = if (style in titlePendingStyles) titlePendingStyles - style else titlePendingStyles + style
            }
        } else {
            val targetSeg = activeTextSegment ?: return
            if (!targetSeg.value.selection.collapsed) {
                recordUndoStateNow()
                targetSeg.value = RichTextHelper.applyStyleToSelection(targetSeg.value, style)
                hasUserEdited = true
                recordUndoStateNow()
            } else {
                bodyPendingStyles = if (style in bodyPendingStyles) bodyPendingStyles - style else bodyPendingStyles + style
            }
        }
    }
    val canUndo = undoStack.size > 1
    val canRedo = redoStack.isNotEmpty()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Left: Back Button + Undo/Redo when Editing
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 0.5.dp,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable { handleBackNavigation() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = isEditingMode,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            CompactHeaderIcon(
                                icon = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo",
                                tint = MaterialTheme.colorScheme.onBackground,
                                enabled = canUndo,
                                onClick = { performUndo() }
                            )
                            CompactHeaderIcon(
                                icon = Icons.AutoMirrored.Filled.Redo,
                                contentDescription = "Redo",
                                tint = MaterialTheme.colorScheme.onBackground,
                                enabled = canRedo,
                                onClick = { performRedo() }
                            )
                        }
                    }
                }
                // Centered Date Header ("Aug 23, 2026")
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Text(
                        text = formattedCleanDate,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    AnimatedVisibility(visible = isEditingMode) {
                        Text(
                            text = saveStatus,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Right: Compact Pin, Lock, Trash & Done Actions
                AnimatedVisibility(
                    visible = isEditingMode,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        CompactHeaderIcon(
                            icon = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = "Pin",
                            tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                            onClick = {
                                isPinned = !isPinned
                                hasUserEdited = true
                            }
                        )
                        CompactHeaderIcon(
                            icon = if (isLocked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                            contentDescription = "Toggle Card Lock",
                            tint = if (isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                            onClick = {
                                isLocked = !isLocked
                                if (isLocked) viewModel.setPrivacyShield(true)
                                hasUserEdited = true
                                Toast.makeText(
                                    context,
                                    if (isLocked) "Card Locked (\uD83D\uDD12 Locked Reflection)" else "Card Unlocked",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                        if (entryId != null) {
                            CompactHeaderIcon(
                                icon = Icons.Outlined.DeleteOutline,
                                contentDescription = "Move to Trash",
                                tint = MaterialTheme.colorScheme.onBackground,
                                onClick = {
                                    viewModel.moveToTrash(actualEntryId)
                                    Toast.makeText(context, "Moved to Trash", Toast.LENGTH_SHORT).show()
                                    onNavigateBack()
                                }
                            )
                        }
                        CompactHeaderIcon(
                            icon = Icons.Default.Check,
                            contentDescription = "Done Editing",
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = { exitEditModeToReadingView() }
                        )
                    }
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = isEditingMode,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
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
                                viewModel.suppressAutoLockTemporarily()
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
                                        viewModel.suppressAutoLockTemporarily()
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
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Centered Bold Title ("Morning Reflection")
            BasicTextField(
                value = titleValue,
                onValueChange = { newValue ->
                    titleValue = RichTextHelper.mergeEditWithSpans(titleValue, newValue, titlePendingStyles)
                    hasUserEdited = true
                },
                textStyle = TextStyle(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (titleValue.text.isEmpty()) {
                            Text(
                                text = "Title your reflection...",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        isTitleFocused = it.isFocused
                        if (it.isFocused) isEditingMode = true
                    }
            )
            // Centered Soft Rounded Pills for Folder & Tags
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentFolderName != null || isEditingMode) {
                    Box {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 0.5.dp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    isEditingMode = true
                                    folderIdRevealingTrash = null
                                    showFolderMenu = true
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                if (isInHiddenVault) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Vault",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                }
                                Text(
                                    text = currentFolderName ?: "+ Folder",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
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
                }
                // Active Tags rendered as soft borderless pills
                tags.forEach { tag ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 0.5.dp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                if (isEditingMode) {
                                    tags.remove(tag)
                                    hasUserEdited = true
                                } else {
                                    isEditingMode = true
                                }
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = tag,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isEditingMode) {
                                Spacer(modifier = Modifier.width(5.dp))
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (isEditingMode) {
                    suggestedMoods.filter { it !in tags }.forEach { mood ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    tags.add(mood)
                                    hasUserEdited = true
                                }
                        ) {
                            Text(
                                text = "+$mood",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { showAddTagDialog = true }
                    ) {
                        Text(
                            text = "+ Tag",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            // Rounded Photo Cards (Tapping opens Full-Screen Pinch-to-Zoom Lightbox; Delete X icon in Editing Mode)
            photoPaths.forEachIndexed { index, path ->
                if (File(path).exists()) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AsyncImage(
                            model = File(path),
                            contentDescription = "Attached Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(225.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .clickable { lightboxInitialIndex = index }
                        )
                        if (isEditingMode) {
                            IconButton(
                                onClick = {
                                    photoPaths.removeAt(index)
                                    hasUserEdited = true
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(10.dp)
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.6f))
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove Photo",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (isRecordingAudio) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
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
            // Sleek Horizontal Waveform Pill Card
            val waveformHeights = remember {
                listOf(10, 16, 22, 14, 26, 18, 12, 24, 28, 16, 20, 14, 22, 12, 18, 24, 14, 10, 20, 16, 12, 22, 14, 18, 10, 14, 12, 8)
            }
            audioBlocks.forEachIndexed { index, audioItem ->
                val isPlayingThis = playingAudioPath == audioItem.path
                val activeBarCount = if (isPlayingThis) playbackProgressBars else 10
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(38.dp)
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
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            waveformHeights.forEachIndexed { barIdx, h ->
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(h.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (barIdx < activeBarCount) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                                        )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = audioItem.duration,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isEditingMode) {
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    if (isPlayingThis) {
                                        mediaHelper.stopPlayback()
                                        playingAudioPath = null
                                    }
                                    audioBlocks.removeAt(index)
                                    hasUserEdited = true
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove Audio",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            // Interleaved Text & Checklist Segments
            val isWholeBodyEmpty = segments.size == 1 &&
                segments.firstOrNull() is EditorSegment.TextSegment &&
                (segments.first() as EditorSegment.TextSegment).value.text.isEmpty()
            segments.forEachIndexed { segIndex, segment ->
                key(segment.id) {
                    when (segment) {
                        is EditorSegment.TextSegment -> {
                            val isLastSegment = segIndex == segments.lastIndex
                            val textFocusRequester = remember(segment.id) {
                                focusRequesters.getOrPut(segment.id) { FocusRequester() }
                            }
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
                                            .defaultMinSize(minHeight = if (isLastSegment) 90.dp else 28.dp)
                                    ) {
                                        if (isWholeBodyEmpty && segIndex == 0) {
                                            Text(
                                                text = "Start writing your thoughts...",
                                                fontSize = 17.sp,
                                                lineHeight = 26.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(textFocusRequester)
                                    .onFocusChanged { state ->
                                        if (state.isFocused) {
                                            isTitleFocused = false
                                            focusedTextSegmentId = segment.id
                                            isEditingMode = true
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
                                    if (isEditingMode) {
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
                                                    recordUndoStateNow()
                                                    segments.remove(segment)
                                                    if (segments.none { it is EditorSegment.TextSegment }) {
                                                        segments.add(EditorSegment.TextSegment())
                                                    }
                                                    hasUserEdited = true
                                                    recordUndoStateNow()
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
                                                // Interactive in BOTH Reading Mode and Editing Mode!
                                                Checkbox(
                                                    checked = item.isChecked,
                                                    onCheckedChange = { checked ->
                                                        recordUndoStateNow()
                                                        segment.items[index] = item.copy(isChecked = checked)
                                                        hasUserEdited = true
                                                        recordUndoStateNow()
                                                    }
                                                )
                                                if (isEditingMode) {
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
                                                            .onFocusChanged { if (it.isFocused) isEditingMode = true }
                                                    )
                                                    IconButton(onClick = {
                                                        recordUndoStateNow()
                                                        focusRequesters.remove(item.id)
                                                        segment.items.removeAt(index)
                                                        if (segment.items.isEmpty()) {
                                                            segments.remove(segment)
                                                        }
                                                        hasUserEdited = true
                                                        recordUndoStateNow()
                                                    }) {
                                                        Icon(Icons.Default.Close, contentDescription = "Remove item", modifier = Modifier.size(16.dp))
                                                    }
                                                } else {
                                                    // Reading Mode: Tapping checkbox toggles silently; tapping text enters Editing Mode
                                                    Text(
                                                        text = item.text.ifBlank { "List item..." },
                                                        fontSize = 16.sp,
                                                        color = if (item.isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                                        textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None,
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clickable {
                                                                isEditingMode = true
                                                                focusTargetId = item.id
                                                            }
                                                            .padding(vertical = 8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (isEditingMode) {
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
            // Blank space tap catcher at the bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        isEditingMode = true
                        val lastText = segments.filterIsInstance<EditorSegment.TextSegment>().lastOrNull()
                        if (lastText != null) {
                            focusTargetId = lastText.id
                        }
                    }
            )
        }
    }
    if (lightboxInitialIndex != null && photoPaths.isNotEmpty()) {
        FullScreenPhotoLightbox(
            photoPaths = photoPaths.toList(),
            initialIndex = lightboxInitialIndex!!,
            onDismiss = { lightboxInitialIndex = null }
        )
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
private fun CompactHeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.32f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
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