package ai.antigem.ui.claude

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import ai.antigem.ui.components.bouncyVerticalScroll
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.agent.claude.ClaudeAccountManager
import ai.antigem.data.agent.claude.ClaudeStatus
import ai.antigem.data.agent.claude.ClaudeChatBackend
import ai.antigem.data.agent.claude.ClaudeConfigManager
import ai.antigem.data.agent.claude.ClaudeMcpAddRequest
import ai.antigem.data.agent.claude.ClaudePluginAction
import ai.antigem.data.agent.claude.ClaudePreferences
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.theme.GeminiBlue
import ai.antigem.theme.GeminiPurple
import ai.antigem.theme.QuotaAmber
import ai.antigem.theme.QuotaGreen
import ai.antigem.theme.QuotaRed
import ai.antigem.ui.components.ClaudeAccent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pages of the Claude Code settings. */
enum class ClaudeSettingsPage(val titleRes: Int, val subtitleRes: Int) {
    ACCOUNT(R.string.agent_settings_account_cli, R.string.agent_settings_sign_in_plan_usage_install_and_update_claude_code),
    DEFAULTS(R.string.agent_settings_chat_defaults, R.string.agent_settings_model_effort_thinking_permission_mode_output_style),
    PROVIDERS(R.string.agent_settings_custom_providers, R.string.agent_settings_openrouter_kimi_and_other_anthropic_compatible_apis),
    PERMISSIONS(R.string.agent_settings_permission_rules, R.string.agent_settings_what_claude_may_do_without_asking),
    MCP(R.string.agent_settings_mcp_servers, R.string.claude_settings_screen_tools_claude_can_call),
    PLUGINS(R.string.android_dev_screen_plugins, R.string.agent_settings_installed_plugins_catalog_and_marketplaces),
    SKILLS(R.string.agent_settings_skills, R.string.agent_settings_add_skills_from_github_update_or_remove_them),
    MEMORY(R.string.agent_settings_memory, R.string.agent_settings_instructions_claude_md_and_the_notes_claude_saved),
    ADVANCED(R.string.agent_settings_advanced, R.string.agent_settings_edit_claude_settings_json_directly)
}

/** Holder passed from the ViewModel into settings. */
class ClaudeSettingsDeps(
    val account: ClaudeAccountManager,
    val config: ClaudeConfigManager,
    val backend: ClaudeChatBackend,
    val prefs: ClaudePreferences
)

private val prettyJson = Json { prettyPrint = true }

/** Claude Code's own settings page: a list of rows, each opening one page (Settings → Claude Code). */
@Composable
fun ClaudeSettingsGroup(deps: ClaudeSettingsDeps, cardBg: Color, onOpen: (ClaudeSettingsPage) -> Unit) {
    val status by deps.account.status.collectAsState()
    val settings by deps.config.settings.collectAsState()
    val mcp by deps.config.mcpServers.collectAsState()
    LaunchedEffect(Unit) {
        if (status == null) deps.account.refreshStatus()
        if (settings == null) deps.config.loadSettings()
    }
    val state by deps.account.state.collectAsState()
    val auth = status?.auth
    val accountBadge = when (state) {
        ClaudeStatus.READY -> auth?.subscriptionType?.let { ai.antigem.ui.agents.claudePlanLabel(it) } ?: "Signed in"
        ClaudeStatus.SIGNED_OUT -> "Signed out"
        else -> state.label
    }
    val accountColor = state.dotColor
    val providers by deps.backend.providers.collectAsState()
    val allowCount = settings?.let { deps.config.rules("allow").size } ?: 0
    @Composable
    fun row(page: ClaudeSettingsPage, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String? = null, valueColor: Color? = null) =
        ai.antigem.ui.settings.SettingsRowItem(icon, ClaudeAccent, stringResource(page.titleRes), value, valueColor) { onOpen(page) }
    ai.antigem.ui.settings.SettingsRowList(
        cardBg = cardBg,
        groups = listOf(
            listOf(row(ClaudeSettingsPage.ACCOUNT, Icons.Outlined.AccountCircle, accountBadge, accountColor)),
            listOf(
                row(ClaudeSettingsPage.DEFAULTS, Icons.Outlined.Tune),
                row(ClaudeSettingsPage.PROVIDERS, Icons.Outlined.Cloud, providers.size.takeIf { it > 0 }?.let { "$it added" }),
                row(ClaudeSettingsPage.PERMISSIONS, Icons.Outlined.Security, allowCount.takeIf { it > 0 }?.let { "$it allowed" }),
                row(ClaudeSettingsPage.MCP, Icons.Outlined.Hub, mcp.size.takeIf { it > 0 }?.let { "$it servers" }),
                row(ClaudeSettingsPage.PLUGINS, Icons.Outlined.Extension),
                row(ClaudeSettingsPage.SKILLS, Icons.Outlined.Psychology),
                row(ClaudeSettingsPage.MEMORY, Icons.Outlined.Description)
            ),
            listOf(row(ClaudeSettingsPage.ADVANCED, Icons.Outlined.Code))
        )
    )
}

