package ai.antigem.ui.settings

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.BuildConfig
import ai.antigem.data.adb.AdbConnectManager
import ai.antigem.data.x11.X11DesktopManager
import ai.antigem.data.x11.X11DesktopManager.Browser
import ai.antigem.data.x11.X11DesktopManager.Desktop
import ai.antigem.data.x11.X11DesktopManager.Task
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.delay

/** Settings → X11 Desktop: installs Termux:X11, its termux-x11 command and a desktop, then starts it. */
@Composable
fun X11DesktopScreen(cardBg: Color, cardBorder: BorderStroke, onOpenAdbConnect: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        X11DesktopManager.init(context)
        AdbConnectManager.init(context)
        AdbConnectManager.refresh(quiet = true)
        // The installer, the uninstaller and the terminal change things while the user is away; keep them live
        while (true) {
            if (X11DesktopManager.state.value.task == null) X11DesktopManager.refresh()
            delay(1500)
        }
    }
    val state by X11DesktopManager.state.collectAsState()
    val adb by AdbConnectManager.state.collectAsState()
    val busy = state.task != null

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IntroCard(state.running, cardBg, cardBorder)

        state.message?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = X11DesktopManager::clearMessage) { Icon(Icons.Default.Close, stringResource(R.string.browser_screen_dismiss), Modifier.size(18.dp)) }
                }
            }
        }

        if (!state.ready) {
            Hint(stringResource(R.string.bridge_ui_checking))
            return@Column
        }
        if (!state.bootstrapInstalled) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.x11_desktop_screen_install_the_linux_environment_first), fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    Hint(stringResource(R.string.x11_desktop_screen_the_desktop_runs_in_antigem_s_terminal))
                }
            }
            return@Column
        }

        // Step 1: the Termux:X11 app (shows the desktop)
        val app = state.app
        StepCard(
            1, stringResource(R.string.x11_desktop_screen_termux_x11_app),
            when {
                app == null -> null
                state.appOk -> stringResource(R.string.claude_settings_screen_installed)
                else -> stringResource(R.string.x11_desktop_screen_wrong_build)
            },
            cardBg, cardBorder,
            badgeColor = if (state.appOk) QuotaGreen else Amber
        ) {
            when {
                app == null -> {
                    Hint(stringResource(R.string.x11_desktop_screen_antigem_s_own_build_of_termux_x11))
                    Button(enabled = !busy, onClick = X11DesktopManager::installApp) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.x11_desktop_screen_download_install))
                    }
                }
                state.appOk -> {
                    Mono(
                        "Termux:X11 ${app.versionName ?: ""}".trim() + "\n" + when {
                            app.sameProcess -> "AntiGem's build, runs inside AntiGem"
                            app.antigemBuild -> "AntiGem's build"
                            else -> "Official build"
                        }
                    )
                    OutlinedButton(enabled = !busy, onClick = X11DesktopManager::installApp) { Text(stringResource(R.string.claude_settings_screen_update)) }
                }
                else -> {
                    Text(
                        if (app.antigemBuild) stringResource(R.string.x11_desktop_screen_this_termux_x11_doesn_t_share_antigem)
                        else stringResource(R.string.x11_desktop_screen_the_official_termux_x11_is_installed_it),
                        fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error
                    )
                    Button(enabled = !busy, onClick = { X11DesktopManager.uninstallApp(context) }) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.x11_desktop_screen_uninstall_termux_x11))
                    }
                }
            }
            TextButton(
                onClick = { openUrl(context, X11DesktopManager.FORK_RELEASES_PAGE) },
                contentPadding = PaddingValues(0.dp)
            ) { Text(stringResource(R.string.x11_desktop_screen_open_the_download_page), fontSize = 12.5.sp) }
            TaskOutput(state, Task.APP)
        }

        // Step 2: the companion package (the termux-x11 command)
        StepCard(
            2, stringResource(R.string.x11_desktop_screen_termux_x11_command),
            when {
                state.companionOk -> stringResource(R.string.claude_settings_screen_installed)
                state.companion != null -> stringResource(R.string.x11_desktop_screen_wrong_package)
                else -> null
            },
            cardBg, cardBorder,
            badgeColor = if (state.companionOk) QuotaGreen else Amber
        ) {
            Hint(stringResource(R.string.x11_desktop_screen_the_terminal_part_the_termux_x11_command, state.neededCompanion.toString(), (if (app == null) "the Termux:X11 above" else "the installed Termux:X11").toString()))
            if (state.companion != null && !state.companionOk) {
                Text(
                    stringResource(R.string.x11_desktop_screen_is_installed_but_this_termux_x11_needs, state.companion.toString(), state.neededCompanion.toString()),
                    fontSize = 12.5.sp, color = Amber
                )
            }
            if (!state.companionOk) {
                Button(enabled = !busy, onClick = X11DesktopManager::installCompanion) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.plugin_repositories_tab_install, state.neededCompanion.toString()))
                }
            } else {
                OutlinedButton(enabled = !busy, onClick = X11DesktopManager::installCompanion) { Text(stringResource(R.string.android_dev_screen_reinstall)) }
            }
            TaskOutput(state, Task.COMPANION)
        }

        // Step 3: a desktop
        StepCard(3, stringResource(R.string.x11_desktop_screen_desktop), if (state.desktopOk) stringResource(R.string.claude_settings_screen_installed) else null, cardBg, cardBorder) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Desktop.entries.forEach { d ->
                    FilterChip(
                        selected = state.desktop == d,
                        enabled = !busy && !state.running,
                        onClick = { X11DesktopManager.setDesktop(d) },
                        label = { Text(if (d in state.installedDesktops) "${d.label} ✓" else d.label) }
                    )
                }
            }
            Hint(
                when (state.desktop) {
                    Desktop.XFCE -> "XFCE is a full, light desktop and a good place to start."
                    Desktop.LXQT -> "LXQt is a light Qt desktop."
                } + " It comes from x11-repo and is a large download."
            )
            if (!state.desktopOk) {
                Button(enabled = !busy, onClick = X11DesktopManager::installDesktop) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.plugin_repositories_tab_install, state.desktop.label.toString()))
                }
            }
            TaskOutput(state, Task.DESKTOP)
        }

        // Step 4: start / open / stop
        StepCard(4, stringResource(R.string.x11_desktop_screen_start_the_desktop), if (state.running) stringResource(R.string.agent_tool_call_card_running) else null, cardBg, cardBorder) {
            if (state.running) {
                Hint(stringResource(R.string.x11_desktop_screen_the_x_server_is_running_on_display))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { X11DesktopManager.open(context) }) {
                        Icon(Icons.Outlined.DesktopWindows, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.x11_desktop_screen_open_desktop))
                    }
                    OutlinedButton(enabled = !busy, onClick = { X11DesktopManager.stop(context) }) { Text(stringResource(R.string.chat_input_bar_stop)) }
                }
            } else {
                if (!state.canStart) Hint(stringResource(R.string.adb_connect_screen_finish_the_steps_above_first))
                Button(enabled = !busy && state.canStart, onClick = { X11DesktopManager.start(context) }) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.x11_desktop_screen_start, state.desktop.label.toString()))
                }
            }
            TaskOutput(state, Task.START)
        }

        // Web Browser: Chromium or Firefox from tur-repo
        val browserInstalled = state.browser in state.installedBrowsers
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.x11_desktop_screen_web_browser), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (browserInstalled) {
                        Pill(stringResource(R.string.claude_settings_screen_installed), QuotaGreen)
                    } else {
                        Pill(stringResource(R.string.android_automation_screen_optional), MaterialTheme.colorScheme.primary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Browser.entries.forEach { b ->
                        FilterChip(
                            selected = state.browser == b,
                            enabled = !busy,
                            onClick = { X11DesktopManager.setBrowser(b) },
                            label = { Text(if (b in state.installedBrowsers) "${b.label} ✓" else b.label) }
                        )
                    }
                }
                Hint(stringResource(R.string.x11_desktop_screen_install_a_browser_from_tur_repo_to))
                if (!state.desktopOk) {
                    Hint(stringResource(R.string.x11_desktop_screen_install_a_desktop_above_first))
                }
                if (!browserInstalled) {
                    Button(
                        enabled = !busy && state.desktopOk,
                        onClick = X11DesktopManager::installBrowser
                    ) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.plugin_repositories_tab_install, state.browser.label.toString()))
                    }
                } else {
                    OutlinedButton(
                        enabled = !busy && state.desktopOk,
                        onClick = X11DesktopManager::installBrowser
                    ) {
                        Text(stringResource(R.string.x11_desktop_screen_reinstall, state.browser.label.toString()))
                    }
                }
                TaskOutput(state, Task.BROWSER)
            }
        }

        // Optional: Android 12+ kills apps' extra processes past a limit, and a desktop starts many
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.x11_desktop_screen_if_the_desktop_closes_by_itself), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Pill(stringResource(R.string.android_automation_screen_optional), MaterialTheme.colorScheme.primary)
                    }
                    Hint(stringResource(R.string.x11_desktop_screen_android_12_and_newer_stop_the_extra))
                    val device = adb.connectedDevice
                    if (device != null) {
                        Button(enabled = !busy, onClick = { X11DesktopManager.disableChildProcessLimit(device.serial) }) { Text(stringResource(R.string.x11_desktop_screen_turn_off_the_limit)) }
                    } else {
                        Hint(stringResource(R.string.x11_desktop_screen_connect_the_terminal_s_adb_to_this))
                        OutlinedButton(onClick = onOpenAdbConnect) { Text(stringResource(R.string.android_dev_screen_set_up_adb_connect)) }
                    }
                    TaskOutput(state, Task.CHILD_LIMIT)
                }
            }
        }

        ManualSetupCard(state.desktop, cardBg, cardBorder)
    }
}

