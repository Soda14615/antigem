package ai.antigem.ui.checkpoint

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.checkpoint.CheckpointProjectInfo
import ai.antigem.data.checkpoint.CheckpointProjectState
import ai.antigem.data.checkpoint.CheckpointSettings
import ai.antigem.data.checkpoint.CheckpointSummary
import ai.antigem.data.checkpoint.ProjectCheckpoints
import ai.antigem.theme.AppColors
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
import ai.antigem.ui.components.accent
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.label
import kotlinx.coroutines.launch

/**
 * Settings → Project checkpoints: on / off / per project, how long checkpoints are kept, and every project's
 * checkpoints (space used, history with restore, clean up, delete).
 */
@Composable
fun ProjectCheckpointsScreen() {
    val scope = rememberCoroutineScope()
    val settings by ProjectCheckpoints.settings.collectAsState()
    val changes by ProjectCheckpoints.changes.collectAsState()
    var projects by remember { mutableStateOf<List<CheckpointProjectInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var historyFor by remember { mutableStateOf<CheckpointProjectInfo?>(null) }
    var cleanFor by remember { mutableStateOf<CheckpointProjectInfo?>(null) }
    var deleteAllFor by remember { mutableStateOf<CheckpointProjectInfo?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(changes, refresh) {
        ProjectCheckpoints.projects().fold({ projects = it; loadError = null }, { loadError = it.message ?: "The bridge did not answer" })
    }

    val openHistory = historyFor
    if (openHistory != null) {
        BackHandler { historyFor = null }
        CheckpointHistory(project = openHistory, onBack = { historyFor = null }, onClean = { cleanFor = openHistory }, onDeleteAll = { deleteAllFor = openHistory })
    } else {
        val s = settings
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                stringResource(R.string.workspace_checkpoint_intro),
                fontSize = 12.5.sp, lineHeight = 17.sp, color = muted
            )
            loadError?.let {
                Spacer(Modifier.height(12.dp))
                Notice(Icons.Outlined.WarningAmber, AppColors.danger, it)
                TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.checkpoint_restore_sheet_try_again)) }
            }

            SectionLabel(stringResource(R.string.workspace_checkpoint_heading))
            val mode = s?.mode ?: CheckpointSettings.MODE_OFF
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(CheckpointSettings.MODE_OFF, stringResource(R.string.workspace_checkpoint_off), stringResource(R.string.workspace_checkpoint_off_summary)),
                    Triple(CheckpointSettings.MODE_SELECTED, stringResource(R.string.workspace_checkpoint_selected), stringResource(R.string.workspace_checkpoint_selected_summary)),
                    Triple(CheckpointSettings.MODE_ALL, stringResource(R.string.workspace_checkpoint_all), stringResource(R.string.workspace_checkpoint_all_summary))
                ).forEach { (value, title, desc) ->
                    ai.antigem.ui.claude.PickerOption(
                        title = title,
                        description = desc,
                        color = if (value == CheckpointSettings.MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        selected = mode == value,
                        icon = when (value) {
                            CheckpointSettings.MODE_OFF -> Icons.Outlined.PowerSettingsNew
                            CheckpointSettings.MODE_SELECTED -> Icons.AutoMirrored.Outlined.PlaylistAddCheck
                            else -> Icons.Outlined.AllInclusive
                        },
                        onClick = {
                            if (s != null && mode != value) scope.launch {
                                ProjectCheckpoints.updateSettings(mode = value).onFailure { toastError(it) }
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.project_checkpoints_screen_checkpoints_work_in_git_projects_only_a),
                fontSize = 11.5.sp, lineHeight = 15.sp, color = muted)

            if (s != null) {
                SectionLabel("保留")
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChoiceRow("删除早于此时长的检查点", s.retentionDays, listOf(7 to "7 天", 30 to "30 天", 90 to "90 天", 0 to "从不")) {
                            scope.launch { ProjectCheckpoints.updateSettings(retentionDays = it).onFailure(::toastError) }
                        }
                        ChoiceRow("每个聊天最多保留", s.maxPerChat, listOf(50 to "50", 100 to "100", 200 to "200", 0 to "不限制")) {
                            scope.launch { ProjectCheckpoints.updateSettings(maxPerChat = it).onFailure(::toastError) }
                        }
                        ChoiceRow("每个项目占用空间", s.maxProjectMb, listOf(200 to "200 MB", 500 to "500 MB", 1024 to "1 GB", 2048 to "2 GB", 0 to "不限制")) {
                            scope.launch { ProjectCheckpoints.updateSettings(maxProjectMb = it).onFailure(::toastError) }
                        }
                        ChoiceRow("跳过大于此大小的文件", s.maxFileMb, listOf(5 to "5 MB", 20 to "20 MB", 50 to "50 MB", 100 to "100 MB")) {
                            scope.launch { ProjectCheckpoints.updateSettings(maxFileMb = it).onFailure(::toastError) }
                        }
                        Text(
                            "旧检查点会自动清理（项目占用空间过多时从最旧的开始删）。" +
                                "删除旧检查点不会影响较新的检查点：每个检查点都是完整独立的。",
                            fontSize = 11.5.sp, lineHeight = 15.sp, color = muted
                        )
                    }
                }
            }

            SectionLabel("Projects")
            val list = projects
            when {
                list == null && loadError == null -> Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.project_checkpoints_screen_loading), fontSize = 13.sp, color = muted)
                }
                list.isNullOrEmpty() -> Text(
                    if (mode == CheckpointSettings.MODE_OFF) stringResource(R.string.project_checkpoints_screen_turn_checkpoints_on_above_then_start_a)
                    else stringResource(R.string.project_checkpoints_screen_no_project_has_checkpoints_yet_they_appear),
                    fontSize = 12.5.sp, color = muted
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.forEach { p ->
                        ProjectCard(
                            p = p,
                            mode = mode,
                            onToggle = { on -> scope.launch { ProjectCheckpoints.setDecision(p.path, if (on) "on" else "off").onFailure(::toastError) } },
                            onHistory = { historyFor = p },
                            onClean = { cleanFor = p },
                            onDeleteAll = { deleteAllFor = p }
                        )
                    }
                }
            }

            SectionLabel(stringResource(R.string.workspace_checkpoint_good_to_know))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Only files inside the project go back. Packages the agent installed, files outside the project, servers it started or things it sent are not undone.",
                        "Files your .gitignore ignores (and node_modules, .gradle, caches) are not saved and never changed by a restore.",
                        "A restore first saves the files as they are, so “Undo restore” always works.",
                        "Going back in a chat (Edit) only changes the chat. Files go back only through a restore point.",
                        "The copies live with the bridge (~/.antigem_bridge/checkpoints), on the machine where the project is."
                    ).forEach { t ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.Info, null, tint = muted, modifier = Modifier.size(14.dp).padding(top = 2.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(t, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    cleanFor?.let { p ->
        CleanOldDialog(p, onDismiss = { cleanFor = null }) { days ->
            cleanFor = null
            scope.launch {
                ProjectCheckpoints.delete(p.path, olderThanDays = days).fold(
                    { r -> AppToastHelper.showToast("Deleted ${r.deleted} checkpoint${if (r.deleted == 1) "" else "s"} · ${formatBytes((r.sizeBefore - r.sizeAfter).coerceAtLeast(0))} freed", ChatToastType.SUCCESS) },
                    ::toastError
                )
            }
        }
    }
    deleteAllFor?.let { p ->
        var forget by remember(p.path) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleteAllFor = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.project_checkpoints_screen_delete_all_checkpoints), fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column {
                    Text("将删除 ${p.name} 的所有检查点（释放 ${formatBytes(p.sizeBytes)}）。项目自身的文件和 git 不受影响。" +
                        "若项目仍处于开启状态，下一条消息会重新开始创建检查点。", fontSize = 14.sp, lineHeight = 20.sp)
                    Row(Modifier.padding(top = 10.dp).clickable { forget = !forget }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = forget, onCheckedChange = { forget = it })
                        Text(stringResource(R.string.project_checkpoints_screen_also_forget_my_answer_for_this_project), fontSize = 13.5.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteAllFor = null
                    if (historyFor?.path == p.path) historyFor = null
                    scope.launch {
                        ProjectCheckpoints.delete(p.path, all = true, forget = forget).fold(
                            { AppToastHelper.showToast("All checkpoints of ${p.name} deleted", ChatToastType.SUCCESS) }, ::toastError
                        )
                    }
                }) { Text(stringResource(R.string.browser_screen_delete_all), color = AppColors.danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleteAllFor = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
}

private fun toastError(e: Throwable) {
    AppToastHelper.showToast(e.message ?: "Something went wrong", ChatToastType.ERROR)
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(22.dp))
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(title: String, current: Int, options: List<Pair<Int, String>>, onPick: (Int) -> Unit) {
    Column {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                FilterChip(selected = value == current, onClick = { if (value != current) onPick(value) }, label = { Text(label, fontSize = 12.5.sp) })
            }
        }
    }
}

@Composable
private fun ProjectCard(
    p: CheckpointProjectInfo, mode: String,
    onToggle: (Boolean) -> Unit, onHistory: () -> Unit, onClean: () -> Unit, onDeleteAll: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (status, color) = when {
        p.tracked -> "Saving checkpoints" to AppColors.success
        !p.exists -> "Folder not found" to AppColors.danger
        mode == CheckpointSettings.MODE_OFF -> "Checkpoints are off" to muted
        !p.isGit -> "Not a git repository" to AppColors.warning
        else -> "Off for this project" to muted
    }
    val switchOn = when (mode) {
        CheckpointSettings.MODE_ALL -> p.decision != "off"
        else -> p.decision == "on"
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Folder, null, tint = color, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.path, fontSize = 11.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = switchOn, enabled = mode != CheckpointSettings.MODE_OFF && p.exists, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(status, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
                if (p.hasData) {
                    Text(
                        "  ·  ${p.count} checkpoint${if (p.count == 1) "" else "s"} · ${formatBytes(p.sizeBytes)}" +
                            if (p.lastTime > 0) " · ${formatCheckpointTime(p.lastTime)}" else "",
                        fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!p.isGit && p.exists && mode != CheckpointSettings.MODE_OFF) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.project_checkpoints_screen_run_git_init_in_this_folder_to), fontSize = 11.5.sp, color = muted)
            }
            if (p.hasData) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onHistory) {
                        Icon(Icons.Outlined.History, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.project_checkpoints_screen_history))
                    }
                    TextButton(onClick = onClean) {
                        Icon(Icons.Outlined.CleaningServices, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.project_checkpoints_screen_clean_old))
                    }
                    TextButton(onClick = onDeleteAll) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(16.dp), tint = AppColors.danger); Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.browser_screen_delete_all), color = AppColors.danger)
                    }
                }
            } else Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun CleanOldDialog(p: CheckpointProjectInfo, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var days by remember { mutableIntStateOf(7) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.project_checkpoints_screen_clean_old_checkpoints), fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column {
                Text(stringResource(R.string.project_checkpoints_screen_delete_the_checkpoints_of_older_than, p.name.toString()), fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                listOf(1 to "1 day", 3 to "3 days", 7 to "7 days", 30 to "30 days").forEach { (d, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { days = d }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = days == d, onClick = { days = d })
                        Text(label, fontSize = 14.sp)
                    }
                }
                Text(stringResource(R.string.project_checkpoints_screen_newer_checkpoints_stay_and_keep_working), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(days) }) { Text(stringResource(R.string.browser_screen_delete), color = AppColors.danger, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_cancel)) } }
    )
}