/** One Claude settings page (the settings top bar shows its title); [onBack] returns to the main settings menu. */
@Composable
fun ClaudeSettingsScreen(
    deps: ClaudeSettingsDeps,
    page: ClaudeSettingsPage,
    onOpenRea: () -> Unit = {},
    onOpenReaGroups: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    BackHandler { onBack() }
    LaunchedEffect(Unit) {
        deps.config.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) {
        deps.account.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // the plugin catalog is a lazy list of its own (thousands of entries); every other page scrolls as a whole
    if (page == ClaudeSettingsPage.PLUGINS) {
        PluginsPage(deps)
        return
    }
    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (page) {
            ClaudeSettingsPage.ACCOUNT -> AccountPage(deps)
            ClaudeSettingsPage.DEFAULTS -> DefaultsPage(deps)
            ClaudeSettingsPage.PROVIDERS -> ProvidersPage(deps)
            ClaudeSettingsPage.PERMISSIONS -> PermissionsPage(deps)
            ClaudeSettingsPage.MCP -> McpPage(deps, onOpenRea, onOpenReaGroups)
            ClaudeSettingsPage.PLUGINS -> Unit
            ClaudeSettingsPage.SKILLS -> ai.antigem.ui.settings.AgentSkillsPanel(ai.antigem.domain.model.AgentKind.CLAUDE, ClaudeAccent)
            ClaudeSettingsPage.MEMORY -> MemoryPage(deps)
            ClaudeSettingsPage.ADVANCED -> AdvancedPage(deps)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
}

private fun activeProjectPath(): String? =
    ai.antigem.data.daemon.IdeDaemonManager.activeProject.value?.path?.removePrefix("file://")?.takeIf { it.isNotBlank() }

// ======================================================================== Account & CLI

@Composable
private fun AccountPage(deps: ClaudeSettingsDeps) {
    val status by deps.account.status.collectAsState()
    val statusError by deps.account.statusError.collectAsState()
    val usage by deps.account.usage.collectAsState()
    val busy by deps.account.isBusy.collectAsState()
    val job by deps.config.cliJob.collectAsState()
    val state by deps.account.state.collectAsState()
    var showLogin by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        deps.account.refreshStatus()
        deps.account.refreshUsage(force = true)
    }

    ClaudeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Claude Code CLI", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { deps.account.refreshStatus() }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.agent_sheet_ui_refresh)) }
        }
        when (state) {
            ClaudeStatus.CHECKING -> Text(stringResource(R.string.bridge_ui_checking), fontSize = 12.5.sp)
            ClaudeStatus.BRIDGE_OFFLINE, ClaudeStatus.BRIDGE_OUTDATED, ClaudeStatus.ERROR -> {
                Text(state.label, color = state.dotColor, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                statusError?.let { Hint(it) }
            }
            ClaudeStatus.NOT_INSTALLED -> Text(stringResource(R.string.claude_settings_screen_claude_code_not_found_on_this_device), fontSize = 12.5.sp, color = state.dotColor)
            else -> {
                Text(status?.version ?: stringResource(R.string.claude_settings_screen_installed), fontSize = 13.sp)
                status?.bin_path?.let { Hint(it) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val running = job?.running == true
            if (state == ClaudeStatus.NOT_INSTALLED) {
                Button(onClick = { deps.config.startCliJob("install") }, enabled = !running,
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text(stringResource(R.string.claude_settings_screen_install_claude_code)) }
            } else if (status?.installed == true) {
                OutlinedButton(onClick = { deps.config.startCliJob("update") }, enabled = !running) { Text(stringResource(R.string.bridge_ui_check_for_updates)) }
            }
            if (running) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
        }
        job?.let { j ->
            Spacer(Modifier.height(8.dp))
            Text(
                "${j.kind}: " + if (j.running) "running…" else "finished (exit ${j.exit_code})",
                fontSize = 12.sp,
                color = if (!j.running && j.exit_code != 0) QuotaRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
                Text(
                    j.log.lines().takeLast(30).joinToString("\n"),
                    fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(8.dp)
                )
            }
        }
    }

    ClaudeCard {
        Text(stringResource(R.string.claude_settings_screen_account), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        val auth = status?.auth
        if (auth?.loggedIn == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(ClaudeAccent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text((auth.email?.firstOrNull()?.uppercaseChar() ?: '✻').toString(), color = ClaudeAccent, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(auth.email ?: stringResource(R.string.claude_settings_screen_signed_in), fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                    Hint(listOfNotNull(auth.subscriptionType?.let { "Plan: ${it.replaceFirstChar { c -> c.uppercase() }}" },
                        auth.authMethod?.let { "via $it" }).joinToString(" · "))
                }
                OutlinedButton(onClick = { deps.account.logout() }, enabled = !busy) { Text(stringResource(R.string.accounts_ui_sign_out), color = QuotaRed) }
            }
        } else {
            Hint(stringResource(R.string.claude_settings_screen_sign_in_with_your_claude_subscription_or))
            Spacer(Modifier.height(8.dp))
            Button(onClick = { showLogin = true }, enabled = !state.isBlocking && state != ClaudeStatus.CHECKING && (!busy || state == ClaudeStatus.SIGNING_IN),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) {
                Text(if (state == ClaudeStatus.SIGNING_IN) stringResource(R.string.claude_settings_screen_signing_in) else stringResource(R.string.claude_settings_screen_sign_in))
            }
        }
    }

    val usageRefreshing by deps.account.isRefreshingUsage.collectAsState()
    val usageError by deps.account.usageError.collectAsState()
    val usageUpdatedAt by deps.account.usageUpdatedAt.collectAsState()
    if (usage != null || usageRefreshing || usageError != null) {
        ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.claude_settings_screen_plan_usage), fontWeight = FontWeight.Bold)
                    Hint(
                        when {
                            usageRefreshing -> stringResource(R.string.claude_settings_screen_updating)
                            usageUpdatedAt > 0 -> "Updated " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(usageUpdatedAt))
                            else -> ""
                        }
                    )
                }
                if (usageRefreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                else IconButton(onClick = { deps.account.refreshUsage(force = true) }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.claude_settings_screen_refresh_usage)) }
            }
            usageError?.let { Text(stringResource(R.string.claude_settings_screen_could_not_update, it.toString()), fontSize = 12.sp, color = QuotaRed) }
            val u = usage ?: return@ClaudeCard
            val rl = u.rate_limits
            if (rl == null || !u.rate_limits_available) {
                Hint(stringResource(R.string.claude_settings_screen_plan_limits_are_not_available_for_this))
            } else {
                rl.five_hour?.let { UsageBar("5-hour limit", (it.utilization ?: 0.0) / 100.0, formatReset(it.resets_at)) }
                Spacer(Modifier.height(8.dp))
                rl.seven_day?.let { UsageBar("Weekly limit (all models)", (it.utilization ?: 0.0) / 100.0, formatReset(it.resets_at)) }
                // a model's own weekly share (Fable on Max), on top of the all-models limit
                ai.antigem.data.agent.claude.ClaudeQuotaGroups.scopedLimits(rl).forEach {
                    Spacer(Modifier.height(8.dp))
                    UsageBar("Weekly limit (${it.name})", it.used, formatReset(it.resetsAt))
                }
            }
        }
    }

    if (showLogin) ClaudeLoginDialog(deps.account) { showLogin = false }
}