@Composable
private fun IntroCard(running: Boolean, cardBg: Color, cardBorder: BorderStroke) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DesktopWindows, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.x11_desktop_screen_linux_desktop), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (running) Pill(stringResource(R.string.agent_tool_call_card_running), QuotaGreen)
            }
            Hint(stringResource(R.string.x11_desktop_screen_run_linux_desktop_apps_a_browser_an))
            Text(
                stringResource(R.string.x11_desktop_screen_the_desktop_runs_inside_antigem_so_android),
                fontSize = 12.sp, color = QuotaGreen
            )
        }
    }
}

/** Progress and the log of [task], shown in its own step while it runs and after it finishes. */
@Composable
private fun TaskOutput(state: X11DesktopManager.State, task: Task) {
    if (state.task == task) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: stringResource(R.string.claude_login_dialog_starting))
    }
    if (state.logTask == task && state.log.isNotEmpty()) LogBox(state.log)
}

@Composable
private fun ManualSetupCard(desktop: Desktop, cardBg: Color, cardBorder: BorderStroke) {
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    val start = "termux-x11 :0 -xstartup \"${desktop.startCommand}\""
    val commands = listOf(
        "1. Install antigem-termux-x11-sharedUid.apk and download antigem-termux-x11-*-all.deb from ${X11DesktopManager.FORK_RELEASES_PAGE}" to null,
        "2. Enable x11-repo" to "pkg install x11-repo",
        "3. Install the termux-x11 command (in the folder with the .deb)" to "apt install ./antigem-termux-x11-*-all.deb",
        "4. Update your packages, then install a desktop" to "pkg upgrade -y && pkg install dbus ${desktop.packages}",
        "5. Start it, then open Termux:X11" to start
    )
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.x11_desktop_screen_set_up_manually), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) stringResource(R.string.claude_settings_screen_hide) else stringResource(R.string.claude_settings_screen_show), fontSize = 12.5.sp) }
            }
            if (!open) {
                Hint(stringResource(R.string.android_dev_screen_prefer_the_terminal_these_are_the_same))
                return@Column
            }
            commands.forEach { (label, command) ->
                Hint(label)
                if (command != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Mono(command) }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(command)) }) {
                            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy_text), Modifier.size(18.dp))
                        }
                    }
                }
            }
            Hint(stringResource(R.string.x11_desktop_screen_don_t_install_termux_x11_nightly_from))
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
