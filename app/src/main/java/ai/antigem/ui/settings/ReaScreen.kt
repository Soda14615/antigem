package ai.antigem.ui.settings

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.adb.AdbConnectManager
import ai.antigem.data.rea.ReaManager
import ai.antigem.data.rea.ReaManager.Component
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings → REA: sets up REA (reverse engineering for agents) in a proot-distro Ubuntu container, part by part,
 * and its tool groups. REA works with any agent: it is connected in each agent's own MCP settings with the REA
 * preset ([ReaServerDialog]); this page only says where and shows the plain command for other agents.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaScreen(
    cardBg: Color,
    cardBorder: BorderStroke,
    onOpenGroups: () -> Unit,
    onOpenAdbConnect: () -> Unit,
    onOpenPlugins: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ReaManager.init(context)
        AdbConnectManager.init(context)
        AdbConnectManager.refresh(quiet = true)
        ReaManager.refresh()
        ReaManager.checkForUpdate()
        // The terminal and other screens change things while the user is away; keep the page live
        while (true) {
            delay(2000)
            if (!ReaManager.state.value.busy) ReaManager.refresh()
        }
    }
    val state by ReaManager.state.collectAsState()
    val adb by AdbConnectManager.state.collectAsState()
    val busy = state.busy

    // Installs take minutes: keep the screen on while one runs (the manager also holds a wake lock)
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    var confirm by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ReaIntroCard(state, cardBg, cardBorder)

        state.message?.let { msg ->
            Surface(shape = RoundedCornerShape(12.dp), color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = ReaManager::clearMessage) { Icon(Icons.Default.Close, stringResource(R.string.browser_screen_dismiss), Modifier.size(18.dp)) }
                }
            }
        }

        if (!state.ready) {
            Hint(stringResource(R.string.bridge_ui_checking))
            return@Column
        }
        if (!state.bootstrapInstalled) {
            InfoCard("先安装终端", cardBg, cardBorder) {
                Hint(stringResource(R.string.rea_screen_rea_installs_into_antigem_s_terminal_install))
            }
            return@Column
        }

        // Step 1: setup files, downloaded on demand (nothing of REA ships in the app)
        StepCard(
            1, stringResource(R.string.android_dev_screen_setup_files),
            when {
                state.filesUpdate != null -> stringResource(R.string.claude_settings_screen_update)
                state.filesVersion != null -> "v${state.filesVersion}"
                else -> null
            },
            cardBg, cardBorder,
            badgeColor = if (state.filesUpdate != null) Amber else QuotaGreen
        ) {
            Hint(stringResource(R.string.rea_screen_rea_s_setup_scripts_rea_setup_sh))
            SourceRow(state.source, enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.filesVersion == null -> Button(enabled = !busy, onClick = ReaManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.android_dev_screen_get_setup_files))
                    }
                    state.filesUpdate != null -> Button(enabled = !busy, onClick = ReaManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.android_dev_screen_update_to_v, state.filesUpdate.toString()))
                    }
                    else -> OutlinedButton(enabled = !busy, onClick = ReaManager::downloadSetupFiles) { Text(stringResource(R.string.android_dev_screen_download_again)) }
                }
                if (state.filesVersion != null && state.filesUpdate == null) {
                    TextButton(enabled = !busy, onClick = ReaManager::checkForUpdate) { Text(stringResource(R.string.android_dev_screen_check_for_update)) }
                }
            }
            ReaTaskOutput(state, "files")
        }

        // Step 2: the parts, installed one by one or several at once
        InstallStep(state, cardBg, cardBorder)

        // Step 3: which tools agents see
        StepCard(
            3, stringResource(R.string.rea_groups_screen_tool_groups),
            if (state.groups.isNotEmpty()) stringResource(R.string.rea_screen_groups, state.groups.size.toString()) else null,
            cardBg, cardBorder
        ) {
            Hint(stringResource(R.string.rea_screen_rea_has_over_200_tools_tool_groups))
            if (state.groups.isNotEmpty()) {
                val grouped = state.allToolNames.size - state.ungrouped.size
                Mono(
                    "${state.groups.size} groups · $grouped of ${state.allToolNames.size} tools grouped" +
                        (if (state.ungrouped.isNotEmpty()) "\n${state.ungrouped.size} tools in no group" else "") +
                        (state.toolsServer?.let { "\nTool list from $it" } ?: "\nTool list: install REA to read it")
                )
            } else {
                Hint(stringResource(R.string.rea_screen_the_default_groups_come_with_the_setup))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = state.groups.isNotEmpty() || state.filesReady, onClick = onOpenGroups) {
                    Icon(Icons.Outlined.Category, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.rea_screen_edit_tool_groups))
                }
                OutlinedButton(enabled = !busy && state.usable, onClick = ReaManager::refreshTools) { Text(stringResource(R.string.rea_screen_refresh_tool_list)) }
            }
            ReaTaskOutput(state, "tools")
        }

        // Step 4: connecting it to an agent happens in that agent's MCP settings, the same way for every agent
        StepCard(4, stringResource(R.string.rea_screen_connect_rea_to_your_agent), null, cardBg, cardBorder) {
            ConnectGuide(state)
        }

        // Step 5: health check
        StepCard(5, stringResource(R.string.rea_screen_check_it_works), null, cardBg, cardBorder) {
            Hint("在 Ubuntu 中运行 rea doctor：它会报告 REA 能用哪些引擎（JADX、apktool、objdump、rizin、adb……）。没安装的引擎会显示为缺失，这没关系。")
            OutlinedButton(enabled = !busy && state.usable, onClick = ReaManager::runDoctor) {
                Icon(Icons.Outlined.HealthAndSafety, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.rea_screen_run_health_check))
            }
            ReaTaskOutput(state, "doctor")
            Text(stringResource(R.string.android_dev_screen_try_in_chat), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            listOf(
                "Use REA to find the APK of the installed app com.example.app and summarize its manifest and permissions.",
                "With REA, is com.example.app a Flutter, React Native or native app? Show the evidence.",
                "Decompile the MainActivity of com.example.app with REA and explain what it does."
            ).forEach { CopyLine(it) }
        }

        SkillPluginCard(state, busy, cardBg, cardBorder, onOpenPlugins)

        // Optional: adb to this phone, for REA's device tools
        InfoCard("手机访问（可选）", cardBg, cardBorder, pill = if (adb.connectedDevice != null) "已连接" to QuotaGreen else "可选" to MaterialTheme.colorScheme.primary) {
            Hint(stringResource(R.string.rea_screen_rea_s_phone_tools_installed_apps_pulling))
            val device = adb.connectedDevice
            if (device != null) Mono("adb 已连接：${device.serial}") else OutlinedButton(onClick = onOpenAdbConnect) { Text(stringResource(R.string.android_dev_screen_set_up_adb_connect)) }
        }

        WebsitesCard(state, cardBg, cardBorder)

        ManualSetupCard(state, cardBg, cardBorder)

        RemoveCard(state, busy, cardBg, cardBorder) { confirm = it }
    }

    confirm?.let { what ->
        val (title, text, action) = when (what) {
            "uninstall" -> Triple(
                "Remove REA?",
                "Removes REA, the JADX and apktool jars, hermes-dec and the rea-mcp launcher. " +
                    (if (state.chroot) "Java, Node.js and your tool groups stay in the Ubuntu chroot." else "The Ubuntu container, Java, Node.js and your tool groups stay.") +
                    " REA servers in your agents stop working until you install it again.",
                { ReaManager.uninstallRea() }
            )
            else -> Triple(
                "Delete the Ubuntu container?",
                "Deletes the whole '${ReaManager.CONTAINER}' proot-distro container: REA, Java, Node.js and anything else you installed or saved in it (also outside REA). This cannot be undone.",
                { ReaManager.removeContainer() }
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { action(); confirm = null }) { Text(if (what == "uninstall") stringResource(R.string.browser_screen_remove) else stringResource(R.string.browser_screen_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
}

@Composable
private fun ReaIntroCard(state: ReaManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BugReport, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.rea_screen_reverse_engineering_with_rea), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                when {
                    state.usable && state.requiredDone -> Pill(stringResource(R.string.android_dev_screen_ready), QuotaGreen)
                    state.installed.isNotEmpty() -> Pill(stringResource(R.string.android_dev_screen_partly_set_up), Amber)
                    else -> Pill(stringResource(R.string.android_dev_screen_not_set_up), MaterialTheme.colorScheme.primary)
                }
            }
            Hint("让智能体拆解 Android 应用（APK、Flutter、React Native）和原生代码：反编译 class、读取 manifest 和资源、检查 .so 库、查看已安装应用。REA 是开源 MCP 服务器；AntiGem 把它运行在 " +
                if (state.chroot) "这台手机的 Ubuntu chroot 中。" else "终端内的 Ubuntu 容器（proot-distro）中。")
            Hint("只安装你需要的部分。APK 分析的全套大约 1 GB。")
            TextButton(onClick = { openReaUrl(context, ReaManager.REA_HOMEPAGE) }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.rea_screen_about_rea_github_com_morluto_rea), fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun SourceRow(source: String, enabled: Boolean) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(source) { mutableStateOf(source) }
    var error by remember { mutableStateOf<String?>(null) }
    if (!editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.android_dev_screen_from, source.toString()), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
            TextButton(enabled = enabled, onClick = { editing = true }) { Text(stringResource(R.string.android_dev_screen_change), fontSize = 12.sp) }
        }
        return
    }
    OutlinedTextField(
        value = text, onValueChange = { text = it; error = null }, singleLine = true,
        label = { Text(stringResource(R.string.android_dev_screen_owner_repo_branch)) }, isError = error != null,
        supportingText = { Text(error ?: stringResource(R.string.android_dev_screen_a_fork_of_the_antigem_plugins_repository)) },
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            runCatching { ReaManager.setSource(text) }
                .onSuccess { editing = false; ReaManager.checkForUpdate() }
                .onFailure { error = it.message }
        }) { Text(stringResource(R.string.claude_providers_page_save)) }
        TextButton(onClick = { text = ReaManager.DEFAULT_SOURCE }) { Text(stringResource(R.string.android_dev_screen_default)) }
        TextButton(onClick = { editing = false; text = source }) { Text(stringResource(R.string.accounts_ui_cancel)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstallStep(state: ReaManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val busy = state.busy
    val installedCount = state.installed.size
    val total = Component.entries.size
    // What the user ticked; starts with what APK analysis needs plus the native tools
    val selected = remember { mutableStateListOf<Component>() }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(state.ready, state.installed) {
        if (!initialized && state.ready) {
            selected.clear()
            selected.addAll(Component.entries.filter { (it.required || it == Component.NATIVE) && it !in state.installed })
            initialized = true
        } else {
            selected.removeAll { it in state.installed }
        }
    }
    val plan = ReaManager.plan(selected.toList())

    StepCard(
        2, stringResource(R.string.claude_settings_screen_install),
        when {
            state.requiredDone -> stringResource(R.string.agent_question_panel_of, installedCount.toString(), total.toString())
            installedCount > 0 -> stringResource(R.string.agent_question_panel_of, installedCount.toString(), total.toString())
            else -> null
        },
        cardBg, cardBorder,
        badgeColor = if (state.requiredDone) QuotaGreen else Amber
    ) {
        Hint(stringResource(R.string.rea_screen_pick_the_parts_you_need_and_install))
        val facts = listOfNotNull(
            state.containerName?.let { if (state.chroot) "Ubuntu chroot: $it" else "Container: $it" },
            state.nodeVersion?.let { "Node.js $it" },
            state.reaVersion?.let { "REA $it" }
        )
        if (facts.isNotEmpty()) Mono(facts.joinToString("\n"))

        Text(stringResource(R.string.rea_screen_needed_for_apk_analysis), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Component.entries.filter { it.required }.forEach { c -> ComponentRow(c, state, c in selected, busy) { on -> if (on) selected.add(c) else selected.remove(c) } }
        Text(stringResource(R.string.android_automation_screen_optional), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        Component.entries.filter { !it.required }.forEach { c -> ComponentRow(c, state, c in selected, busy) { on -> if (on) selected.add(c) else selected.remove(c) } }

        if (isInstallTask(state.task) || isInstallTask(state.logTask)) BatchProgress(state)
        if (!busy) {
            if (plan.isNotEmpty()) {
                val extra = plan.filter { it !in selected }
                Hint(
                    "Installs ${plan.size} part${if (plan.size == 1) "" else "s"}: ${plan.joinToString(", ") { it.label }}" +
                        if (extra.isNotEmpty()) " (${extra.joinToString(", ") { it.label }} added because the others need them)." else "."
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = plan.isNotEmpty(), onClick = { ReaManager.installAll(plan) }) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (plan.isEmpty()) stringResource(R.string.android_dev_screen_nothing_to_install) else stringResource(R.string.android_dev_screen_install_selected, plan.size.toString()))
                }
                TextButton(onClick = {
                    selected.clear(); selected.addAll(Component.entries.filter { it !in state.installed })
                }) { Text(stringResource(R.string.claude_providers_page_select_all)) }
                TextButton(onClick = { selected.clear() }) { Text(stringResource(R.string.custom_system_prompt_dialog_clear)) }
            }
            if (!state.filesReady) Hint(stringResource(R.string.android_dev_screen_the_setup_files_are_downloaded_first))
        } else {
            Hint(stringResource(R.string.android_dev_screen_keep_antigem_open_the_screen_stays_on))
            OutlinedButton(onClick = ReaManager::stop, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.chat_input_bar_stop))
            }
        }
    }
}

