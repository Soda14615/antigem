package ai.antigem.ui.settings

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import ai.antigem.ui.components.bouncyVerticalScroll
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.antigem.ui.browser.BrowserExtension
import ai.antigem.ui.browser.BrowserExtensionManager
import ai.antigem.ui.browser.PluginRepositories
import ai.antigem.ui.browser.PluginVersions
import kotlinx.coroutines.launch

@Composable
fun WebViewExtensionsScreen(onChanged: () -> Unit) {
    val manager = remember { BrowserExtensionManager.instance }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<BrowserExtension?>(null) }
    var zip by remember { mutableStateOf<Uri?>(null) }
    // Set when a new ZIP matches an installed plugin (same folder or ID): ask to update it or install a separate copy
    var conflict by remember { mutableStateOf<BrowserExtension?>(null) }
    var asNew by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf("") }
    var zipVersion by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    fun folderTaken(name: String) = manager.extensions.any { it.folderName.equals(name.trim(), ignoreCase = true) }
    fun freeFolder(base: String) = generateSequence(2) { it + 1 }.map { "${base.take(60)}-$it" }.first { !folderTaken(it) }
    var tab by remember { mutableIntStateOf(0) }
    // ID of the plugin being zipped for download, and the plugin waiting for remove confirmation
    var exporting by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<BrowserExtension?>(null) }
    var deleteData by remember { mutableStateOf(false) }
    /** Runs a plugin change; the text it returns is shown as a notice. */
    fun runOperation(action: suspend () -> String?) {
        scope.launch {
            busy = true; error = null; notice = null
            runCatching { action() }.onSuccess { notice = it; onChanged() }.onFailure { error = it.message ?: "Plugin operation failed" }
            busy = false
        }
    }
    fun install(uri: Uri, name: String, updateId: String?, copy: Boolean, previousVersion: String?) = runOperation {
        val installed = manager.installArchive(uri, name, updateId, asNew = copy)
        if (previousVersion != null) "Updated ${installed.folderName}: ${PluginVersions.label(previousVersion)} → ${PluginVersions.label(installed.version)}"
            else "Installed ${installed.folderName} ${PluginVersions.label(installed.version)}"
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        asNew = false
        val filename = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
        folder = update?.folderName ?: filename.orEmpty().substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "-").take(64).ifBlank { "my-extension" }
        scope.launch {
            busy = true
            // An unreadable ZIP is reported by the install itself
            val manifest = runCatching { manager.archiveManifest(uri) }.getOrNull()
            busy = false
            zipVersion = manifest?.optString("version").orEmpty()
            val id = manifest?.optString("id").orEmpty()
            if (update == null) conflict = manager.extensions.firstOrNull { it.folderName.equals(folder, ignoreCase = true) || (id.isNotBlank() && it.id == id) }
            zip = uri
        }
    }
    LaunchedEffect(Unit) { runCatching { manager.refreshExtensions() }.onFailure { error = it.message } }
    // Repository listings also drive the "update available" line on installed plugins
    LaunchedEffect(Unit) { PluginRepositories.refreshAll() }
    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.claude_settings_screen_installed)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.git_hub_screen_repositories)) })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (tab == 1) {
            val progress = PluginRepositories.installProgress
            if (progress != null) PluginInstallProgress(progress)
            else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            PluginRepositoriesTab(busy, ::runOperation, ::folderTaken, ::freeFolder)
            return@Column
        }
        Text(stringResource(R.string.web_view_extensions_screen_install_custom_antigem_plugins_from_zip_files), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.web_view_extensions_screen_to_create_a_plugin_with_your_model), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { update = null; picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.web_view_extensions_screen_install_zip)) }
            OutlinedButton(enabled = !busy, onClick = { runOperation { manager.refreshExtensions(); null } }) { Text(stringResource(R.string.agent_sheet_ui_refresh)) }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
        }
        PluginRepositories.installProgress?.let { PluginInstallProgress(it) }
        manager.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (manager.extensions.isEmpty()) Text(stringResource(R.string.web_view_extensions_screen_no_plugins_installed_choose_a_zip_containing))
        manager.extensions.forEach { extension ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PluginIcon(extension.iconPath)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(extension.folderName, style = MaterialTheme.typography.titleMedium)
                            Text("${extension.name} · ${PluginVersions.label(extension.version)} · ${extension.toolCount} MCP tools" + (if (extension.skillCount > 0) " · ${extension.skillCount} skills" else ""), style = MaterialTheme.typography.bodySmall)
                            extension.source?.let { Text("来自 ${it.repo}" + (if (it.ref.isNotBlank()) "@${it.ref}" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (exporting == extension.id) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                        else IconButton(enabled = exporting == null, onClick = {
                            scope.launch {
                                exporting = extension.id; error = null
                                runCatching {
                                    val zip = manager.exportZip(extension.id)
                                    try { saveToDownloads(context, zip, "application/zip") } finally { zip.parentFile?.deleteRecursively() }
                                }.onFailure { error = "无法打包 ${extension.folderName}：${it.message}" }
                                exporting = null
                            }
                        }) { Icon(Icons.Outlined.Download, stringResource(R.string.web_view_extensions_screen_download_as_zip)) }
                        IconButton(enabled = !busy, onClick = { removing = extension; deleteData = false }) { Icon(Icons.Outlined.Delete, stringResource(R.string.browser_screen_remove), tint = MaterialTheme.colorScheme.error) }
                        Switch(checked = extension.isEnabled, enabled = !busy, onCheckedChange = { enabled -> runOperation { manager.setEnabled(extension.id, enabled); null } })
                    }
                    if (extension.description.isNotBlank()) Text(extension.description, style = MaterialTheme.typography.bodySmall)
                    val source = extension.source
                    val repo = source?.let { s -> PluginRepositories.repos.firstOrNull { it.slug.equals(s.repo, ignoreCase = true) && it.ref == s.ref } }
                    val offered = PluginRepositories.updateFor(extension)
                    offered?.let { Text(stringResource(R.string.plugin_repositories_tab_update_available, (PluginVersions.label(extension.version)).toString(), (PluginVersions.label(it.version)).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (repo != null && offered != null) TextButton(enabled = !busy, onClick = {
                            runOperation {
                                val updated = PluginRepositories.install(repo, offered, extension.folderName, extension.id)
                                "Updated ${updated.folderName}: ${PluginVersions.label(extension.version)} → ${PluginVersions.label(updated.version)}"
                            }
                        }) { Text(stringResource(R.string.claude_settings_screen_update)) }
                        TextButton(enabled = !busy, onClick = { update = extension; picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.web_view_extensions_screen_update_zip)) }
                        TextButton(enabled = !busy && extension.isEnabled, onClick = { runOperation { manager.reload(extension.id); null } }) { Text(stringResource(R.string.browser_screen_reload)) }
                    }
                }
            }
        }
        Text(stringResource(R.string.web_view_extensions_screen_add_an_enabled_plugin_to_your_model), style = MaterialTheme.typography.bodySmall)
    }
    conflict?.takeIf { zip != null }?.let { existing -> AlertDialog(
        onDismissRequest = { zip = null; conflict = null },
        title = { Text(stringResource(R.string.web_view_extensions_screen_plugin_already_installed)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.web_view_extensions_screen_is_already_installed_update_it_with_this, existing.folderName.toString(), existing.name.toString()))
            VersionChange(existing.version, zipVersion)
        } },
        confirmButton = { TextButton(onClick = {
            val uri = zip!!; zip = null; conflict = null
            install(uri, existing.folderName, existing.id, copy = false, previousVersion = existing.version)
        }) { Text(stringResource(R.string.claude_settings_screen_update)) } },
        dismissButton = { Row {
            TextButton(onClick = { zip = null; conflict = null }) { Text(stringResource(R.string.accounts_ui_cancel)) }
            TextButton(onClick = { conflict = null; asNew = true; folder = freeFolder(folder) }) { Text(stringResource(R.string.web_view_extensions_screen_install_as_new)) }
        } }
    ) }
    removing?.let { extension -> AlertDialog(
        onDismissRequest = { removing = null },
        title = { Text(stringResource(R.string.claude_providers_page_remove, extension.folderName.toString())) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (extension.location) {
                BrowserExtension.Location.BUNDLED -> stringResource(R.string.web_view_extensions_screen_this_is_built_into_the_app_it)
                BrowserExtension.Location.USER_FOLDER -> stringResource(R.string.web_view_extensions_screen_this_deletes_the_folder_from_your_files, extension.dirPath.toString())
                BrowserExtension.Location.INSTALLED -> stringResource(R.string.web_view_extensions_screen_deletes_the_plugin_from, extension.dirPath.toString())
            })
            Text(stringResource(R.string.web_view_extensions_screen_if_you_added_it_in_mcp_settings), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = deleteData, onCheckedChange = { deleteData = it })
                Text(stringResource(R.string.web_view_extensions_screen_also_delete_its_stored_data_otherwise_kept), style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(onClick = {
            val wipe = deleteData; removing = null
            runOperation { manager.uninstall(extension.id, wipe); "Removed ${extension.folderName}" }
        }) { Text(stringResource(R.string.browser_screen_remove), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
    ) }
    if (zip != null && conflict == null) {
        val taken = update == null && folderTaken(folder)
        AlertDialog(
            onDismissRequest = { zip = null }, title = { Text(if (update != null) stringResource(R.string.web_view_extensions_screen_update_plugin) else if (asNew) stringResource(R.string.web_view_extensions_screen_install_as_new_plugin) else stringResource(R.string.web_view_extensions_screen_install_plugin)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = folder, onValueChange = { folder = it }, enabled = update == null, label = { Text(stringResource(R.string.plugin_repositories_tab_folder_name)) }, singleLine = true,
                    isError = taken, supportingText = if (taken) {{ Text(stringResource(R.string.plugin_repositories_tab_a_plugin_already_uses_this_folder_name)) }} else null)
                update?.let { VersionChange(it.version, zipVersion) }
                if (update == null) Text(stringResource(R.string.web_view_extensions_screen_version, (PluginVersions.label(zipVersion)).toString()), style = MaterialTheme.typography.bodySmall)
                Text(when {
                    update != null -> stringResource(R.string.web_view_extensions_screen_the_plugin_s_id_and_stored_data)
                    asNew -> stringResource(R.string.web_view_extensions_screen_installed_as_a_separate_plugin_next_to)
                    else -> stringResource(R.string.web_view_extensions_screen_the_zip_may_contain_manifest_json_directly)
                })
            } },
            confirmButton = { TextButton(enabled = folder.isNotBlank() && !taken, onClick = {
                val uri = zip!!; val id = update?.id; val name = folder.trim(); val copy = asNew; val previous = update?.version; zip = null
                install(uri, name, id, copy, previous)
            }) { Text(if (update == null) stringResource(R.string.claude_settings_screen_install) else stringResource(R.string.claude_settings_screen_update)) } },
            dismissButton = { TextButton(onClick = { zip = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
}

@Composable
fun WebViewExtensionMcpPicker(onDismiss: () -> Unit, onSelect: (BrowserExtension) -> Unit) {
    val manager = remember { BrowserExtensionManager.instance }
    LaunchedEffect(Unit) { manager.loadExtensions() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.web_view_extensions_screen_custom_antigem_plugin_mcp)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val available = manager.extensions.filter { it.isEnabled && (it.toolCount > 0 || it.skillCount > 0) }
            if (available.isEmpty()) Text(stringResource(R.string.web_view_extensions_screen_enable_or_install_a_plugin_in_settings))
            available.forEach { extension ->
                OutlinedButton(onClick = { onSelect(extension) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        PluginIcon(extension.iconPath, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                        Text(extension.folderName)
                        Text("${extension.name} · ${extension.toolCount} tools" + (if (extension.skillCount > 0) " · ${extension.skillCount} skills" else ""), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_close)) } })
}
