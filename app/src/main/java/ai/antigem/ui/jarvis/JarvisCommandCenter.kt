package ai.antigem.ui.jarvis

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.daemon.IdeDaemonManager
import ai.antigem.data.jarvis.JarvisController
import ai.antigem.data.jarvis.JarvisEvents
import ai.antigem.data.jarvis.JarvisManagers
import ai.antigem.data.jarvis.JarvisOrchestrationRules
import ai.antigem.data.jarvis.JarvisTasks
import ai.antigem.data.jarvis.JarvisWorktreeManager
import ai.antigem.domain.model.AgentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun agentLabel(name: String) = when (name.uppercase()) {
    "AGY" -> "Antigravity"
    "OPENCODE" -> "OpenCode"
    "CODEX" -> "Codex"
    "CLAUDE" -> "Claude"
    else -> name
}

private fun age(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return if (m < 1) "now" else if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
}

/**
 * The command strip above the input of a Jarvis chat: the mission and its progress, what Jarvis is doing, its teams'
 * running tasks (tap one to open its chat), and the switches the user controls (voice replies, autopilot). Tap the head
 * for the full board: every step, team and task.
 */
@Composable
fun JarvisCommandStrip(chatId: String, onOpenChat: (String) -> Unit, toolsMissing: Boolean = false) {
    val managers by JarvisManagers.managers.collectAsState()
    val statuses by JarvisManagers.status.collectAsState()
    val tasks by JarvisTasks.tasks.collectAsState()
    val voiceSide by ai.antigem.data.jarvis.JarvisPreferences.voiceAssistant.state.collectAsState()
    val key = remember(chatId, managers) { JarvisManagers.keyForChat(chatId) } ?: return
    val m = managers[key] ?: JarvisManagers.Manager(key)
    val voice = key == JarvisManagers.VOICE
    val mine = tasks.filter { JarvisManagers.ownerOrVoice(it.owner) == key }
    val active = mine.filter { it.active }
    val mission = m.mission
    val status = statuses[key]
    var expanded by remember(chatId) { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accent = Cyan

    val title = when {
        !mission.isEmpty -> mission.goal
        voice -> "Jarvis · voice chat"
        else -> teamText("Team orchestration", "团队编排")
    }
    val subtitle = when {
        status != null -> status
        active.any { it.state.needsUser } -> "有任务需要决定"
        active.isNotEmpty() -> "${active.size} task${if (active.size == 1) "" else "s"} working · news comes in by itself"
        mission.status == "done" -> "任务完成"
        mission.status == "paused" -> "任务已暂停"
        mission.isEmpty -> "交给我一个大任务：我来规划、带团队执行并持续推进。"
        mission.autopilot -> "Autopilot on · keeps going until done"
        else -> "等待你"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, accent.copy(alpha = 0.26f), RoundedCornerShape(16.dp))
            .animateContentSize()
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            JarvisBotIcon(28.dp, accent, animated = active.isNotEmpty() || status != null)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 11.sp, color = onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (mission.steps.isNotEmpty()) {
                Text("${mission.doneCount}/${mission.steps.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
                Spacer(Modifier.width(2.dp))
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) stringResource(R.string.jarvis_command_center_hide_the_board) else stringResource(R.string.jarvis_command_center_show_the_board), tint = onSurface.copy(alpha = 0.6f))
        }
        if (mission.steps.isNotEmpty()) {
            val p = mission.doneCount.toFloat() / mission.steps.size
            Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(onSurface.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(RoundedCornerShape(50)).background(if (mission.status == "done") Green else accent))
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // "chat only" (Settings → Jarvis): nothing is ever said, so no Voice switch
            if (!voice && voiceSide) {
                ToggleChip(if (m.speak) "语音开" else "语音关", if (m.speak) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, m.speak) {
                    JarvisManagers.setSpeak(key, !m.speak)
                }
            }
            if (!mission.isEmpty) {
                ToggleChip(if (mission.autopilot) "自动驾驶开启" else "Autopilot", Icons.Filled.Autorenew, mission.autopilot) {
                    JarvisManagers.setAutopilot(key, !mission.autopilot)
                }
            }
            active.forEach { t -> TaskPill(t) { onOpenChat(t.id) } }
            if (active.isEmpty() && mine.isNotEmpty()) {
                Text(stringResource(R.string.jarvis_command_center_finished_task, mine.size.toString(), (if (mine.size == 1) "" else "s").toString()), fontSize = 11.sp, color = onSurface.copy(alpha = 0.5f))
            }
        }
        if (toolsMissing) {
            // the chat's agent answered that it has no jarvis_mcp tools
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Amber.copy(alpha = 0.12f))
                    .clickable { ai.antigem.data.jarvis.JarvisController.requestSettings("JARVIS_SETUP") }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.jarvis_command_center_this_chat_s_agent_has_no_jarvis),
                    fontSize = 11.5.sp, color = onSurface.copy(alpha = 0.85f), modifier = Modifier.weight(1f)
                )
            }
        }
        if (expanded) androidx.compose.runtime.key(chatId) { Board(m, tasks, onOpenChat) }
    }
}

