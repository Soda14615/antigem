package ai.antigem.ui.settings

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.adb.AdbConnectManager
import ai.antigem.data.androiddev.AndroidDevManager
import ai.antigem.data.androiddev.AndroidDevManager.Component
import ai.antigem.data.androiddev.AndroidDevManager.Framework
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.delay

/**
 * Settings → Android Development: installs what is needed to build Android apps on the phone (Kotlin / Java /
 * Compose, Flutter, React Native) in the proot-distro Ubuntu container, part by part or per framework, builds a
 * hello world to check it, and installs the skill that teaches agents to use it. Agent-neutral: every agent uses
 * the same `adev` command in the terminal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AndroidDevScreen(
    cardBg: Color,
    cardBorder: BorderStroke,
    onOpenAdbConnect: () -> Unit,
    onOpenPlugins: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        AndroidDevManager.init(context)
        AdbConnectManager.init(context)
        AdbConnectManager.refresh(quiet = true)
        AndroidDevManager.refresh()
        AndroidDevManager.checkForUpdate()
        // The terminal (or an agent) changes things while the user is away; keep the page live
        while (true) {
            delay(2000)
            if (!AndroidDevManager.state.value.busy) AndroidDevManager.refresh()
        }
    }
    val state by AndroidDevManager.state.collectAsState()
    val adb by AdbConnectManager.state.collectAsState()
    val busy = state.busy

    // Installs and first builds take minutes: keep the screen on while one runs (the manager also holds a wake lock)
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
        IntroCard(state, cardBg, cardBorder)

        state.message?.let { msg ->
            Surface(shape = RoundedCornerShape(12.dp), color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = AndroidDevManager::clearMessage) { Icon(Icons.Default.Close, stringResource(R.string.browser_screen_dismiss), Modifier.size(18.dp)) }
                }
            }
        }

        if (!state.ready) {
            Hint(stringResource(R.string.bridge_ui_checking))
            return@Column
        }
        if (!state.bootstrapInstalled) {
            DevInfoCard("Install the terminal first", cardBg, cardBorder) {
                Hint(stringResource(R.string.android_dev_screen_the_tools_install_into_antigem_s_terminal))
            }
            return@Column
        }

        // Step 1: setup files, downloaded on demand (nothing of this ships in the app)
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
            Hint(stringResource(R.string.android_dev_screen_the_setup_script_android_setup_sh_and))
            DevSourceRow(state.source, enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.filesVersion == null -> Button(enabled = !busy, onClick = AndroidDevManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.android_dev_screen_get_setup_files))
                    }
                    state.filesUpdate != null -> Button(enabled = !busy, onClick = AndroidDevManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.android_dev_screen_update_to_v, state.filesUpdate.toString()))
                    }
                    else -> OutlinedButton(enabled = !busy, onClick = AndroidDevManager::downloadSetupFiles) { Text(stringResource(R.string.android_dev_screen_download_again)) }
                }
                if (state.filesVersion != null && state.filesUpdate == null) {
                    TextButton(enabled = !busy, onClick = AndroidDevManager::checkForUpdate) { Text(stringResource(R.string.android_dev_screen_check_for_update)) }
                }
            }
            TaskOutput(state, "files")
        }

        // Step 2: what to build → the parts it needs
        InstallStep(state, cardBg, cardBorder)

        // Step 3: a hello world per framework proves the setup works
        StepCard(3, stringResource(R.string.android_dev_screen_build_a_test_app), if (state.testApks.isNotEmpty()) stringResource(R.string.android_dev_screen_built, state.testApks.size.toString()) else null, cardBg, cardBorder) {
            Hint(stringResource(R.string.android_dev_screen_creates_a_hello_world_app_in_and, AndroidDevManager.PROJECTS_DIR.toString()))
            Framework.entries.forEach { f -> TestBuildRow(f, state, busy) }
            if (state.task?.startsWith("test-") == true || state.logTask?.startsWith("test-") == true) {
                TaskOutput(state, state.task ?: state.logTask!!)
            }
        }

        // Step 4: agents use it from the terminal, the same way whatever the agent
        StepCard(4, stringResource(R.string.android_dev_screen_use_it), null, cardBg, cardBorder) {
            UseGuide(state)
        }

        SkillCard(state, busy, cardBg, cardBorder, onOpenPlugins)

        DevInfoCard("Install on this phone", cardBg, cardBorder, pill = if (adb.connectedDevice != null) "adb connected" to QuotaGreen else "Optional" to MaterialTheme.colorScheme.primary) {
            Hint(stringResource(R.string.android_dev_screen_the_install_buttons_above_open_android_s))
            val device = adb.connectedDevice
            if (device != null) Mono("adb connected: ${device.serial}") else OutlinedButton(onClick = onOpenAdbConnect) { Text(stringResource(R.string.android_dev_screen_set_up_adb_connect)) }
        }

        // Check
        DevInfoCard("Check the tools", cardBg, cardBorder) {
            Hint(stringResource(R.string.android_dev_screen_prints_the_version_of_every_tool_in))
            OutlinedButton(enabled = !busy && Component.CONTAINER in state.installed && state.filesReady, onClick = AndroidDevManager::runDoctor) {
                Icon(Icons.Outlined.HealthAndSafety, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.android_dev_screen_check))
            }
            TaskOutput(state, "doctor")
        }

        TerminalCard(state, cardBg, cardBorder)

        RemoveCard(state, busy, cardBg, cardBorder) { confirm = it }
    }

    confirm?.let { what ->
        val (title, text, action) = when (what) {
            "uninstall-flutter" -> Triple("Remove Flutter?", "Deletes Flutter and its package cache (≈1.5 GB). Your Flutter projects stay.", { AndroidDevManager.uninstallFlutter() })
            "clean-cache" -> Triple("Delete Gradle's caches?", "Frees the space Gradle's downloads use. The next build of every project downloads them again (slow).", { AndroidDevManager.cleanCache() })
            else -> Triple(
                "Remove the Android tools?",
                "Deletes the Android SDK, Flutter, Gradle's files and the adev commands. The Ubuntu container, Java, Node.js (REA uses them) and your projects stay.",
                { AndroidDevManager.uninstall() }
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { action(); confirm = null }) { Text(stringResource(R.string.browser_screen_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
}

@Composable
private fun IntroCard(state: AndroidDevManager.State, cardBg: Color, cardBorder: BorderStroke) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Android, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.android_dev_screen_build_android_apps_on_this_phone), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                when {
                    state.anyReady -> Pill(stringResource(R.string.android_dev_screen_ready), QuotaGreen)
                    state.installed.isNotEmpty() -> Pill(stringResource(R.string.android_dev_screen_partly_set_up), Amber)
                    else -> Pill(stringResource(R.string.android_dev_screen_not_set_up), MaterialTheme.colorScheme.primary)
                }
            }
            Hint(stringResource(R.string.android_dev_screen_let_agents_and_you_create_build_and))
            Hint(stringResource(R.string.android_dev_screen_install_only_what_you_need_kotlin_java))
        }
    }
}

@Composable
private fun DevSourceRow(source: String, enabled: Boolean) {
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
            runCatching { AndroidDevManager.setSource(text) }
                .onSuccess { editing = false; AndroidDevManager.checkForUpdate() }
                .onFailure { error = it.message }
        }) { Text(stringResource(R.string.claude_providers_page_save)) }
        TextButton(onClick = { text = AndroidDevManager.DEFAULT_SOURCE }) { Text(stringResource(R.string.android_dev_screen_default)) }
        TextButton(onClick = { editing = false; text = source }) { Text(stringResource(R.string.accounts_ui_cancel)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstallStep(state: AndroidDevManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val busy = state.busy
    // What the user wants to build; starts with Kotlin / Java (everything needs it)
    val frameworks = remember { mutableStateListOf(Framework.KOTLIN) }
    val extras = remember { mutableStateListOf<Component>() }
    val wanted = (frameworks.flatMap { AndroidDevManager.componentsFor(it) } + extras).distinct()
    val plan = AndroidDevManager.plan(wanted)
    val readyCount = Framework.entries.count { state.ready(it) }

    StepCard(
        2, stringResource(R.string.claude_settings_screen_install),
        if (readyCount > 0) stringResource(R.string.android_dev_screen_of_ready, readyCount.toString(), Framework.entries.size.toString()) else null,
        cardBg, cardBorder,
        badgeColor = if (readyCount > 0) QuotaGreen else Amber
    ) {
        Hint(stringResource(R.string.android_dev_screen_pick_what_you_want_to_build_everything))
        val facts = listOfNotNull(
            state.containerName?.let { "Container: $it" },
            state.platforms.takeIf { it.isNotEmpty() }?.let { "SDK platforms: ${it.joinToString()}" },
            state.flutterVersion?.let { "Flutter $it" },
            state.nodeVersion?.let { "Node.js $it" }
        )
        if (facts.isNotEmpty()) Mono(facts.joinToString("\n"))

        Text(stringResource(R.string.android_dev_screen_what_will_you_build), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Framework.entries.forEach { f ->
            FrameworkRow(f, state, f in frameworks, busy) { on ->
                if (on) frameworks.add(f) else frameworks.remove(f)
            }
        }
        Text(stringResource(R.string.android_dev_screen_extras), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        listOf(Component.ADB, Component.NDK).forEach { c ->
            PartRow(c, state, checked = c in extras, busy = busy) { on -> if (on) extras.add(c) else extras.remove(c) }
        }

        if (isInstallTask(state.task) || isInstallTask(state.logTask)) BatchProgress(state)
        if (!busy) {
            if (plan.isNotEmpty()) Hint(stringResource(R.string.android_dev_screen_installs_part, plan.size.toString(), (if (plan.size == 1) "" else "s").toString(), (plan.joinToString(", ") { it.label }).toString()))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = plan.isNotEmpty(), onClick = { AndroidDevManager.installAll(plan) }) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (plan.isEmpty()) stringResource(R.string.android_dev_screen_nothing_to_install) else stringResource(R.string.android_dev_screen_install_selected, plan.size.toString()))
                }
            }
            if (!state.filesReady) Hint(stringResource(R.string.android_dev_screen_the_setup_files_are_downloaded_first))
        } else if (isInstallTask(state.task)) {
            Hint(stringResource(R.string.android_dev_screen_keep_antigem_open_the_screen_stays_on))
            OutlinedButton(onClick = AndroidDevManager::stop, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.chat_input_bar_stop))
            }
        }

        var showParts by remember { mutableStateOf(false) }
        TextButton(onClick = { showParts = !showParts }, contentPadding = PaddingValues(0.dp)) {
            Text(if (showParts) stringResource(R.string.android_dev_screen_hide_the_parts) else stringResource(R.string.android_dev_screen_all_parts_install_repair_or_update_one), fontSize = 12.5.sp)
        }
        if (showParts) {
            Component.entries.forEach { c -> PartRow(c, state, checked = false, busy = busy, onCheck = null) }
        }
    }
}

@Composable
private fun FrameworkRow(f: Framework, state: AndroidDevManager.State, checked: Boolean, busy: Boolean, onCheck: (Boolean) -> Unit) {
    val ready = state.ready(f)
    val missing = AndroidDevManager.componentsFor(f).filter { it !in state.installed }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy && !ready) { onCheck(!checked) }.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
            if (ready) Icon(Icons.Outlined.CheckCircle, stringResource(R.string.android_dev_screen_ready), tint = QuotaGreen, modifier = Modifier.padding(12.dp).size(22.dp))
            else Checkbox(checked = checked, enabled = !busy, onCheckedChange = onCheck)
            Column(Modifier.weight(1f).padding(top = 10.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (ready) stringResource(R.string.android_dev_screen_ready) else stringResource(R.string.android_dev_screen_needs, f.size.toString(), (missing.joinToString(", ") { it.label }).toString()),
                    fontSize = 11.5.sp, color = if (ready) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Text(f.description, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }
    }
}

/** One part: a checkbox when [onCheck] is set (extras), else install / reinstall only. */
@Composable
private fun PartRow(c: Component, state: AndroidDevManager.State, checked: Boolean, busy: Boolean, onCheck: ((Boolean) -> Unit)?) {
    val installed = c in state.installed
    val running = state.installing == c
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (running) 0.08f else 0.03f)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    installed -> Icon(Icons.Outlined.CheckCircle, stringResource(R.string.claude_settings_screen_installed), tint = QuotaGreen, modifier = Modifier.padding(12.dp).size(22.dp))
                    onCheck != null -> Checkbox(checked = checked, enabled = !busy, onCheckedChange = onCheck)
                    else -> Icon(Icons.Outlined.RadioButtonUnchecked, stringResource(R.string.android_dev_screen_not_installed), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), modifier = Modifier.padding(12.dp).size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(c.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Text(
                        c.size + when {
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
                } else {
                    TextButton(enabled = !busy, onClick = { AndroidDevManager.install(c) }) {
                        Text(if (installed) (if (c == Component.FLUTTER) stringResource(R.string.claude_settings_screen_update) else stringResource(R.string.android_dev_screen_reinstall)) else stringResource(R.string.claude_settings_screen_install), fontSize = 12.5.sp)
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

@Composable
private fun TestBuildRow(f: Framework, state: AndroidDevManager.State, busy: Boolean) {
    val running = state.task == "test-${f.key}"
    val apk = state.testApks[f.key]
    val ready = state.ready(f)
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (running) 0.08f else 0.03f)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        running -> stringResource(R.string.android_dev_screen_building)
                        apk != null -> stringResource(R.string.android_dev_screen_hello_apk_built, AndroidDevManager.PROJECTS_DIR.toString(), f.key.toString())
                        !ready -> stringResource(R.string.android_dev_screen_install_it_first)
                        else -> "~/${AndroidDevManager.PROJECTS_DIR}/hello-${f.key}"
                    },
                    fontSize = 11.5.sp, color = if (apk != null && !running) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            if (running) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
            } else {
                if (apk != null) TextButton(onClick = { AndroidDevManager.installApk(apk) }) { Text(stringResource(R.string.claude_settings_screen_install), fontSize = 12.5.sp) }
                TextButton(enabled = !busy && ready, onClick = { AndroidDevManager.testBuild(f) }) { Text(if (apk != null) stringResource(R.string.android_dev_screen_rebuild) else stringResource(R.string.android_dev_screen_build), fontSize = 12.5.sp) }
            }
        }
    }
}