@Composable
private fun ComponentRow(c: Component, state: ReaManager.State, checked: Boolean, busy: Boolean, onCheck: (Boolean) -> Unit) {
    val installed = c in state.installed
    val running = state.installing == c
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (running) 0.08f else 0.03f)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (installed) {
                    Icon(Icons.Outlined.CheckCircle, stringResource(R.string.claude_settings_screen_installed), tint = QuotaGreen, modifier = Modifier.padding(12.dp).size(22.dp))
                } else {
                    Checkbox(checked = checked, enabled = !busy, onCheckedChange = onCheck)
                }
                Column(Modifier.weight(1f)) {
                    Text(c.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (state.chroot && c == Component.CONTAINER) "this phone's runtime" else c.size + when {
                            running -> " · installing…"
                            installed -> " · installed"
                            else -> ""
                        },
                        fontSize = 11.5.sp,
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                if (running) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
                } else if (!(state.chroot && c == Component.CONTAINER)) {
                    // the Ubuntu chroot is the phone's runtime: nothing to install or reinstall here
                    TextButton(enabled = !busy, onClick = { ReaManager.install(c) }) {
                        Text(if (installed) (if (c == Component.REA) stringResource(R.string.claude_settings_screen_update) else stringResource(R.string.android_dev_screen_reinstall)) else stringResource(R.string.claude_settings_screen_install), fontSize = 12.5.sp)
                    }
                }
            }
            if (expanded || running) {
                Text(c.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
                val missing = c.needs.filter { it !in state.installed }
                if (missing.isNotEmpty() && !installed) {
                    Text(stringResource(R.string.android_dev_screen_also_installs, (missing.joinToString(", ") { it.label }).toString()), fontSize = 11.5.sp, color = Amber, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                }
            }
        }
    }
}

