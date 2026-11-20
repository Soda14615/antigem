package ai.antigem.ui.jarvis

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import ai.antigem.ui.components.bouncyVerticalScroll
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.agent.claude.ClaudeMcpAddRequest
import ai.antigem.data.jarvis.JarvisController
import ai.antigem.data.jarvis.JarvisPreferences
import ai.antigem.data.jarvis.JarvisSkill
import ai.antigem.data.jarvis.JarvisVoice
import ai.antigem.data.remote.AndroidLocalBridgeServer
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.McpServerSpec
import kotlinx.coroutines.launch

private val JarvisCyan = Color(0xFF22D3EE)

/** The sections of Settings → Jarvis: each opens on its own page (Settings → Jarvis → section). */
enum class JarvisSettingsPage { AGENT, PERSONALITY, SETUP, WAKE, LISTEN, VOICE, TASKS, APPROVALS, SCREEN, CHAT }

/**
 * Settings → Jarvis: the on/off switch, then one row per section ([JarvisSettingsPage]); a section opens on its own
 * page ([JarvisSettingsSectionPage]), so this page stays light and opens at once.
 */
@Composable
fun JarvisSettingsScreen(cardBg: Color, cardBorder: BorderStroke, onOpen: (JarvisSettingsPage) -> Unit) {
    val P = JarvisPreferences
    val enabled by P.enabled.state.collectAsState()
    val agentName by P.agent.state.collectAsState()
    val hotword by P.hotword.state.collectAsState()
    val autoSend by P.autoSend.state.collectAsState()
    val engine by P.ttsEngine.state.collectAsState()
    val quiet by P.quietMode.state.collectAsState()
    val policy by P.permissionPolicy.state.collectAsState()
    val voiceSide by P.voiceAssistant.state.collectAsState()

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        JCard(cardBg, cardBorder, "Jarvis") {
            JSwitch(
                stringResource(R.string.jarvis_settings_screen_turn_on_jarvis),
                "A hands-free assistant for the whole app: talk to open projects and files, run and watch tasks, answer approvals, " +
                    "change settings, ask for help or your usage. Off by default; nothing listens until you turn it on.",
                enabled
            ) { JarvisController.setEnabled(it) }
            if (enabled) {
                JSwitch(
                    stringResource(R.string.jarvis_settings_screen_floating_button_voice),
                    "Off = chat only: no floating button, no microphone, nothing read out loud. You use Jarvis in the Jarvis chats you open " +
                        "from the chat list (Jarvis tab → New Jarvis chat), by typing.",
                    voiceSide
                ) { JarvisController.setVoiceAssistant(it) }
            }
            JHint(
                if (!enabled || voiceSide) "Tap the orb to talk, long-press it for the Jarvis panel (tasks, conversation, typing, quick switches). " +
                    "You can also type to Jarvis: Jarvis tab in the chat list → New Jarvis chat."
                else stringResource(R.string.jarvis_settings_screen_chat_only_open_the_jarvis_tab_in)
            )
        }
        if (enabled && JarvisController.isAttached) {
            JCard(cardBg, cardBorder, "Jarvis chats in the chat list") {
                val inAll by P.jarvisInAll.state.collectAsState()
                JChips(listOf("none" to "Only under Jarvis", "text" to "My Jarvis chats in All too", "all" to "All Jarvis chats in All too"), inAll) { P.jarvisInAll.set(it) }
                JHint(when (inAll) {
                    "text" -> stringResource(R.string.jarvis_settings_screen_the_jarvis_chats_you_opened_yourself_new)
                    "all" -> stringResource(R.string.jarvis_settings_screen_every_jarvis_chat_the_voice_chats_jarvis)
                    else -> stringResource(R.string.jarvis_settings_screen_jarvis_chats_are_listed_only_under_the)
                })
            }
        }
        if (!JarvisController.isAttached) {
            Text(stringResource(R.string.jarvis_settings_screen_jarvis_is_starting), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            return@Column
        }
        val c = ai.antigem.theme.AppColors
        val I = androidx.compose.material.icons.Icons.Outlined
        fun row(page: JarvisSettingsPage, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, value: String? = null) =
            ai.antigem.ui.settings.SettingsRowItem(icon, tint, title, value) { onOpen(page) }
        listOf(
            listOfNotNull(
                row(JarvisSettingsPage.AGENT, I.SmartToy, c.info, "Voice agent & model", when (agentName) {
                    AgentKind.AGY.name -> "Antigravity"
                    AgentKind.CLAUDE.name -> "Claude Code"
                    AgentKind.OPENCODE.name -> "OpenCode"
                    AgentKind.CODEX.name -> "Codex"
                    else -> "Not set"
                }),
                row(JarvisSettingsPage.PERSONALITY, I.Face, c.accentPink, "Personality & you"),
                // the setup checklist is only for a Jarvis that is turned on
                if (enabled) row(JarvisSettingsPage.SETUP, I.Checklist, c.success, "Setup") else null
            ),
            // "chat only": nothing listens or speaks, so those pages are hidden
            if (!voiceSide) emptyList() else listOf(
                row(JarvisSettingsPage.WAKE, I.Hearing, c.accentTeal, "Waking Jarvis", if (hotword == "phrase") "Wake phrase" else "Button"),
                row(JarvisSettingsPage.LISTEN, I.Mic, c.accentIndigo, "Listening & sending", if (autoSend) "Auto-send" else "Manual send"),
                row(JarvisSettingsPage.VOICE, I.RecordVoiceOver, c.accentAlt, "Voice output", when {
                    quiet -> "Quiet"
                    engine == "google_cloud" -> "Google Cloud"
                    engine == "openai" -> "OpenAI"
                    else -> "This device"
                })
            ),
            listOf(
                row(JarvisSettingsPage.TASKS, I.TaskAlt, c.warning, "Manager & tasks"),
                row(JarvisSettingsPage.APPROVALS, I.VerifiedUser, c.danger, "Approvals", when (policy) {
                    "auto_readonly" -> "Auto: read-only"
                    "auto_except_risky" -> "Auto: not risky"
                    "auto_all" -> "Auto: all"
                    else -> "Ask each time"
                }),
                row(JarvisSettingsPage.SCREEN, I.Visibility, c.info, "Screen & look"),
                row(JarvisSettingsPage.CHAT, I.ChatBubbleOutline, JarvisCyan, "Jarvis chat")
            )
        ).filter { it.isNotEmpty() }.forEach { ai.antigem.ui.settings.SettingsRowGroup(it, cardBg) }
    }
}