// ======================================================================== Chat defaults

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultsPage(deps: ClaudeSettingsDeps) {
    val settings by deps.config.settings.collectAsState()
    val models by deps.backend.modelInfos.collectAsState()
    val styles by deps.backend.outputStyles.collectAsState()
    val voiceLang by deps.prefs.voiceLanguage.collectAsState()
    LaunchedEffect(Unit) {
        deps.config.loadSettings()
        if (models.isEmpty()) deps.backend.refreshInfo(force = false)
    }
    val s = settings ?: JsonObject(emptyMap())
    fun str(key: String) = (s[key] as? JsonPrimitive)?.content
    val model = str("model") ?: "default"
    val effort = str("effortLevel")
    val thinking = (s["alwaysThinkingEnabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
    val style = str("outputStyle") ?: "default"
    // no default in settings.json: new chats start in Auto
    val mode = deps.config.defaultMode()?.takeIf { it != "bypassPermissions" } ?: "auto"

    Hint(stringResource(R.string.claude_settings_screen_these_apply_to_new_claude_chats_saved))

    ClaudeCard {
        SectionTitle(stringResource(R.string.claude_settings_screen_default_model))
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val options = models.map { it.value to it.displayName.ifBlank { it.value } }.ifEmpty { listOf("default" to "Default") }
            options.forEach { (value, label) ->
                ControlChip(label, highlighted = value == model, color = if (value == model) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    onClick = { deps.config.setValue("model", if (value == "default") null else JsonPrimitive(value)) })
            }
        }
        val levels = models.find { it.value == model }?.supportedEffortLevels ?: models.firstOrNull()?.supportedEffortLevels.orEmpty()
        if (levels.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.claude_settings_screen_default_effort))
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<String?>(null) + levels).forEach { level ->
                    ControlChip(effortLabel(level), highlighted = level == effort,
                        color = if (level == effort) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        onClick = { deps.config.setValue("effortLevel", level?.let { JsonPrimitive(it) }) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle(stringResource(R.string.claude_chat_controls_thinking))
                Hint(stringResource(R.string.claude_settings_screen_reason_before_answering))
            }
            Switch(checked = thinking, onCheckedChange = { deps.config.setValue("alwaysThinkingEnabled", JsonPrimitive(it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent))
        }
    }

    ClaudeCard {
        SectionTitle(stringResource(R.string.claude_settings_screen_default_permission_mode))
        Spacer(Modifier.height(6.dp))
        ClaudeChatBackend.PERMISSION_MODES.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { deps.config.setDefaultMode(value) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.RadioButton(selected = value == mode, onClick = { deps.config.setDefaultMode(value) })
                Column {
                    Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    Hint(permissionModeDescription(value))
                }
            }
        }
    }

    if (styles.isNotEmpty()) {
        ClaudeCard {
            SectionTitle(stringResource(R.string.claude_settings_screen_output_style))
            Hint(stringResource(R.string.claude_settings_screen_how_claude_writes_its_replies))
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                styles.forEach { st ->
                    ControlChip(st, highlighted = st.equals(style, true),
                        color = if (st.equals(style, true)) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        onClick = { deps.config.setValue("outputStyle", if (st == "default") null else JsonPrimitive(st)) })
                }
            }
        }
    }

    ClaudeCard {
        SectionTitle(stringResource(R.string.claude_settings_screen_dictation_language))
        Hint(stringResource(R.string.claude_settings_screen_language_code_for_voice_input_in_claude))
        Spacer(Modifier.height(6.dp))
        var lang by remember(voiceLang) { mutableStateOf(voiceLang) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(lang, { lang = it.trim() }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { deps.prefs.setVoiceLanguage(lang.ifBlank { "en" }) }, colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text(stringResource(R.string.claude_providers_page_save)) }
        }
    }
}

// ======================================================================== Permission rules

@Composable
private fun PermissionsPage(deps: ClaudeSettingsDeps) {
    val settings by deps.config.settings.collectAsState()
    LaunchedEffect(Unit) { deps.config.loadSettings() }
    var newRule by remember { mutableStateOf("") }
    var behavior by remember { mutableStateOf("allow") }
    if (settings == null) Hint(stringResource(R.string.claude_settings_screen_loading_settings))

    // Claude Code's command sandbox: the safe way to have fewer prompts (Bypass is never used by this app)
    val sandbox by deps.config.sandbox.collectAsState()
    LaunchedEffect(Unit) { deps.config.loadSandbox() }
    ClaudeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle(stringResource(R.string.claude_settings_screen_sandbox_commands))
                Hint(
                    when {
                        sandbox == null -> stringResource(R.string.claude_settings_screen_checking_whether_the_sandbox_can_run_on)
                        sandbox?.available == true -> stringResource(R.string.claude_settings_screen_run_claude_s_shell_commands_in_an)
                        else -> stringResource(R.string.claude_settings_screen_not_available_here_auto_mode_already_asks, (sandbox?.reason ?: "unknown reason").toString())
                    }
                )
            }
            Switch(
                checked = sandbox?.enabled == true,
                enabled = sandbox?.available == true,
                onCheckedChange = { deps.config.setSandboxEnabled(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent)
            )
        }
    }

    Hint(stringResource(R.string.claude_settings_screen_rules_use_the_cli_syntax_bash_npm))
    ClaudeCard {
        SectionTitle(stringResource(R.string.claude_settings_screen_add_a_rule))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(newRule, { newRule = it }, singleLine = true, placeholder = { Text(stringResource(R.string.claude_settings_screen_bash_npm_run_test)) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("allow" to QuotaGreen, "ask" to QuotaAmber, "deny" to QuotaRed).forEach { (b, c) ->
                ControlChip(b.replaceFirstChar { it.uppercase() }, highlighted = behavior == b, color = if (behavior == b) c else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), onClick = { behavior = b })
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                deps.config.addRule(behavior, newRule)
                newRule = ""
            }, enabled = newRule.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text(stringResource(R.string.browser_screen_add)) }
        }
    }
    listOf("allow" to "Allowed without asking", "ask" to "Always ask", "deny" to "Never allowed").forEach { (b, title) ->
        val rules = deps.config.rules(b)
        ClaudeCard {
            SectionTitle("$title (${rules.size})")
            if (rules.isEmpty()) Hint(stringResource(R.string.claude_settings_screen_no_rules))
            rules.forEach { rule ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rule, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { deps.config.removeRule(b, rule) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.browser_screen_remove), tint = QuotaRed.copy(alpha = 0.8f)) }
                }
            }
        }
    }
}

