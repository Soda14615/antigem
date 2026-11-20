package ai.antigem.ui.settings

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.memory.AgentMemory
import ai.antigem.data.memory.MemoryBridgeClient
import ai.antigem.domain.model.AgentKind
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The row on Settings → Agents that opens [AgentMemoryScreen]. */
@Composable
fun AgentMemoryEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val enabled by AgentMemory.enabled.collectAsState()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Memory, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.agent_memory_screen_agent_memory_history), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.agent_memory_screen_memories_and_past_chats_every_agent_can, (if (enabled) "On" else "Off").toString()),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Settings → Agents → Agent memory & history: the switches of [AgentMemory] and the memories the bridge keeps
 * (list, search, add, edit, delete), plus a read-only look at the past chats a search finds. Chat history only
 * exists with memory on; [onOpenHistory] opens the saved chats ([AgentHistoryScreen]).
 */
@Composable
fun AgentMemoryScreen(onOpenHistory: (() -> Unit)? = null) {
    val enabled by AgentMemory.enabled.collectAsState()
    val agents by AgentMemory.agents.collectAsState()
    val listLimit by AgentMemory.listLimit.collectAsState()
    val saveHistory by AgentMemory.saveHistory.collectAsState()
    val changes by AgentMemory.changes.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var root by remember { mutableStateOf("") }
    var memories by remember { mutableStateOf<List<MemoryBridgeClient.Memory>>(emptyList()) }

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<MemoryBridgeClient.SearchResult?>(null) }

    // A memory with a blank id is a new one.
    var editing by remember { mutableStateOf<MemoryBridgeClient.Memory?>(null) }

    LaunchedEffect(changes, reload) {
        loading = true
        val r = MemoryBridgeClient.list()
        ensureActive()
        r.onSuccess { (folder, list) -> root = folder; memories = list; loadError = null }
            .onFailure { loadError = it.message ?: "Could not reach the bridge." }
        loading = false
    }

    LaunchedEffect(query, changes) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = null; searchError = null; searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        val r = MemoryBridgeClient.search(q)
        ensureActive()
        r.onSuccess { results = it; searchError = null }
            .onFailure { searchError = it.message ?: "Search failed." }
        searching = false
    }

    val knownProjects = remember(memories) {
        memories.filter { it.isProject && it.project.isNotBlank() }.map { it.project }.distinct()
            .sortedBy { it.trimEnd('/').substringAfterLast('/').lowercase() }
    }

    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
        // ----- master switch -----
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.agent_memory_screen_agent_memory), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.agent_memory_screen_shared_by_every_agent), fontSize = 12.sp, color = muted)
                    }
                    Switch(checked = enabled, onCheckedChange = { AgentMemory.setEnabled(it) })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "智能体会在回复中保存简短记忆；聊天里会显示一个可撤销的标签。新聊天会被告知" +
                        "“始终”类记忆和最相关的其他记忆，智能体也能检索过往聊天。",
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.agent_memory_screen_stored_by_the_bridge_next_to_the),
                    fontSize = 11.5.sp, lineHeight = 15.sp, color = muted
                )
            }
        }

        if (enabled) {
            // ----- per agent -----
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.agent_memory_screen_use_with), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.agent_memory_screen_a_weak_model_may_not_follow_the),
                fontSize = 12.sp, lineHeight = 16.sp, color = muted
            )
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    AgentKind.entries.forEach { agent ->
                        val on = agent in agents
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { AgentMemory.setAgent(agent, !on) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(agent.label(), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Switch(checked = on, onCheckedChange = { AgentMemory.setAgent(agent, it) })
                        }
                    }
                }
            }

            // ----- how many are listed -----
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.agent_memory_screen_memories_listed_per_chat), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.agent_memory_screen_memories_set_to_always_are_always_included),
                            fontSize = 12.sp, lineHeight = 16.sp, color = muted
                        )
                    }
                    IconButton(onClick = { AgentMemory.setListLimit(listLimit - 1) }, enabled = listLimit > 0) {
                        Icon(Icons.Outlined.Remove, stringResource(R.string.agent_memory_screen_fewer))
                    }
                    Text(
                        "$listLimit", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 26.dp)
                    )
                    IconButton(onClick = { AgentMemory.setListLimit(listLimit + 1) }, enabled = listLimit < AgentMemory.MAX_LIST_LIMIT) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.text_selection_more))
                    }
                }
            }

            // ----- chat history -----
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.agent_memory_screen_save_chat_history), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "每个聊天的精简副本（你的消息与回复，不含工具输出），当您提到之前的工作时" +
                                "智能体可以读取。",
                            fontSize = 12.sp, lineHeight = 16.sp, color = muted
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = saveHistory, onCheckedChange = { AgentMemory.setSaveHistory(it) })
                }
            }
            if (onOpenHistory != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    onClick = onOpenHistory,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.agent_memory_screen_saved_chats), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.agent_memory_screen_read_edit_or_remove_the_chat_history), fontSize = 12.sp, lineHeight = 16.sp, color = muted)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted)
                    }
                }
            }
        }

        // ----- the memories -----
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.agent_memory_screen_memories), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (enabled) stringResource(R.string.agent_memory_screen_tap_one_to_edit_it) else stringResource(R.string.agent_memory_screen_kept_while_memory_is_off_agents_do),
                    fontSize = 12.sp, color = muted
                )
            }
            if (loading || searching) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
            FilledTonalButton(onClick = { editing = MemoryBridgeClient.Memory() }) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.browser_screen_add))
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.agent_memory_screen_search_memories_and_past_chats)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.custom_system_prompt_dialog_clear)) } }) else null,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))

        if (query.isNotBlank()) {
            searchError?.let { ErrorText(it) }
            results?.let { r ->
                // no memory, no history: past chats only show while memory is on
                val chats = if (enabled) r.history else emptyList()
                if (r.memories.isEmpty() && chats.isEmpty() && !searching) {
                    Text(stringResource(R.string.agent_memory_screen_nothing_found), fontSize = 12.5.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp))
                }
                if (r.memories.isNotEmpty()) {
                    GroupHeader("Memories", null)
                    r.memories.forEach { m ->
                        MemoryCard(m, onClick = { editing = m })
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (chats.isNotEmpty()) {
                    GroupHeader("Past chats", "Open Saved chats to edit or remove them")
                    chats.forEach { h ->
                        HistoryCard(h)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        } else {
            loadError?.let { err ->
                ErrorText(err)
                TextButton(onClick = { reload++ }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.checkpoint_restore_sheet_try_again)) }
            }
            if (memories.isEmpty() && !loading && loadError == null) {
                Text(
                    stringResource(R.string.agent_memory_screen_no_memories_yet_agents_save_them_as),
                    fontSize = 12.5.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            val always = memories.filter { it.isAlways }.sortedByDescending { it.updated }
            val global = memories.filter { !it.isAlways && !it.isProject }.sortedByDescending { it.updated }
            val projects = memories.filter { !it.isAlways && it.isProject }
                .groupBy { it.projectDir.ifBlank { it.project } }
                .values
                .map { group -> group.sortedByDescending { it.updated } }
                .sortedBy { it.first().projectName.lowercase() }

            if (always.isNotEmpty()) {
                GroupHeader("Always", "Every chat gets these in full")
                always.forEach { m ->
                    MemoryCard(m, onClick = { editing = m })
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (global.isNotEmpty()) {
                GroupHeader("Global", "Listed in any project when relevant")
                global.forEach { m ->
                    MemoryCard(m, onClick = { editing = m })
                    Spacer(Modifier.height(8.dp))
                }
            }
            projects.forEach { group ->
                val first = group.first()
                GroupHeader("Project: ${first.projectName.ifBlank { "unknown" }}", first.project.ifBlank { null })
                group.forEach { m ->
                    MemoryCard(m, onClick = { editing = m })
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (root.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.agent_memory_screen_stored_by_the_bridge_in, root.toString()), fontSize = 11.5.sp, lineHeight = 15.sp, color = muted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { m ->
        MemoryEditor(
            memory = m,
            knownProjects = knownProjects,
            onDone = { editing = null },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun GroupHeader(title: String, subtitle: String?) {
    Spacer(Modifier.height(14.dp))
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (!subtitle.isNullOrBlank()) {
        Text(
            subtitle, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun MemoryCard(m: MemoryBridgeClient.Memory, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(m.name.ifBlank { "Untitled" }, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.description.isNotBlank()) {
                Text(
                    m.description.trim(), fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = muted
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MemoryChip(if (m.isAlways) "Always" else "Passive", highlight = m.isAlways)
                MemoryChip(if (m.isProject) m.projectName.ifBlank { "One project" } else "All projects", highlight = false)
            }
            val meta = listOfNotNull(formatDay(m.updated), savedBy(m.agent)).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(meta, fontSize = 11.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HistoryCard(h: MemoryBridgeClient.HistoryHit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(h.title.ifBlank { "Untitled chat" }, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                h.agent.takeIf { it.isNotBlank() }?.let { agentLabel(it) },
                h.project.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() },
                formatDay(h.updated)
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 11.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.snippet.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(h.snippet.trim(), fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MemoryChip(label: String, highlight: Boolean) {
    val c = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Text(
        label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditor(
    memory: MemoryBridgeClient.Memory,
    knownProjects: List<String>,
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    val isNew = memory.id.isBlank()
    var name by remember { mutableStateOf(memory.name) }
    var description by remember { mutableStateOf(memory.description) }
    var text by remember { mutableStateOf(memory.text) }
    var always by remember { mutableStateOf(memory.isAlways) }
    var oneProject by remember { mutableStateOf(memory.isProject) }
    var project by remember { mutableStateOf(memory.project) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp),
        title = { Text(if (isNew) stringResource(R.string.agent_memory_screen_new_memory) else stringResource(R.string.agent_memory_screen_edit_memory), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.browser_screen_name)) }, placeholder = { Text("short-name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, singleLine = true,
                    label = { Text(stringResource(R.string.agent_memory_screen_description)) }, placeholder = { Text(stringResource(R.string.agent_memory_screen_one_line_what_it_is_about)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(stringResource(R.string.agent_memory_screen_what_the_agents_should_remember)) },
                    minLines = 6, maxLines = 14,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.agent_memory_screen_mode), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                MemoryOption("Always", "Every chat gets it in full.", always) { always = true }
                MemoryOption("Passive", "Listed when relevant; the agent reads it when it needs it.", !always) { always = false }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.agent_memory_screen_scope), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                MemoryOption("All projects", "Any chat can get it.", !oneProject) { oneProject = false }
                MemoryOption("One project", "Only chats in that project folder.", oneProject) { oneProject = true }
                if (oneProject) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = project, onValueChange = { project = it }, singleLine = true,
                        label = { Text(stringResource(R.string.agent_memory_screen_project_folder)) }, placeholder = { Text("/path/to/project") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (knownProjects.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            knownProjects.forEach { p ->
                                FilterChip(
                                    selected = p == project.trim(),
                                    onClick = { project = p },
                                    label = {
                                        Text(
                                            p.trimEnd('/').substringAfterLast('/').ifBlank { p },
                                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && name.isNotBlank() && text.isNotBlank() && (!oneProject || project.isNotBlank()),
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        MemoryBridgeClient.save(
                            id = memory.id.ifBlank { null },
                            file = name.trim(),
                            scope = if (oneProject) "project" else "global",
                            project = if (oneProject) project.trim() else null,
                            mode = if (always) "always" else "passive",
                            description = description.trim(),
                            text = text.trim()
                        ).onSuccess {
                            AgentMemory.changed()
                            onDone()
                        }.onFailure {
                            error = it.message ?: "Could not save the memory."
                            saving = false
                        }
                    }
                }
            ) { Text(if (saving) stringResource(R.string.claude_providers_page_saving) else stringResource(R.string.claude_providers_page_save)) }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !saving) {
                        Text(stringResource(R.string.browser_screen_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.accounts_ui_cancel)) }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.run_screen_delete, (memory.name.ifBlank { "Untitled" }).toString())) },
            text = { Text(stringResource(R.string.agent_memory_screen_agents_will_no_longer_get_it_chats)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    saving = true
                    error = null
                    scope.launch {
                        MemoryBridgeClient.delete(memory.id).onSuccess {
                            AgentMemory.changed()
                            onDone()
                        }.onFailure {
                            error = it.message ?: "Could not delete the memory."
                            saving = false
                        }
                    }
                }) { Text(stringResource(R.string.browser_screen_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
}

@Composable
private fun MemoryOption(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Claude Code" for "claude", "Antigravity" for "agy", …; the raw name for anything else. */
private fun agentLabel(agent: String): String =
    AgentKind.entries.firstOrNull { it.name.equals(agent, ignoreCase = true) }?.label() ?: agent

private fun savedBy(agent: String): String? = when {
    agent.isBlank() -> null
    agent.equals("user", ignoreCase = true) -> "Added by you"
    else -> "Saved by ${agentLabel(agent)}"
}

/** "18 Nov 2026", or null when the bridge sent no time. */
private fun formatDay(ms: Long): String? =
    if (ms <= 0) null else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
