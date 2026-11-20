package ai.antigem.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.R
import ai.antigem.data.egm.EgmManager
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll

/** The EGM row of Settings → Agents, with what is installed. */
@Composable
fun EgmEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { EgmManager.init(context) }
    val state by EgmManager.state.collectAsState()
    val settings by EgmManager.settings.collectAsState()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.tertiary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("EGM · Elephant-Goldfish", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        state.version == null -> stringResource(R.string.upgrade_egm_not_installed)
                        !settings.enabled -> "${state.label}  ·  " + stringResource(R.string.upgrade_egm_commands_off)
                        else -> "${state.label}  ·  " + EgmManager.Command.entries.filter { EgmManager.available(it, state) }.joinToString { "/${it.token}" }
                    },
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Settings → Agents → EGM: installs Google's Elephant-Goldfish Model ([EgmManager]) and sets how its slash commands
 * run: which reviewers, how many at once, re-reviews, where the documents go, who the Elephant is in a Jarvis chat,
 * and the instructions each command sends.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EgmScreen(cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        EgmManager.init(context)
        EgmManager.refresh()
        EgmManager.checkForUpdate()
    }
    val state by EgmManager.state.collectAsState()
    val settings by EgmManager.settings.collectAsState()
    val busy = state.busy
    var confirmRemove by remember { mutableStateOf(false) }
    // true: the Jarvis instructions, false: the normal-chat ones
    var editTemplate by remember { mutableStateOf<Boolean?>(null) }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EgmCard(stringResource(R.string.upgrade_egm_what_does), cardBg, cardBorder) {
            Hint(stringResource(R.string.upgrade_egm_overview))
            Hint(stringResource(R.string.upgrade_egm_token_note))
            TextButton(onClick = { egmOpenUrl(context, EgmManager.HOMEPAGE) }, contentPadding = PaddingValues(0.dp)) {
                Text("github.com/google/elephant-goldfish-model", fontSize = 12.5.sp)
            }
        }

        state.message?.let { msg ->
            Surface(shape = RoundedCornerShape(12.dp), color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = EgmManager::clearMessage) { Icon(Icons.Default.Close, stringResource(R.string.upgrade_egm_dismiss), Modifier.size(18.dp)) }
                }
            }
        }

        StepCard(
            1, stringResource(R.string.upgrade_egm_install),
            when {
                state.hasUpdate -> stringResource(R.string.upgrade_egm_update)
                else -> state.label
            },
            cardBg, cardBorder,
            badgeColor = if (state.hasUpdate) Amber else QuotaGreen
        ) {
            Hint(stringResource(R.string.upgrade_egm_install_hint))
            EgmManager.Source.entries.forEach { src ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        if (settings.source != src) {
                            EgmManager.update { it.copy(source = src) }
                            EgmManager.checkForUpdate()
                        }
                    }
                ) {
                    RadioButton(selected = settings.source == src, onClick = null, enabled = !busy, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(egmSourceLabel(src), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Text(egmSourceDescription(src), fontSize = 11.5.sp, color = muted)
                    }
                }
            }
            // installed from the other source: one tap installs from the chosen one instead
            val switching = state.version != null && state.installedSource != settings.source
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    state.version == null -> Button(enabled = !busy, onClick = EgmManager::install) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.upgrade_egm_install_button))
                    }
                    switching -> Button(enabled = !busy, onClick = EgmManager::install) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (settings.source == EgmManager.Source.MAIN) stringResource(R.string.upgrade_egm_install_dev) else stringResource(R.string.upgrade_egm_install_release))
                    }
                    state.hasUpdate -> Button(enabled = !busy, onClick = EgmManager::install) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (state.installedSource == EgmManager.Source.MAIN) "Update to main ${state.latestCommit}" else "Update to v${state.latest}")
                    }
                    else -> OutlinedButton(enabled = !busy, onClick = EgmManager::install) { Text(stringResource(R.string.upgrade_egm_reinstall)) }
                }
                if (state.version != null && !state.hasUpdate && !switching) {
                    TextButton(enabled = !busy, onClick = { EgmManager.checkForUpdate() }) { Text(stringResource(R.string.upgrade_egm_check_update)) }
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(state.progress ?: stringResource(R.string.upgrade_egm_working), fontSize = 12.5.sp)
                }
            }
            if (state.log.isNotEmpty()) LogBox(state.log)
            if (state.version != null) Mono(EgmManager.rootDir.absolutePath)
        }

        if (state.version == null) return@Column

        EgmCard(stringResource(R.string.upgrade_egm_slash_commands), cardBg, cardBorder, pill = if (settings.enabled) (stringResource(R.string.upgrade_egm_on) to QuotaGreen) else (stringResource(R.string.upgrade_egm_off) to Amber)) {
            EgmSwitchRow(stringResource(R.string.upgrade_egm_use_commands), stringResource(R.string.upgrade_egm_commands_hint), settings.enabled) { on ->
                EgmManager.update { it.copy(enabled = on) }
            }
            EgmManager.Command.entries.forEach { c ->
                val available = EgmManager.available(c, state)
                Column {
                    Text(
                        "/${c.token} <${c.argumentHint}>", fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                        color = if (available) Color.Unspecified else muted
                    )
                    Hint(egmCommandDescription(c))
                    if (!available) Text(liteMissingNote(state.label), fontSize = 11.5.sp, color = Amber)
                }
            }
            Hint(stringResource(R.string.upgrade_egm_execution_hint))
        }

        EgmGuideCard(cardBg, cardBorder, settings.artifactDir, liteNote = if (state.hasLite) null else liteMissingNote(state.label))

        EgmCard(stringResource(R.string.upgrade_egm_reviewers), cardBg, cardBorder, pill = "${settings.reviewers.size} of ${EgmManager.Persona.entries.size}" to MaterialTheme.colorScheme.primary) {
            Hint(stringResource(R.string.upgrade_egm_reviewers_hint))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EgmManager.Preset.entries.forEach { p ->
                    FilterChip(
                        selected = settings.reviewers == p.reviewers,
                        onClick = { EgmManager.update { it.copy(reviewers = p.reviewers) } },
                        label = { Text(p.label) }
                    )
                }
            }
            EgmManager.Persona.entries.forEach { p ->
                val checked = p in settings.reviewers
                // the last reviewer stays: a review needs at least one
                val canChange = !(checked && settings.reviewers.size == 1)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = canChange) {
                        EgmManager.update { s -> s.copy(reviewers = if (checked) s.reviewers - p else s.reviewers + p) }
                    }
                ) {
                    Checkbox(checked = checked, enabled = canChange, onCheckedChange = null)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(p.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Text(egmPersonaDescription(p), fontSize = 11.5.sp, color = muted)
                    }
                }
            }
        }

        EgmCard(stringResource(R.string.upgrade_egm_review), cardBg, cardBorder) {
            EgmSwitchRow(
                stringResource(R.string.upgrade_egm_stop_each_stage),
                stringResource(R.string.upgrade_egm_stop_each_stage_hint),
                settings.checkpoints
            ) { on -> EgmManager.update { it.copy(checkpoints = on) } }
            EgmStepper(
                stringResource(R.string.upgrade_egm_rereviews),
                stringResource(R.string.upgrade_egm_rereviews_hint),
                settings.rounds, 0, EgmManager.MAX_ROUNDS
            ) { v -> EgmManager.update { it.copy(rounds = v) } }
            EgmStepper(
                stringResource(R.string.upgrade_egm_reviewers_parallel),
                stringResource(R.string.upgrade_egm_reviewers_parallel_hint),
                settings.parallel, 1, EgmManager.MAX_PARALLEL
            ) { v -> EgmManager.update { it.copy(parallel = v) } }
            EgmArtifactDirField(settings.artifactDir)
        }

        EgmCard(stringResource(R.string.upgrade_egm_jarvis), cardBg, cardBorder) {
            Hint(stringResource(R.string.upgrade_egm_jarvis_hint))
            EgmManager.Elephant.entries.forEach { e ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().clickable { EgmManager.update { it.copy(elephant = e) } }
                ) {
                    RadioButton(selected = settings.elephant == e, onClick = null, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(egmElephantLabel(e), fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Text(egmElephantDescription(e), fontSize = 11.5.sp, color = muted)
                    }
                }
            }
        }

        EgmCard(stringResource(R.string.upgrade_egm_instructions), cardBg, cardBorder) {
            Hint(stringResource(R.string.upgrade_egm_instructions_hint))
            EgmTemplateRow(stringResource(R.string.upgrade_egm_normal_chats), settings.chatTemplate != EgmManager.DEFAULT_CHAT_TEMPLATE) { editTemplate = false }
            EgmTemplateRow(stringResource(R.string.upgrade_egm_jarvis_chats), settings.jarvisTemplate != EgmManager.DEFAULT_JARVIS_TEMPLATE) { editTemplate = true }
            Hint(stringResource(R.string.upgrade_egm_rules_hint))
            Mono(EgmManager.adapterDir.absolutePath)
        }

        EgmCard(stringResource(R.string.upgrade_egm_remove), cardBg, cardBorder) {
            Hint(stringResource(R.string.upgrade_egm_remove_note))
            OutlinedButton(
                enabled = !busy,
                onClick = { confirmRemove = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.upgrade_egm_remove_button))
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.upgrade_egm_remove_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.upgrade_egm_remove_confirm), fontSize = 13.5.sp) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; EgmManager.uninstall() }) {
                    Text(stringResource(R.string.upgrade_egm_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.upgrade_egm_cancel)) } }
        )
    }

    editTemplate?.let { jarvis ->
        EgmTemplateDialog(jarvis, if (jarvis) settings.jarvisTemplate else settings.chatTemplate) { editTemplate = null }
    }
}