private fun isInstallTask(key: String?): Boolean = key == "batch" || Component.entries.any { it.step == key }

@Composable
private fun BatchProgress(state: ReaManager.State) {
    if (isInstallTask(state.task)) {
        val n = state.batch.size
        if (n > 1) {
            LinearProgressIndicator(
                progress = { (state.batchIndex.toFloat() / n).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().clip(CircleShape)
            )
        }
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: stringResource(R.string.claude_login_dialog_starting))
    }
    if (isInstallTask(state.logTask) && state.log.isNotEmpty()) LogBox(state.log)
}

/** Progress and the log of [task], shown in its own card while it runs and after it finishes. */
@Composable
private fun ReaTaskOutput(state: ReaManager.State, task: String) {
    if (state.task == task) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: stringResource(R.string.claude_login_dialog_starting))
        OutlinedButton(onClick = ReaManager::stop) { Text(stringResource(R.string.chat_input_bar_stop)) }
    }
    if (state.logTask == task && state.log.isNotEmpty()) LogBox(state.log)
}

/** Where REA is connected (each agent's own MCP settings), what the two ways of offering its tools cost, and the plain command. */
@Composable
private fun ConnectGuide(state: ReaManager.State) {
    val onDemand = state.serverTokens(ReaManager.Mode.ON_DEMAND, listOf("all"), emptyList(), false)
    val directDefault = state.serverTokens(ReaManager.Mode.DIRECT, state.defaultSelection.ifEmpty { listOf("all") }, emptyList(), false)
    val directAll = state.serverTokens(ReaManager.Mode.DIRECT, listOf("all"), emptyList(), false)
    Hint(stringResource(R.string.rea_screen_rea_is_not_tied_to_an_agent))
    Mono(stringResource(R.string.rea_screen_antigravity_mcp_servers_rea_claude_code_mcp))
    if (!state.usable) Hint(stringResource(R.string.rea_screen_install_rea_and_the_mcp_launcher_above))
    Text(stringResource(R.string.rea_screen_the_preset_asks_how_the_agent_should), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "按需（推荐）：智能体获得 4 个小工具。它会按名称列出 REA 的工具，读取需要的那些再运行；会改动内容的工具通过各自的专用工具执行。" +
            "无论你允许多少工具，每个请求约 ${formatTokens(onDemand ?: 700)} 个 token。",
        fontSize = 12.sp
    )
    Text(
        "直接：每个允许的工具都是普通 MCP 工具，无需查找步骤，但每个请求都会携带全部工具：" +
            (directDefault?.let { "≈${formatTokens(it)} tokens for the default groups" } ?: "≈24k tokens for the default groups") +
            (directAll?.let { ", ≈${formatTokens(it)} for all of REA." } ?: ", ≈75k for all of REA."),
        fontSize = 12.sp
    )
    Hint(stringResource(R.string.rea_screen_it_also_asks_which_tool_groups_the))
    Text(stringResource(R.string.rea_screen_another_agent_or_by_hand), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Hint(stringResource(R.string.rea_screen_add_a_stdio_local_command_mcp_server))
    CopyLine(ReaManager.LAUNCHER, mono = true)
    CopyLine("REA_MODE=lazy\nREA_GROUPS=all", mono = true)
}

@Composable
private fun SkillPluginCard(
    state: ReaManager.State,
    busy: Boolean,
    cardBg: Color,
    cardBorder: BorderStroke,
    onOpenPlugins: () -> Unit
) {
    InfoCard(
        "供智能体使用的 REA 技能（可选）", cardBg, cardBorder,
        pill = if (state.pluginInstalled) "v${state.pluginVersion ?: "?"}" to QuotaGreen else "可选" to MaterialTheme.colorScheme.primary
    ) {
        Hint(stringResource(R.string.rea_screen_a_skills_only_plugin_from_the_antigem))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = ReaManager::installSkillPlugin) {
                Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (state.pluginInstalled) stringResource(R.string.android_dev_screen_update_skill) else stringResource(R.string.android_dev_screen_install_skill))
            }
            TextButton(onClick = onOpenPlugins) { Text(stringResource(R.string.android_dev_screen_plugins)) }
        }
        ReaTaskOutput(state, "plugin")
        if (state.pluginInstalled) {
            Hint(stringResource(R.string.rea_screen_to_give_an_agent_the_skill_type))
        }
    }
}

