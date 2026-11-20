package ai.antigem.ui.codex

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.agent.codex.CodexChatBackend
import ai.antigem.data.agent.codex.CodexLimitWindow
import ai.antigem.data.agent.codex.CodexPreferences
import ai.antigem.data.agent.codex.CodexState
import ai.antigem.domain.model.AgentKind
import ai.antigem.ui.claude.ClaudeCard
import ai.antigem.ui.components.CodexAccent
import kotlinx.coroutines.launch

/** The pages of Settings → Codex (each opens from the Codex row list). */
enum class CodexSettingsPage(val titleRes: Int, val subtitleRes: Int) {
    ACCOUNT(R.string.claude_settings_screen_account, R.string.agent_settings_sign_in_with_chatgpt_or_sign_out),
    USAGE(R.string.codex_settings_screen_usage_limits, R.string.agent_settings_how_much_of_your_plan_s_limits_is_used_and_when_they_reset),
    PERMISSIONS(R.string.app_permissions_dialog_permissions, R.string.agent_settings_what_codex_asks_before_acting_in_new_chats),
    MCP(R.string.agent_settings_mcp_servers, R.string.agent_settings_tools_codex_can_call_presets_and_your_own_servers),
    PLUGINS(R.string.android_dev_screen_plugins, R.string.agent_settings_install_plugins_from_codex_catalog_or_any_github_marketplace),
    SKILLS(R.string.agent_settings_skills, R.string.agent_settings_add_skills_from_github_update_or_remove_them),
    SERVER(R.string.codex_settings_screen_server_install, R.string.agent_settings_install_update_and_restart_codex)
}

/** Settings → Codex: one row per page, each with its current state. */
@Composable
fun CodexSettingsGroup(backend: CodexChatBackend, cardBg: Color, onOpen: (CodexSettingsPage) -> Unit) {
    val state by backend.state.collectAsState()
    val status by backend.status.collectAsState()
    val account by backend.account.collectAsState()
    val limits by backend.rateLimits.collectAsState()
    val mode by backend.prefs.permissionMode.collectAsState()
    var mcp by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        backend.refreshStatus()
        backend.mcpServerNames().onSuccess { mcp = it.size }
    }
    @Composable
    fun row(page: CodexSettingsPage, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String? = null, valueColor: Color? = null) =
        ai.antigem.ui.settings.SettingsRowItem(icon, CodexAccent, stringResource(page.titleRes), value, valueColor) { onOpen(page) }
    val ready = state == CodexState.READY
    ai.antigem.ui.settings.SettingsRowList(
        cardBg = cardBg,
        groups = listOf(
            listOf(
                row(CodexSettingsPage.ACCOUNT, Icons.Outlined.AccountCircle,
                    if (account != null) account?.plan?.replaceFirstChar { it.uppercase() }?.let { "$it plan" } ?: "Signed in" else state.label,
                    if (ready) CodexAccent else MaterialTheme.colorScheme.error),
                row(CodexSettingsPage.USAGE, Icons.Outlined.DataUsage, limits?.primary?.let { "${it.usedPercent.toInt()}% used" })
            ),
            listOf(
                row(CodexSettingsPage.PERMISSIONS, Icons.Outlined.Tune, codexModeLabel(mode)),
                row(CodexSettingsPage.MCP, Icons.Outlined.Hub, mcp?.takeIf { it > 0 }?.let { "$it servers" }),
                row(CodexSettingsPage.PLUGINS, Icons.Outlined.Extension),
                row(CodexSettingsPage.SKILLS, Icons.Outlined.Psychology)
            ),
            listOf(
                row(CodexSettingsPage.SERVER, Icons.Outlined.Dns,
                    if (state == CodexState.NOT_INSTALLED) state.label else status?.version ?: state.label,
                    if (state == CodexState.NOT_INSTALLED || state == CodexState.ERROR || state == CodexState.OFFLINE) MaterialTheme.colorScheme.error else CodexAccent)
            )
        )
    )
}