/** One project's checkpoints, newest first: tap one to see what it would change and restore it (files only). */
@Composable
private fun CheckpointHistory(project: CheckpointProjectInfo, onBack: () -> Unit, onClean: () -> Unit, onDeleteAll: () -> Unit) {
    val scope = rememberCoroutineScope()
    val changes by ProjectCheckpoints.changes.collectAsState()
    var items by remember { mutableStateOf<List<CheckpointSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<CheckpointSummary?>(null) }
    LaunchedEffect(project.path, changes) {
        ProjectCheckpoints.history(project.path).fold({ items = it; error = null }, { error = it.message })
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.agent_approval_panel_back)) }
            Column(Modifier.weight(1f)) {
                Text(project.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.project_checkpoints_screen_checkpoints, (items?.size ?: project.count).toString(), (formatBytes(project.sizeBytes)).toString()), fontSize = 12.sp, color = muted)
            }
            IconButton(onClick = onClean) { Icon(Icons.Outlined.CleaningServices, stringResource(R.string.project_checkpoints_screen_clean_old)) }
            IconButton(onClick = onDeleteAll) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.browser_screen_delete_all), tint = AppColors.danger) }
        }
        error?.let { Box(Modifier.padding(16.dp)) { Notice(Icons.Outlined.WarningAmber, AppColors.danger, it) } }
        val list = items
        if (list == null && error == null) {
            Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
        } else if (list != null && list.isEmpty()) {
            Text(stringResource(R.string.project_checkpoints_screen_no_checkpoints_left), fontSize = 13.sp, color = muted, modifier = Modifier.padding(20.dp))
        } else if (list != null) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { cp -> HistoryRow(cp) { open = cp } }
            }
        }
    }
    open?.let { cp ->
        CheckpointRestoreSheet(
            projectPath = project.path,
            checkpoint = cp,
            onDismiss = { open = null },
            onDelete = {
                open = null
                scope.launch { ProjectCheckpoints.delete(project.path, ids = listOf(cp.id)).onFailure(::toastError) }
            }
        )
    }
}

