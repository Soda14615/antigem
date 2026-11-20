package ai.antigem.ui.files

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.daemon.ProjectItem
import ai.antigem.data.files.*
import kotlinx.coroutines.launch

// ---------------- places ----------------

private fun rootIcon(kind: String): ImageVector = when (kind) {
    "home" -> Icons.Outlined.Home
    "projects" -> Icons.Outlined.FolderSpecial
    "storage" -> Icons.Outlined.PhoneAndroid
    "downloads" -> Icons.Outlined.Download
    "documents" -> Icons.Outlined.Description
    "camera" -> Icons.Outlined.PhotoCamera
    "pictures" -> Icons.Outlined.Image
    "movies" -> Icons.Outlined.Movie
    "music" -> Icons.Outlined.MusicNote
    "sdcard" -> Icons.Outlined.SdCard
    "root" -> Icons.Outlined.Storage
    else -> Icons.Outlined.Folder
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlacesSheet(
    roots: List<ExplorerRoot>,
    projects: List<ProjectItem>,
    recent: List<String>,
    currentPath: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            item { Text(stringResource(R.string.explorer_components_places), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp)) }
            if (roots.isEmpty()) {
                item { Text(stringResource(R.string.explorer_dialogs_loading_places), modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(roots, key = { "r" + it.path }) { r ->
                PlaceRow(rootIcon(r.kind), r.name, r.path, currentPath == r.path, r.total, r.free) { onPick(r.path) }
            }
            if (projects.isNotEmpty()) {
                item { SectionLabel("Projects") }
                items(projects.take(10), key = { "p" + it.path }) { p ->
                    PlaceRow(Icons.Outlined.FolderSpecial, p.name, p.path, currentPath == p.path) { onPick(p.path) }
                }
            }
            val rec = recent.filter { r -> roots.none { it.path == r } && projects.none { it.path == r } }
            if (rec.isNotEmpty()) {
                item { SectionLabel("Recent") }
                items(rec, key = { "h$it" }) { p ->
                    PlaceRow(Icons.Outlined.History, p.trimEnd('/').substringAfterLast('/').ifBlank { p }, p, currentPath == p) { onPick(p) }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(icon: ImageVector, name: String, path: String, active: Boolean, total: Long = 0, free: Long = 0, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (total > 0) {
                val used = (total - free).coerceAtLeast(0)
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { (used.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (free < total / 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {}
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.explorer_dialogs_free_of, (FileKinds.formatSize(free)).toString(), (FileKinds.formatSize(total)).toString()), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(path, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
        }
    }
}

// ---------------- item actions ----------------

internal data class ActionCaps(
    val ide: Boolean,
    val openFolderInIde: Boolean,
    val pickFolder: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryActionsSheet(
    entry: ExplorerEntry,
    caps: ActionCaps,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onOpenWith: () -> Unit,
    onOpenInIde: () -> Unit,
    onOpenFolderInIde: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onCopyTo: () -> Unit,
    onMoveTo: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onCompress: () -> Unit,
    onExtractHere: () -> Unit,
    onExtractTo: () -> Unit,
    onViewArchive: () -> Unit,
    onCopyPath: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                EntryIcon(entry, 48.dp, thumbnails = true, corner = 14.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (entry.isDir) "" else entry.kind.label + "  ·  ") + entrySubtitle(entry),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickAction(if (entry.isDir) Icons.Outlined.FolderOpen else Icons.AutoMirrored.Outlined.OpenInNew, "Open", onClick = onOpen)
                if (!entry.isDir) QuickAction(Icons.Outlined.Share, stringResource(R.string.workspace_file_share), onClick = onShare)
                QuickAction(Icons.Outlined.ContentCopy, "Copy", onClick = onCopy)
                QuickAction(Icons.Outlined.ContentCut, "Cut", onClick = onCut)
                QuickAction(Icons.Outlined.DriveFileRenameOutline, "Rename", onClick = onRename)
                QuickAction(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.error, onClick = onDelete)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            if (entry.isDir) {
                if (caps.openFolderInIde) SheetRow(Icons.Outlined.Code, stringResource(R.string.explorer_dialogs_open_folder_in_ide), "Use it as the active project", tint = MaterialTheme.colorScheme.primary, onClick = onOpenFolderInIde)
            } else {
                SheetRow(Icons.Outlined.Apps, stringResource(R.string.file_link_handler_open_with), "Choose the editor or another app", onClick = onOpenWith)
                if (caps.ide) SheetRow(Icons.Outlined.Code, stringResource(R.string.explorer_dialogs_open_in_code_editor), if (entry.kind.isTextual || entry.kind == FileKind.IMAGE) null else "Not a text file: may not display correctly", onClick = onOpenInIde)
            }
            if (!entry.isDir && FileKinds.isBrowsableArchive(entry.name)) SheetRow(Icons.Outlined.Inventory2, stringResource(R.string.explorer_dialogs_view_contents), onClick = onViewArchive)
            if (!entry.isDir && FileKinds.isExtractable(entry.name)) {
                SheetRow(Icons.Outlined.Unarchive, stringResource(R.string.explorer_dialogs_extract_here), "Into a new folder next to it", onClick = onExtractHere)
                SheetRow(Icons.Outlined.DriveFolderUpload, stringResource(R.string.explorer_dialogs_extract_to), onClick = onExtractTo)
            }
            SheetRow(Icons.Outlined.FolderZip, stringResource(R.string.explorer_dialogs_compress_to_zip), onClick = onCompress)
            SheetRow(Icons.Outlined.FileCopy, stringResource(R.string.explorer_dialogs_copy_to), onClick = onCopyTo)
            SheetRow(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.explorer_dialogs_move_to), onClick = onMoveTo)
            SheetRow(Icons.Outlined.ControlPointDuplicate, stringResource(R.string.explorer_dialogs_duplicate), onClick = onDuplicate)
            SheetRow(Icons.Outlined.Link, stringResource(R.string.explorer_dialogs_copy_path), entry.path, onClick = onCopyPath)
            SheetRow(Icons.Outlined.Info, stringResource(R.string.explorer_dialogs_details), onClick = onDetails)
        }
    }
}

// ---------------- open with ----------------

internal data class OpenOption(val choice: OpenChoice, val icon: ImageVector, val title: String, val subtitle: String, val recommended: Boolean, val warn: Boolean = false)

/** [contentIsText]: what the file's bytes say, when known; otherwise the extension decides. */
@Composable
private fun openOptionsFor(entry: ExplorerEntry, ideAvailable: Boolean, contentIsText: Boolean? = null, allowPreview: Boolean = true): List<OpenOption> {
    val k = entry.kind
    val textual = contentIsText ?: k.isTextual
    val ide = if (!ideAvailable) null else when {
        textual -> OpenOption(OpenChoice.IDE, Icons.Outlined.Code, stringResource(R.string.workspace_file_editor), stringResource(R.string.workspace_file_edit_in_ide), recommended = true)
        // pictures and videos have AntiGem's own viewer and player
        k == FileKind.IMAGE || k == FileKind.VIDEO -> null
        else -> OpenOption(OpenChoice.IDE, Icons.Outlined.Code, stringResource(R.string.workspace_file_editor), stringResource(R.string.workspace_file_non_text_warning), recommended = false, warn = true)
    }
    // a mini app package goes to AntiGem's own installer (FileOpener), like an .apk to Android's
    val agapp = entry.name.endsWith(".agapp", ignoreCase = true)
    val external = OpenOption(
        OpenChoice.EXTERNAL,
        if (agapp) Icons.Outlined.InstallMobile else when (k) {
            FileKind.VIDEO -> Icons.Outlined.PlayCircle
            FileKind.AUDIO -> Icons.Outlined.MusicNote
            FileKind.IMAGE -> Icons.Outlined.PhotoLibrary
            FileKind.PDF -> Icons.Outlined.PictureAsPdf
            FileKind.APK -> Icons.Outlined.InstallMobile
            else -> Icons.AutoMirrored.Outlined.OpenInNew
        },
        if (agapp) stringResource(R.string.workspace_file_install_mini_app) else when (k) {
            FileKind.VIDEO -> stringResource(R.string.workspace_file_video_player)
            FileKind.AUDIO -> stringResource(R.string.workspace_file_music_player)
            FileKind.IMAGE -> stringResource(R.string.workspace_file_gallery)
            FileKind.PDF -> stringResource(R.string.workspace_file_pdf_reader)
            FileKind.APK -> stringResource(R.string.workspace_file_install_app)
            FileKind.DOCUMENT -> stringResource(R.string.workspace_file_document_app)
            else -> stringResource(R.string.workspace_file_another_app)
        },
        if (agapp) stringResource(R.string.workspace_file_check_signature) else stringResource(R.string.workspace_file_pick_app),
        recommended = !textual
    )
    val preview = when {
        !allowPreview -> null
        k == FileKind.MARKDOWN -> OpenOption(OpenChoice.PREVIEW, Icons.Outlined.Visibility, stringResource(R.string.workspace_file_preview), stringResource(R.string.workspace_file_rendered_markdown), recommended = false)
        k == FileKind.VIDEO -> OpenOption(OpenChoice.PREVIEW, Icons.Outlined.SmartDisplay, stringResource(R.string.workspace_file_play_antigem), stringResource(R.string.workspace_file_player_features), recommended = true)
        k == FileKind.IMAGE && !textual -> OpenOption(OpenChoice.PREVIEW, Icons.Outlined.Image, stringResource(R.string.workspace_file_view_antigem), stringResource(R.string.workspace_file_viewer_features), recommended = true)
        FileKinds.isBrowsableArchive(entry.name) -> OpenOption(OpenChoice.PREVIEW, Icons.Outlined.Inventory2, stringResource(R.string.workspace_file_view_contents), stringResource(R.string.workspace_file_preview_archive), recommended = k == FileKind.ARCHIVE)
        else -> null
    }
    // sending the file to another app is always offered, last
    val share = OpenOption(OpenChoice.SHARE, Icons.Outlined.Share, stringResource(R.string.workspace_file_share), stringResource(R.string.workspace_file_share_app), recommended = false)
    val list = listOfNotNull(ide, preview, external, share)
    // exactly one recommendation, and it goes first
    val rec = list.firstOrNull { it.recommended } ?: list.first()
    return listOf(rec.copy(recommended = true)) + list.filter { it !== rec }.map { it.copy(recommended = false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenWithSheet(
    entry: ExplorerEntry,
    ideAvailable: Boolean,
    onChoose: (OpenChoice, Boolean) -> Unit,
    onDismiss: () -> Unit,
    contentIsText: Boolean? = null,
    allowPreview: Boolean = true
) {
    val options = openOptionsFor(entry, ideAvailable, contentIsText, allowPreview)
    val ext = FileKinds.extOf(entry.name)
    var remember by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EntryIcon(entry, 44.dp, thumbnails = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.explorer_dialogs_open_with), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            options.forEach { o ->
                Surface(
                    onClick = { onChoose(o.choice, remember && ext.isNotBlank() && o.choice != OpenChoice.SHARE) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (o.recommended) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = if (o.recommended) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        val tint = if (o.warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Icon(o.icon, null, tint = tint, modifier = Modifier.size(21.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(o.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                if (o.recommended) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.explorer_dialogs_recommended),
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(o.subtitle, fontSize = 12.5.sp, color = if (o.warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (ext.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { remember = !remember }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text(stringResource(R.string.explorer_dialogs_always_open_files_this_way, ext.toString()), fontSize = 13.5.sp)
                }
            }
        }
    }
}

// ---------------- delete ----------------

@Composable
internal fun DeleteConfirmDialog(entries: List<ExplorerEntry>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val folders = entries.filter { it.isDir }
    val nested = folders.sumOf { it.childCount }
    val needsAck = folders.any { it.childCount > 0 } || entries.size > 5
    var ack by remember { mutableStateOf(false) }
    val title = if (entries.size == 1) stringResource(R.string.workspace_file_delete_confirm_named, entries.first().name) else stringResource(R.string.workspace_file_delete_confirm_many, entries.size)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(30.dp)) },
        title = { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entries.size > 1) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            entries.take(4).forEach { e ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    EntryIcon(e, 24.dp, thumbnails = false, corner = 7.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(e.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                }
                            }
                            if (entries.size > 4) Text(stringResource(R.string.explorer_dialogs_and_more, (entries.size - 4).toString()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (folders.isNotEmpty()) {
                    Text(
                        if (nested > 0) stringResource(R.string.explorer_dialogs_folders_are_deleted_with_everything_inside_them, (if (nested > 1000) "1000+" else nested).toString())
                        else stringResource(R.string.explorer_dialogs_empty_folders_will_be_removed),
                        fontSize = 13.5.sp
                    )
                }
                Text(stringResource(R.string.explorer_dialogs_this_is_permanent_deleted_files_cannot_be), fontSize = 13.5.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                if (needsAck) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { ack = !ack },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = ack, onCheckedChange = { ack = it }, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.error))
                        Text(stringResource(R.string.explorer_dialogs_i_understand_delete_permanently), fontSize = 13.5.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !needsAck || ack,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
            ) { Text(stringResource(R.string.browser_screen_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_cancel)) } }
    )
}

// ---------------- name input (new / rename / compress) ----------------

@Composable
internal fun NameDialog(
    title: String,
    icon: ImageVector,
    initial: String,
    confirmLabel: String,
    selectStem: Boolean,
    existingNames: Set<String>,
    helper: String? = null,
    onConfirm: suspend (String) -> String?,
    onDismiss: () -> Unit
) {
    val stemEnd = if (selectStem) initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stemEnd))) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val name = value.text.trim()
    val localError = when {
        name.isEmpty() -> null
        name.contains('/') -> stringResource(R.string.workspace_file_invalid_slash)
        name == "." || name == ".." -> stringResource(R.string.workspace_file_invalid_name)
        name != initial && name in existingNames -> stringResource(R.string.workspace_file_item_exists, name)
        else -> null
    }
    val error = localError ?: serverError
    fun submit() {
        if (name.isEmpty() || localError != null || busy) return
        busy = true
        scope.launch {
            serverError = onConfirm(name)
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; serverError = null },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: helper ?: "") },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
        },
        confirmButton = {
            Button(onClick = { submit() }, enabled = name.isNotEmpty() && localError == null && !busy && (name != initial || !selectStem)) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_cancel)) } }
    )
}

// ---------------- create menu + new project ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateSheet(allowProject: Boolean, onFolder: () -> Unit, onFile: () -> Unit, onProject: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.workspace_folder_browser_dialog_create), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
            SheetRow(Icons.Outlined.CreateNewFolder, stringResource(R.string.explorer_dialogs_folder), tint = MaterialTheme.colorScheme.primary, onClick = onFolder)
            SheetRow(Icons.Outlined.NoteAdd, stringResource(R.string.explorer_dialogs_file), "Empty file, any extension", tint = MaterialTheme.colorScheme.primary, onClick = onFile)
            if (allowProject) SheetRow(Icons.Outlined.RocketLaunch, stringResource(R.string.explorer_dialogs_project_from_template), "Python, Node.js, Web, Kotlin, C++…", tint = MaterialTheme.colorScheme.primary, onClick = onProject)
        }
    }
}