/** One page of Settings → Codex. */
@Composable
fun CodexSettingsScreen(
    backend: CodexChatBackend,
    page: CodexSettingsPage = CodexSettingsPage.ACCOUNT,
    onOpenRea: () -> Unit = {},
    onOpenReaGroups: () -> Unit = {}
) {
    if (page == CodexSettingsPage.MCP) {
        CodexMcpPage(backend, onOpenRea, onOpenReaGroups)
        return
    }
    if (page == CodexSettingsPage.PLUGINS) {
        CodexPluginsPage(backend)
        return
    }
    if (page == CodexSettingsPage.SKILLS) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ai.antigem.ui.settings.AgentSkillsPanel(ai.antigem.domain.model.AgentKind.CODEX, CodexAccent)
        }
        return
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by backend.state.collectAsState()
    val status by backend.status.collectAsState()
    val account by backend.account.collectAsState()
    val limits by backend.rateLimits.collectAsState()
    val login by backend.login.collectAsState()
    val mode by backend.prefs.permissionMode.collectAsState()
    val job by backend.cliJob.collectAsState()
    val jobRunning = job?.running == true
    var loading by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    val remote = ai.antigem.data.preferences.AuthPreferences.currentRemoteMode

    suspend fun reload() {
        loading = true
        backend.refreshStatus()
        loading = false
    }
    LaunchedEffect(Unit) {
        backend.refreshCliJob()
        reload()
    }
    LaunchedEffect(jobRunning) { if (!jobRunning && job != null) reload() }

    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- account
        if (page == CodexSettingsPage.ACCOUNT) ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ai.antigem.ui.agents.AgentMark(AgentKind.CODEX, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Codex", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        when {
                            account != null -> listOfNotNull(account?.email, account?.plan?.replaceFirstChar { it.uppercase() }?.let { "$it plan" }).joinToString(" · ").ifBlank { "Signed in" }
                            else -> state.label
                        },
                        fontSize = 12.sp,
                        color = if (state == CodexState.READY) CodexAccent else MaterialTheme.colorScheme.error
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { scope.launch { reload() } }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.agent_sheet_ui_refresh)) }
            }
            val l = login
            when {
                l != null -> {
                    Spacer(Modifier.height(10.dp))
                    if (l.code != null) {
                        Text(stringResource(R.string.codex_settings_screen_open_the_link_sign_in_with_your), fontSize = 12.5.sp, lineHeight = 17.sp)
                        SelectionContainer {
                            Text(l.code, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = CodexAccent,
                                modifier = Modifier.padding(vertical = 8.dp))
                        }
                    } else {
                        Text(stringResource(R.string.codex_settings_screen_finish_signing_in_in_your_browser_codex), fontSize = 12.5.sp, lineHeight = 17.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { open(l.url) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CodexAccent, contentColor = Color.White)
                        ) { Text(stringResource(R.string.codex_settings_screen_open_sign_in_page)) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { backend.cancelLogin() }) { Text(stringResource(R.string.accounts_ui_cancel)) }
                    }
                    Text(l.url, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                state == CodexState.SIGNED_OUT -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.codex_settings_screen_sign_in_with_the_chatgpt_account_whose),
                        fontSize = 12.5.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { backend.startLogin(deviceCode = remote) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CodexAccent, contentColor = Color.White)
                        ) { Text(stringResource(R.string.codex_settings_screen_sign_in_with_chatgpt)) }
                        Spacer(Modifier.width(8.dp))
                        if (!remote) TextButton(onClick = { backend.startLogin(deviceCode = true) }) { Text(stringResource(R.string.codex_settings_screen_use_a_code)) }
                    }
                    Text(
                        if (remote) stringResource(R.string.codex_settings_screen_the_server_is_remote_you_get_a)
                        else stringResource(R.string.codex_settings_screen_use_a_code_works_when_the_browser),
                        fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                account != null -> {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { backend.logout() }, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.accounts_ui_sign_out)) }
                }
            }
        }

        // ---- usage
        if (page == CodexSettingsPage.USAGE) {
            LaunchedEffect(Unit) { backend.refreshRateLimits() }
            ClaudeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.codex_settings_screen_usage_limits), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        (limits?.plan ?: account?.plan)?.let {
                            Text(stringResource(R.string.codex_settings_screen_plan_every_codex_model_counts_toward_the, (it.replaceFirstChar { c -> c.uppercase() }).toString()),
                                fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    IconButton(onClick = { scope.launch { backend.refreshRateLimits() } }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.agent_sheet_ui_refresh)) }
                }
                Spacer(Modifier.height(8.dp))
                val lim = limits
                when {
                    lim == null -> Text(
                        if (state == CodexState.READY) stringResource(R.string.codex_settings_screen_no_usage_information_yet) else stringResource(R.string.codex_settings_screen_sign_in_to_codex_to_see_your),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    lim.primary == null && lim.secondary == null -> Text(stringResource(R.string.codex_settings_screen_no_limits_reported_for_this_account), fontSize = 12.sp)
                    else -> {
                        lim.primary?.let { LimitRow(it) }
                        lim.secondary?.let { Spacer(Modifier.height(10.dp)); LimitRow(it) }
                    }
                }
            }
        }

        // ---- approvals for new chats
        if (page == CodexSettingsPage.PERMISSIONS) ClaudeCard {
            Text(stringResource(R.string.codex_settings_screen_approvals), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(stringResource(R.string.codex_settings_screen_what_codex_asks_before_acting_each_chat),
                fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            CodexPreferences.MODES.forEach { m ->
                ai.antigem.ui.claude.PickerOption(
                    title = codexModeLabel(m),
                    description = codexModeDescription(m),
                    color = codexModeColor(m),
                    selected = m == mode,
                    icon = codexModeIcon(m),
                    onClick = { backend.setPermissionMode(m) }
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        // ---- server
        if (page == CodexSettingsPage.SERVER) ClaudeCard {
            Text(stringResource(R.string.codex_settings_screen_server_install), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                listOfNotNull(status?.version?.let { "Codex $it" }, if (status?.running == true) "running" else if (status?.installed == true) "starts when needed" else null)
                    .joinToString(" · ").ifBlank { state.label },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            status?.binary?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
            if (state == CodexState.NOT_INSTALLED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (status?.termux_package == true) stringResource(R.string.codex_settings_screen_codex_is_not_installed_on_this_phone)
                    else stringResource(R.string.codex_settings_screen_codex_is_not_installed_on_the_server),
                    fontSize = 12.5.sp, lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == CodexState.NOT_INSTALLED) {
                    Button(
                        onClick = { backend.startCliJob("install") },
                        enabled = !jobRunning,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CodexAccent, contentColor = Color.White)
                    ) { Text(if (jobRunning) stringResource(R.string.claude_settings_screen_installing) else stringResource(R.string.codex_settings_screen_install_codex)) }
                } else {
                    OutlinedButton(onClick = { backend.startCliJob("update") }, enabled = !jobRunning, shape = RoundedCornerShape(12.dp)) {
                        Text(if (jobRunning) stringResource(R.string.bridge_ui_checking) else stringResource(R.string.bridge_ui_check_for_updates))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            restarting = true
                            backend.restartServer { err ->
                                restarting = false
                                ai.antigem.ui.components.AppToastHelper.showToast(
                                    err?.let { "Restart failed: $it" } ?: "Codex restarted",
                                    if (err == null) ai.antigem.ui.components.ChatToastType.SUCCESS else ai.antigem.ui.components.ChatToastType.ERROR
                                )
                            }
                        },
                        enabled = !restarting && !jobRunning,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(if (restarting) stringResource(R.string.agents_setup_ui_restarting) else stringResource(R.string.codex_settings_screen_restart)) }
                }
                if (jobRunning || restarting) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CodexAccent)
                }
            }
            job?.let { j ->
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (j.kind == "update") "Update" else "Install") + ": " + when {
                        j.running -> "running…"
                        j.exit_code == 0 -> "done"
                        else -> "failed"
                    },
                    fontSize = 12.sp,
                    color = if (!j.running && j.exit_code != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    j.log.trim().lines().takeLast(14).joinToString("\n"),
                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(8.dp)
                )
            }
            Text(
                stringResource(R.string.codex_settings_screen_restarting_ends_replies_that_are_running_codex),
                fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(top = 8.dp)
            )
            status?.error?.takeIf { state != CodexState.READY }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LimitRow(w: CodexLimitWindow) {
    val used = (w.usedPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
    val window = w.windowMinutes?.let { m ->
        when {
            m >= 60 * 24 * 7 && m % (60 * 24) == 0L -> "${m / (60 * 24)}-day limit"
            m >= 60 * 24 -> "${m / (60 * 24)}-day limit"
            m >= 60 -> "${m / 60}-hour limit"
            else -> "$m-minute limit"
        }
    } ?: "Limit"
    val resets = w.resetsAt?.let { s ->
        val left = s * 1000 - System.currentTimeMillis()
        if (left <= 0) "resets now" else {
            val h = left / 3_600_000
            if (h >= 48) "resets in ${h / 24} days" else if (h >= 1) "resets in $h h" else "resets in ${left / 60_000} min"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(window, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.codex_settings_screen_used, (w.usedPercent.toInt()).toString()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(
        progress = { used },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = if (used >= 0.9f) MaterialTheme.colorScheme.error else CodexAccent,
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    )
    resets?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.padding(top = 3.dp)) }
}