@Composable
private fun HistoryRow(cp: CheckpointSummary, onClick: () -> Unit) {
    val agent = agentOf(cp.agent)
    val color = if (cp.isPreRestore) AppColors.info else agent?.accent() ?: MaterialTheme.colorScheme.primary
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Icon(if (cp.isPreRestore) Icons.AutoMirrored.Outlined.Undo else Icons.Outlined.History, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    cp.title?.takeIf { it.isNotBlank() } ?: if (cp.isPreRestore) stringResource(R.string.project_checkpoints_screen_before_a_restore) else stringResource(R.string.project_checkpoints_screen_message),
                    fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                val flags = listOfNotNull(
                    if (cp.isPreRestore) "undo point" else agent?.label(),
                    formatCheckpointTime(cp.time),
                    if (cp.midTurn) "sent while working" else null,
                    if (cp.late) "late" else null,
                    if (cp.repos > 1) "${cp.repos} repos" else null
                )
                Text(flags.joinToString(" · "), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Asked once per project when checkpoints are on for chosen projects: keep checkpoints for [state]'s project?
 * Dismissing without an answer asks again later.
 */
@Composable
fun TrackProjectDialog(state: CheckpointProjectState, onAnswer: (String?) -> Unit) {
    val name = state.path.trimEnd('/').substringAfterLast('/').ifBlank { state.path }
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.project_checkpoints_screen_keep_checkpoints_for, name.toString()), fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "每条消息发送前，AntiGem 都会保存一份该项目文件的隐藏副本。这样每条消息都有一个还原" +
                        "点：可以回到它之前的状态，并预览每个发生变化的文件。你的 git 历史、分支和暂存区不受影响。",
                    fontSize = 14.sp, lineHeight = 20.sp
                )
                if (!state.isGit) {
                    Notice(Icons.Outlined.WarningAmber, AppColors.warning,
                        "此文件夹不是 git 仓库。检查点仅适用于 git 项目：在此运行 git init 后才会开始工作。")
                }
                Text(stringResource(R.string.project_checkpoints_screen_you_can_change_this_any_time_in), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer("on") }) { Text(stringResource(R.string.project_checkpoints_screen_keep_checkpoints), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = { onAnswer("off") }) { Text(stringResource(R.string.project_checkpoints_screen_not_for_this_project)) } }
    )
}