@Composable
internal fun NewProjectDialog(onCreate: suspend (name: String, template: String) -> String?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("python") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val templates = listOf("python" to "Python", "node" to "Node.js", "web" to "Web", "kotlin" to "Kotlin", "cpp" to "C++", "blank" to "Blank")
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.RocketLaunch, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.explorer_dialogs_new_project), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null }, label = { Text(stringResource(R.string.agy_cloud_ui_project_name)) }, singleLine = true,
                    isError = error != null, supportingText = error?.let { { Text(it) } }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    templates.forEach { (k, label) ->
                        FilterChip(selected = template == k, onClick = { template = k }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && !busy && !name.contains('/'),
                onClick = { busy = true; scope.launch { error = onCreate(name.trim(), template); busy = false } }
            ) { Text(if (busy) stringResource(R.string.explorer_dialogs_creating) else stringResource(R.string.explorer_dialogs_create_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_cancel)) } }
    )
}

// ---------------- details ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsSheet(entry: ExplorerEntry, onCopyPath: (String) -> Unit, onDismiss: () -> Unit, side: ExplorerSide = ExplorerSide.BRIDGE) {
    var stat by remember { mutableStateOf<ExplorerStat?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(entry.path, reload) {
        ExplorerSource.of(side).stat(entry.path).onSuccess { stat = it }.onFailure { error = it.message }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EntryIcon(entry, 56.dp, thumbnails = true, corner = 16.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(entry.kind.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            val s = stat
            DetailRow(stringResource(R.string.file_details_dialog_location), entry.path.substringBeforeLast('/').ifBlank { "/" }, mono = true) { onCopyPath(entry.path) }
            when {
                s == null && error == null -> DetailRow(stringResource(R.string.file_details_dialog_size), stringResource(R.string.explorer_dialogs_calculating))
                s == null -> DetailRow(stringResource(R.string.file_details_dialog_size), error ?: "")
                s.isDir -> {
                    DetailRow(stringResource(R.string.file_details_dialog_size), FileKinds.formatSize(s.size) + if (s.partial) " (partial, very large folder)" else "")
                    DetailRow(stringResource(R.string.explorer_dialogs_contains), stringResource(R.string.explorer_dialogs_files_folders, s.files.toString(), s.dirs.toString()))
                }
                else -> DetailRow(stringResource(R.string.file_details_dialog_size), stringResource(R.string.explorer_dialogs_bytes, (FileKinds.formatSize(s.size)).toString(), ("%,d".format(s.size)).toString()))
            }
            DetailRow(stringResource(R.string.file_link_handler_modified), formatModified(entry.modTime))
            if (s != null) {
                if (s.mime.isNotBlank()) DetailRow(stringResource(R.string.file_details_dialog_type), s.mime)
                DetailRow(stringResource(R.string.app_permissions_dialog_permissions), "${s.mode}  (${s.modeOctal})", mono = true)
                DetailRow(stringResource(R.string.explorer_dialogs_access), listOfNotNull(if (s.readable) "read" else null, if (s.writable) "write" else null).joinToString(" + ").ifBlank { "none" })
                if (s.symlinkTarget.isNotBlank()) DetailRow(stringResource(R.string.explorer_dialogs_links_to), s.symlinkTarget, mono = true)
                // the bridge's machine only (Android storage has no Unix permissions to change)
                if (!s.isDir && side == ExplorerSide.BRIDGE) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.explorer_dialogs_executable), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.explorer_dialogs_lets_scripts_and_programs_run_chmod_x), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = s.executable, onCheckedChange = { on ->
                            scope.launch {
                                FileExplorerApi.chmod(entry.path, if (on) "755" else "644")
                                reload++
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false, onCopy: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(104.dp))
        Text(value, fontSize = 13.5.sp, fontFamily = if (mono) FontFamily.Monospace else null, modifier = Modifier.weight(1f))
        if (onCopy != null) {
            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.explorer_dialogs_copy_path), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).clickable(onClick = onCopy))
        }
    }
}