private fun isInstallTask(key: String?): Boolean = key == "batch" || Component.entries.any { it.step == key }

@Composable
private fun BatchProgress(state: AndroidDevManager.State) {
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

/** Progress and the log of [task], shown while it runs and after it finishes. */
@Composable
private fun TaskOutput(state: AndroidDevManager.State, task: String) {
    if (state.task == task) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: stringResource(R.string.claude_login_dialog_starting))
        OutlinedButton(onClick = AndroidDevManager::stop) { Text(stringResource(R.string.chat_input_bar_stop)) }
    }
    if (state.logTask == task && state.log.isNotEmpty()) LogBox(state.log)
}

/** How agents (any agent) and the user build: the adev commands in the terminal. */
@Composable
private fun UseGuide(state: AndroidDevManager.State) {
    Hint(stringResource(R.string.android_dev_screen_any_agent_that_can_run_terminal_commands))
    if (Component.LAUNCHER !in state.installed) Hint(stringResource(R.string.android_dev_screen_install_kotlin_java_above_first))
    DevCopyLine("adev-new compose ~/${AndroidDevManager.PROJECTS_DIR}/my-app", mono = true)
    DevCopyLine("cd ~/${AndroidDevManager.PROJECTS_DIR}/my-app && adev ./gradlew assembleDebug", mono = true)
    Hint(stringResource(R.string.android_dev_screen_adev_runs_a_command_in_the_container))
    Text(stringResource(R.string.android_dev_screen_try_in_chat), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    listOf(
        "Create a Jetpack Compose app called Notes in ~/AndroidProjects/notes with a list and an add button, build it and give me the APK.",
        "Make a Flutter counter app in ~/AndroidProjects/counter, build the debug APK and install it on this phone.",
        "Create a React Native app in ~/AndroidProjects/hello-rn that shows today's date, and build it."
    ).forEach { DevCopyLine(it) }
}

@Composable
private fun SkillCard(state: AndroidDevManager.State, busy: Boolean, cardBg: Color, cardBorder: BorderStroke, onOpenPlugins: () -> Unit) {
    DevInfoCard(
        "Android Development skill for agents", cardBg, cardBorder,
        pill = if (state.pluginInstalled) "v${state.pluginVersion ?: "?"}" to QuotaGreen else "Recommended" to MaterialTheme.colorScheme.primary
    ) {
        Hint(stringResource(R.string.android_dev_screen_a_skills_only_plugin_from_the_antigem))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = AndroidDevManager::installSkillPlugin) {
                Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (state.pluginInstalled) stringResource(R.string.android_dev_screen_update_skill) else stringResource(R.string.android_dev_screen_install_skill))
            }
            TextButton(onClick = onOpenPlugins) { Text(stringResource(R.string.android_dev_screen_plugins)) }
        }
        TaskOutput(state, "plugin")
        if (state.pluginInstalled) {
            Hint(stringResource(R.string.android_dev_screen_to_give_an_agent_the_skill_type))
        }
    }
}

