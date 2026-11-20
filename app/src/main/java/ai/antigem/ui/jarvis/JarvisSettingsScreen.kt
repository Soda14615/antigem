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
                "覆盖整个应用的免手操作助手：可与打开的项目和文件对话、运行并查看任务、处理审批，" +
                    "更改设置、查询帮助或用量。默认关闭；开启前不会监听任何内容。",
                enabled
            ) { JarvisController.setEnabled(it) }
            if (enabled) {
                JSwitch(
                    stringResource(R.string.jarvis_settings_screen_floating_button_voice),
                    "关闭 = 仅聊天：没有浮动按钮、没有麦克风、不会朗读任何内容。你可以用打字的方式使用 Jarvis，入口是聊天列表中的 " +
                        "Jarvis 标签页 → 新建 Jarvis 聊天。",
                    voiceSide
                ) { JarvisController.setVoiceAssistant(it) }
            }
            JHint(
                if (!enabled || voiceSide) "点按悬浮球说话，长按打开 Jarvis 面板（任务、对话、打字、快捷开关）。" +
                    "你也可以打字给 Jarvis：聊天列表中的 Jarvis 标签页 → 新建 Jarvis 聊天。"
                else stringResource(R.string.jarvis_settings_screen_chat_only_open_the_jarvis_tab_in)
            )
        }
        if (enabled && JarvisController.isAttached) {
            JCard(cardBg, cardBorder, "聊天列表中的 Jarvis 聊天") {
                val inAll by P.jarvisInAll.state.collectAsState()
                JChips(listOf("none" to "仅在 Jarvis 下", "text" to "我的 Jarvis 聊天也显示在“全部”中", "all" to "所有 Jarvis 聊天也显示在“全部”中"), inAll) { P.jarvisInAll.set(it) }
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
                row(JarvisSettingsPage.AGENT, I.SmartToy, c.info, "语音智能体与模型", when (agentName) {
                    AgentKind.AGY.name -> "Antigravity"
                    AgentKind.CLAUDE.name -> "Claude Code"
                    AgentKind.OPENCODE.name -> "OpenCode"
                    AgentKind.CODEX.name -> "Codex"
                    else -> "未设置"
                }),
                row(JarvisSettingsPage.PERSONALITY, I.Face, c.accentPink, "个性与个人信息"),
                // the setup checklist is only for a Jarvis that is turned on
                if (enabled) row(JarvisSettingsPage.SETUP, I.Checklist, c.success, "初始设置") else null
            ),
            // "chat only": nothing listens or speaks, so those pages are hidden
            if (!voiceSide) emptyList() else listOf(
                row(JarvisSettingsPage.WAKE, I.Hearing, c.accentTeal, "唤醒 Jarvis", if (hotword == "phrase") "唤醒词" else "Button"),
                row(JarvisSettingsPage.LISTEN, I.Mic, c.accentIndigo, "聆听与发送", if (autoSend) "自动发送" else "手动发送"),
                row(JarvisSettingsPage.VOICE, I.RecordVoiceOver, c.accentAlt, "语音输出", when {
                    quiet -> "静音"
                    engine == "google_cloud" -> "Google Cloud"
                    engine == "openai" -> "OpenAI"
                    else -> "本机"
                })
            ),
            listOf(
                row(JarvisSettingsPage.TASKS, I.TaskAlt, c.warning, "管理器与任务"),
                row(JarvisSettingsPage.APPROVALS, I.VerifiedUser, c.danger, "审批", when (policy) {
                    "auto_readonly" -> "自动：只读"
                    "auto_except_risky" -> "自动：非高风险"
                    "auto_all" -> "自动：全部"
                    else -> "每次询问"
                }),
                row(JarvisSettingsPage.SCREEN, I.Visibility, c.info, "屏幕与外观"),
                row(JarvisSettingsPage.CHAT, I.ChatBubbleOutline, JarvisCyan, "Jarvis 聊天")
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
        null -> "该智能体"
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
                    JHint("Jarvis 与该智能体的一个隐藏普通聊天对话（在聊天列表的 Jarvis 下可找到）。语音场景建议用响应快的模型；" +
                        "大型任务会在各自的聊天中作为任务运行。")
                }
            }
            JarvisSettingsPage.PERSONALITY -> {
                JCard(cardBg, cardBorder, "") {
                    val persona by P.persona.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_jarvis_speaks_as), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("" to "未设置", "female" to "Female", "male" to "Male", "neutral" to "Neutral"), persona) { P.persona.set(it) }
                    JHint("Jarvis 自称时使用的语法性别。在印地语等语言中会影响措辞（“main kar rahi hoon” / “main kar raha hoon”）。" +
                        "请在“语音输出”中选择匹配的音色。")
                    val about by P.aboutUser.state.collectAsState()
                    OutlinedTextField(
                        value = about, onValueChange = { P.aboutUser.set(it.take(2000)) },
                        label = { Text(stringResource(R.string.jarvis_settings_screen_about_you_and_extra_instructions)) },
                        placeholder = { Text(stringResource(R.string.jarvis_settings_screen_e_g_call_me_santosh_reply_in), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8
                    )
                    JHint("随每个新 Jarvis 聊天的首条消息发送。当你修改此内容（或人格）后，当前聊天会在你下一条消息时获知更新。" +
                        "Jarvis 自己的记忆备注（设置 → Jarvis → Jarvis 聊天）是独立的，由 Jarvis 自行编辑。")
                }
            }
            JarvisSettingsPage.SETUP -> {
                if (!enabled) {
                    JHint(stringResource(R.string.jarvis_settings_screen_turn_on_jarvis_first_settings_jarvis))
                } else {
                    if (agent == null) {
                        JHint(stringResource(R.string.jarvis_settings_screen_pick_the_agent_jarvis_talks_to_first))
                    } else JCard(cardBg, cardBorder, "为 ${agentTitle} 进行初始设置") {
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
                            "1. 添加 Jarvis MCP 服务器",
                            if (mcpAdded) "已添加" + (mcpState?.let { " · $it" } ?: "") else "Required: these are the tools Jarvis controls the app with.",
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
                                "2. 让 Jarvis 的工具免询问运行",
                                if (allowed) "已允许" else "否则每次语音回答都需要审批。",
                                allowed, "允许"
                            ) { vm.claudeConfig.addRule("allow", "mcp__$name") }
                        }

                        // 3. the microphone
                        var hasMic by remember { mutableStateOf(JarvisController.hasMic()) }
                        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }
                        JStep(
                            "${if (agent == AgentKind.CLAUDE) "3" else "2"}. 允许使用麦克风",
                            if (hasMic) "已允许" else "用于听到你的声音。",
                            hasMic, "允许"
                        ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }

                        // 4. the optional skill
                        var skillInstalled by remember(agent) { mutableStateOf<Boolean?>(null) }
                        LaunchedEffect(agent) { skillInstalled = runCatching { JarvisSkill.isInstalled(agent) }.getOrDefault(false) }
                        JStep(
                            "可选：安装 Jarvis 技能",
                            when (skillInstalled) { true -> "已安装"; false -> "不需要：指南会随首条消息一起发送。"; null -> "Checking…" },
                            skillInstalled == true, if (skillInstalled == true) "重新安装" else "安装"
                        ) {
                            scope.launch {
                                JarvisSkill.install(agent)
                                    .onSuccess { skillInstalled = true; Toast.makeText(context, context.getString(R.string.jarvis_settings_installed_item, it), Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.jarvis_settings_install_failed), Toast.LENGTH_LONG).show() }
                            }
                        }
                        JHint("每个 Jarvis 聊天的首条消息都会带上完整指南，以及其工具的清单与参数，因此" +
                            "模型可以立即行动，无需先检索。如果缺少 Jarvis MCP 服务器，Jarvis 会说明，此卡片也会显示警告。")
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
                        JHint("“Hey Jarvis，打开我的项目”会立即触发。“Jarvis”的常见误听也算。唤醒词需要 " +
                            "免手模式，并会让系统语音识别器持续工作，比较耗电。")
                    }
                    val hands by P.handsFree.state.collectAsState()
                    JSwitch(
                        stringResource(R.string.jarvis_settings_screen_hands_free_mode),
                        "保持 Jarvis 麦克风服务运行，使唤醒词和耳机键在息屏或使用其他应用时也能工作。" +
                            "通知栏会用“说话 / 发送 / 停止”显示其状态。",
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
                        JChips(listOf("smart" to "智能停顿", "pause" to "固定停顿"), if (mode == "pause") "pause" else "smart") { P.sendMode.set(it) }
                        JHint(if (mode == "pause") stringResource(R.string.jarvis_settings_screen_sent_after_a_fixed_pause_a_bar)
                        else stringResource(R.string.jarvis_settings_screen_jarvis_waits_longer_when_you_sound_unfinished))
                    }
                    val silence by P.silenceMs.state.collectAsState()
                    JSlider("发送前停顿", silence.toFloat(), 600f..4000f, "%.1f s".format(silence / 1000f)) { P.silenceMs.set(it.toInt()) }
                    val answers by P.answerSend.state.collectAsState()
                    Text(stringResource(R.string.jarvis_quick_settings_answers_to_jarvis_questions), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("same" to "与其他设置相同", "auto" to "停顿后发送"), answers) { P.answerSend.set(it) }
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
                        FoldedPhrases("发送短语", sendList.size) {
                            PhraseEditor(sendList, cancelList, "cancel", ai.antigem.data.jarvis.JarvisPhrases.SUGGESTED_SEND) {
                                P.sendPhrases.set(ai.antigem.data.jarvis.JarvisPhrases.format(it))
                            }
                        }
                    }
                    JSwitch(stringResource(R.string.jarvis_settings_screen_cancel_with_a_phrase), stringResource(R.string.jarvis_settings_screen_end_with_one_of_these_and_nothing), cancelOn) { P.cancelPhrasesOn.set(it) }
                    if (cancelOn) {
                        FoldedPhrases("取消短语", cancelList.size) {
                            PhraseEditor(cancelList, sendList, "send", ai.antigem.data.jarvis.JarvisPhrases.SUGGESTED_CANCEL) {
                                P.cancelPhrases.set(ai.antigem.data.jarvis.JarvisPhrases.format(it))
                            }
                        }
                    }
                    if (sendOn || cancelOn) {
                        JHint("短语只在句末才算数。“……and send it”“……to send it”“can you do it”和“don't send it”仍属于你的" +
                            "消息内容。短句或日常用语（“send”“over”“do it”）只有在短暂停顿后单独说出时才生效。" +
                            "如果某个短语频繁误触发，请删掉它，或改用更长的句子（例如“jarvis send it now”）。")
                    }
                    val listenAfter by P.listenAfter.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_after_jarvis_speaks_listen_for_your_reply), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("auto" to "当它期待回答时", "always" to "总是", "never" to "从不"), listenAfter) { P.listenAfter.set(it) }
                    JHint(when (listenAfter) {
                        "always" -> stringResource(R.string.jarvis_settings_screen_the_microphone_opens_after_every_answer_while)
                        "never" -> stringResource(R.string.jarvis_settings_screen_the_microphone_only_opens_when_you_tap)
                        else -> stringResource(R.string.jarvis_settings_screen_jarvis_decides_after_done_want_me_to)
                    })
                    if (listenAfter != "never") {
                        val follow by P.followUpSeconds.state.collectAsState()
                        JSlider("等待回复", follow.toFloat(), 3f..20f, "${follow} 秒") { P.followUpSeconds.set(it.toInt()) }
                        JHint(stringResource(R.string.jarvis_settings_screen_if_you_say_nothing_at_all_in))
                    }
                    val maxListen by P.maxListenSeconds.state.collectAsState()
                    JSlider("单条消息最长", maxListen.toFloat(), 20f..240f, "${maxListen} 秒") { P.maxListenSeconds.set(it.toInt()) }
                    val side by P.sideTalk.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_talking_to_other_people_nearby), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("off" to "关闭", "smart" to "智能", "strict" to "严格"), side) { P.sideTalk.set(it) }
                    JHint(when (side) {
                        "off" -> stringResource(R.string.jarvis_settings_screen_everything_heard_is_for_jarvis)
                        "strict" -> stringResource(R.string.jarvis_settings_screen_during_follow_up_listening_jarvis_only_reacts)
                        else -> stringResource(R.string.jarvis_settings_screen_during_follow_up_listening_jarvis_ignores_what)
                    })
                    val busyB by P.busyBehavior.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_speaking_while_jarvis_is_still_working), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("queue" to "等它说完", "interrupt" to "总是打断", "smart" to "我的打断短语"), busyB) { P.busyBehavior.set(it) }
                    if (busyB == "smart") JHint(stringResource(R.string.jarvis_settings_screen_only_a_message_that_starts_with_one))
                    val earcons by P.earcons.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_tones_when_the_microphone_opens_and_closes), null, earcons) { P.earcons.set(it) }
                    val stt by P.sttEngine.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_speech_recognition), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("auto" to "与智能体相同", "claude" to "Claude", "agy" to "Antigravity", "android" to "本机"), stt) { P.sttEngine.set(it) }
                    JHint(stringResource(R.string.jarvis_settings_screen_this_device_uses_android_s_recognizer_and))
                    val lang by P.sttLanguage.state.collectAsState()
                    JField("语言（如 zh、en、hi）", lang) { P.sttLanguage.set(it) }
                }
                JCard(cardBg, cardBorder, "语音快捷指令") {
                    JHint("你自己设定的短语，单独说出时（可带“Jarvis”或“please”）会立即执行，无需询问模型。" +
                        "在你添加之前没有任何短语，因此对话中随口说的“okay”“stop”仍会像普通内容一样发给 Jarvis。")
                    val J = ai.antigem.data.jarvis.JarvisPhrases
                    listOf(
                        Triple("停止一切", P.cmdStop, "stop"),
                        Triple("结束对话", P.cmdBye, "bye"),
                        Triple("重复上一个回答", P.cmdRepeat, "repeat"),
                        Triple("说慢一点", P.cmdSlower, "slower"),
                        Triple("说快一点", P.cmdFaster, "faster"),
                        Triple("开启静音模式", P.cmdQuietOn, "quiet_on"),
                        Triple("关闭静音模式", P.cmdQuietOff, "quiet_off"),
                        Triple("开始新会话", P.cmdNewSession, "new_session"),
                        Triple("打断 Jarvis 的工作（会开始一条消息）", P.interruptPhrases, "interrupt")
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
                    JChips(listOf("android" to "本机", "google_cloud" to "Google Cloud", "openai" to "OpenAI-compatible"), engine) { P.ttsEngine.set(it) }
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
                        JField("语言（可选，如 zh-CN）", language) { P.ttsLanguage.set(it) }
                    } else {
                        val key by P.ttsApiKey.state.collectAsState()
                        JField("API 密钥", key, secret = true) { P.ttsApiKey.set(it) }
                        if (engine == "google_cloud") {
                            JField("音色名称（如 en-US-Neural2-F）", voice) { P.ttsVoice.set(it) }
                            JField("语言代码（如 zh-CN）", language) { P.ttsLanguage.set(it) }
                        } else {
                            val base by P.ttsBaseUrl.state.collectAsState()
                            val model by P.ttsModel.state.collectAsState()
                            JField("基础 URL（默认 https://api.openai.com/v1）", base) { P.ttsBaseUrl.set(it) }
                            JField("模型（默认 tts-1）", model) { P.ttsModel.set(it) }
                            JField("音色（默认 alloy）", voice) { P.ttsVoice.set(it) }
                        }
                        JHint(stringResource(R.string.jarvis_settings_screen_if_the_online_voice_fails_jarvis_falls))
                    }
                    val rate by P.ttsRate.state.collectAsState()
                    JSlider("语速", rate, 0.5f..2f, "%.1fx".format(rate)) { P.ttsRate.set(it) }
                    val pitch by P.ttsPitch.state.collectAsState()
                    JSlider("音调", pitch, 0.5f..2f, "%.1f".format(pitch)) { P.ttsPitch.set(it) }
                    val err by JarvisVoice.lastError.collectAsState()
                    err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { scope.launch { JarvisVoice.speak("你好，我是 Jarvis。这是我说话的声音。", wait = false) } }) { Text(stringResource(R.string.jarvis_settings_screen_test_voice)) }
                    val progress by P.speakProgress.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_speak_progress_updates), stringResource(R.string.jarvis_settings_screen_jarvis_says_a_short_line_while_a), progress) { P.speakProgress.set(it) }
                    JHint(stringResource(R.string.jarvis_settings_screen_you_can_also_say_speak_slower_faster))
                }
            }
            JarvisSettingsPage.TASKS -> {
                JCard(cardBg, cardBorder, "") {
                    JHint("说“构建发布包，完成后告诉我”：Jarvis 会在独立聊天中启动任务并持续关注。你可以询问进度、" +
                        "调整方向（“顺便跑一下测试”）、停止它，或用语音回答它的问题。任务会列在 Jarvis 面板中。")
                    val routing by P.eventRouting.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_when_a_task_finishes_fails_or_gets), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("model" to "由 Jarvis 决定", "speak" to "直接告诉我"), routing) { P.eventRouting.set(it) }
                    JHint(if (routing == "model") stringResource(R.string.jarvis_settings_screen_jarvis_reads_the_result_tells_you_the)
                    else stringResource(R.string.jarvis_settings_screen_jarvis_just_announces_it_release_build_is))
                    val taskAppr by P.taskApprovals.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_approvals_inside_tasks), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("policy" to "使用我的审批规则", "jarvis" to "由 Jarvis 决定"), taskAppr) { P.taskApprovals.set(it) }
                    JHint(if (taskAppr == "jarvis") stringResource(R.string.jarvis_settings_screen_jarvis_approves_what_clearly_serves_the_task)
                    else stringResource(R.string.jarvis_settings_screen_approvals_follow_approvals_by_voice_below_approved))
                    val stall by P.stallMinutes.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_report_a_task_with_no_progress_after), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to "从不", "5" to "5 分钟", "10" to "10 分钟", "20" to "20 分钟", "45" to "45 分钟"), stall.toString()) { P.stallMinutes.set(it.toInt()) }
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
                JCard(cardBg, cardBorder, "指挥中心") {
                    JHint("在聊天列表中打开 Jarvis 标签页，点按“新建 Jarvis 聊天”即可像普通聊天一样打字给 Jarvis。交给它一个大任务：它会拟定" +
                        "任务书（目标、完成标准、步骤），组建智能体团队（如构建者和审查者），并行派发任务，检查回传结果" +
                        "并持续推进。输入框上方会显示任务书、各团队任务以及下方开关。")
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
                        listOf("ask_each" to "每次问我", "auto_readonly" to "自动：只读", "auto_except_risky" to "自动：高风险除外", "auto_all" to "自动：全部"),
                        policy
                    ) { if (it == "auto_all") confirmAll = true else P.permissionPolicy.set(it) }
                    JHint(
                        when (policy) {
                            "auto_readonly" -> "读取文件和搜索会自动通过；其他操作都会朗读出来，需要你确认。"
                            "auto_except_risky" -> "除高风险操作（删除、推送、从网页安装）外都会自动通过，高风险操作会先朗读出来。"
                            "auto_all" -> "不再询问任何操作。仅在你完全信任智能体行为时使用。"
                            else -> "Jarvis 会朗读每个请求（“它想执行这条命令……”），然后等待你确认或拒绝。"
                        } + " Jarvis 自己的工具无需审批。仅当免手模式开启时才会处理屏幕上的聊天（任务则始终处理）。"
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
                        "主题、聊天字号、悬浮球与切换器、新聊天使用的智能体。Jarvis 自己的语音和聆听选项始终可用语音修改；" +
                            "审批与安全设置则永远不能。",
                        allow
                    ) { P.allowSettingChanges.set(it) }
                }
            }
            JarvisSettingsPage.SCREEN -> {
                JCard(cardBg, cardBorder, "") {
                    val screenOn by P.keepScreenOn.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_keep_the_screen_on), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("always" to "Jarvis 开启期间", "active" to "活跃期间", "off" to "从不"), screenOn) { P.keepScreenOn.set(it) }
                    JHint(stringResource(R.string.jarvis_settings_screen_while_it_s_active_while_jarvis_listens))
                    val size by P.orbSize.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_orb_while_idle), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("tiny" to "极小", "small" to "小", "normal" to "标准", "hidden" to "隐藏"), size) { P.orbSize.set(it) }
                    JHint(stringResource(R.string.jarvis_settings_screen_drag_the_orb_anywhere_it_rests_on))
                    val style by P.orbStyle.state.collectAsState()
                    JChips(listOf("floating" to "浮动卡片", "fullscreen" to "全屏"), style) { P.orbStyle.set(it) }
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
                    JChips(listOf("0" to "从不", "5" to "5 分钟", "15" to "15 分钟", "30" to "30 分钟", "60" to "1 小时", "180" to "3 小时"), idle.toString()) { P.rotateIdleMinutes.set(it.toInt()) }
                    val maxMsgs by P.rotateMaxMessages.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_start_a_fresh_chat_after_this_many), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to "从不", "10" to "10", "20" to "20", "40" to "40", "80" to "80"), maxMsgs.toString()) { P.rotateMaxMessages.set(it.toInt()) }
                    JHint("两者默认都关闭，以免 Jarvis 在任务中途遗忘上下文。只有你说下一条消息时才会更换聊天，工作时绝不会更换。" +
                        "你随时可以说“重新开始”或“新会话”。")
                    val carry by P.carryOver.state.collectAsState()
                    JSwitch(stringResource(R.string.jarvis_settings_screen_carry_the_conversation_over), stringResource(R.string.jarvis_settings_screen_a_new_chat_starts_with_the_jarvis), carry) { P.carryOver.set(it) }
                    val count by P.currentChatMessages.state.collectAsState()
                    Text(stringResource(R.string.jarvis_settings_screen_current_chat_message, count.toString(), ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
            p in phrases -> "“${p}”已在列表中。"
            p in other -> "“${p}”已经是${if (otherName == "cancel") "取消" else "发送"}短语，请先在那里移除。"
            else -> {
                onChange(phrases + p)
                input = ""
                if (J.isRisky(p)) "已添加。“${p}”较短或常见，只有在短暂停顿后单独说出时才生效。" else null
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
        if (done && button == "允许") Text("✓", color = Color(0xFF22C55E), fontSize = 18.sp)
        else OutlinedButton(onClick = onClick) { Text(button, fontSize = 12.sp) }
    }
}