/**
 * Website analysis: REA's website tools on the browser's tabs. One switch adds the "web" tool group (and tells agents to
 * analyze with REA first); the other runs the loopback DevTools bridge REA connects to, with a warning that any app on
 * the phone can reach it while it is on.
 */
@Composable
private fun WebsitesCard(state: ReaManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val bridge by ai.antigem.data.rea.ReaWebBridge.state.collectAsState()
    InfoCard(
        "Websites (optional)", cardBg, cardBorder,
        pill = when {
            bridge.running -> "Listening" to Amber
            state.webEnabled -> "On" to QuotaGreen
            else -> "Off" to MaterialTheme.colorScheme.primary
        }
    ) {
        Hint(stringResource(R.string.rea_screen_let_agents_analyze_websites_open_in_antigem))
        SwitchRow(
            "Website analysis",
            "Adds the Websites tool group and tells agents to analyze sites with REA first, screenshots included, and to use the browser MCP only for what REA cannot do.",
            state.webEnabled, enabled = state.ready
        ) { ReaManager.setWebEnabled(it) }
        if (!state.webEnabled) return@InfoCard
        if (!state.usable) Hint(stringResource(R.string.rea_screen_install_rea_and_the_mcp_launcher_above))
        Hint(stringResource(R.string.rea_screen_rea_servers_you_already_added_only_show))

        SwitchRow(
            "Browser connection (port ${bridge.port})",
            "Opens ${bridge.endpoint} so REA can reach the browser's tabs through Chrome DevTools. Only browser tabs are shown, never your chats.",
            bridge.running
        ) { ReaManager.setBrowserConnection(context, it) }
        Surface(shape = RoundedCornerShape(10.dp), color = Amber.copy(alpha = 0.12f)) {
            Text(
                stringResource(R.string.rea_screen_while_this_is_on_any_app_on, bridge.port.toString()),
                fontSize = 12.sp, color = Amber, modifier = Modifier.padding(10.dp)
            )
        }
        bridge.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        if (bridge.running) {
            CopyLine(bridge.endpoint, mono = true)
            Mono(
                "${bridge.connections} REA connection${if (bridge.connections == 1) "" else "s"} open · ${bridge.requests} requests" +
                    (bridge.lastActivity?.let { "\nLast: $it" } ?: "")
            )
        }
        Text(stringResource(R.string.android_dev_screen_try_in_chat), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        listOf(
            "Open https://example.com in the browser, then analyze it with REA: structure, technologies, scripts and the APIs it calls, and give recommendations.",
            "With REA, check the open browser tab for accessibility problems, console errors and failed requests, with a screenshot.",
            "Use REA to find which frameworks and libraries the site in the browser uses, with the evidence."
        ).forEach { CopyLine(it) }
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun ManualSetupCard(state: ReaManager.State, cardBg: Color, cardBorder: BorderStroke) {
    var open by remember { mutableStateOf(false) }
    val repo = runCatching { ai.antigem.ui.browser.PluginRepositories.parse(state.source) }.getOrNull()
    val raw = "https://raw.githubusercontent.com/${repo?.slug ?: "Santoshkurmi/antigem_plugins"}/${repo?.ref?.ifBlank { "main" } ?: "main"}/rea/skills/rea/scripts"
    // In the Ubuntu chroot the scripts run inside it, as root (AntiGem copies the files to /opt/antigem/share/antigem-rea)
    val chrootSetup = "sudo env ANTIGEM_RUNTIME=chroot bash /opt/antigem/share/antigem-rea/rea-setup.sh"
    val steps = if (state.chroot) listOf(
        "1. The setup files are in /opt/antigem/share/antigem-rea (Get setup files above copies them there). See every step" to "$chrootSetup help",
        "2. Install what APK analysis needs (or name single steps: base java node rea jadx apktool native launcher)" to "$chrootSetup android",
        "3. Optional extras (adb and fastboot, hermes-dec, gdb, binwalk)" to "$chrootSetup adb hermes debug firmware",
        "4. Check it" to "rea-mcp --doctor",
        "5. Connect it to an agent: in the app, use the REA preset in the agent's MCP settings. By hand, a stdio MCP server with this command and env (REA_MODE lazy = on demand, ≈0.7k tokens; direct = every tool, each costs context; REA_GROUPS all or group ids from ~/.config/antigem/rea/groups.json)" to
            "${ReaManager.CHROOT_LAUNCHER}   REA_MODE=lazy  REA_GROUPS=all",
        "6. Run REA's tools by hand (hbc-decompiler, apktool, rea …)" to "rea-shell"
    ) else listOf(
        "1. Download the setup files" to
            "mkdir -p \$PREFIX/share/antigem-rea && cd \$PREFIX/share/antigem-rea && for f in files.json rea-setup.sh rea-mcp rea-shell rea-filter.mjs groups.default.json; do curl -fsSLO $raw/\$f; done",
        "2. See every step" to "bash \$PREFIX/share/antigem-rea/rea-setup.sh help",
        "3. Install what APK analysis needs (or name single steps: container base java node rea jadx apktool native launcher)" to
            "bash \$PREFIX/share/antigem-rea/rea-setup.sh android",
        "4. Optional extras" to "bash \$PREFIX/share/antigem-rea/rea-setup.sh adb hermes debug firmware",
        "5. Check it" to "rea-mcp --doctor",
        "6. Connect it to an agent: in the app, use the REA preset in the agent's MCP settings. By hand, a stdio MCP server with this command and env (REA_MODE lazy = on demand, ≈0.7k tokens; direct = every tool, each costs context; REA_GROUPS all or group ids from ~/.config/antigem/rea/groups.json)" to
            "${ReaManager.LAUNCHER}   REA_MODE=lazy  REA_GROUPS=all",
        "7. Run REA's tools by hand (hbc-decompiler, apktool, rea …) inside the container" to "rea-shell"
    )
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.android_dev_screen_set_up_in_the_terminal), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) stringResource(R.string.claude_settings_screen_hide) else stringResource(R.string.claude_settings_screen_show), fontSize = 12.5.sp) }
            }
            if (!open) {
                Hint(stringResource(R.string.rea_screen_prefer_the_terminal_or_setting_up_claude))
                return@Column
            }
            steps.forEach { (label, command) ->
                Hint(label)
                CopyLine(command, mono = true)
            }
            Hint(stringResource(R.string.rea_screen_each_step_can_be_run_again_to))
        }
    }
}