// ---------------- paste conflicts ----------------

@Composable
internal fun ConflictDialog(names: List<String>, move: Boolean, onChoose: (ConflictPolicy) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CopyAll, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(if (names.size == 1) stringResource(R.string.explorer_dialogs_already_exists, (names.first()).toString()) else stringResource(R.string.explorer_dialogs_items_already_exist_here, names.size.toString()), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (names.size > 1) Text(names.take(5).joinToString("\n") + if (names.size > 5) "\n…" else "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if (move) R.string.workspace_file_conflict_move else R.string.workspace_file_conflict_copy), fontSize = 14.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceButton(Icons.Outlined.ControlPointDuplicate, stringResource(R.string.workspace_file_keep_both), stringResource(R.string.workspace_file_rename_conflict)) { onChoose(ConflictPolicy.RENAME) }
                    ChoiceButton(Icons.Outlined.SkipNext, stringResource(R.string.workspace_file_skip), stringResource(R.string.workspace_file_skip_conflict)) { onChoose(ConflictPolicy.SKIP) }
                    ChoiceButton(Icons.Outlined.SwapHoriz, stringResource(R.string.workspace_file_replace), stringResource(R.string.workspace_file_replace_conflict), danger = true) { onChoose(ConflictPolicy.OVERWRITE) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_cancel)) } }
    )
}

