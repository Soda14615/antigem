package ai.antigem.ui.settings

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import ai.antigem.ui.components.bouncyVerticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.antigem.data.remote.CustomSlashCommand
import ai.antigem.data.remote.CustomSlashCommands
import ai.antigem.ui.browser.BrowserExtensionManager

@Composable
fun CustomSlashCommandsScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CustomSlashCommands.init(context) }
    var editing by remember { mutableStateOf<CustomSlashCommand?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CustomSlashCommand?>(null) }

    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.custom_slash_commands_screen_create_your_own_commands_for_chat_when), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.custom_slash_commands_screen_use_arguments_in_the_expansion_to_insert), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }) { Text(stringResource(R.string.custom_slash_commands_screen_add_command)) }
            OutlinedButton(onClick = { CustomSlashCommands.restoreDefaults() }) { Text(stringResource(R.string.custom_slash_commands_screen_restore_defaults)) }
        }
        if (CustomSlashCommands.commands.isEmpty()) Text(stringResource(R.string.custom_slash_commands_screen_no_custom_commands_yet), style = MaterialTheme.typography.bodySmall)
        CustomSlashCommands.commands.forEach { command ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("/${command.name}", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                        if (CustomSlashCommands.isDefault(command.name)) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(stringResource(R.string.custom_slash_commands_screen_built_in), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    if (command.description.isNotBlank()) Text(command.description, style = MaterialTheme.typography.bodySmall)
                    if (command.pluginId.isNotBlank()) Text(stringResource(R.string.custom_slash_commands_screen_plugin, command.pluginId.toString(), (if (command.skill.isNotBlank()) " · skill: ${command.skill}" else "").toString()), style = MaterialTheme.typography.bodySmall)
                    if (command.expansion.isNotBlank()) Text(command.expansion, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = command }) { Text(stringResource(R.string.chat_screen_edit)) }
                        TextButton(onClick = { deleting = command }) { Text(stringResource(R.string.browser_screen_delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (creating || editing != null) CustomSlashCommandDialog(
        initial = editing,
        onDismiss = { creating = false; editing = null }
    )
    deleting?.let { command -> AlertDialog(
        onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.custom_slash_commands_screen_delete, command.name.toString())) },
        confirmButton = { TextButton(onClick = { CustomSlashCommands.delete(command.name); deleting = null }) { Text(stringResource(R.string.browser_screen_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
    ) }
}

@Composable
private fun CustomSlashCommandDialog(initial: CustomSlashCommand?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var expansion by remember { mutableStateOf(initial?.expansion.orEmpty()) }
    var pluginId by remember { mutableStateOf(initial?.pluginId.orEmpty()) }
    var skill by remember { mutableStateOf(initial?.skill.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val plugins = BrowserExtensionManager.instance.extensions.filter { it.isEnabled || it.id == pluginId }
    val plugin = plugins.firstOrNull { it.id == pluginId }
    val skills = plugin?.skills.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.custom_slash_commands_screen_new_slash_command) else stringResource(R.string.custom_slash_commands_screen_edit, initial.name.toString())) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.removePrefix("/").trim() }, label = { Text(stringResource(R.string.browser_screen_name)) }, prefix = { Text("/") }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.custom_slash_commands_screen_description_shown_in_the_list)) }, singleLine = true)
                OutlinedTextField(value = expansion, onValueChange = { expansion = it }, label = { Text(stringResource(R.string.custom_slash_commands_screen_expands_to)) },
                    placeholder = { Text(stringResource(R.string.custom_slash_commands_screen_e_g_review_arguments_for_bugs_and)) }, minLines = 4, maxLines = 10)
                Choice("Plugin", plugin?.let { "${it.folderName} (${it.name})" } ?: "None",
                    listOf("" to "None") + plugins.map { it.id to "${it.folderName} (${it.name})" }) { pluginId = it; skill = "" }
                if (plugin != null && skills.isNotEmpty()) Choice("Skill", skill.ifBlank { "None (whole plugin)" },
                    listOf("" to "None (whole plugin)") + skills.map { it.name to it.name }) { skill = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = {
            runCatching { CustomSlashCommands.save(CustomSlashCommand(name, description.trim(), expansion.trim(), pluginId, skill), initial?.name) }
                .onSuccess { onDismiss() }.onFailure { error = it.message }
        }) { Text(stringResource(R.string.claude_providers_page_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_cancel)) } }
    )
}

@Composable
private fun Choice(label: String, current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onPick(value); open = false }) }
            }
        }
    }
}