@Composable
private fun RemoveCard(state: ReaManager.State, busy: Boolean, cardBg: Color, cardBorder: BorderStroke, onConfirm: (String) -> Unit) {
    if (state.installed.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.browser_screen_remove), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) stringResource(R.string.claude_settings_screen_hide) else stringResource(R.string.claude_settings_screen_show), fontSize = 12.5.sp) }
            }
            if (!open) return@Column
            Hint(if (state.chroot) "移除 REA 会在 Ubuntu chroot 中保留 Java 和 Node.js 供其他用途。chroot 本身在它自己的页面删除（智能体运行在哪里 → Ubuntu chroot）。"
                else "移除 REA 会保留 Ubuntu 容器（以及其中的 Java 和 Node.js）供其他用途。删除容器可以释放全部空间。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && state.filesReady && (Component.REA in state.installed || Component.LAUNCHER in state.installed), onClick = { onConfirm("uninstall") }) { Text(stringResource(R.string.rea_screen_remove_rea)) }
                // the chroot is the phone's runtime, not REA's container: never deleted from here
                if (!state.chroot) OutlinedButton(
                    enabled = !busy && state.filesReady && Component.CONTAINER in state.installed,
                    onClick = { onConfirm("remove-container") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.rea_screen_delete_container)) }
            }
            ReaTaskOutput(state, "uninstall")
            ReaTaskOutput(state, "remove-container")
        }
    }
}