@Composable
private fun TerminalCard(state: AndroidDevManager.State, cardBg: Color, cardBorder: BorderStroke) {
    var open by remember { mutableStateOf(false) }
    val repo = runCatching { ai.antigem.ui.browser.PluginRepositories.parse(state.source) }.getOrNull()
    val raw = "https://raw.githubusercontent.com/${repo?.slug ?: "Santoshkurmi/antigem_plugins"}/${repo?.ref?.ifBlank { "main" } ?: "main"}/android-dev/skills/android-dev/scripts"
    val s = "\$PREFIX/share/antigem-android/android-setup.sh"
    val steps = listOf(
        "1. Download the setup files" to
            "mkdir -p \$PREFIX/share/antigem-android && cd \$PREFIX/share/antigem-android && for f in files.json android-setup.sh adev adev-new; do curl -fsSLO $raw/\$f; done",
        "2. See every step" to "bash $s help",
        "3. Kotlin / Java / Compose (container base java sdk launcher)" to "bash $s kotlin",
        "4. Flutter (with release builds) and / or React Native (Node.js and the NDK)" to "bash $s flutter flutter-release node ndk",
        "5. Check it, and build a test app" to "bash $s doctor && bash $s test compose"
    )
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.android_dev_screen_set_up_in_the_terminal), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) stringResource(R.string.claude_settings_screen_hide) else stringResource(R.string.claude_settings_screen_show), fontSize = 12.5.sp) }
            }
            if (!open) {
                Hint(stringResource(R.string.android_dev_screen_prefer_the_terminal_these_are_the_same))
                return@Column
            }
            steps.forEach { (label, command) ->
                Hint(label)
                DevCopyLine(command, mono = true)
            }
            Hint(stringResource(R.string.android_dev_screen_each_step_can_be_run_again_to))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoveCard(state: AndroidDevManager.State, busy: Boolean, cardBg: Color, cardBorder: BorderStroke, onConfirm: (String) -> Unit) {
    if (state.installed.none { it == Component.SDK || it == Component.FLUTTER || it == Component.LAUNCHER }) return
    var open by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.android_dev_screen_free_space_remove), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) stringResource(R.string.claude_settings_screen_hide) else stringResource(R.string.claude_settings_screen_show), fontSize = 12.5.sp) }
            }
            if (!open) return@Column
            Hint(stringResource(R.string.android_dev_screen_your_projects_are_never_deleted_the_ubuntu))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(enabled = !busy && state.filesReady, onClick = { onConfirm("clean-cache") }) { Text(stringResource(R.string.android_dev_screen_clean_gradle_cache)) }
                if (Component.FLUTTER in state.installed) OutlinedButton(enabled = !busy && state.filesReady, onClick = { onConfirm("uninstall-flutter") }) { Text(stringResource(R.string.android_dev_screen_remove_flutter)) }
                OutlinedButton(
                    enabled = !busy && state.filesReady,
                    onClick = { onConfirm("uninstall") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.jarvis_settings_screen_remove_all)) }
            }
            TaskOutput(state, "clean-cache")
            TaskOutput(state, "uninstall-flutter")
            TaskOutput(state, "uninstall")
        }
    }
}

@Composable
private fun DevInfoCard(
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
private fun DevCopyLine(text: String, mono: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (mono) Mono(text) else Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        }
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy_text), Modifier.size(18.dp)) }
    }
}
