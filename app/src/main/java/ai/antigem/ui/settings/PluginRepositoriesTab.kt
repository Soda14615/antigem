package ai.antigem.ui.settings

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.antigem.ui.browser.PluginRepo
import ai.antigem.ui.browser.PluginRepositories
import ai.antigem.ui.browser.PluginVersions
import ai.antigem.ui.browser.RepoPlugin
import kotlinx.coroutines.launch

/**
 * Settings → Custom AntiGem Plugins → Repositories: GitHub repositories of plugin folders, their plugins with
 * install/update status, and the guide for hosting one. [runOperation]'s result is shown as a notice.
 */
@Composable
fun PluginRepositoriesTab(busy: Boolean, runOperation: (suspend () -> String?) -> Unit, folderTaken: (String) -> Boolean, freeFolder: (String) -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var guide by remember { mutableStateOf<String?>(null) }
    // A new install whose default folder name is taken waits here for another name
    var pending by remember { mutableStateOf<Pair<PluginRepo, RepoPlugin>?>(null) }
    var folder by remember { mutableStateOf("") }

    fun install(repo: PluginRepo, plugin: RepoPlugin, name: String) = runOperation {
        val installed = PluginRepositories.install(repo, plugin, name, updateId = null)
        "Installed ${installed.folderName} ${PluginVersions.label(installed.version)} from ${repo.label}"
    }
    fun installOrUpdate(repo: PluginRepo, plugin: RepoPlugin) {
        val installed = PluginRepositories.installedFor(repo, plugin)
        if (installed != null) runOperation {
            val updated = PluginRepositories.install(repo, plugin, installed.folderName, installed.id)
            "Updated ${updated.folderName}: ${PluginVersions.label(installed.version)} → ${PluginVersions.label(updated.version)}"
        } else {
            val name = plugin.path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "-").trimStart('.', '-', '_').take(64).ifBlank { "plugin" }
            if (folderTaken(name)) { pending = repo to plugin; folder = freeFolder(name) } else install(repo, plugin, name)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.plugin_repositories_tab_host_your_own_repository), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.plugin_repositories_tab_how_to_lay_out_a_github_repo, PluginRepositories.INDEX.toString()), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { guide = runCatching { readBundledGuide(context) }.getOrElse { "Guide not found: ${it.message}" } }) { Icon(Icons.Outlined.Visibility, stringResource(R.string.plugin_repositories_tab_view_guide)) }
                IconButton(onClick = { scope.launch { saveToDownloads(context, "antigem-plugin-repository.md", readBundledGuide(context)) } }) { Icon(Icons.Outlined.Download, stringResource(R.string.plugin_repositories_tab_download_guide)) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.plugin_repositories_tab_plugin_sdk_for_pc), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.plugin_repositories_tab_all_plugin_docs_and_examples_as_a), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { scope.launch { saveToDownloads(context, "antigem-plugin-sdk.zip", bundledSdkZip(context), "application/zip") } }) { Icon(Icons.Outlined.FolderZip, stringResource(R.string.plugin_repositories_tab_download_plugin_sdk_zip)) }
            }
        }
        OutlinedTextField(value = input, onValueChange = { input = it; addError = null }, label = { Text(stringResource(R.string.plugin_repositories_tab_github_repository)) },
            placeholder = { Text(stringResource(R.string.plugin_repositories_tab_owner_repo_owner_repo_branch_or_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = addError != null, supportingText = { Text(addError ?: stringResource(R.string.plugin_repositories_tab_only_add_repositories_you_trust_plugins_run)) })
        Button(enabled = input.isNotBlank(), onClick = {
            runCatching { PluginRepositories.parse(input).also { PluginRepositories.add(it) } }
                .onSuccess { repo -> input = ""; scope.launch { PluginRepositories.refresh(repo) } }
                .onFailure { addError = it.message }
        }) { Text(stringResource(R.string.plugin_repositories_tab_add_repository)) }

        if (PluginRepositories.repos.isEmpty()) Text(stringResource(R.string.plugin_repositories_tab_no_repositories_added), style = MaterialTheme.typography.bodySmall)
        PluginRepositories.repos.forEach { repo ->
            val listing = PluginRepositories.listings[repo.label]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(listing?.title ?: repo.label, style = MaterialTheme.typography.titleMedium)
                            if (listing?.title != null && listing.title != repo.label) Text(repo.label, style = MaterialTheme.typography.bodySmall)
                        }
                        if (repo.label in PluginRepositories.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { scope.launch { PluginRepositories.refresh(repo) } }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.bridge_ui_check_for_updates)) }
                        IconButton(onClick = { PluginRepositories.remove(repo) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.plugin_repositories_tab_remove_repository)) }
                    }
                    PluginRepositories.errors[repo.label]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (listing != null && listing.plugins.isEmpty()) Text(stringResource(R.string.plugin_repositories_tab_this_repository_lists_no_plugins), style = MaterialTheme.typography.bodySmall)
                    listing?.plugins?.forEach { plugin -> RepoPluginRow(repo, plugin, busy) { installOrUpdate(repo, plugin) } }
                }
            }
        }
    }

    guide?.let { MarkdownViewerDialog("Plugin repository guide", it, "antigem-plugin-repository.md") { guide = null } }
    pending?.let { (repo, plugin) ->
        val taken = folderTaken(folder)
        AlertDialog(
            onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.plugin_repositories_tab_install, plugin.name.toString())) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.plugin_repositories_tab_a_plugin_already_uses_the_folder_choose, (plugin.path.substringAfterLast('/')).toString()))
                OutlinedTextField(value = folder, onValueChange = { folder = it }, label = { Text(stringResource(R.string.plugin_repositories_tab_folder_name)) }, singleLine = true,
                    isError = taken, supportingText = if (taken) {{ Text(stringResource(R.string.plugin_repositories_tab_a_plugin_already_uses_this_folder_name)) }} else null)
            } },
            confirmButton = { TextButton(enabled = folder.isNotBlank() && !taken, onClick = { val name = folder.trim(); pending = null; install(repo, plugin, name) }) { Text(stringResource(R.string.claude_settings_screen_install)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
}

@Composable
private fun RepoPluginRow(repo: PluginRepo, plugin: RepoPlugin, busy: Boolean, onInstall: () -> Unit) {
    val installed = PluginRepositories.installedFor(repo, plugin)
    val comparison = installed?.let { PluginVersions.compare(plugin.version, it.version) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PluginIcon(plugin.iconUrl, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${plugin.name} · ${PluginVersions.label(plugin.version)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (plugin.description.isNotBlank()) Text(plugin.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (installed != null) Text(when {
                comparison == null -> stringResource(R.string.plugin_repositories_tab_installed_as, (PluginVersions.label(installed.version)).toString(), installed.folderName.toString())
                comparison > 0 -> stringResource(R.string.plugin_repositories_tab_update_available, (PluginVersions.label(installed.version)).toString(), (PluginVersions.label(plugin.version)).toString())
                comparison == 0 -> stringResource(R.string.plugin_repositories_tab_installed_up_to_date)
                else -> stringResource(R.string.plugin_repositories_tab_installed_is_newer_than_this_repository_s, (PluginVersions.label(installed.version)).toString())
            }, style = MaterialTheme.typography.labelSmall, color = if (comparison != null && comparison > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(6.dp))
        when {
            installed == null -> Button(enabled = !busy, onClick = onInstall, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(stringResource(R.string.claude_settings_screen_install)) }
            comparison != null && comparison > 0 -> Button(enabled = !busy, onClick = onInstall, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(stringResource(R.string.claude_settings_screen_update)) }
            else -> TextButton(enabled = !busy, onClick = onInstall) { Text(stringResource(R.string.android_dev_screen_reinstall)) }
        }
    }
}

/** Real progress of a repository install or update; the bar only moves without a percentage while the size is unknown. */
@Composable
internal fun PluginInstallProgress(progress: ai.antigem.ui.browser.RepoInstallProgress, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    val fraction = progress.fraction
    val files = if (progress.filesTotal > 0) "${progress.filesDone}/${progress.filesTotal} files" else null
    val label = when (progress.phase) {
        "listing" -> "Reading the repository…"
        "downloading" -> listOfNotNull(
            "Downloading", files,
            if (progress.bytesTotal > 0) "${size(progress.bytesDone)} / ${size(progress.bytesTotal)}" else size(progress.bytesDone)
        ).joinToString(" · ")
        "extracting" -> listOfNotNull("Extracting", files).joinToString(" · ")
        "installing" -> "Installing…"
        else -> "Starting plugin…"
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        if (progress.note.isNotBlank()) Text(progress.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (fraction != null) Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