@Composable
private fun ToggleChip(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val c = if (on) Cyan else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Cyan.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, c.copy(alpha = if (on) 0.6f else 0.3f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = c, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (on) MaterialTheme.colorScheme.onSurface else c)
    }
}

@Composable
private fun TaskPill(t: JarvisTasks.Task, onClick: () -> Unit) {
    val c = taskColor(t.state)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.12f))
            .border(1.dp, c.copy(alpha = 0.4f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PulseDot(c, working = t.state == JarvisTasks.State.RUNNING || t.state == JarvisTasks.State.STARTING)
        Spacer(Modifier.width(3.dp))
        Text(
            (if (t.team.isNotBlank()) "${t.team} · " else "") + t.title.take(22),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), maxLines = 1
        )
    }
}

/** Everything of one manager: mission steps, teams, tasks. */
@Composable
private fun Board(m: JarvisManagers.Manager, tasks: List<JarvisTasks.Task>, onOpenChat: (String) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val owner = m.key
    var showArchived by remember(owner) { mutableStateOf(false) }
    val tree = teamTaskTree(owner, tasks)
    val activeTree = tree.filterNot { it.task.archived }
    val archivedTree = tree.filter { it.task.archived }
    var editRole by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf<JarvisManagers.Team?>(null) }
    var selectedTask by remember { mutableStateOf<String?>(null) }
    var showApprovals by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
    ) {
        item { MissionSection(owner, m.mission) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { BoardTitle(teamText("Roles", "岗位")) }
                if (m.parentKey.isBlank()) SmallAction(teamText("Add role", "新增岗位"), Cyan) {
                    role = null
                    editRole = true
                }
            }
        }
        items(JarvisManagers.profiles(owner), key = { "role:${it.name}" }) { profile ->
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        listOf(profile.name, agentLabel(profile.agent.name), profile.model).filter { it.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = onSurface
                    )
                    if (profile.childRoles.isNotEmpty()) {
                        Text(teamText("Can delegate to ", "可委派给 ") + profile.childRoles.joinToString(), fontSize = 11.sp)
                    }
                    if (profile.role.isNotBlank()) {
                        Text(profile.role, fontSize = 11.sp, color = onSurface.copy(alpha = 0.55f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (m.parentKey.isBlank()) SmallAction(teamText("Edit", "编辑"), Cyan) {
                    role = profile
                    editRole = true
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { BoardTitle(teamText("Task tree", "任务树")) }
                if (archivedTree.isNotEmpty()) SmallAction(
                    if (showArchived) teamText("Hide archived", "隐藏归档") else teamText("Archived (${archivedTree.size})", "归档 (${archivedTree.size})"), onSurface
                ) {
                    showArchived = !showArchived
                    selectedTask = null
                }
                SmallAction(teamText("Approvals", "审批"), Amber) { showApprovals = true }
            }
            if (activeTree.isEmpty()) Text(stringResource(R.string.jarvis_command_center_none_yet), fontSize = 12.sp, color = onSurface.copy(alpha = 0.5f))
        }
        items(activeTree, key = { "task:${it.task.id}" }) { (task, depth) ->
            TeamTaskRow(task, depth, { selectedTask = task.id }, { onOpenChat(task.id) })
        }
        if (showArchived) items(archivedTree, key = { "archived:${it.task.id}" }) { (task, depth) ->
            TeamTaskRow(task, depth, { selectedTask = task.id }, { onOpenChat(task.id) })
        }
        feedback?.let { message ->
            item { Text(message, fontSize = 12.sp, color = onSurface, modifier = Modifier.padding(top = 6.dp)) }
        }
    }

    if (editRole) TeamRoleEditor(owner, role) { editRole = false }
    tree.firstOrNull { it.task.id == selectedTask }?.task?.let { task ->
        key(task.id) {
            TeamTaskDetail(
                task = task,
                canArchive = tree.none { it.task.owner == task.id && it.task.active },
                onClose = { selectedTask = null },
                onOpen = { selectedTask = null; onOpenChat(task.id) },
                onArchived = { selectedTask = null; feedback = it }
            )
        }
    }
    if (showApprovals) TeamApprovals(tree, onOpenChat) { showApprovals = false }
}

@Composable
private fun MissionSection(owner: String, mi: JarvisManagers.Mission) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column {
        BoardTitle("Mission")
        if (mi.isEmpty) {
            Text(
                stringResource(R.string.jarvis_command_center_no_mission_yet_ask_for_something_big),
                fontSize = 12.sp, color = onSurface.copy(alpha = 0.6f), lineHeight = 17.sp
            )
        } else {
            if (mi.doneWhen.isNotBlank()) Text(stringResource(R.string.jarvis_command_center_done_when, mi.doneWhen.toString()), fontSize = 12.sp, color = onSurface.copy(alpha = 0.7f), lineHeight = 16.sp)
            mi.steps.forEach { s ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                    val (mark, c) = when (s.status) {
                        "done" -> "✓" to Green
                        "doing" -> "▶" to Cyan
                        "blocked" -> "!" to Danger
                        else -> "○" to onSurface.copy(alpha = 0.4f)
                    }
                    Text(mark, fontSize = 12.sp, color = c, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${s.n}. ${s.text}", fontSize = 12.5.sp, color = onSurface.copy(alpha = if (s.status == "done") 0.55f else 0.9f), lineHeight = 17.sp)
                        if (s.note.isNotBlank()) Text(s.note, fontSize = 11.sp, color = onSurface.copy(alpha = 0.5f), lineHeight = 15.sp)
                    }
                }
            }
            if (mi.notes.isNotBlank()) Text(stringResource(R.string.jarvis_command_center_notes, mi.notes.toString()), fontSize = 11.5.sp, color = onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction("清除任务书", Danger) { JarvisManagers.clearMission(owner) }
                if (mi.status != "active") SmallAction("Resume", Cyan) { JarvisManagers.updateMission(owner, null, null, null, emptyList(), "active", null, null) }
                else SmallAction("Pause", Amber) { JarvisManagers.updateMission(owner, null, null, null, emptyList(), "paused", null, null) }
            }
        }
    }
}