@Composable
private fun EgmCard(
    title: String,
    cardBg: Color,
    cardBorder: BorderStroke,
    pill: Pair<String, Color>? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                pill?.let { Pill(it.first, it.second) }
            }
            content()
        }
    }
}

/** How to use the commands: which one when, an example of each, normal chat vs Jarvis chat, a run's course, tips. Folds up. */
@Composable
private fun EgmGuideCard(cardBg: Color, cardBorder: BorderStroke, artifactDir: String, liteNote: String?) {
    var open by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.upgrade_egm_guide), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) stringResource(R.string.upgrade_egm_hide_guide) else stringResource(R.string.upgrade_egm_show_guide))
            }
            if (!open) {
                Hint(stringResource(R.string.upgrade_egm_guide_collapsed))
                return@Column
            }

            EgmGuideHeading(stringResource(R.string.upgrade_egm_guide_which_command))
            EgmGuideCommand(
                "/egm <task>",
                stringResource(R.string.upgrade_egm_guide_full_when),
                stringResource(R.string.upgrade_egm_guide_full_steps),
                stringResource(R.string.upgrade_egm_guide_full_example)
            )
            EgmGuideCommand(
                "/egm-lite <task>",
                stringResource(R.string.upgrade_egm_guide_lite_when),
                stringResource(R.string.upgrade_egm_guide_lite_steps),
                stringResource(R.string.upgrade_egm_guide_lite_example),
                note = liteNote
            )
            EgmGuideCommand(
                "/egm-review <doc path>",
                stringResource(R.string.upgrade_egm_guide_review_when),
                stringResource(R.string.upgrade_egm_guide_review_steps),
                stringResource(R.string.upgrade_egm_guide_review_example)
            )

            EgmGuideHeading(stringResource(R.string.upgrade_egm_guide_normal_or_jarvis))
            Hint(stringResource(R.string.upgrade_egm_guide_normal_chat))
            Hint(stringResource(R.string.upgrade_egm_guide_jarvis_chat))

            EgmGuideHeading(stringResource(R.string.upgrade_egm_guide_during))
            Hint(
                stringResource(R.string.upgrade_egm_guide_during_steps, artifactDir)
            )

            EgmGuideHeading(stringResource(R.string.upgrade_egm_guide_tips))
            Hint(
                stringResource(R.string.upgrade_egm_guide_tip_list)
            )
        }
    }
}