@Composable
private fun ChoiceButton(icon: ImageVector, title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (danger) tint else MaterialTheme.colorScheme.onSurface)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------- archive contents ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveSheet(entry: ExplorerEntry, onExtractHere: () -> Unit, onExtractTo: () -> Unit, onDismiss: () -> Unit, side: ExplorerSide = ExplorerSide.BRIDGE) {
    var entries by remember { mutableStateOf<List<ArchiveEntry>?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.path) {
        ExplorerSource.of(side).archiveEntries(entry.path).onSuccess { entries = it.first; truncated = it.second }.onFailure { error = it.message }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EntryIcon(entry, 44.dp, thumbnails = false)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    val list = entries
                    Text(
                        when {
                            error != null -> error!!
                            list == null -> stringResource(R.string.explorer_dialogs_reading)
                            else -> stringResource(R.string.explorer_dialogs_files_unpacked, (list.count { !it.isDir }).toString(), (if (truncated) "+" else "").toString(), (FileKinds.formatSize(list.sumOf { it.size })).toString())
                        },
                        fontSize = 12.5.sp,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (FileKinds.isExtractable(entry.name)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onExtractHere, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.Unarchive, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.explorer_dialogs_extract_here))
                    }
                    OutlinedButton(onClick = onExtractTo, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.explorer_dialogs_extract_to)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(entries.orEmpty()) { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val depth = a.name.trimEnd('/').count { it == '/' }
                        Spacer(Modifier.width((depth * 12).coerceAtMost(72).dp))
                        Icon(
                            if (a.isDir) Icons.Outlined.Folder else FileKinds.icon(FileKinds.of(a.name), a.name),
                            null,
                            tint = if (a.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(a.name.trimEnd('/').substringAfterLast('/'), fontSize = 13.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!a.isDir) Text(FileKinds.formatSize(a.size), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------------- sort & view ----------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ViewOptionsSheet(settings: ExplorerSettings, onChange: ((ExplorerSettings) -> ExplorerSettings) -> Unit, onForgetChoices: () -> Unit, rememberedCount: Int, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.explorer_dialogs_sort_view), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.explorer_dialogs_sort_by), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortBy.entries.forEach { s ->
                    FilterChip(selected = settings.sortBy == s, onClick = { onChange { it.copy(sortBy = s) } }, label = {
                        Text(stringResource(when (s) {
                            SortBy.NAME -> R.string.browser_screen_name
                            SortBy.DATE -> R.string.workspace_file_sort_date
                            SortBy.SIZE -> R.string.file_details_dialog_size
                            SortBy.TYPE -> R.string.file_details_dialog_type
                        }))
                    })
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                SegmentedButton(selected = !settings.descending, onClick = { onChange { it.copy(descending = false) } }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.explorer_dialogs_ascending)) }
                SegmentedButton(selected = settings.descending, onClick = { onChange { it.copy(descending = true) } }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.explorer_dialogs_descending)) }
            }
            Text(stringResource(R.string.explorer_dialogs_layout), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !settings.grid, onClick = { onChange { it.copy(grid = false) } }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = { Icon(Icons.Outlined.ViewList, null, Modifier.size(18.dp)) }) { Text(stringResource(R.string.explorer_dialogs_list)) }
                SegmentedButton(selected = settings.grid, onClick = { onChange { it.copy(grid = true) } }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = { Icon(Icons.Outlined.GridView, null, Modifier.size(18.dp)) }) { Text(stringResource(R.string.explorer_dialogs_grid)) }
            }
            SwitchRow(stringResource(R.string.workspace_file_folders_first), settings.foldersFirst) { v -> onChange { it.copy(foldersFirst = v) } }
            SwitchRow(stringResource(R.string.workspace_file_show_hidden), settings.showHidden, stringResource(R.string.workspace_file_hidden_help)) { v -> onChange { it.copy(showHidden = v) } }
            SwitchRow(stringResource(R.string.workspace_file_thumbnails), settings.thumbnails) { v -> onChange { it.copy(thumbnails = v) } }
            if (rememberedCount > 0) {
                TextButton(onClick = onForgetChoices) { Text(stringResource(R.string.explorer_dialogs_forget_always_open_with_choices, rememberedCount.toString())) }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------- binary warning ----------------

@Composable
internal fun NotTextDialog(name: String, mime: String, onOpenAnyway: () -> Unit, onOpenElsewhere: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.explorer_dialogs_not_a_text_file), fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                stringResource(R.string.explorer_dialogs_looks_like_the_code_editor_may_show, name.toString(), (mime.ifBlank { "binary data" }).toString()),
                fontSize = 14.sp
            )
        },
        confirmButton = { TextButton(onClick = onOpenAnyway) { Text(stringResource(R.string.explorer_dialogs_open_anyway), color = MaterialTheme.colorScheme.error) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_cancel)) }
                Button(onClick = onOpenElsewhere) { Text(stringResource(R.string.explorer_dialogs_other_app)) }
            }
        }
    )
}
