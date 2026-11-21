package ai.antigem.ui.settings

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.context.GlobalContext
import ai.antigem.ui.components.bouncyVerticalScroll

/** The row on Settings → Agents that opens [GlobalContextScreen]. */
@Composable
fun GlobalContextEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val builtInOn by GlobalContext.builtInEnabled.collectAsState()
    val entries by GlobalContext.entries.collectAsState()
    val own = entries.count { it.enabled }
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
            ) { Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.global_context_screen_global_context), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.global_context_screen_built_in_of_yours_on, (if (builtInOn) "on" else "off").toString(), own.toString()),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Settings → Agents → Global context: the app's built-in context (read-only, can be turned off) and the user's own
 * entries, each sent in the first message of a chat or with every message, and to Jarvis only when allowed.
 */
@Composable
fun GlobalContextScreen() {
    val builtInOn by GlobalContext.builtInEnabled.collectAsState()
    val entries by GlobalContext.entries.collectAsState()
    var showBuiltIn by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GlobalContext.Entry?>(null) }
    val builtIn = remember { GlobalContext.builtInText() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "会隐藏地添加在你的消息前面，对所有智能体生效（聊天中不显示）。首条消息上下文每个聊天发送" +
                "一次，仅在内容变化时再发；每条消息项则随每条消息发送。",
            fontSize = 12.5.sp, lineHeight = 17.sp, color = muted
        )
        Spacer(Modifier.height(16.dp))

        // ----- built-in -----
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.global_context_screen_built_in_context), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, null, tint = muted, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.global_context_screen_by_antigem_read_only_first_message), fontSize = 12.sp, color = muted)
                        }
                    }
                    Switch(checked = builtInOn, onCheckedChange = { GlobalContext.setBuiltInEnabled(it) })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "告知智能体它运行在哪里（" + when (GlobalContext.runPlace) {
                        GlobalContext.RunPlace.ANTIGEM -> "这台手机上 AntiGem 的内置环境：用 \$TMPDIR 而非 /tmp；用 pkg 而非 sudo"
                        GlobalContext.RunPlace.CHROOT -> "这台手机上的 Ubuntu chroot：用 apt，sudo 就是整台手机的 root"
                        GlobalContext.RunPlace.SERVER -> "你的服务器"
                        GlobalContext.RunPlace.PC -> "你的电脑，通过「连接到 PC」"
                    } + "），不要自行使用 sudo、su 或 root（只在你要求时），以及如何在此聊天中显示图片、视频和文件。" +
                        "Jarvis 及其发起的聊天也会收到。",
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(4.dp))
                Text("「你运行在哪里」这部分跟随 设置 → 智能体运行在哪里。", fontSize = 11.5.sp, color = muted)
                TextButton(onClick = { showBuiltIn = !showBuiltIn }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (showBuiltIn) stringResource(R.string.global_context_screen_hide_the_text) else stringResource(R.string.global_context_screen_show_exactly_what_is_sent))
                }
                if (showBuiltIn) {
                    SelectionContainer {
                        Text(
                            builtIn,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(10.dp)
                        )
                    }
                }
            }
        }

        // ----- the user's own -----
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.global_context_screen_your_context), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.global_context_screen_your_own_instructions_for_every_chat), fontSize = 12.sp, color = muted)
            }
            FilledTonalButton(onClick = { editing = GlobalContext.Entry(title = "", text = "") }) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.browser_screen_add))
            }
        }
        Spacer(Modifier.height(10.dp))
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.global_context_screen_nothing_yet_examples_reply_in_hindi_never),
                fontSize = 12.5.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        entries.forEach { e ->
            EntryCard(e, onToggle = { GlobalContext.upsert(e.copy(enabled = it)) }, onClick = { editing = e })
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { e ->
        EntryEditor(
            entry = e,
            isNew = entries.none { it.id == e.id },
            onSave = { GlobalContext.upsert(it); editing = null },
            onDelete = { GlobalContext.delete(e.id); editing = null },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun EntryCard(e: GlobalContext.Entry, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title.ifBlank { "Untitled" }, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    e.text.trim(), fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(if (e.everyMessage) "Every message" else "First message", highlight = e.everyMessage)
                    if (e.forJarvis) Chip("Jarvis too", highlight = false)
                }
            }
            Switch(checked = e.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun Chip(label: String, highlight: Boolean) {
    val c = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Text(
        label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = c,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun EntryEditor(
    entry: GlobalContext.Entry,
    isNew: Boolean,
    onSave: (GlobalContext.Entry) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(entry.title) }
    var text by remember { mutableStateOf(entry.text) }
    var everyMessage by remember { mutableStateOf(entry.everyMessage) }
    var forJarvis by remember { mutableStateOf(entry.forJarvis) }
    var confirmDelete by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp),
        title = { Text(if (isNew) stringResource(R.string.global_context_screen_new_context) else stringResource(R.string.global_context_screen_edit_context), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text(stringResource(R.string.global_context_screen_title)) }, placeholder = { Text(stringResource(R.string.global_context_screen_e_g_language)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(stringResource(R.string.global_context_screen_what_the_agent_should_know_or_do)) },
                    minLines = 5, maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.global_context_screen_when_to_send), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                WhenOption("First message of each chat", "Enough for most things. Sent again when you change it.", !everyMessage) { everyMessage = false }
                WhenOption("Every message", "For rules the agent must never forget, even in long chats. Costs a few tokens each time.", everyMessage) { everyMessage = true }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { forJarvis = !forJarvis }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.global_context_screen_also_for_jarvis), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Jarvis 有自己严格的规则（会说话、不等待任务），因此面向普通聊天的说明可能与其冲突。" +
                                "开启后，Jarvis 收到的内容会标注“冲突时以 Jarvis 规则为准”。Jarvis 为任务发起的聊天也会收到。",
                            fontSize = 11.5.sp, lineHeight = 15.sp, color = muted
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = forJarvis, onCheckedChange = { forJarvis = it })
                }
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = { onSave(entry.copy(title = title.trim(), text = text.trim(), everyMessage = everyMessage, forJarvis = forJarvis)) }
            ) { Text(stringResource(R.string.claude_providers_page_save)) }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.browser_screen_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_cancel)) }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.run_screen_delete, (entry.title.ifBlank { "Untitled" }).toString())) },
            text = { Text(stringResource(R.string.global_context_screen_new_messages_will_no_longer_carry_it)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.browser_screen_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
}

@Composable
private fun WhenOption(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
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