@Composable
private fun EgmGuideHeading(text: String) {
    Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun EgmGuideCommand(command: String, whenToUse: String, steps: String, example: String, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(command, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        note?.let { Text(it, fontSize = 11.5.sp, color = Amber) }
        Hint(whenToUse)
        Hint(steps)
        Text(stringResource(R.string.upgrade_egm_guide_example), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Mono(example)
    }
}

@Composable
private fun EgmSwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EgmStepper(title: String, text: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        IconButton(enabled = value > min, onClick = { onChange(value - 1) }) { Icon(Icons.Outlined.Remove, stringResource(R.string.upgrade_egm_less), Modifier.size(18.dp)) }
        Text("$value", fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 20.dp))
        IconButton(enabled = value < max, onClick = { onChange(value + 1) }) { Icon(Icons.Outlined.Add, stringResource(R.string.upgrade_egm_more), Modifier.size(18.dp)) }
    }
}

@Composable
private fun EgmArtifactDirField(saved: String) {
    var dir by remember(saved) { mutableStateOf(saved) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = dir,
                onValueChange = { dir = it },
                label = { Text(stringResource(R.string.upgrade_egm_folder_label)) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(enabled = dir.trim().trimEnd('/') != saved && dir.isNotBlank(), onClick = { EgmManager.update { it.copy(artifactDir = dir) } }) {
                Text(stringResource(R.string.upgrade_egm_save))
            }
        }
        Hint(stringResource(R.string.upgrade_egm_artifact_folder_hint))
    }
}