@Composable
private fun teamText(english: String, chinese: String): String =
    if (LocalConfiguration.current.locales[0].language == "zh") chinese else english

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TeamRoleEditor(owner: String, existing: JarvisManagers.Team?, onClose: () -> Unit) {
    val vm = JarvisController.vm
    val enabled by vm.enabledAgents.collectAsState()
    val managers by JarvisManagers.managers.collectAsState()
    val projects by IdeDaemonManager.projects.collectAsState()
    val activeProject by IdeDaemonManager.activeProject.collectAsState()
    val catalog = managers[owner]?.teams.orEmpty()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var agent by remember { mutableStateOf(existing?.agent ?: enabled.firstOrNull() ?: AgentKind.AGY) }
    var model by remember { mutableStateOf(existing?.model.orEmpty()) }
    var effort by remember { mutableStateOf(existing?.effort.orEmpty()) }
    var project by remember { mutableStateOf(existing?.project.orEmpty()) }
    var instructions by remember { mutableStateOf(existing?.role.orEmpty()) }
    var children by remember { mutableStateOf(existing?.childRoles.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val models by when (agent) {
        AgentKind.AGY -> vm.agyBackend.enabledModels
        AgentKind.CLAUDE -> vm.claudeBackend.availableModels
        AgentKind.CODEX -> vm.codexBackend.availableModels
        AgentKind.OPENCODE -> vm.openCodeBackend.availableModels
    }.collectAsState()
    val efforts = JarvisController.effortLevelsFor(agent, model.ifBlank { null }).getOrDefault(emptyList())
    val defaultValue = teamText("Default", "默认")
    val duplicateName = teamText("A role with this name already exists.", "已存在同名岗位。")
    val invalidGraph = teamText("Delegation must reference existing roles and cannot form a cycle.", "委派只能引用已有岗位，且不能形成循环。")
    val inUse = teamText("Remove this role from the roles that delegate to it first.", "请先移除其他岗位对该岗位的委派授权。")
    val missingRole = teamText("This role was removed. Reopen the editor to add it again.", "该岗位已被移除，请重新打开编辑器添加。")
    val saveFailure = teamText("Could not save this role.", "无法保存岗位。")

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (existing == null) teamText("Add role", "新增岗位") else teamText("Edit role", "编辑岗位"))
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text(teamText("Role name", "岗位名称")) }, singleLine = true,
                    readOnly = existing != null, modifier = Modifier.fillMaxWidth()
                )
                val agents = AgentKind.entries.filter { it in enabled || it == existing?.agent }
                TeamChoice(
                    teamText("Provider", "服务商"), agent, agents.map { it to agentLabel(it.name) }, agentLabel(agent.name)
                ) {
                    if (it != agent) {
                        agent = it
                        model = ""
                        effort = ""
                    }
                }
                TeamChoice(
                    teamText("Model", "模型"), model,
                    listOf("" to defaultValue) + models.map { it.id to it.displayName.ifBlank { it.id } },
                    model.ifBlank { defaultValue }
                ) {
                    if (it != model) {
                        model = it
                        effort = ""
                    }
                }
                if (efforts.isNotEmpty() || effort.isNotBlank()) {
                    TeamChoice(
                        teamText("Reasoning effort", "推理强度"), effort,
                        listOf("" to defaultValue) + efforts.map { it to it }, effort.ifBlank { defaultValue }
                    ) { effort = it }
                }
                TeamChoice(
                    teamText("Project", "项目"), project,
                    listOf("" to teamText("Use active project", "使用当前项目")) +
                        (listOfNotNull(activeProject) + projects).distinctBy { it.path }.map { it.path to it.name.ifBlank { it.path } },
                    project.ifBlank { teamText("Use active project", "使用当前项目") }
                ) { project = it }
                OutlinedTextField(
                    value = instructions, onValueChange = { instructions = it },
                    label = { Text(teamText("Instructions", "职责说明")) }, minLines = 2, maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                val eligible = (catalog.map { it.name }.filterNot { it.equals(name.trim(), true) } + children)
                    .distinctBy { it.lowercase() }
                if (eligible.isNotEmpty()) {
                    Text(teamText("May delegate to", "允许委派给"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        eligible.forEach { child ->
                            val selected = children.any { it.equals(child, true) }
                            FilterChip(selected = selected, label = { Text(child) }, onClick = {
                                children = if (selected) children.filterNot { it.equals(child, true) } else children + child
                                error = null
                            })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (agent in enabled || agent == existing?.agent),
                onClick = {
                    val next = JarvisManagers.Team(name.trim(), agent, model, instructions.trim(), project, effort, children)
                    val problem = synchronized(JarvisManagers) {
                        val latest = JarvisManagers.of(owner).teams
                        val matching = latest.firstOrNull { it.name.equals(next.name, true) }
                        val graph = (latest.filterNot { it == matching } + next).associate { it.name to it.childRoles }
                        when {
                            existing == null && matching != null -> duplicateName
                            existing != null && matching == null -> missingRole
                            !JarvisOrchestrationRules.validRoleGraph(graph) -> invalidGraph
                            else -> runCatching { JarvisManagers.upsertTeam(owner, next) }.exceptionOrNull()?.let { it.message ?: saveFailure }
                        }
                    }
                    if (problem == null) onClose() else error = problem.ifBlank { saveFailure }
                }
            ) { Text(teamText("Save", "保存")) }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = {
                        val problem = synchronized(JarvisManagers) {
                            val latest = JarvisManagers.of(owner).teams
                            val referenced = latest.any { profile -> profile.childRoles.any { it.equals(existing.name, true) } }
                            when {
                                referenced -> inUse
                                JarvisManagers.removeTeam(owner, existing.name) -> null
                                else -> missingRole
                            }
                        }
                        if (problem == null) onClose() else error = problem
                    }) { Text(teamText("Remove role", "移除岗位"), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onClose) { Text(teamText("Cancel", "取消")) }
            }
        }
    )
}

