package com.myjournal.app.ui.components
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.myjournal.app.data.local.EntryEntity
import com.myjournal.app.ui.editor.RichTextHelper
import com.myjournal.app.util.DateUtils
import java.io.File
private fun highlightQueryInText(
    base: AnnotatedString,
    query: String,
    highlightBg: Color
): AnnotatedString {
    val clean = query.trim()
    if (clean.isEmpty()) return base
    return buildAnnotatedString {
        append(base)
        val raw = base.text
        var startIndex = 0
        while (startIndex < raw.length) {
            val found = raw.indexOf(clean, startIndex, ignoreCase = true)
            if (found == -1) break
            addStyle(
                SpanStyle(background = highlightBg, fontWeight = FontWeight.Bold),
                found,
                found + clean.length
            )
            startIndex = found + clean.length
        }
    }
}
@Composable
fun ReflectionCard(
    entry: EntryEntity,
    isPrivacyMasked: Boolean,
    isPlayingAudio: Boolean,
    audioProgressStep: Int,
    searchQuery: String = "",
    onCardClick: (EntryEntity) -> Unit,
    onPlayAudioClick: (EntryEntity) -> Unit,
    onTogglePin: (EntryEntity) -> Unit,
    onToggleLock: (EntryEntity) -> Unit,
    onMoveToVault: (EntryEntity) -> Unit = {},
    onToggleArchive: (EntryEntity) -> Unit = {},
    onMoveToTrash: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showLightbox by remember { mutableStateOf(false) }
    val tags = remember(entry.tagsCsv) {
        entry.tagsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
    val gradientColors = if (entry.thumbnailColorHex == 0xFF4A7C9B) {
        listOf(Color(0xFF4A7C9B), Color(0xFFD4A373))
    } else {
        listOf(Color(0xFF3A4D2C), Color(0xFF8C7A4B))
    }
    val isInVault = entry.folderId == "folder_private"
    val hideContent = entry.isLocked && isPrivacyMasked
    val formattedDateHeader = remember(entry.entryDate, entry.timeLabel) {
        DateUtils.formatCardHeader(entry.entryDate, entry.timeLabel)
    }
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
    val styledTitle = remember(entry.title, searchQuery, highlightColor) {
        val parsed = RichTextHelper.fromHtml(entry.title)
        highlightQueryInText(parsed, searchQuery, highlightColor)
    }
    val styledPreview = remember(entry.previewText, searchQuery, highlightColor) {
        val cleanQ = searchQuery.trim()
        val rawText = entry.previewText
        val snippetText = if (cleanQ.isNotEmpty() && rawText.contains(cleanQ, ignoreCase = true)) {
            val matchLine = rawText.lineSequence().firstOrNull { it.contains(cleanQ, ignoreCase = true) }
            if (matchLine != null && rawText.lineSequence().firstOrNull() != matchLine) {
                "$matchLine\n$rawText"
            } else {
                rawText
            }
        } else {
            rawText
        }
        highlightQueryInText(AnnotatedString(snippetText), searchQuery, highlightColor)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable { onCardClick(entry) },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = formattedDateHeader,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onToggleLock(entry) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (entry.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Toggle Card Lock",
                            tint = if (entry.isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit Reflection") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onCardClick(entry)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (entry.isPinned) "Unpin" else "Pin to Top") },
                                leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onTogglePin(entry)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (entry.isLocked) "Remove Card Lock" else "Lock Card") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onToggleLock(entry)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isInVault) "Remove from Hidden Vault" else "Move to Hidden Vault") },
                                leadingIcon = { Icon(Icons.Default.FolderSpecial, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onMoveToVault(entry)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Archive Reflection") },
                                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onToggleArchive(entry)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Move to Trash") },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onMoveToTrash(entry.id)
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (hideContent) {
                Text(
                    text = "\uD83D\uDD12 Locked Reflection",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Protected by Privacy Shield. Tap to unlock with fingerprint.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = styledTitle,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    val hasPhotoFile = !entry.mediaUri.isNullOrBlank() && entry.mediaUri.endsWith(".jpg") && File(entry.mediaUri).exists()
                    if (hasPhotoFile) {
                        AsyncImage(
                            model = File(entry.mediaUri!!),
                            contentDescription = "Attached Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showLightbox = true }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(gradientColors)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (entry.audioDuration != null) Icons.Default.GraphicEq else Icons.Default.WbSunny,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        if (tags.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                tags.take(3).forEach { tag ->
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = tag,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (entry.previewText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = styledPreview,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (entry.audioDuration != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            AudioWaveformRow(
                                duration = entry.audioDuration,
                                isPlaying = isPlayingAudio,
                                activeBars = if (isPlayingAudio) audioProgressStep else 8,
                                onPlayClick = { onPlayAudioClick(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
    if (showLightbox && !entry.mediaUri.isNullOrBlank()) {
        FullScreenPhotoLightbox(
            photoPaths = listOf(entry.mediaUri),
            initialIndex = 0,
            onDismiss = { showLightbox = false }
        )
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullScreenPhotoLightbox(
    photoPaths: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit
) {
    if (photoPaths.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, photoPaths.lastIndex),
        pageCount = { photoPaths.size }
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                var scale by remember(page) { mutableFloatStateOf(1f) }
                var offset by remember(page) { mutableStateOf(Offset.Zero) }
                val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                    val newScale = (scale * zoomChange).coerceIn(1f, 4f)
                    scale = newScale
                    offset = if (newScale > 1f) {
                        Offset(
                            x = (offset.x + panChange.x).coerceIn(-600f * (newScale - 1f), 600f * (newScale - 1f)),
                            y = (offset.y + panChange.y).coerceIn(-900f * (newScale - 1f), 900f * (newScale - 1f))
                        )
                    } else {
                        Offset.Zero
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(page) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.4f
                                    }
                                },
                                onTap = {
                                    if (scale <= 1.05f) onDismiss()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = File(photoPaths[page]),
                        contentDescription = "Full Screen Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                            .transformable(state = transformState)
                    )
                }
            }
            if (photoPaths.size > 1) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${photoPaths.size}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Lightbox",
                    tint = Color.White
                )
            }
        }
    }
}
@Composable
fun AudioWaveformRow(
    duration: String,
    isPlaying: Boolean,
    activeBars: Int,
    onPlayClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(onClick = onPlayClick)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause Audio" else "Play Audio",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        val heights = listOf(8, 14, 10, 18, 22, 12, 16, 20, 10, 14, 18, 8, 12, 6, 10, 8)
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            heights.forEachIndexed { index, h ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(h.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < activeBars) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isPlaying) "Playing" else duration,
            fontSize = 12.sp,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal
        )
    }
}