// ======================================================================== MCP servers

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpPage(deps: ClaudeSettingsDeps, onOpenRea: () -> Unit, onOpenReaGroups: () -> Unit) {
    val servers by deps.config.mcpServers.collectAsState()
    val loading by deps.config.mcpLoading.collectAsState()
    val ops by deps.config.mcpOps.collectAsState()
    val addError by deps.config.mcpAddError.collectAsState()
    val cwd = activeProjectPath()
    var addDraft by remember { mutableStateOf<ClaudeMcpAddRequest?>(null) }
    var toDelete by remember { mutableStateOf<ai.antigem.data.agent.claude.ClaudeMcpServer?>(null) }
    var expandedTools by remember { mutableStateOf(setOf<String>()) }
    var expandedErrors by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { deps.config.loadMcp(cwd) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.claude_settings_screen_tools_claude_can_call), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Hint(if (cwd != null) stringResource(R.string.claude_settings_screen_includes_servers_of_the_open_project, cwd.toString()) else stringResource(R.string.claude_settings_screen_open_a_project_to_also_see_its))
        }
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
        else IconButton(onClick = { deps.config.loadMcp(cwd) }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.agent_sheet_ui_refresh)) }
    }

    // quick presets, like the Antigravity MCP screen
    val context = LocalContext.current
    val deviceId = remember(context) {
        runCatching { android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty()
    }
    val bridge = ai.antigem.data.remote.AndroidLocalBridgeServer
    val deviceHeaders = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
    fun local(name: String, path: String) =
        ClaudeMcpAddRequest(name = name, transport = "http", url = "http://127.0.0.1:${bridge.DEFAULT_PORT}$path", headers = deviceHeaders)
    val presets = listOf(
        ClaudeMcpPreset("Plugin Developer", "Build & test AntiGem plugins", Icons.Outlined.DeveloperMode, GeminiPurple, local(bridge.DEVELOPER_MCP_NAME, "/extension_developer")),
        ClaudeMcpPreset("Mini App Developer", "Build, test & sign mini apps", Icons.Outlined.Apps, GeminiBlue, local(bridge.MINI_APP_DEVELOPER_MCP_NAME, "/mini_app_developer")),
        ClaudeMcpPreset("Browser", "Control the in-app browser", Icons.Outlined.Language, GeminiBlue, local("browser_mcp", "/browser")),
        ClaudeMcpPreset("Terminal", "Run commands in the terminal", Icons.Outlined.Terminal, QuotaGreen, local("terminal_mcp", "/terminal")),
        ClaudeMcpPreset("Android", "Tap, type & read this phone", Icons.Outlined.PhoneAndroid, ai.antigem.theme.AppColors.accentTeal, local(bridge.ANDROID_AUTOMATION_MCP_NAME, "/android")),
        ClaudeMcpPreset("Notes & To-dos", "Read & edit your notes", Icons.Outlined.EditNote, ai.antigem.theme.AppColors.warning, local(bridge.NOTES_MCP_NAME, "/notes")),
        ClaudeMcpPreset("Jarvis", "Voice control of this app", Icons.Outlined.RecordVoiceOver, ai.antigem.theme.AppColors.accentTeal, local(bridge.JARVIS_MCP_NAME, "/jarvis")),
        ClaudeMcpPreset("Theme", "Create & switch app themes", Icons.Outlined.Palette, ai.antigem.theme.AppColors.accentAlt, local(bridge.THEME_MCP_NAME, "/theme")),
        ClaudeMcpPreset("Stitch", "Google Stitch UI design · API key", Icons.Outlined.Palette, ai.antigem.theme.AppColors.accentPink,
            ClaudeMcpAddRequest(name = "stitch", transport = "http", url = "https://stitch.googleapis.com/mcp", headers = mapOf("X-Goog-Api-Key" to "")))
    )
    var chooseWebViewExtension by remember { mutableStateOf(false) }
    var showReaDialog by remember { mutableStateOf(false) }
    // REA servers: stdio servers running the rea-mcp launcher (name → env)
    val reaServers = servers.mapNotNull { srv ->
        val config = srv.config ?: return@mapNotNull null
        val command = (config["command"] as? JsonPrimitive)?.content
        if (!ai.antigem.data.rea.ReaManager.isReaCommand(command)) return@mapNotNull null
        srv.name to (config["env"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
    }.toMap()
    if (showReaDialog) ai.antigem.ui.settings.ReaServerDialog(
        agent = "Claude Code",
        existing = reaServers,
        takenNames = servers.map { it.name }.toSet(),
        canReplace = false,
        onDismiss = { showReaDialog = false },
        onSave = { name, env ->
            deps.config.addMcp(ClaudeMcpAddRequest(name = name, transport = "stdio", command = ai.antigem.data.rea.ReaManager.LAUNCHER, env = env), cwd)
            showReaDialog = false
        },
        onOpenRea = { showReaDialog = false; onOpenRea() },
        onOpenGroups = { showReaDialog = false; onOpenReaGroups() }
    )
    val extensionManager = remember { ai.antigem.ui.browser.BrowserExtensionManager.instance }
    if (chooseWebViewExtension) ai.antigem.ui.settings.WebViewExtensionMcpPicker(onDismiss = { chooseWebViewExtension = false }) { extension ->
        addDraft = ClaudeMcpAddRequest(
            name = extensionManager.mcpServerName(extension.id), transport = "http",
            url = extensionManager.mcpUrl(extension.id), headers = deviceHeaders
        )
        chooseWebViewExtension = false
    }
    Text(stringResource(R.string.claude_settings_screen_quick_presets), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    val tiles = listOf(
        ClaudeMcpPreset("Custom Plugin MCP", "Pick an installed plugin", Icons.Outlined.Extension, ClaudeTerracotta, null),
    ) + presets +
        // REA runs in the terminal's proot-distro Ubuntu; its tile picks the tool groups
        ClaudeMcpPreset("REA", "Reverse engineering · APK & native", Icons.Outlined.BugReport, ai.antigem.theme.AppColors.accentPink, null) +
        ClaudeMcpPreset("Custom server", "Any stdio, HTTP or SSE server", Icons.Outlined.Add, ClaudeAccent, null)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    val req = p.request
                    val isRea = p.name == "REA" && req == null
                    // The plugin developer may already be added under its old name
                    val added = if (isRea) reaServers.isNotEmpty()
                        else req != null && servers.any { it.name == req.name || (bridge.isDeveloperMcpName(req.name) && bridge.isDeveloperMcpName(it.name)) }
                    val busy = req != null && req.name in ops
                    ai.antigem.ui.settings.McpPresetTile(
                        name = p.name,
                        subtitle = if (busy) "Adding…" else p.subtitle,
                        icon = p.icon,
                        accent = p.accent,
                        added = added,
                        addedLabel = "Added",
                        // REA can be added more than once (different tool groups)
                        enabled = isRea || (!added && !busy),
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) {
                        when {
                            req != null -> addDraft = req
                            isRea -> showReaDialog = true
                            p.name == "Custom server" -> addDraft = ClaudeMcpAddRequest(name = "")
                            else -> chooseWebViewExtension = true
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.claude_settings_screen_configured_servers, servers.size.toString()), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (servers.isNotEmpty()) Hint(stringResource(R.string.claude_settings_screen_tools, (servers.sumOf { it.tools.size }).toString()))
    }

    // servers being added show up right away with their progress
    ops.filterKeys { name -> servers.none { it.name == name } }.forEach { (name, op) ->
        ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Hint(if (op.startsWith("Connecting")) stringResource(R.string.claude_settings_screen_added_checking_that_it_starts_and_listing) else op)
                }
            }
        }
    }

    when {
        servers.isEmpty() && loading && ops.isEmpty() -> ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.claude_settings_screen_checking_mcp_servers), fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                    Hint(stringResource(R.string.claude_settings_screen_claude_code_starts_each_server_to_list))
                }
            }
        }
        servers.isEmpty() && ops.isEmpty() -> ClaudeCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Hub, null, tint = ClaudeAccent.copy(alpha = 0.7f), modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.claude_settings_screen_no_mcp_servers_yet), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Hint(stringResource(R.string.claude_settings_screen_add_one_from_the_presets_above_or))
            }
        }
    }

    servers.forEach { srv ->
        val op = ops[srv.name]
        val (statusColor, statusLabel) = when {
            op != null -> ClaudeAccent to op
            srv.status == "connected" -> QuotaGreen to "Connected"
            srv.status == "pending" -> QuotaAmber to "Starting"
            srv.status == "failed" -> QuotaRed to "Failed"
            srv.status == "needs-auth" -> QuotaAmber to "Needs sign-in"
            srv.status == "disabled" -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) to "Disabled"
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f) to srv.status.ifBlank { "Unknown" }.replaceFirstChar { it.uppercase() }
        }
        val cfg = srv.config
        fun cfgStr(key: String) = (cfg?.get(key) as? JsonPrimitive)?.content
        val transport = cfgStr("type") ?: if (cfgStr("url") != null) "http" else "stdio"
        val target = cfgStr("url") ?: listOfNotNull(cfgStr("command"),
            (cfg?.get("args") as? kotlinx.serialization.json.JsonArray)?.joinToString(" ") { (it as? JsonPrimitive)?.content.orEmpty() }).joinToString(" ").trim()
        ClaudeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(transportColor(transport).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Extension, null, tint = transportColor(transport), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(srv.name, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TransportBadge(transport)
                        Badge(statusLabel, statusColor)
                        (srv.scope ?: srv.source)?.let { Hint(scopeLabel(it)) }
                    }
                }
                when {
                    op != null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                    srv.scope in setOf("user", "project", "local") -> IconButton(onClick = { toDelete = srv }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.browser_screen_remove), tint = QuotaRed.copy(alpha = 0.8f))
                    }
                }
            }
            if (target.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                    Text(if (transport == "stdio") "> $target" else stringResource(R.string.claude_settings_screen_url, target.toString()), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            srv.error?.takeIf { it.isNotBlank() }?.let { err ->
                val open = srv.name in expandedErrors
                Spacer(Modifier.height(8.dp))
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expandedErrors = if (open) expandedErrors - srv.name else expandedErrors + srv.name },
                    shape = RoundedCornerShape(8.dp), color = QuotaRed.copy(alpha = 0.08f)
                ) {
                    Text(err, fontSize = 11.5.sp, color = QuotaRed, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            if (srv.tools.isNotEmpty()) {
                val open = srv.name in expandedTools
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { expandedTools = if (open) expandedTools - srv.name else expandedTools + srv.name }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.claude_settings_screen_tools, srv.tools.size.toString()), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ClaudeAccent, modifier = Modifier.weight(1f))
                    Text(if (open) stringResource(R.string.claude_settings_screen_hide) else stringResource(R.string.claude_settings_screen_show), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
                if (open) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        srv.tools.forEach { tool ->
                            Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                                Text(tool.name, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    addDraft?.let { draft ->
        AddMcpDialog(
            initial = draft,
            cwd = cwd,
            error = addError?.takeIf { it.first == draft.name }?.second,
            onDismiss = { addDraft = null }
        ) { req ->
            deps.config.addMcp(req, cwd)
            addDraft = null
        }
    }
    toDelete?.let { srv ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.claude_providers_page_remove, srv.name.toString()), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.claude_settings_screen_claude_will_no_longer_be_able_to), fontSize = 13.5.sp) },
            confirmButton = {
                TextButton(onClick = {
                    deps.config.removeMcp(srv.name, srv.scope, cwd)
                    toDelete = null
                }) { Text(stringResource(R.string.browser_screen_remove), color = QuotaRed) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
}

private fun transportColor(transport: String) = if (transport == "stdio") ai.antigem.theme.AppColors.accentAlt else ai.antigem.theme.AppColors.info

@Composable
private fun TransportBadge(transport: String) = Badge(if (transport == "stdio") stringResource(R.string.claude_settings_screen_stdio) else transport.uppercase(), transportColor(transport))

@Composable
private fun Badge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.15f)) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

private fun scopeLabel(scope: String) = when (scope) {
    "user" -> "all projects"
    "local" -> "this project (you)"
    "project" -> "this project (.mcp.json)"
    else -> scope
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddMcpDialog(
    initial: ClaudeMcpAddRequest,
    cwd: String?,
    error: String?,
    onDismiss: () -> Unit,
    onAdd: (ClaudeMcpAddRequest) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var transport by remember { mutableStateOf(initial.transport) }
    var scope by remember { mutableStateOf(initial.scope) }
    var command by remember { mutableStateOf((listOf(initial.command) + initial.args).filter { it.isNotBlank() }.joinToString(" ")) }
    var url by remember { mutableStateOf(initial.url) }
    var pairs by remember {
        mutableStateOf(
            if (initial.transport == "stdio") initial.env.entries.joinToString("\n") { "${it.key}=${it.value}" }
            else initial.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) stringResource(R.string.claude_settings_screen_add_mcp_server) else stringResource(R.string.claude_providers_page_add, initial.name.toString()), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let {
                    Surface(shape = RoundedCornerShape(8.dp), color = QuotaRed.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                        Text(it, fontSize = 12.sp, color = QuotaRed, modifier = Modifier.padding(8.dp))
                    }
                }
                OutlinedTextField(name, { name = it.trim() }, label = { Text(stringResource(R.string.browser_screen_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.claude_settings_screen_transport), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("stdio", "http", "sse").forEach { t ->
                        ControlChip(t, highlighted = transport == t, color = if (transport == t) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), onClick = { transport = t })
                    }
                }
                Text(stringResource(R.string.claude_settings_screen_available_in), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("user" to "All projects", "local" to "This project (me)", "project" to "This project (.mcp.json)").forEach { (sc, label) ->
                        val enabled = sc == "user" || cwd != null
                        ControlChip(label, highlighted = scope == sc, color = if (scope == sc) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.3f),
                            onClick = { if (enabled) scope = sc })
                    }
                }
                if (transport == "stdio") {
                    OutlinedTextField(command, { command = it }, label = { Text(stringResource(R.string.claude_settings_screen_command_and_arguments)) }, placeholder = { Text("npx -y @modelcontextprotocol/server-filesystem /sdcard") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pairs, { pairs = it }, label = { Text(stringResource(R.string.claude_settings_screen_environment_key_value_per_line)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(url, { url = it.trim() }, label = { Text("URL") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pairs, { pairs = it }, label = { Text(stringResource(R.string.claude_settings_screen_headers_name_value_per_line)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parts = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                val sep = if (transport == "stdio") "=" else ":"
                val map = pairs.lines().mapNotNull { line ->
                    val i = line.indexOf(sep)
                    if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                }.toMap()
                onAdd(
                    ClaudeMcpAddRequest(
                        name = name, scope = scope, transport = transport,
                        command = parts.firstOrNull().orEmpty(), args = parts.drop(1),
                        env = if (transport == "stdio") map else emptyMap(),
                        url = url, headers = if (transport != "stdio") map else emptyMap(),
                        cwd = cwd.orEmpty()
                    )
                )
            }, enabled = name.isNotBlank() && (if (transport == "stdio") command.isNotBlank() else url.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text(stringResource(R.string.browser_screen_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_cancel)) } }
    )
}

// ======================================================================== Plugins

@Composable
private fun PluginsPage(deps: ClaudeSettingsDeps) {
    val data by deps.config.plugins.collectAsState()
    val ops by deps.config.pluginOps.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var marketSource by remember { mutableStateOf("") }
    var addingMarket by remember { mutableStateOf<String?>(null) }
    // Marketplaces tab: the marketplace whose plugins are shown under it, and the filter of that list
    var expandedMarket by remember { mutableStateOf<String?>(null) }
    var marketQuery by remember { mutableStateOf("") }
    var namesBeforeAdd by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scrollToMarket by remember { mutableStateOf<String?>(null) }
    // a repository Claude couldn't add as a marketplace: offer the "Set up with AI" request (the CLI's error is a toast)
    var aiSetup by remember { mutableStateOf<String?>(null) }
    var lastFailed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { deps.config.loadPlugins() }
    // the field empties once the marketplace add has finished, and the marketplace just added opens with its
    // plugins (the list is reloaded before the action ends), so they can be installed right there
    LaunchedEffect(ops) {
        addingMarket?.let { src ->
            if (src !in ops) {
                val markets = data?.marketplaces.orEmpty()
                val added = markets.firstOrNull { it.name !in namesBeforeAdd } ?: markets.firstOrNull { sameMarketSource(it, src) }
                added?.let { expandedMarket = it.name; marketQuery = ""; scrollToMarket = it.name }
                if (added == null) { aiSetup = src; lastFailed = src } else lastFailed = null
                addingMarket = null
                marketSource = ""
            }
        }
    }

    // the catalog has thousands of plugins: search once typing pauses, off the main thread
    val available = data?.plugins?.available.orEmpty()
    val searchIndex = remember(available) { available.map { p -> "${p.name}\n${p.description}\n${p.category.orEmpty()}".lowercase() } }
    var results by remember { mutableStateOf(available) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query, available) {
        searching = true
        if (query.isNotBlank()) kotlinx.coroutines.delay(300)
        val q = query.trim().lowercase()
        results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (q.isEmpty()) available else available.filterIndexed { i, _ -> searchIndex[i].contains(q) }
        }
        searching = false
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            listOf("Installed", "Discover", "Marketplaces").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) })
            }
        }
        val d = data
        if (d == null) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                Spacer(Modifier.width(8.dp))
                Hint(stringResource(R.string.claude_settings_screen_loading_plugins_the_catalog_can_take_a))
            }
            return@Column
        }
        val installedIds = remember(d) { d.plugins.installed.map { it.key }.toSet() }
        // every marketplace's plugins (ids are name@marketplace), the installed ones included
        val byMarket = remember(d) {
            val availableIds = d.plugins.available.map { it.key }.toSet()
            (d.plugins.available + d.plugins.installed.filter { it.key !in availableIds })
                .groupBy { it.key.substringAfter('@', "") }
                .mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val bounce = ai.antigem.ui.components.rememberBouncyLazyScroll(listState)
        LaunchedEffect(scrollToMarket, d) {
            val name = scrollToMarket ?: return@LaunchedEffect
            val i = d.marketplaces.indexOfFirst { it.name == name }
            // the Marketplaces tab lists "about" and "add" first; only the opened marketplace has rows under it
            if (tab == 2 && i >= 0) listState.animateScrollToItem(2 + i)
            scrollToMarket = null
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize().then(bounce.modifier),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            userScrollEnabled = bounce.userScrollEnabled,
            overscrollEffect = bounce.overscrollEffect
        ) {
            when (tab) {
                0 -> {
                    if (d.plugins.installed.isEmpty()) item { Hint(stringResource(R.string.claude_settings_screen_no_plugins_installed_find_some_in_discover)) }
                    items(d.plugins.installed.size, key = { "i_" + d.plugins.installed[it].key }) { i ->
                        val p = d.plugins.installed[i]
                        val op = ops[p.key]
                        ClaudeCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name.ifBlank { p.key }, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                    Hint(listOfNotNull(p.version?.let { "v$it" }, p.scope, p.key.substringAfter('@', "").takeIf { it.isNotBlank() }).joinToString(" · "))
                                }
                                if (op != null) PluginProgress(op)
                                else if (p.enabled != null) Switch(
                                    checked = p.enabled,
                                    onCheckedChange = { deps.config.pluginAction(if (it) "enable" else "disable", ClaudePluginAction(plugin = p.key, scope = p.scope)) },
                                    colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent)
                                )
                            }
                            if (p.description.isNotBlank()) Text(p.description, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                OutlinedButton(onClick = { deps.config.pluginAction("update", ClaudePluginAction(plugin = p.key)) }, enabled = op == null) { Text(stringResource(R.string.claude_settings_screen_update), fontSize = 12.sp) }
                                OutlinedButton(onClick = { deps.config.pluginAction("uninstall", ClaudePluginAction(plugin = p.key, scope = p.scope)) }, enabled = op == null) {
                                    Text(stringResource(R.string.claude_settings_screen_uninstall), fontSize = 12.sp, color = QuotaRed)
                                }
                            }
                        }
                    }
                }
                1 -> {
                    item(key = "search") {
                        OutlinedTextField(
                            query, { query = it }, singleLine = true,
                            placeholder = { Text(stringResource(R.string.claude_settings_screen_search_plugins, available.size.toString())) },
                            trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeAccent) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item(key = "count") { Hint(if (searching) stringResource(R.string.claude_settings_screen_searching) else stringResource(R.string.claude_settings_screen_plugin_s, results.size.toString())) }
                    items(results.size, key = { "a_" + results[it].key }) { i ->
                        val p = results[i]
                        val op = ops[p.key]
                        ClaudeCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                    Hint(listOfNotNull(p.category, p.key.substringAfter('@', "").takeIf { it.isNotBlank() }).joinToString(" · "))
                                }
                                when {
                                    op != null -> PluginProgress(op)
                                    p.key in installedIds -> Text(stringResource(R.string.claude_settings_screen_installed), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = QuotaGreen)
                                    else -> Button(onClick = { deps.config.pluginAction("install", ClaudePluginAction(plugin = p.key, scope = "user")) },
                                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text(stringResource(R.string.claude_settings_screen_install), fontSize = 12.sp) }
                                }
                            }
                            if (p.description.isNotBlank()) Text(p.description, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                        }
                    }
                }
                else -> {
                    item(key = "about") {
                        Hint(stringResource(R.string.claude_settings_screen_a_marketplace_is_a_catalog_of_plugins))
                    }
                    item(key = "add") {
                        ClaudeCard {
                            SectionTitle(stringResource(R.string.claude_settings_screen_add_a_marketplace))
                            Hint(stringResource(R.string.claude_settings_screen_github_owner_repo_a_git_url_or))
                            lastFailed?.let { failed ->
                                ai.antigem.ui.settings.SetupWithAiLink(ClaudeAccent) { aiSetup = failed }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(marketSource, { marketSource = it.trim() }, singleLine = true, placeholder = { Text("owner/repo") }, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                val adding = addingMarket != null
                                Button(onClick = {
                                    namesBeforeAdd = d.marketplaces.map { it.name }.toSet()
                                    addingMarket = marketSource
                                    deps.config.pluginAction("marketplace-add", ClaudePluginAction(source = marketSource))
                                }, enabled = marketSource.isNotBlank() && !adding, colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) {
                                    if (adding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White) else Text(stringResource(R.string.browser_screen_add))
                                }
                            }
                        }
                    }
                    d.marketplaces.forEach { m ->
                        val plugins = byMarket[m.name].orEmpty()
                        val open = expandedMarket == m.name
                        item(key = "m_" + m.name) {
                            val op = ops[m.name]
                            ClaudeCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(
                                        Modifier.weight(1f).clickable { expandedMarket = if (open) null else m.name; marketQuery = "" },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                            Hint(listOfNotNull("${plugins.size} plugin${if (plugins.size == 1) "" else "s"}", listOfNotNull(m.repo, m.url, m.source).firstOrNull()).joinToString(" · "))
                                        }
                                        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) stringResource(R.string.claude_settings_screen_hide_plugins) else stringResource(R.string.claude_settings_screen_show_plugins))
                                    }
                                    if (op != null) PluginProgress(op)
                                    else {
                                        IconButton(onClick = { deps.config.pluginAction("marketplace-update", ClaudePluginAction(name = m.name)) }) {
                                            Icon(Icons.Outlined.Refresh, stringResource(R.string.claude_settings_screen_refresh_catalog))
                                        }
                                        IconButton(onClick = { deps.config.pluginAction("marketplace-remove", ClaudePluginAction(name = m.name)) }) {
                                            Icon(Icons.Outlined.Delete, stringResource(R.string.browser_screen_remove), tint = QuotaRed.copy(alpha = 0.8f))
                                        }
                                    }
                                }
                            }
                        }
                        if (open) {
                            // its plugins, to install right here (the same as from Discover)
                            if (plugins.size > 8) item(key = "mq_" + m.name) {
                                OutlinedTextField(marketQuery, { marketQuery = it }, singleLine = true, placeholder = { Text(stringResource(R.string.claude_settings_screen_search_plugins_in, plugins.size.toString(), m.name.toString())) },
                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp))
                            }
                            val q = marketQuery.trim().lowercase()
                            val shown = if (q.isEmpty()) plugins else plugins.filter { "${it.name}\n${it.description}\n${it.category.orEmpty()}".lowercase().contains(q) }
                            if (plugins.isEmpty()) item(key = "me_" + m.name) {
                                Box(Modifier.padding(start = 12.dp)) { Hint(stringResource(R.string.claude_settings_screen_no_plugins_in_this_marketplace_yet_refresh)) }
                            }
                            items(shown.size, key = { "mp_" + m.name + "_" + shown[it].key }) { j ->
                                val p = shown[j]
                                val pop = ops[p.key]
                                ClaudeCard(Modifier.padding(start = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            p.category?.let { Hint(it) }
                                        }
                                        when {
                                            pop != null -> PluginProgress(pop)
                                            p.key in installedIds -> Text(stringResource(R.string.claude_settings_screen_installed), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = QuotaGreen)
                                            else -> Button(onClick = { deps.config.pluginAction("install", ClaudePluginAction(plugin = p.key, scope = "user")) },
                                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text(stringResource(R.string.claude_settings_screen_install), fontSize = 12.sp) }
                                        }
                                    }
                                    if (p.description.isNotBlank()) Text(p.description, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    aiSetup?.let { src ->
        ai.antigem.ui.settings.SetupWithAiDialog(src, ai.antigem.domain.model.AgentKind.CLAUDE, "Claude Code found no plugin marketplace in it") { aiSetup = null }
    }
}

/** Whether marketplace [m] is the one added from [source] (owner/repo, a GitHub URL or a git URL). */
private fun sameMarketSource(m: ai.antigem.data.agent.claude.ClaudeMarketplace, source: String): Boolean {
    fun norm(x: String) = x.trim().trimEnd('/').removeSuffix(".git").lowercase()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("github.com/")
    val src = norm(source)
    if (src.isEmpty()) return false
    return listOfNotNull(m.repo, m.url, m.source).map(::norm).any { it == src || it.endsWith("/$src") || src.endsWith("/$it") }
}

/** The running (or queued) action of one plugin / marketplace. */
@Composable
private fun PluginProgress(action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeAccent)
        Spacer(Modifier.width(6.dp))
        Text(
            when (action) {
                "install" -> stringResource(R.string.claude_settings_screen_installing)
                "uninstall" -> stringResource(R.string.claude_settings_screen_removing)
                "enable" -> stringResource(R.string.claude_settings_screen_enabling)
                "disable" -> stringResource(R.string.claude_settings_screen_disabling)
                "update", "marketplace-update" -> stringResource(R.string.claude_settings_screen_updating)
                "marketplace-remove" -> stringResource(R.string.claude_settings_screen_removing)
                else -> stringResource(R.string.claude_settings_screen_working)
            },
            fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = ClaudeAccent
        )
    }
}