@Composable
private fun <T> TeamChoice(label: String, selected: T, choices: List<Pair<T, String>>, fallback: String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            TextButton(enabled = choices.isNotEmpty(), onClick = { open = true }) {
                Text(choices.firstOrNull { it.first == selected }?.second ?: fallback)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { (value, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(value) })
                }
            }
        }
    }
}

private data class TeamTaskNode(val task: JarvisTasks.Task, val depth: Int)

private fun teamTaskTree(owner: String, tasks: List<JarvisTasks.Task>): List<TeamTaskNode> {
    val children = tasks.groupBy { JarvisManagers.ownerOrVoice(it.owner) }
    val seen = hashSetOf(owner)
    val queue = ArrayDeque<TeamTaskNode>()
    fun push(parent: String, depth: Int) {
        children[parent].orEmpty().asReversed().forEach { queue.addLast(TeamTaskNode(it, depth)) }
    }
    push(owner, 0)
    return buildList {
        while (queue.isNotEmpty()) {
            val node = queue.removeLast()
            if (seen.add(node.task.id)) {
                add(node)
                push(node.task.id, node.depth + 1)
            }
        }
    }
}

@Composable
private fun TeamTaskRow(task: JarvisTasks.Task, depth: Int, onDetails: () -> Unit, onOpen: () -> Unit) {
    val color = taskColor(task.state)
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(7) * 12).dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onDetails).padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(task.title, fontSize = 12.5.sp, color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(task.team, agentLabel(task.agent.name), task.state.label, if (task.archived) teamText("Archived", "已归档") else "", age(task.createdAt))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 10.5.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconMini(Icons.AutoMirrored.Filled.OpenInNew, teamText("Open chat", "打开会话"), onSurface.copy(alpha = 0.6f), onOpen)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TeamTaskDetail(task: JarvisTasks.Task, canArchive: Boolean, onClose: () -> Unit, onOpen: () -> Unit, onArchived: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var showDiff by remember { mutableStateOf(false) }
    val workspace = task.workspace
    val failure = teamText("Could not complete the action.", "无法完成操作。")
    val archived = teamText("Task and its child task history were archived.", "任务及其子任务历史已归档。")
    val restored = teamText("Task history restored to the task tree.", "任务历史已恢复到任务树。")
    val reviewGoal = teamText("Review the saved goal before you send it.", "发送前请检查已保存的任务目标。")
    fun runAction(action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                feedback = action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                feedback = error.message ?: failure
            } finally {
                busy = false
            }
        }
    }
    fun send(interrupt: Boolean) {
        val text = message.trim()
        runAction {
            JarvisTasks.reply(task.id, text, interrupt).also {
                if (it.startsWith("Sent") || it.startsWith("Queued")) message = ""
            }
        }
    }

    if (showDiff && workspace != null) {
        TeamWorktreeDiff(workspace) { showDiff = false }
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(task.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.state.label, color = taskColor(task.state))
                if (task.archived) Text(teamText("Archived", "已归档"), style = MaterialTheme.typography.labelSmall)
                if (task.note.isNotBlank()) SelectionContainer { Text(task.note) }
                if (workspace != null) {
                    TextButton(enabled = !busy, onClick = { showDiff = true }) {
                        Text(teamText("Review Git changes", "审查 Git 变更"))
                    }
                }
                if (task.goal.isNotBlank()) {
                    Text(teamText("Goal", "目标"), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(task.goal) }
                }
                if (task.result.isNotBlank()) {
                    Text(
                        if (task.resultChars > task.result.length) teamText("Result preview", "结果摘要") else teamText("Result", "结果"),
                        style = MaterialTheme.typography.labelLarge
                    )
                    SelectionContainer { Text(task.result) }
                }
                OutlinedTextField(
                    value = message, onValueChange = { message = it },
                    label = { Text(teamText("Follow up", "追问")) }, minLines = 2, maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow {
                    TextButton(enabled = !busy && message.isNotBlank(), onClick = { send(interrupt = false) }) {
                        Text(teamText("Send", "发送"))
                    }
                    if (task.active) {
                        TextButton(enabled = !busy && message.isNotBlank(), onClick = { send(interrupt = true) }) {
                            Text(teamText("Interrupt and send", "打断并发送"))
                        }
                        TextButton(enabled = !busy, onClick = { runAction { JarvisTasks.stop(task.id) } }) {
                            Text(teamText("Stop", "停止"), color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        if (task.goal.isNotBlank()) {
                            TextButton(enabled = !busy, onClick = {
                                if (task.goal.length >= 600) {
                                    message = task.goal
                                    feedback = reviewGoal
                                } else {
                                    runAction { JarvisTasks.reply(task.id, task.goal, interrupt = false) }
                                }
                            }) {
                                Text(teamText("Restart with goal", "按原目标重启"))
                            }
                        }
                        if (!task.archived && canArchive) {
                            TextButton(enabled = !busy, onClick = {
                                if (JarvisTasks.archive(task.id)) onArchived(archived) else feedback = failure
                            }) { Text(teamText("Archive task", "归档任务")) }
                        } else if (task.archived) {
                            TextButton(enabled = !busy, onClick = {
                                if (JarvisTasks.unarchive(task.id)) onArchived(restored) else feedback = failure
                            }) { Text(teamText("Restore task", "恢复任务")) }
                        }
                    }
                }
                feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onOpen) { Text(teamText("Open chat", "打开会话")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(teamText("Close", "关闭")) } }
    )
}

@Composable
private fun TeamWorktreeDiff(workspace: JarvisWorktreeManager.Workspace, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var revision by remember { mutableStateOf(0) }
    var diff by remember(workspace, revision) { mutableStateOf<JarvisWorktreeManager.Diff?>(null) }
    var failure by remember(workspace, revision) { mutableStateOf<String?>(null) }
    val loadError = teamText("Could not read the Git worktree.", "无法读取 Git worktree。")
    LaunchedEffect(workspace, revision) {
        try {
            diff = JarvisWorktreeManager.diff(context, workspace)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = error.message ?: loadError
        }
    }

    val changes = diff
    val blocks = remember(changes?.patch) { patchPreviewBlocks(changes?.patch.orEmpty()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(teamText("Git worktree changes", "Git worktree 变更")) },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    SelectionContainer {
                        Column {
                            Text(workspace.branch, style = MaterialTheme.typography.labelLarge)
                            Text(workspace.checkoutRoot, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val problem = failure
                when {
                    problem != null -> item { Text(problem, color = MaterialTheme.colorScheme.error) }
                    changes == null -> item { Text(teamText("Reading Git changes…", "正在读取 Git 变更…")) }
                    else -> {
                        if (blocks.isEmpty() && changes.untrackedFiles.isEmpty()) {
                            item { Text(teamText("No changes from the base commit.", "相对于基准提交没有变更。")) }
                        }
                        if (changes.untrackedFiles.isNotEmpty()) {
                            item { BoardTitle(teamText("Untracked files", "未跟踪文件")) }
                            items(changes.untrackedFiles) { path ->
                                SelectionContainer { Text(path, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if (blocks.isNotEmpty()) {
                            item { BoardTitle(teamText("Patch", "补丁")) }
                            if (changes.truncated) item {
                                Text(teamText("Preview limit reached. The full changes remain in the worktree.", "预览已达上限，完整变更仍保留在工作树中。"))
                            }
                            items(blocks) { block ->
                                SelectionContainer {
                                    Text(block, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(teamText("Back to task", "返回任务")) } },
        dismissButton = {
            TextButton(enabled = changes != null || failure != null, onClick = { revision++ }) {
                Text(teamText("Refresh", "刷新"))
            }
        }
    )
}

/** Preserve line boundaries between lazy rows except when a single line exceeds the layout limit. */
private fun patchPreviewBlocks(patch: String): List<String> = buildList {
    var start = 0
    while (start < patch.length) {
        var end = (start + 4096).coerceAtMost(patch.length)
        if (end < patch.length) {
            val newline = patch.lastIndexOf('\n', startIndex = end - 1)
            if (newline >= start) end = newline + 1
            else if (patch[end - 1].isHighSurrogate()) end--
        }
        add(patch.substring(start, end).removeSuffix("\n"))
        start = end
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TeamApprovals(tree: List<TeamTaskNode>, onOpenChat: (String) -> Unit, onClose: () -> Unit) {
    val taskById = tree.associate { it.task.id to it.task }
    fun pending() = tree.map { JarvisManagers.ownerOrVoice(it.task.owner) }.distinct().flatMap { owner ->
        JarvisController.pendingFor(owner).filter { it.chatId in taskById }.map { owner to it }
    }
    // Task state changes cause the board to recompose. Selections refresh immediately and can also be refreshed manually.
    var revision by remember { mutableStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val approvals = remember(tree, revision) { pending() }
    val expired = teamText("This request is no longer pending.", "该请求已不再等待审批。")
    val refusal = teamText("The user denied this request.", "用户拒绝了该请求。")
    fun decide(owner: String, perm: JarvisController.Perm, allow: Boolean) {
        feedback = if (JarvisController.pendingFor(owner).any { it.key == perm.key && it.chatId == perm.chatId }) {
            JarvisController.resolve(perm.key, approve = allow, all = false, reason = if (allow) null else refusal, caller = owner)
        } else expired
        revision++
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(teamText("Pending task approvals", "待审批任务")) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (approvals.isEmpty()) Text(teamText("No approvals are pending.", "没有待审批请求。"))
                approvals.forEach { (owner, perm) ->
                    key(perm.key) {
                        Column {
                            Text(taskById.getValue(perm.chatId).title, style = MaterialTheme.typography.titleSmall)
                            if (perm.risk == JarvisController.Risk.RISKY) {
                                Text(teamText("Risky operation", "风险操作"), color = MaterialTheme.colorScheme.error)
                            }
                            SelectionContainer { Text(perm.summary) }
                            FlowRow {
                                TextButton(onClick = { decide(owner, perm, true) }) { Text(teamText("Allow once", "允许一次")) }
                                TextButton(onClick = { decide(owner, perm, false) }) { Text(teamText("Deny", "拒绝")) }
                                TextButton(onClick = { onClose(); onOpenChat(perm.chatId) }) { Text(teamText("Open chat", "打开会话")) }
                            }
                        }
                    }
                }
                feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(teamText("Close", "关闭")) } },
        dismissButton = { TextButton(onClick = { revision++ }) { Text(teamText("Refresh", "刷新")) } }
    )
}

@Composable
private fun BoardTitle(text: String) {
    Text(
        text.uppercase(), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp, color = Cyan.copy(alpha = 0.9f),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun SmallAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun IconMini(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(16.dp))
    }
}

// ------------------------------------------------------------------------------------------------ the event card

private fun eventColor(kind: String): Color = when (kind) {
    "task_done" -> Green
    "task_failed" -> Danger
    "task_stalled" -> Color(0xFFF97316)
    "task_approval", "task_question", "autopilot_paused" -> Amber
    "nudge" -> Violet
    else -> Cyan
}

private fun eventWord(kind: String): String = when (kind) {
    "task_done" -> "Finished"
    "task_failed" -> "Failed"
    "task_stalled" -> "无进展"
    "task_approval" -> "需要审批"
    "task_question" -> "Asks a question"
    "nudge" -> "自动驾驶：继续"
    "autopilot_paused" -> "自动驾驶已暂停"
    else -> kind
}

/**
 * The news Jarvis got from its teams, as it shows in the chat: one row per event (who, what happened), and on tap what
 * each sub-agent reported. The instructions that went to the model with it are never shown.
 */
@Composable
fun JarvisEventCard(raw: String, onOpenChat: (String) -> Unit) {
    val events = remember(raw) { JarvisEvents.parse(raw).orEmpty() }
    if (events.isEmpty()) return
    var open by remember(raw) { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val hasDetails = events.any { it.text.isNotBlank() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Cyan.copy(alpha = 0.05f))
            .border(1.dp, Cyan.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .clickable(enabled = hasDetails) { open = !open }
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JarvisBotIcon(18.dp, Cyan)
            Spacer(Modifier.width(7.dp))
            Text(
                if (events.size == 1) stringResource(R.string.jarvis_command_center_team_update) else stringResource(R.string.jarvis_command_center_team_updates, events.size.toString()),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Cyan, modifier = Modifier.weight(1f)
            )
            if (hasDetails) Text(if (open) stringResource(R.string.jarvis_command_center_hide_reports) else stringResource(R.string.jarvis_command_center_show_reports), fontSize = 11.sp, color = onSurface.copy(alpha = 0.5f))
        }
        events.forEach { e ->
            val c = eventColor(e.kind)
            Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        e.title.ifBlank { "Mission" } + listOfNotNull(e.team.takeIf { it.isNotBlank() }, e.agent.takeIf { it.isNotBlank() }?.let { agentLabel(it) })
                            .joinToString(" · ").let { if (it.isBlank()) "" else "  ·  $it" },
                        fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(eventWord(e.kind), fontSize = 11.sp, color = c)
                    if (open && e.text.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                e.text.take(4000), fontSize = 12.sp, lineHeight = 17.sp, color = onSurface.copy(alpha = 0.78f),
                                modifier = Modifier.padding(top = 4.dp).widthIn(max = 640.dp)
                            )
                        }
                    }
                }
                if (e.taskId.isNotBlank() && JarvisTasks.find(e.taskId) != null) {
                    IconMini(Icons.AutoMirrored.Filled.OpenInNew, "打开任务的聊天", onSurface.copy(alpha = 0.55f)) { onOpenChat(e.taskId) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ a fresh Jarvis chat

/** What a new Jarvis text chat shows before the first message: what it does, and a few starters (tap one to edit it). */
@Composable
fun JarvisChatWelcome(onExample: (String) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp)) {
        JarvisBotBadge(72.dp, Cyan, animated = true)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.jarvis_command_center_jarvis_command_center), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.jarvis_command_center_give_me_a_big_job_i_write),
            fontSize = 13.5.sp, lineHeight = 19.sp, color = onSurface.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        listOf(
            "在我当前项目的设置页里加一个深色模式开关，让审查团队检查并运行测试。不跑通就别停。",
            "组建两个团队：构建者（Claude）负责实现，审查者（Antigravity）负责审查和测试。然后修复此项目中所有失败的测试。",
            "排查应用启动慢的原因，并行尝试三个最有希望的修复方案，并告诉我哪个效果最好。"
        ).forEach { ex ->
            Text(
                ex, fontSize = 12.5.sp, lineHeight = 17.sp, color = onSurface.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Cyan.copy(alpha = 0.06f))
                    .border(1.dp, Cyan.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                    .clickable { onExample(ex) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}