/** One section of Settings → Jarvis on its own page (the page title is in the settings top bar). */
@Composable
fun JarvisSettingsSectionPage(page: JarvisSettingsPage, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val P = JarvisPreferences
    val enabled by P.enabled.state.collectAsState()
    val agentName by P.agent.state.collectAsState()
    // null until the user picks the agent Jarvis talks to
    val agent: AgentKind? = agentName.takeIf { it.isNotBlank() }?.let { runCatching { AgentKind.valueOf(it) }.getOrNull() }
    val agentTitle = when (agent) {
        AgentKind.CLAUDE -> "Claude Code"
        AgentKind.AGY -> "Antigravity"
        AgentKind.OPENCODE -> "OpenCode"
        AgentKind.CODEX -> "Codex"
        null -> "the agent"
    }

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!JarvisController.isAttached) {
            Text(stringResource(R.string.jarvis_settings_screen_jarvis_is_starting), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            return@Column
        }
        val vm = JarvisController.vm
        when (page) {
            JarvisSettingsPage.AGENT -> {
                JCard(cardBg, cardBorder, "") {
                    val enabledAgents by vm.enabledAgents.collectAsState()
                    JChips(
                        listOf(AgentKind.CLAUDE.name to "Claude Code", AgentKind.AGY.name to "Antigravity", AgentKind.OPENCODE.name to "OpenCode", AgentKind.CODEX.name to "Codex"),
                        agentName
                    ) { P.agent.set(it); P.modelId.set("") }
                    if (agent == null) {
                        Text(stringResource(R.string.jarvis_settings_screen_pick_the_agent_jarvis_talks_to_nothing), fontSize = 12.5.sp, color = Color(0xFFF59E0B))
                    } else if (agent !in enabledAgents) {
                        Text(stringResource(R.string.jarvis_settings_screen_is_turned_off_in_settings_agents_turn, agentTitle.toString()), fontSize = 12.5.sp, color = Color(0xFFF59E0B))
                    }
                    val models by (when (agent) {
                        AgentKind.CLAUDE -> vm.claudeBackend.availableModels
                        AgentKind.OPENCODE -> vm.openCodeBackend.availableModels
                        AgentKind.CODEX -> vm.codexBackend.availableModels
                        else -> vm.agyBackend.enabledModels
                    }).collectAsState()
                    val modelId by P.modelId.state.collectAsState()
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { menu = true }) {
                            val label = models.firstOrNull { it.id == modelId }?.let { it.displayName.ifBlank { it.id } } ?: stringResource(R.string.jarvis_settings_default_model)
                            Text(stringResource(R.string.chat_history_drawer_model, label.toString()))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.claude_settings_screen_default_model)) }, onClick = { P.modelId.set(""); menu = false })
                            models.forEach { m ->
                                DropdownMenuItem(text = { Text(m.displayName.ifBlank { m.id }) }, onClick = { P.modelId.set(m.id); menu = false })
                            }
                        }
                    }
                    JHint("Jarvis talks to a normal hidden chat of this agent (find it under Jarvis in the chat list). A fast model feels best for voice; " +
                        "big jobs run as tasks in their own chats.")
                }
            }
            JarvisSettingsPage.PERSONALITY -> {
                JCard(cardBg, cardBorder, "") {
                    val persona by P.persona.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_jarvis_speaks_as), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("" to "Not set", "female" to "Female", "male" to "Male", "neutral" to "Neutral"), persona) { P.persona.set(it) }
                    JHint("The grammatical gender Jarvis uses for itself. It matters in languages like Hindi (“main kar rahi hoon” / “main kar raha hoon”). " +
                        "Pick a matching voice under Voice output.")
                    val about by P.aboutUser.state.collectAsState()
                    OutlinedTextField(
                        value = about, onValueChange = { P.aboutUser.set(it.take(2000)) },
                        label = { Text(stringResource(R.string.jarvis_settings_screen_about_you_and_extra_instructions)) },
                        placeholder = { Text(stringResource(R.string.jarvis_settings_screen_e_g_call_me_santosh_reply_in), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8
                    )
                    JHint("Sent with the first message of every new Jarvis chat. When you change it (or the persona), the open chat is told with your next message. " +
                        "Jarvis' own memory note (Settings → Jarvis → Jarvis chat) is separate: Jarvis edits that one itself.")
                }
            }
            JarvisSettingsPage.SETUP -> {
                if (!enabled) {
                    JHint(stringResource(R.string.jarvis_settings_screen_turn_on_jarvis_first_settings_jarvis))
                } else {
                    if (agent == null) {
                        JHint(stringResource(R.string.jarvis_settings_screen_pick_the_agent_jarvis_talks_to_first))
                    } else JCard(cardBg, cardBorder, "Setup for $agentTitle") {
                        val deviceId = remember(context) {
                            runCatching { android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "" }.getOrDefault("")
                        }
                        val headers = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
                        val url = "http://127.0.0.1:${AndroidLocalBridgeServer.DEFAULT_PORT}/jarvis"
                        val name = AndroidLocalBridgeServer.JARVIS_MCP_NAME
                        val mcpProblem by JarvisController.mcpProblem.collectAsState()
                        if (mcpProblem) {
                            Text(
                                stringResource(R.string.jarvis_settings_screen_jarvis_reported_that_its_app_tools_are, agentTitle.toString()),
                                fontSize = 12.5.sp, color = Color(0xFFF59E0B)
                            )
                        }

                        // 1. the MCP server
                        val claudeServers by vm.claudeConfig.mcpServers.collectAsState()
                        val agyServers by vm.mcpServers.collectAsState()
                        // OpenCode keeps the servers the app adds in the bridge's OpenCode config
                        var openCodeServers by remember { mutableStateOf<Set<String>>(emptySet()) }
                        val reloadOpenCodeMcp: () -> Unit = { scope.launch { vm.openCodeBackend.client.mcpServers().onSuccess { openCodeServers = it } } }
                        // Codex keeps them in its own config.toml
                        var codexServers by remember { mutableStateOf<Set<String>>(emptySet()) }
                        val reloadCodexMcp: () -> Unit = { scope.launch { vm.codexBackend.mcpServerNames().onSuccess { codexServers = it } } }
                        LaunchedEffect(agent) {
                            when (agent) {
                                AgentKind.CLAUDE -> vm.claudeConfig.loadMcp(null)
                                AgentKind.OPENCODE -> reloadOpenCodeMcp()
                                AgentKind.CODEX -> reloadCodexMcp()
                                else -> vm.loadMcpServers()
                            }
                        }
                        val mcpState = when (agent) {
                            AgentKind.CLAUDE -> claudeServers.firstOrNull { it.name == name }?.status
                            AgentKind.OPENCODE, AgentKind.CODEX -> null
                            else -> agyServers.firstOrNull { it.name == name }?.status
                        }
                        val mcpAdded = when (agent) {
                            AgentKind.CLAUDE -> claudeServers.any { it.name == name }
                            AgentKind.OPENCODE -> name in openCodeServers
                            AgentKind.CODEX -> name in codexServers
                            else -> agyServers.any { it.name == name }
                        }
                        JStep(
                            "1. Add the Jarvis MCP server",
                            if (mcpAdded) "Added" + (mcpState?.let { " · $it" } ?: "") else "Required: these are the tools Jarvis controls the app with.",
                            mcpAdded, if (mcpAdded) "Refresh" else "Add"
                        ) {
                            if (agent == AgentKind.CODEX) {
                                if (mcpAdded) reloadCodexMcp()
                                else scope.launch {
                                    vm.codexBackend.addRemoteMcp(name, url, headers)
                                        .onSuccess { reloadCodexMcp(); Toast.makeText(context, context.getString(R.string.jarvis_settings_added_to_codex), Toast.LENGTH_SHORT).show() }
                                        .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.jarvis_settings_could_not_add), Toast.LENGTH_LONG).show() }
                                }
                            } else if (agent == AgentKind.OPENCODE) {
                                if (mcpAdded) reloadOpenCodeMcp()
                                else scope.launch {
                                    vm.openCodeBackend.client.addRemoteMcp(name, url, headers)
                                        .onSuccess { reloadOpenCodeMcp(); Toast.makeText(context, context.getString(R.string.jarvis_settings_added_to_opencode), Toast.LENGTH_SHORT).show() }
                                        .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.jarvis_settings_could_not_add), Toast.LENGTH_LONG).show() }
                                }
                            } else if (agent == AgentKind.CLAUDE) {
                                if (mcpAdded) vm.claudeConfig.loadMcp(null)
                                else vm.claudeConfig.addMcp(ClaudeMcpAddRequest(name = name, transport = "http", url = url, headers = headers), null)
                            } else {
                                if (mcpAdded) vm.loadMcpServers()
                                else vm.saveMcpServer(McpServerSpec(serverName = name, serverUrl = url, headers = headers))
                            }
                        }

                        // 2. never ask about Jarvis' own tools (Claude)
                        if (agent == AgentKind.CLAUDE) {
                            val settings by vm.claudeConfig.settings.collectAsState()
                            LaunchedEffect(Unit) { vm.claudeConfig.loadSettings() }
                            val allowed = settings != null && vm.claudeConfig.rules("allow").contains("mcp__$name")
                            JStep(
                                "2. Let Jarvis' tools run without asking",
                                if (allowed) "Allowed" else "Otherwise every spoken answer would need an approval.",
                                allowed, "Allow"
                            ) { vm.claudeConfig.addRule("allow", "mcp__$name") }
                        }

                        // 3. the microphone
                        var hasMic by remember { mutableStateOf(JarvisController.hasMic()) }
                        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }
                        JStep(
                            "${if (agent == AgentKind.CLAUDE) "3" else "2"}. Allow the microphone",
                            if (hasMic) "Allowed" else "Needed to hear you.",
                            hasMic, "Allow"
                        ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }

                        // 4. the optional skill
                        var skillInstalled by remember(agent) { mutableStateOf<Boolean?>(null) }
                        LaunchedEffect(agent) { skillInstalled = runCatching { JarvisSkill.isInstalled(agent) }.getOrDefault(false) }
                        JStep(
                            "Optional: install the Jarvis skill",
                            when (skillInstalled) { true -> "Installed"; false -> "Not needed: the guide goes with the first message."; null -> "Checking…" },
                            skillInstalled == true, if (skillInstalled == true) "Reinstall" else "Install"
                        ) {
                            scope.launch {
                                JarvisSkill.install(agent)
                                    .onSuccess { skillInstalled = true; Toast.makeText(context, context.getString(R.string.jarvis_settings_installed_item, it), Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.jarvis_settings_install_failed), Toast.LENGTH_LONG).show() }
                            }
                        }
                        JHint("The first message of every Jarvis chat carries the whole guide and the list of its tools with their parameters, so the " +
                            "model acts at once instead of searching. If the Jarvis MCP server is missing, Jarvis says so and this card shows a warning.")
                    }
                }
            }
            JarvisSettingsPage.WAKE -> {
                JCard(cardBg, cardBorder, "") {
                    val hotword by P.hotword.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_trigger), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("push" to "Button / tile / headset", "phrase" to "Wake phrase too"), hotword) { P.hotword.set(it) }
                    if (hotword == "phrase") {
                        val phrase by P.wakePhrase.state.collectAsState()
                        JField("Wake phrase(s), comma separated", phrase) { P.wakePhrase.set(it.lowercase()) }
                        val match by P.wakeMatch.state.collectAsState()
                        JChips(listOf("phrase" to "Whole phrase", "name" to "The name alone"), match) { P.wakeMatch.set(it) }
                        JHint("“Hey Jarvis, open my project” starts right away. Common mishearings of “Jarvis” count too. The wake phrase needs " +
                            "hands-free mode and keeps the device recognizer busy, which uses battery.")
                    }
                    val hands by P.handsFree.state.collectAsState()
                    JSwitch(
                        stringResource(R.string.jarvis_settings_screen_hands_free_mode),
                        "Keeps Jarvis' microphone service running so the wake phrase and the headset button work with the screen off or another app open. " +
                            "A notification shows its state with Talk / Send / Stop.",
                        hands, enabledSwitch = enabled
                    ) { JarvisController.setHandsFree(it) }
                    val headset by P.headsetButton.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_headset_button), stringResource(R.string.jarvis_settings_screen_with_hands_free_on_one_press_talks), headset) { P.headsetButton.set(it) }
                    JHint(stringResource(R.string.jarvis_settings_screen_quick_settings_tile_pull_down_the_notification))
                }
            }
            JarvisSettingsPage.LISTEN -> {
                JCard(cardBg, cardBorder, "") {
                    JHint(stringResource(R.string.jarvis_settings_screen_you_can_always_send_with_the_send))
                    val auto by P.autoSend.state.collectAsState()
                    JSwitch(
                        stringResource(R.string.jarvis_settings_screen_send_automatically_when_i_pause),
                        stringResource(R.string.jarvis_settings_screen_off_nothing_is_sent_while_you_take),
                        auto
                    ) { P.autoSend.set(it) }
                    if (auto) {
                        val mode by P.sendMode.state.collectAsState()
                        JChips(listOf("smart" to "Smart pause", "pause" to "Fixed pause"), if (mode == "pause") "pause" else "smart") { P.sendMode.set(it) }
                        JHint(if (mode == "pause") stringResource(R.string.jarvis_settings_screen_sent_after_a_fixed_pause_a_bar)
                        else stringResource(R.string.jarvis_settings_screen_jarvis_waits_longer_when_you_sound_unfinished))
                    }
                    val silence by P.silenceMs.state.collectAsState()
                    JSlider("Pause before sending", silence.toFloat(), 600f..4000f, "%.1f s".format(silence / 1000f)) { P.silenceMs.set(it.toInt()) }
                    val answers by P.answerSend.state.collectAsState()
                    Text(stringResource(R.string.jarvis_quick_settings_answers_to_jarvis_questions), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("same" to "Like everything else", "auto" to "Send on pause"), answers) { P.answerSend.set(it) }
                    JHint(if (answers == "auto") stringResource(R.string.jarvis_settings_screen_when_jarvis_just_asked_you_something_your)
                    else stringResource(R.string.jarvis_settings_screen_answers_are_sent_exactly_like_everything_else))

                    // send phrases
                    val sendOn by P.sendPhrasesOn.state.collectAsState()
                    val sendRaw by P.sendPhrases.state.collectAsState()
                    val cancelOn by P.cancelPhrasesOn.state.collectAsState()
                    val cancelRaw by P.cancelPhrases.state.collectAsState()
                    val sendList = remember(sendRaw) { ai.antigem.data.jarvis.JarvisPhrases.parse(sendRaw) }
                    val cancelList = remember(cancelRaw) { ai.antigem.data.jarvis.JarvisPhrases.parse(cancelRaw) }
                    JSwitch(stringResource(R.string.jarvis_settings_screen_send_with_a_phrase), stringResource(R.string.jarvis_settings_screen_end_with_one_of_these_and_it), sendOn) { P.sendPhrasesOn.set(it) }
                    if (sendOn) {
                        FoldedPhrases("Send phrases", sendList.size) {
                            PhraseEditor(sendList, cancelList, "cancel", ai.antigem.data.jarvis.JarvisPhrases.SUGGESTED_SEND) {
                                P.sendPhrases.set(ai.antigem.data.jarvis.JarvisPhrases.format(it))
                            }
                        }
                    }
                    JSwitch(stringResource(R.string.jarvis_settings_screen_cancel_with_a_phrase), stringResource(R.string.jarvis_settings_screen_end_with_one_of_these_and_nothing), cancelOn) { P.cancelPhrasesOn.set(it) }
                    if (cancelOn) {
                        FoldedPhrases("Cancel phrases", cancelList.size) {
                            PhraseEditor(cancelList, sendList, "send", ai.antigem.data.jarvis.JarvisPhrases.SUGGESTED_CANCEL) {
                                P.cancelPhrases.set(ai.antigem.data.jarvis.JarvisPhrases.format(it))
                            }
                        }
                    }
                    if (sendOn || cancelOn) {
                        JHint("A phrase only counts at the very end. “…and send it”, “…to send it”, “can you do it” and “don't send it” stay part of your " +
                            "message. Short or everyday phrases (“send”, “over”, “do it”) only count when you say them on their own after a short pause. " +
                            "When a phrase keeps firing by mistake, remove it or add a longer sentence instead (e.g. “jarvis send it now”).")
                    }
                    val listenAfter by P.listenAfter.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_after_jarvis_speaks_listen_for_your_reply), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("auto" to "When it expects an answer", "always" to "Always", "never" to "Never"), listenAfter) { P.listenAfter.set(it) }
                    JHint(when (listenAfter) {
                        "always" -> stringResource(R.string.jarvis_settings_screen_the_microphone_opens_after_every_answer_while)
                        "never" -> stringResource(R.string.jarvis_settings_screen_the_microphone_only_opens_when_you_tap)
                        else -> stringResource(R.string.jarvis_settings_screen_jarvis_decides_after_done_want_me_to)
                    })
                    if (listenAfter != "never") {
                        val follow by P.followUpSeconds.state.collectAsState()
                        JSlider("Wait for a reply", follow.toFloat(), 3f..20f, "$follow s") { P.followUpSeconds.set(it.toInt()) }
                        JHint(stringResource(R.string.jarvis_settings_screen_if_you_say_nothing_at_all_in))
                    }
                    val maxListen by P.maxListenSeconds.state.collectAsState()
                    JSlider("Longest single message", maxListen.toFloat(), 20f..240f, "$maxListen s") { P.maxListenSeconds.set(it.toInt()) }
                    val side by P.sideTalk.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_talking_to_other_people_nearby), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("off" to "Off", "smart" to "Smart", "strict" to "Strict"), side) { P.sideTalk.set(it) }
                    JHint(when (side) {
                        "off" -> stringResource(R.string.jarvis_settings_screen_everything_heard_is_for_jarvis)
                        "strict" -> stringResource(R.string.jarvis_settings_screen_during_follow_up_listening_jarvis_only_reacts)
                        else -> stringResource(R.string.jarvis_settings_screen_during_follow_up_listening_jarvis_ignores_what)
                    })
                    val busyB by P.busyBehavior.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_speaking_while_jarvis_is_still_working), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("queue" to "Wait for it", "interrupt" to "Always interrupt", "smart" to "My interrupt phrases"), busyB) { P.busyBehavior.set(it) }
                    if (busyB == "smart") JHint(stringResource(R.string.jarvis_settings_screen_only_a_message_that_starts_with_one))
                    val earcons by P.earcons.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_tones_when_the_microphone_opens_and_closes), null, earcons) { P.earcons.set(it) }
                    val stt by P.sttEngine.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_speech_recognition), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("auto" to "Same as agent", "claude" to "Claude", "agy" to "Antigravity", "android" to "This device"), stt) { P.sttEngine.set(it) }
                    JHint(stringResource(R.string.jarvis_settings_screen_this_device_uses_android_s_recognizer_and))
                    val lang by P.sttLanguage.state.collectAsState()
                    JField("Language (e.g. en, hi, es)", lang) { P.sttLanguage.set(it) }
                }
                JCard(cardBg, cardBorder, "Voice shortcuts") {
                    JHint("Your own phrases that act at once, without asking the model, when you say them on their own (optionally with “Jarvis” or “please”). " +
                        "There are none until you add them, so a plain “okay” or “stop” in a conversation always goes to Jarvis like anything else.")
                    val J = ai.antigem.data.jarvis.JarvisPhrases
                    listOf(
                        Triple("Stop everything", P.cmdStop, "stop"),
                        Triple("End the conversation", P.cmdBye, "bye"),
                        Triple("Repeat the last answer", P.cmdRepeat, "repeat"),
                        Triple("Speak slower", P.cmdSlower, "slower"),
                        Triple("Speak faster", P.cmdFaster, "faster"),
                        Triple("Quiet mode on", P.cmdQuietOn, "quiet_on"),
                        Triple("Quiet mode off", P.cmdQuietOff, "quiet_off"),
                        Triple("Start a new session", P.cmdNewSession, "new_session"),
                        Triple("Interrupt Jarvis' work (starts a message)", P.interruptPhrases, "interrupt")
                    ).forEach { (title, pref, id) ->
                        val raw by pref.state.collectAsState()
                        val list = remember(raw) { J.parse(raw) }
                        FoldedPhrases(title, list.size) {
                            PhraseEditor(list, emptyList(), "other", J.SUGGESTED_SHORTCUTS[id].orEmpty()) { pref.set(J.format(it)) }
                        }
                    }
                }
            }
            JarvisSettingsPage.VOICE -> {
                JCard(cardBg, cardBorder, "") {
                    val quiet by P.quietMode.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_quiet_mode), stringResource(R.string.jarvis_settings_screen_jarvis_shows_its_answers_on_the_card), quiet) { P.quietMode.set(it) }
                    val engine by P.ttsEngine.state.collectAsState()
                    JChips(listOf("android" to "This device", "google_cloud" to "Google Cloud", "openai" to "OpenAI-compatible"), engine) { P.ttsEngine.set(it) }
                    val voice by P.ttsVoice.state.collectAsState()
                    val language by P.ttsLanguage.state.collectAsState()
                    if (engine == "android") {
                        var voices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                        var menu by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { voices = runCatching { JarvisVoice.deviceVoices() }.getOrDefault(emptyList()) }
                        Box {
                            OutlinedButton(onClick = { menu = true }) { Text(stringResource(R.string.jarvis_settings_voice_prefix, voices.firstOrNull { it.first == voice }?.second ?: stringResource(R.string.jarvis_settings_system_default))) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.jarvis_settings_screen_system_default)) }, onClick = { P.ttsVoice.set(""); menu = false })
                                voices.take(80).forEach { (id, label) ->
                                    DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = { P.ttsVoice.set(id); menu = false })
                                }
                            }
                        }
                        JField("Language (optional, e.g. en-US)", language) { P.ttsLanguage.set(it) }
                    } else {
                        val key by P.ttsApiKey.state.collectAsState()
                        JField("API key", key, secret = true) { P.ttsApiKey.set(it) }
                        if (engine == "google_cloud") {
                            JField("Voice name (e.g. en-US-Neural2-F)", voice) { P.ttsVoice.set(it) }
                            JField("Language code (e.g. en-US)", language) { P.ttsLanguage.set(it) }
                        } else {
                            val base by P.ttsBaseUrl.state.collectAsState()
                            val model by P.ttsModel.state.collectAsState()
                            JField("Base URL (default https://api.openai.com/v1)", base) { P.ttsBaseUrl.set(it) }
                            JField("Model (default tts-1)", model) { P.ttsModel.set(it) }
                            JField("Voice (default alloy)", voice) { P.ttsVoice.set(it) }
                        }
                        JHint(stringResource(R.string.jarvis_settings_screen_if_the_online_voice_fails_jarvis_falls))
                    }
                    val rate by P.ttsRate.state.collectAsState()
                    JSlider("Speed", rate, 0.5f..2f, "%.1fx".format(rate)) { P.ttsRate.set(it) }
                    val pitch by P.ttsPitch.state.collectAsState()
                    JSlider("Pitch", pitch, 0.5f..2f, "%.1f".format(pitch)) { P.ttsPitch.set(it) }
                    val err by JarvisVoice.lastError.collectAsState()
                    err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { scope.launch { JarvisVoice.speak("Hello, I'm Jarvis. This is how I sound.", wait = false) } }) { Text(stringResource(R.string.jarvis_settings_screen_test_voice)) }
                    val progress by P.speakProgress.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_speak_progress_updates), stringResource(R.string.jarvis_settings_screen_jarvis_says_a_short_line_while_a), progress) { P.speakProgress.set(it) }
                    JHint(stringResource(R.string.jarvis_settings_screen_you_can_also_say_speak_slower_faster))
                }
            }
            JarvisSettingsPage.TASKS -> {
                JCard(cardBg, cardBorder, "") {
                    JHint("Say “build the release and tell me when it's done”: Jarvis starts a task in its own chat and watches it. You can ask how it is going, " +
                        "redirect it (“also run the tests”), stop it, or answer its questions by voice. Tasks are listed in the Jarvis panel.")
                    val routing by P.eventRouting.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_when_a_task_finishes_fails_or_gets), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("model" to "Jarvis decides", "speak" to "Just tell me"), routing) { P.eventRouting.set(it) }
                    JHint(if (routing == "model") stringResource(R.string.jarvis_settings_screen_jarvis_reads_the_result_tells_you_the)
                    else stringResource(R.string.jarvis_settings_screen_jarvis_just_announces_it_release_build_is))
                    val taskAppr by P.taskApprovals.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_approvals_inside_tasks), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("policy" to "Use my approval rules", "jarvis" to "Jarvis decides"), taskAppr) { P.taskApprovals.set(it) }
                    JHint(if (taskAppr == "jarvis") stringResource(R.string.jarvis_settings_screen_jarvis_approves_what_clearly_serves_the_task)
                    else stringResource(R.string.jarvis_settings_screen_approvals_follow_approvals_by_voice_below_approved))
                    val stall by P.stallMinutes.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_report_a_task_with_no_progress_after), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to "Never", "5" to "5 min", "10" to "10 min", "20" to "20 min", "45" to "45 min"), stall.toString()) { P.stallMinutes.set(it.toInt()) }
                    val done by P.announceDone.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_say_when_any_other_chat_finishes), stringResource(R.string.jarvis_settings_screen_chat_title_is_done_please_check_it), done) { P.announceDone.set(it) }
                    if (done) {
                        val off by P.announceOffscreenOnly.state.collectAsState()
                        JSwitch(stringResource(R.string.jarvis_settings_screen_not_for_the_chat_i_m_looking), null, off) { P.announceOffscreenOnly.set(it) }
                    }
                    val sum by P.announceSummary.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_also_read_the_start_of_the_reply), stringResource(R.string.jarvis_settings_screen_for_announcements_without_a_model_turn), sum) { P.announceSummary.set(it) }
                    val low by P.announceUsageLow.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_warn_when_the_5_hour_usage_is), stringResource(R.string.jarvis_settings_screen_once_per_window_at_about_15_left), low) { P.announceUsageLow.set(it) }
                }
                JCard(cardBg, cardBorder, "Command center") {
                    JHint("Open the Jarvis tab in the chat list and tap “New Jarvis chat” to type to Jarvis like in any chat. Give it a big job: it writes a " +
                        "mission (goal, done-when, steps), creates teams of agents (e.g. builders and reviewers), hands out tasks in parallel, checks what comes " +
                        "back and keeps going. The strip above the input shows the mission, the teams' tasks and the switches below.")
                    val speakDefault by P.managerSpeakDefault.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_read_replies_out_loud_in_new_jarvis), stringResource(R.string.jarvis_settings_screen_each_chat_has_its_own_voice_switch), speakDefault) {
                        P.managerSpeakDefault.set(it)
                    }
                    val guard by P.waitGuard.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_when_jarvis_sleeps_or_polls_while_waiting), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("interrupt" to "Stop it, hand it the news", "remind" to "Only remind it", "off" to "Leave it"), guard) { P.waitGuard.set(it) }
                    JHint(when (guard) {
                        "interrupt" -> stringResource(R.string.jarvis_settings_screen_a_turn_that_waits_sleep_wait_loops)
                        "remind" -> stringResource(R.string.jarvis_settings_screen_the_next_message_tells_it_not_to)
                        else -> stringResource(R.string.jarvis_settings_screen_no_guard_news_waits_until_jarvis_ends)
                    })
                    val batch by P.eventBatchMs.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_collect_news_before_sending_it), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to "At once", "2000" to "2 s", "5000" to "5 s", "15000" to "15 s"), batch.toString()) { P.eventBatchMs.set(it.toInt()) }
                    JHint(stringResource(R.string.jarvis_settings_screen_whatever_comes_in_meanwhile_and_everything_that))
                    val nudges by P.autopilotMaxNudges.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_autopilot_gives_up_after), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("2" to "2 nudges", "4" to "4 nudges", "8" to "8 nudges", "15" to "15 nudges"), nudges.toString()) { P.autopilotMaxNudges.set(it.toInt()) }
                    JHint(stringResource(R.string.jarvis_settings_screen_with_autopilot_on_jarvis_is_nudged_to))
                    JHint(stringResource(R.string.jarvis_settings_screen_whether_jarvis_chats_also_show_in_all))
                    JHint(stringResource(R.string.jarvis_settings_screen_several_jarvis_chats_can_work_at_the))
                }
            }
            JarvisSettingsPage.APPROVALS -> {
                JCard(cardBg, cardBorder, "") {
                    val policy by P.permissionPolicy.state.collectAsState()
                    var confirmAll by remember { mutableStateOf(false) }
                    JChips(
                        listOf("ask_each" to "Ask me each time", "auto_readonly" to "Auto: read-only", "auto_except_risky" to "Auto: except risky", "auto_all" to "Auto: everything"),
                        policy
                    ) { if (it == "auto_all") confirmAll = true else P.permissionPolicy.set(it) }
                    JHint(
                        when (policy) {
                            "auto_readonly" -> "Reading files and searching is approved; anything else is read out and needs a yes."
                            "auto_except_risky" -> "Everything is approved except risky actions (deleting, pushing, installing from the web), which are read out first."
                            "auto_all" -> "Nothing is asked. Use only if you fully trust what the agents do."
                            else -> "Jarvis reads each request out (“It wants to run the command…”) and waits for your yes or no."
                        } + " Jarvis' own tools never need approval. The chat on screen is only handled while hands-free mode is on (tasks always are)."
                    )
                    if (confirmAll) {
                        AlertDialog(
                            onDismissRequest = { confirmAll = false },
                            title = { Text(stringResource(R.string.jarvis_settings_screen_approve_everything_automatically)) },
                            text = { Text(stringResource(R.string.jarvis_settings_screen_agents_will_run_commands_edit_files_and)) },
                            confirmButton = { TextButton(onClick = { P.permissionPolicy.set("auto_all"); confirmAll = false }) { Text(stringResource(R.string.jarvis_settings_screen_yes_approve_everything)) } },
                            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
                        )
                    }
                    val allow by P.allowSettingChanges.state.collectAsState()
                    JSwitch(
                        stringResource(R.string.jarvis_settings_screen_let_jarvis_change_app_settings),
                        "Theme, chat font size, floating bubble and switcher, new-chat agent. Jarvis' own voice and listening options can always be changed by voice; " +
                            "approval and security settings never.",
                        allow
                    ) { P.allowSettingChanges.set(it) }
                }
            }
            JarvisSettingsPage.SCREEN -> {
                JCard(cardBg, cardBorder, "") {
                    val screenOn by P.keepScreenOn.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_keep_the_screen_on), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("always" to "While Jarvis is on", "active" to "While it's active", "off" to "Never"), screenOn) { P.keepScreenOn.set(it) }
                    JHint(stringResource(R.string.jarvis_settings_screen_while_it_s_active_while_jarvis_listens))
                    val size by P.orbSize.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_orb_while_idle), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("tiny" to "Tiny", "small" to "Small", "normal" to "Normal", "hidden" to "Hidden"), size) { P.orbSize.set(it) }
                    JHint(stringResource(R.string.jarvis_settings_screen_drag_the_orb_anywhere_it_rests_on))
                    val style by P.orbStyle.state.collectAsState()
                    JChips(listOf("floating" to "Floating card", "fullscreen" to "Full screen"), style) { P.orbStyle.set(it) }
                    val haptics by P.haptics.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_vibrate_when_listening_starts), null, haptics) { P.haptics.set(it) }
                    val activity by P.showActivity.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_show_what_jarvis_is_doing), stringResource(R.string.jarvis_settings_screen_a_slim_status_line_checking_the_build), activity) { P.showActivity.set(it) }
                    val transcript by P.showTranscript.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_show_what_jarvis_heard_and_said), null, transcript) { P.showTranscript.set(it) }
                }
            }
            JarvisSettingsPage.CHAT -> {
                JCard(cardBg, cardBorder, "") {
                    val ctx by P.contextInPrompt.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_send_live_context_with_every_message), stringResource(R.string.jarvis_settings_screen_screen_project_chat_on_screen_tasks_approvals), ctx) { P.contextInPrompt.set(it) }
                    val idle by P.rotateIdleMinutes.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_start_a_fresh_chat_after_idle_time), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to "Never", "5" to "5 min", "15" to "15 min", "30" to "30 min", "60" to "1 h", "180" to "3 h"), idle.toString()) { P.rotateIdleMinutes.set(it.toInt()) }
                    val maxMsgs by P.rotateMaxMessages.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_start_a_fresh_chat_after_this_many), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to "Never", "10" to "10", "20" to "20", "40" to "40", "80" to "80"), maxMsgs.toString()) { P.rotateMaxMessages.set(it.toInt()) }
                    JHint("Both are off by default so Jarvis never forgets mid-task. A chat is only replaced when you speak the next message, never while it is working. " +
                        "You can always say “start again” or “new session”.")
                    val carry by P.carryOver.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_carry_the_conversation_over), stringResource(R.string.jarvis_settings_screen_a_new_chat_starts_with_the_jarvis), carry) { P.carryOver.set(it) }
                    val count by P.currentChatMessages.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_current_chat_message, count.toString(), (if (count == 1) "" else "s").toString()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    OutlinedButton(onClick = { JarvisController.newSession(carry = carry); Toast.makeText(context, context.getString(R.string.jarvis_settings_next_message_new_chat), Toast.LENGTH_SHORT).show() }) {
                        Text(stringResource(R.string.jarvis_settings_screen_start_a_fresh_chat_now))
                    }
                    val memory by P.memory.state.collectAsState()
                    OutlinedTextField(
                        value = memory, onValueChange = { P.memory.set(it.take(4000)) },
                        label = { Text(stringResource(R.string.jarvis_settings_screen_jarvis_memory_jarvis_can_edit_this_too)) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A phrase list folded to one line ("Send phrases · 19   Edit"): its chips and field only exist while it is open, so
 * the listening page opens at once.
 */
@Composable
private fun FoldedPhrases(title: String, count: Int, editor: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$title · $count", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
        Text(if (open) stringResource(R.string.browser_screen_done) else stringResource(R.string.chat_screen_edit), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
    if (open) editor()
}


// ---------------------------------------------------------------------------- small building blocks

/**
 * Editable list of phrases (one chip each, tap × to remove), a box to add a word or a whole sentence, and reset.
 * [other] is the opposite list (send ↔ cancel): the same phrase cannot be in both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhraseEditor(phrases: List<String>, other: List<String>, otherName: String, defaults: List<String>, onChange: (List<String>) -> Unit) {
    val J = ai.antigem.data.jarvis.JarvisPhrases
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    fun add() {
        val p = J.normalize(input)
        if (p.isBlank()) return
        note = when {
            p in phrases -> "“$p” is already in the list."
            p in other -> "“$p” is already a $otherName phrase. Remove it there first."
            else -> {
                onChange(phrases + p)
                input = ""
                if (J.isRisky(p)) "Added. “$p” is short or common, so it only counts when said on its own after a short pause." else null
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // light chips (a rounded outline with the phrase and ×; tap removes it): the lists hold ~20 phrases each, and
        // as Material InputChips they made opening Jarvis' settings stall for about half a second
        val chipShape = RoundedCornerShape(8.dp)
        val chipBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        val xColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        phrases.forEach { p ->
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(chipShape)
                    .border(1.dp, chipBorder, chipShape)
                    .clickable { onChange(phrases - p) }
                    .padding(start = 12.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(p, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text("×", fontSize = 14.sp, color = xColor)
            }
        }
        if (phrases.isEmpty()) Text(stringResource(R.string.jarvis_settings_screen_no_phrases_yet), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input, onValueChange = { input = it; note = null },
            label = { Text(stringResource(R.string.jarvis_settings_screen_add_a_word_or_a_whole_sentence), fontSize = 12.sp) }, singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() })
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { add() }, enabled = input.isNotBlank()) { Text(stringResource(R.string.browser_screen_add), fontSize = 12.sp) }
    }
    note?.let { Text(it, fontSize = 12.sp, color = Color(0xFFF59E0B)) }
    // nothing is active until added: suggestions are offered, never applied by themselves
    val missing = defaults.map { J.normalize(it) }.filter { it !in phrases && it !in other }
    Row {
        if (missing.isNotEmpty()) TextButton(onClick = { onChange(phrases + missing); note = null }) { Text(stringResource(R.string.jarvis_settings_screen_add_suggestions, missing.size.toString()), fontSize = 12.sp) }
        if (phrases.isNotEmpty()) TextButton(onClick = { onChange(emptyList()); note = null }) { Text(stringResource(R.string.jarvis_settings_screen_remove_all), fontSize = 12.sp) }
    }
}

@Composable
private fun JCard(bg: Color, border: BorderStroke, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = border
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title.isNotBlank()) Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = JarvisCyan)
            content()
        }
    }
}

@Composable
private fun JSwitch(title: String, subtitle: String?, checked: Boolean, enabledSwitch: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabledSwitch)
    }
}

@Composable
private fun JChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label, fontSize = 12.sp) })
        }
    }
}

@Composable
private fun JField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
    )
}

@Composable
private fun JSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(shown, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun JHint(text: String) {
    Text(text, fontSize = 11.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
}

@Composable
private fun JStep(title: String, subtitle: String, done: Boolean, button: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = if (done) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Spacer(Modifier.width(8.dp))
        if (done && button == "Allow") Text("✓", color = Color(0xFF22C55E), fontSize = 18.sp)
        else OutlinedButton(onClick = onClick) { Text(button, fontSize = 12.sp) }
    }
}