@Composable
private fun EgmTemplateRow(title: String, changed: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Pill(if (changed) stringResource(R.string.upgrade_egm_changed) else stringResource(R.string.upgrade_egm_default), if (changed) Amber else QuotaGreen)
        }
    }
}

@Composable
private fun EgmTemplateDialog(jarvis: Boolean, current: String, onDismiss: () -> Unit) {
    var text by remember(jarvis) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (jarvis) stringResource(R.string.upgrade_egm_instructions_jarvis) else stringResource(R.string.upgrade_egm_instructions_normal), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    if (jarvis) "Sent after the run's facts (EGM's folder, the planner and reviewer goals, the limits). It tells Jarvis how to manage the planner and the reviewer chats."
                    else "Sent after the run's facts (EGM's folder, the reviewers, the limits). It tells the agent how to run the reviewers in its own chat."
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp)
                )
                TextButton(
                    onClick = { text = if (jarvis) EgmManager.DEFAULT_JARVIS_TEMPLATE else EgmManager.DEFAULT_CHAT_TEMPLATE },
                    contentPadding = PaddingValues(0.dp)
                ) { Text(stringResource(R.string.upgrade_egm_reset_default)) }
            }
        },
        confirmButton = {
            Button(onClick = {
                EgmManager.update { if (jarvis) it.copy(jarvisTemplate = text) else it.copy(chatTemplate = text) }
                onDismiss()
            }) { Text(stringResource(R.string.upgrade_egm_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.upgrade_egm_cancel)) } }
    )
}

/** Why /egm-lite is off: the installed EGM ([label], e.g. v0.1.1) has no egm-lite skill (releases up to 0.1.1). */
@Composable
private fun liteMissingNote(label: String?): String = if (label != null) {
    stringResource(R.string.upgrade_egm_lite_missing_version, label)
} else {
    stringResource(R.string.upgrade_egm_lite_missing)
}

private fun egmOpenUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}


@Composable
private fun egmSourceLabel(source: EgmManager.Source): String = stringResource(
    when (source) {
        EgmManager.Source.RELEASE -> R.string.upgrade_egm_source_release_label
        EgmManager.Source.MAIN -> R.string.upgrade_egm_source_main_label
    }
)

@Composable
private fun egmSourceDescription(source: EgmManager.Source): String = stringResource(
    when (source) {
        EgmManager.Source.RELEASE -> R.string.upgrade_egm_source_release_desc
        EgmManager.Source.MAIN -> R.string.upgrade_egm_source_main_desc
    }
)

@Composable
private fun egmCommandDescription(command: EgmManager.Command): String = stringResource(
    when (command) {
        EgmManager.Command.FULL -> R.string.upgrade_egm_cmd_full_desc
        EgmManager.Command.LITE -> R.string.upgrade_egm_cmd_lite_desc
        EgmManager.Command.REVIEW -> R.string.upgrade_egm_cmd_review_desc
    }
)

@Composable
private fun egmPersonaDescription(persona: EgmManager.Persona): String = stringResource(
    when (persona) {
        EgmManager.Persona.ZOOM_OUT -> R.string.upgrade_egm_persona_zoom_out_desc
        EgmManager.Persona.INVENTIVE -> R.string.upgrade_egm_persona_inventive_desc
        EgmManager.Persona.COMPREHENSION -> R.string.upgrade_egm_persona_comprehension_desc
        EgmManager.Persona.DESIGN -> R.string.upgrade_egm_persona_design_desc
        EgmManager.Persona.STRUCTURAL -> R.string.upgrade_egm_persona_structural_desc
        EgmManager.Persona.STYLE -> R.string.upgrade_egm_persona_style_desc
        EgmManager.Persona.READINESS -> R.string.upgrade_egm_persona_readiness_desc
        EgmManager.Persona.CODEBASE -> R.string.upgrade_egm_persona_codebase_desc
    }
)

@Composable
private fun egmElephantLabel(elephant: EgmManager.Elephant): String = stringResource(
    when (elephant) {
        EgmManager.Elephant.PLANNER_CHAT -> R.string.upgrade_egm_elephant_planner_label
        EgmManager.Elephant.JARVIS -> R.string.upgrade_egm_elephant_jarvis_label
    }
)

@Composable
private fun egmElephantDescription(elephant: EgmManager.Elephant): String = stringResource(
    when (elephant) {
        EgmManager.Elephant.PLANNER_CHAT -> R.string.upgrade_egm_elephant_planner_desc
        EgmManager.Elephant.JARVIS -> R.string.upgrade_egm_elephant_jarvis_desc
    }
)