/**
 * The REA preset of every agent's MCP settings (Antigravity, Claude Code, OpenCode, Codex): adds or updates an REA MCP server.
 * The user chooses how the agent sees REA's tools (on demand through four small gateway tools, or every tool
 * directly), which tools it may use (tool groups or all), and in on-demand mode which groups to offer directly as
 * well; each choice shows what it does and what it costs in context per request.
 *
 * Agent-neutral: [existing] are this agent's REA servers (name → env), [takenNames] all its server names. [onSave]
 * gets the name and env; the page saves a stdio server with the command [ReaManager.LAUNCHER] in its own format.
 * [canReplace]: the agent can update a server in place (otherwise an existing name must be removed first).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaServerDialog(
    agent: String,
    existing: Map<String, Map<String, String>>,
    takenNames: Set<String>,
    canReplace: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, env: Map<String, String>) -> Unit,
    onOpenRea: (() -> Unit)? = null,
    onOpenGroups: (() -> Unit)? = null
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ReaManager.init(context)
        ReaManager.refresh()
    }
    val state by ReaManager.state.collectAsState()
    // Opens on the agent's first REA server (to change it) or a free name for a new one
    var name by remember { mutableStateOf(existing.keys.firstOrNull() ?: generateSequence(1) { it + 1 }.map { if (it == 1) "rea" else "rea-$it" }.first { it !in takenNames }) }
    val current = existing[name]
    var mode by remember { mutableStateOf(ReaManager.Mode.ON_DEMAND) }
    var allTools by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }
    val direct = remember { mutableStateListOf<String>() }
    var fullSchemas by remember { mutableStateOf(false) }
    // Load the settings of the REA server the name points at; a new name starts from the recommended settings
    var loadedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(name, state.ready, state.groups) {
        if (!state.ready || loadedFor == name) return@LaunchedEffect
        val env = existing[name]
        if (env == null && loadedFor != null) return@LaunchedEffect // typing a new name keeps the choices made
        chosen.clear(); direct.clear()
        if (env != null) {
            mode = ReaManager.modeOf(env)
            val groups = ReaManager.groupsOf(env)
            allTools = groups.isEmpty()
            chosen.addAll(groups)
            direct.addAll(ReaManager.directGroupsOf(env))
            fullSchemas = ReaManager.fullOutputSchemas(env)
        } else {
            // New server: on demand costs the same whatever it allows, so allow every group that works without
            // extra engines (the decompiler session needs Ghidra/IDA/Hopper/Kuna)
            mode = ReaManager.Mode.ON_DEMAND
            allTools = false
            chosen.addAll(state.groups.map { it.id }.filter { it != "decompiler" })
            fullSchemas = false
        }
        loadedFor = name
    }
    val initialGroups = current?.let { ReaManager.groupsOf(it) }
    val nameError = when {
        name.isBlank() -> "Enter a name"
        !Regex("[A-Za-z0-9_-]+").matches(name) -> "Letters, digits, - and _ only"
        current == null && name in takenNames -> "Another server has this name"
        current != null && !canReplace -> "Already added. To change it, remove it from the list first, or use another name."
        else -> null
    }
    val scope = if (allTools) listOf("all") else chosen.toList()
    val directIds = direct.filter { allTools || it in chosen }
    val usable = state.toolsFor(scope).size
    val directCount = state.toolsFor(scope).intersect(if (directIds.isEmpty()) emptySet() else state.toolsFor(directIds)).size
    val onDemandTokens = state.serverTokens(ReaManager.Mode.ON_DEMAND, scope, directIds, fullSchemas)
    val directTokens = state.serverTokens(ReaManager.Mode.DIRECT, scope, emptyList(), fullSchemas)
    val tokens = if (mode == ReaManager.Mode.ON_DEMAND) onDemandTokens else directTokens
    fun tk(t: Int?) = t?.let { "≈${formatTokens(it)} tokens" } ?: "size unknown until REA is installed"
    val dialogScroll = rememberScrollState()
    val scrollScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current != null && canReplace) stringResource(R.string.rea_screen_rea_server, name.toString(), agent.toString()) else stringResource(R.string.rea_screen_add_rea_to, agent.toString())) },
        text = {
            // The dialog has more than fits on a screen: a scrollbar, a fade at the bottom and a "More options
            // below" button make that visible, so the choices further down are not missed
            Column {
            Box(Modifier.weight(1f, fill = false).scrollIndicator(dialogScroll, AlertDialogDefaults.containerColor)) {
            Column(
                Modifier.bouncyVerticalScroll(dialogScroll).padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!state.usable) {
                    Text(stringResource(R.string.rea_screen_rea_is_not_installed_yet_set_it), fontSize = 12.5.sp, color = Amber)
                    if (onOpenRea != null) OutlinedButton(onClick = onOpenRea) { Text(stringResource(R.string.rea_groups_screen_open_rea_setup)) }
                }
                if (ai.antigem.data.preferences.AuthPreferences.currentRemoteMode) {
                    Text(
                        stringResource(R.string.rea_screen_server_mode_is_on_agents_start_mcp),
                        fontSize = 12.5.sp, color = Amber
                    )
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it.trim() }, singleLine = true,
                    label = { Text(stringResource(R.string.rea_screen_server_name)) }, isError = nameError != null,
                    supportingText = {
                        Text(nameError ?: if (current != null) stringResource(R.string.rea_screen_changes_the_existing_rea_server, name.toString()) else if (existing.isNotEmpty()) stringResource(R.string.rea_screen_a_new_rea_server_type_an_existing, (existing.keys.joinToString()).toString()) else stringResource(R.string.rea_screen_a_new_rea_server))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(stringResource(R.string.rea_screen_how_the_agent_sees_rea_s_tools), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                ModeCard(
                    selected = mode == ReaManager.Mode.ON_DEMAND,
                    title = stringResource(R.string.rea_screen_on_demand),
                    badge = "推荐",
                    cost = tk(onDemandTokens),
                    text = "智能体获得 4 个小工具：按名称列出 REA 的工具，读取需要的详情，然后运行。" +
                        "无论允许多少工具，上下文都很小。首次使用某个工具会多花 1–2 步。" +
                        "会改动内容的工具（安装应用、调试器、Frida……）通过各自的专用工具执行，因此可以单独审批。"
                ) { mode = ReaManager.Mode.ON_DEMAND }
                ModeCard(
                    selected = mode == ReaManager.Mode.DIRECT,
                    title = stringResource(R.string.rea_screen_directly),
                    badge = null,
                    cost = tk(directTokens),
                    text = "每个允许的工具都是普通 MCP 工具，智能体可直接使用。但每个请求都会携带所有工具的描述：" +
                        "建议只允许少数几个组（默认五组约 24k token；REA 全部约 75k）。"
                ) { mode = ReaManager.Mode.DIRECT }

                Text(stringResource(R.string.rea_screen_tools_the_agent_may_use), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { allTools = false }) {
                    RadioButton(selected = !allTools, onClick = { allTools = false }); Text(stringResource(R.string.rea_screen_chosen_tool_groups), fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { allTools = true }) {
                    RadioButton(selected = allTools, onClick = { allTools = true }); Text(stringResource(R.string.rea_screen_all_rea_tools, state.allToolNames.size.toString()), fontSize = 13.sp)
                }
                if (!allTools) {
                    if (state.groups.isEmpty()) {
                        Hint(stringResource(R.string.rea_screen_no_tool_groups_yet_download_the_setup))
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            state.groups.forEach { g ->
                                FilterChip(
                                    selected = g.id in chosen,
                                    onClick = { if (g.id in chosen) chosen.remove(g.id) else chosen.add(g.id) },
                                    label = {
                                        val cost = if (mode == ReaManager.Mode.DIRECT) state.tokensFor(listOf(g.id))?.let { " · ${formatTokens(it)}" }.orEmpty() else ""
                                        Text("${g.name} · ${g.tools.size}$cost", fontSize = 12.sp)
                                    }
                                )
                            }
                        }
                        Hint(
                            if (mode == ReaManager.Mode.ON_DEMAND) stringResource(R.string.rea_screen_on_demand_allowing_more_groups_costs_nothing)
                            else stringResource(R.string.rea_screen_directly_each_group_adds_its_size_tokens)
                        )
                        val missing = initialGroups.orEmpty().filter { id -> state.groups.none { it.id == id } }
                        if (missing.isNotEmpty()) Text(stringResource(R.string.rea_screen_removed_groups_still_named_here_saving_drops, (missing.joinToString()).toString()), fontSize = 11.5.sp, color = Amber)
                        if (onOpenGroups != null) TextButton(onClick = onOpenGroups, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.rea_screen_edit_tool_groups), fontSize = 12.5.sp) }
                    }
                }

                if (mode == ReaManager.Mode.ON_DEMAND) {
                    Text(stringResource(R.string.rea_screen_also_offer_directly_optional), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Hint(stringResource(R.string.rea_screen_for_groups_the_agent_uses_all_the))
                    val offerable = state.groups.filter { allTools || it.id in chosen }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        offerable.forEach { g ->
                            FilterChip(
                                selected = g.id in direct,
                                onClick = { if (g.id in direct) direct.remove(g.id) else direct.add(g.id) },
                                label = {
                                    val cost = state.tokensFor(listOf(g.id))?.let { " +${formatTokens(it)}" }.orEmpty()
                                    Text("${g.name}$cost", fontSize = 12.sp)
                                }
                            )
                        }
                    }
                }

                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.rea_screen_of_context_per_request, (tk(tokens).replaceFirstChar { it.uppercase() }).toString()), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (mode == ReaManager.Mode.ON_DEMAND) "$usable tools usable" + if (directCount > 0) ", $directCount offered directly" else ", all through the 4 gateway tools"
                            else stringResource(R.string.rea_screen_tools_each_one_an_mcp_tool, usable.toString()),
                            fontSize = 12.sp
                        )
                        if (mode == ReaManager.Mode.ON_DEMAND && directTokens != null) {
                            Text(stringResource(R.string.rea_screen_directly_the_same_tools_would_cost, (tk(directTokens)).toString()), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        if (tokens != null && tokens > 40_000) Text(stringResource(R.string.rea_screen_that_is_a_lot_agents_get_slower), fontSize = 11.5.sp, color = Amber)
                    }
                }
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.clickable { fullSchemas = !fullSchemas }) {
                    Checkbox(checked = fullSchemas, onCheckedChange = { fullSchemas = it })
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.rea_screen_full_output_schemas), fontSize = 13.sp)
                        Text(
                            stringResource(R.string.rea_screen_describes_the_shape_of_every_rea_result, ReaManager.FULL_SCHEMA_FACTOR.toString()),
                            fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                Hint(stringResource(R.string.rea_screen_changes_apply_when_the_server_restarts))
            }
            }
            if (dialogScroll.canScrollForward) {
                TextButton(
                    onClick = { scrollScope.launch { dialogScroll.animateScrollTo(dialogScroll.maxValue) } },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Outlined.KeyboardDoubleArrowDown, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.rea_screen_more_options_below), fontSize = 12.5.sp)
                }
            }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameError == null && (allTools || chosen.isNotEmpty()),
                onClick = {
                    onSave(
                        name,
                        ReaManager.serverEnv(
                            if (allTools) emptyList() else chosen.toList(), fullSchemas, mode,
                            directGroups = if (mode == ReaManager.Mode.ON_DEMAND) directIds else emptyList(),
                            base = current.orEmpty()
                        )
                    )
                }
            ) { Text(stringResource(R.string.claude_providers_page_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_cancel)) } }
    )
}

/** One choice of how the agent sees the tools: a selectable card with what it does and what it costs. */
@Composable
private fun ModeCard(selected: Boolean, title: String, badge: String?, cost: String, text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(end = 10.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    badge?.let { Pill(it, QuotaGreen) }
                }
                Text(stringResource(R.string.rea_screen_per_request, cost), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * Compose has no scrollbar of its own: draws a thin thumb on the right of a scrolling area and fades its bottom edge
 * into [background] while there is more below, so it is clear the content continues.
 */
internal fun Modifier.scrollIndicator(state: ScrollState, background: Color): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    if (state.canScrollForward) {
        val fade = 36.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(listOf(background.copy(alpha = 0f), background), startY = size.height - fade, endY = size.height),
            topLeft = Offset(0f, size.height - fade),
            size = Size(size.width, fade)
        )
    }
    val track = size.height - 8.dp.toPx()
    val thumbH = (track * size.height / (size.height + max)).coerceAtLeast(24.dp.toPx())
    val thumbY = 4.dp.toPx() + (track - thumbH) * state.value / max
    val thumbW = 4.dp.toPx()
    drawRoundRect(
        color = Color.Gray.copy(alpha = 0.55f),
        topLeft = Offset(size.width - thumbW, thumbY),
        size = Size(thumbW, thumbH),
        cornerRadius = CornerRadius(thumbW / 2)
    )
}

@Composable
private fun InfoCard(
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

@Composable
private fun CopyLine(text: String, mono: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (mono) Mono(text) else Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        }
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy_text), Modifier.size(18.dp)) }
    }
}

internal fun formatTokens(tokens: Int): String = if (tokens >= 1000) "${(tokens + 500) / 1000}k" else "$tokens"

private fun openReaUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