// ======================================================================== Memory

@Composable
private fun MemoryPage(deps: ClaudeSettingsDeps) {
    val memory by deps.config.memory.collectAsState()
    val auto by deps.config.autoMemory.collectAsState()
    // the project Claude chats run in: the open project, otherwise the bridge's default workspace
    val cwd = activeProjectPath()
    val projectName = cwd?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { null } ?: "Default workspace"
    var scope by remember { mutableStateOf("user") }
    var text by remember { mutableStateOf("") }
    var openNote by remember { mutableStateOf<String?>(null) }
    var noteToDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scope) { deps.config.loadMemory(scope, if (scope == "project") cwd else null) }
    LaunchedEffect(memory) { memory?.let { text = it.content } }
    LaunchedEffect(cwd) { deps.config.loadAutoMemory(cwd) }

    // ---- CLAUDE.md
    ClaudeCard {
        SectionTitle(stringResource(R.string.claude_settings_screen_instructions_claude_md))
        Hint(stringResource(R.string.claude_settings_screen_rules_claude_follows_in_every_chat_new))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ControlChip(stringResource(R.string.claude_settings_screen_all_projects), highlighted = scope == "user", color = if (scope == "user") ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), onClick = { scope = "user" })
            ControlChip(projectName, highlighted = scope == "project", color = if (scope == "project") ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), onClick = { scope = "project" })
        }
        Spacer(Modifier.height(8.dp))
        val m = memory
        if (m == null) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ClaudeAccent)
        } else {
            Hint(
                (if (scope == "user") "Used in every project · " else "Used only in $projectName · ") +
                    m.path + if (!m.exists) " (created when you save)" else ""
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(stringResource(R.string.claude_settings_screen_instructions_claude_follows_in_every_chat_prefer)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { deps.config.saveMemory(scope, if (scope == "project") cwd else null, text) },
                enabled = text != m.content,
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
            ) { Text(stringResource(R.string.claude_providers_page_save)) }
        }
    }

    // ---- auto memory
    ClaudeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle(stringResource(R.string.claude_settings_screen_claude_s_memory, projectName.toString()))
                Hint(stringResource(R.string.claude_settings_screen_notes_claude_saved_by_itself_while_working))
            }
            IconButton(onClick = { deps.config.loadAutoMemory(cwd) }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.agent_sheet_ui_refresh)) }
        }
        val a = auto
        when {
            a == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ClaudeAccent)
            a.error != null -> Text(a.error, fontSize = 12.sp, color = QuotaRed)
            a.files.isEmpty() -> Hint(stringResource(R.string.claude_settings_screen_no_notes_yet_ask_claude_to_remember))
            else -> {
                Hint(a.dir)
                a.files.forEach { f ->
                    val open = openNote == f.name
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { openNote = if (open) null else f.name },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            Hint(java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.modified)))
                        }
                        IconButton(onClick = { noteToDelete = f.name }) { Icon(Icons.Outlined.Delete, stringResource(R.string.browser_screen_delete), tint = QuotaRed.copy(alpha = 0.8f)) }
                    }
                    if (open) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
                            Text(f.content, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }

    noteToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text(stringResource(R.string.claude_settings_screen_delete, name.toString()), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.claude_settings_screen_claude_forgets_what_this_note_says), fontSize = 13.5.sp) },
            confirmButton = {
                TextButton(onClick = {
                    deps.config.deleteAutoMemory(cwd, name)
                    noteToDelete = null
                }) { Text(stringResource(R.string.browser_screen_delete), color = QuotaRed) }
            },
            dismissButton = { TextButton(onClick = { noteToDelete = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
}

// ======================================================================== Advanced

@Composable
private fun AdvancedPage(deps: ClaudeSettingsDeps) {
    val settings by deps.config.settings.collectAsState()
    val path by deps.config.settingsPath.collectAsState()
    val error by deps.config.settingsError.collectAsState()
    var text by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { deps.config.loadSettings() }
    LaunchedEffect(settings) { settings?.let { text = prettyJson.encodeToString(JsonObject.serializer(), it) } }

    Hint(stringResource(R.string.claude_settings_screen_every_claude_code_setting_lives_here_permissions))
    if (path.isNotBlank()) Hint(path)
    error?.let { Text(it, color = QuotaRed, fontSize = 12.sp) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            parseError = null
        },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
        isError = parseError != null
    )
    parseError?.let { Text(it, color = QuotaRed, fontSize = 12.sp) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { deps.config.loadSettings() }) { Text(stringResource(R.string.browser_screen_reload)) }
        Button(onClick = {
            val parsed: JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrElse {
                parseError = "Invalid JSON: ${it.message}"
                null
            }
            when (parsed) {
                null -> Unit
                is JsonObject -> deps.config.saveSettings(parsed, "settings.json saved")
                else -> parseError = "settings.json must be a JSON object"
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)) { Text(stringResource(R.string.claude_settings_screen_validate_save)) }
    }
}

private data class ClaudeMcpPreset(
    val name: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accent: Color,
    val request: ClaudeMcpAddRequest?
)
