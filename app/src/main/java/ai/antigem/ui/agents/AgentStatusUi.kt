package ai.antigem.ui.agents

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.agent.claude.ClaudeCliStatus
import ai.antigem.data.agent.claude.ClaudeStatus
import ai.antigem.data.agent.opencode.OpenCodeState
import ai.antigem.data.agent.codex.CodexState
import ai.antigem.data.remote.AgyHubClient
import ai.antigem.data.remote.SystemConnectionState
import ai.antigem.data.remote.SystemStatus
import ai.antigem.domain.model.AgentKind
import ai.antigem.ui.components.accent
import ai.antigem.ui.components.label

/** One agent's health: drives the split status dot, its popup, the accounts dialog and the new-chat picker. */
data class AgentStatusEntry(
    val agent: AgentKind,
    val label: String,
    val color: Color,
    val detail: String? = null,
    val action: AgentStatusAction? = null,
    /** Transitional (starting, checking, signing in): the dot pulses. */
    val pending: Boolean = false,
    val isReady: Boolean = false,
    val isSignedOut: Boolean = false
)

class AgentStatusAction(val label: String, val onClick: () -> Unit)

@Composable
fun agyStatusEntry(
    state: SystemConnectionState,
    authInfo: AgyHubClient.AgyAuthInfo,
    isAuthBusy: Boolean,
    inStartupGrace: Boolean,
    serverRestarting: Boolean,
    onSignIn: () -> Unit,
    onCheckAuth: () -> Unit,
    onRetry: () -> Unit,
    onRestartServer: () -> Unit
): AgentStatusEntry {
    val status = state.status
    fun entry(label: String, color: Color = status.dotColor, detail: String? = null, action: AgentStatusAction? = null, pending: Boolean = false) =
        AgentStatusEntry(AgentKind.AGY, label, color, detail, action, pending, isReady = status == SystemStatus.READY && !isAuthBusy)
    return when {
        serverRestarting -> entry(stringResource(R.string.main_ui_restarting), SystemStatus.STARTING.dotColor, stringResource(R.string.main_ui_the_local_server_is_restarting), pending = true)
        isAuthBusy -> entry(stringResource(R.string.claude_settings_screen_signing_in), SystemStatus.ACQUIRING_CSRF.dotColor, stringResource(R.string.main_ui_finish_signing_in_with_google_in_your), AgentStatusAction(stringResource(R.string.android_dev_screen_check), onCheckAuth), pending = true)
        status == SystemStatus.OFFLINE && inStartupGrace -> entry(stringResource(R.string.main_ui_starting), SystemStatus.STARTING.dotColor, stringResource(R.string.main_ui_starting_the_local_server), pending = true)
        status == SystemStatus.OFFLINE && state is SystemConnectionState.Connected -> entry(
            stringResource(R.string.settings_offline),
            detail = stringResource(R.string.main_ui_the_antigravity_hub_is_stopped),
            action = AgentStatusAction(stringResource(R.string.codex_settings_screen_restart), onRestartServer)
        )
        status == SystemStatus.OFFLINE -> entry(
            stringResource(R.string.settings_offline),
            detail = stringResource(R.string.main_ui_the_local_server_is_not_running),
            action = AgentStatusAction(stringResource(R.string.servers_settings_screen_start), onRestartServer)
        )
        status == SystemStatus.STARTING -> entry(stringResource(R.string.main_ui_starting), detail = stringResource(R.string.main_ui_the_antigravity_hub_is_starting), pending = true)
        status == SystemStatus.ACQUIRING_CSRF -> entry(stringResource(R.string.notification_connecting), detail = stringResource(R.string.main_ui_getting_a_session_token_from_the_hub), pending = true)
        status == SystemStatus.CHECKING_AUTH -> entry(stringResource(R.string.main_ui_checking_sign_in), pending = true)
        status == SystemStatus.UNAUTHENTICATED -> entry(
            stringResource(R.string.main_ui_sign_in_required),
            detail = stringResource(R.string.main_ui_sign_in_with_google_to_chat_with),
            action = AgentStatusAction(stringResource(R.string.claude_settings_screen_sign_in), onSignIn)
        ).copy(isSignedOut = true)
        status == SystemStatus.READY -> entry(
            stringResource(R.string.main_ui_ready),
            detail = listOf(authInfo.email, authInfo.userTier.ifBlank { authInfo.planName })
                .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
        )
        else -> entry(
            stringResource(R.string.markdown_content_error),
            detail = (state as? SystemConnectionState.Connected)?.error ?: (state as? SystemConnectionState.Error)?.message,
            action = if (state is SystemConnectionState.Connected) AgentStatusAction(stringResource(R.string.codex_settings_screen_restart), onRestartServer) else AgentStatusAction(stringResource(R.string.chat_screen_retry), onRetry)
        )
    }
}

@Composable
fun claudeStatusEntry(
    state: ClaudeStatus,
    status: ClaudeCliStatus?,
    error: String?,
    serverRestarting: Boolean,
    onSignIn: () -> Unit,
    onSetup: () -> Unit,
    onRestartServer: () -> Unit,
    onRetry: () -> Unit
): AgentStatusEntry {
    if (serverRestarting) {
        return AgentStatusEntry(AgentKind.CLAUDE, stringResource(R.string.main_ui_restarting), ClaudeStatus.CHECKING.dotColor, stringResource(R.string.main_ui_the_local_server_is_restarting), pending = true)
    }
    val auth = status?.auth
    val label = stringResource(when (state) {
            ClaudeStatus.CHECKING -> R.string.bridge_ui_checking
            ClaudeStatus.BRIDGE_OFFLINE -> R.string.main_ui_bridge_offline
            ClaudeStatus.BRIDGE_OUTDATED -> R.string.main_ui_restart_needed
            ClaudeStatus.NOT_INSTALLED -> R.string.main_ui_claude_code_not_found
            ClaudeStatus.SIGNED_OUT -> R.string.main_ui_sign_in_required
            ClaudeStatus.SIGNING_IN -> R.string.claude_settings_screen_signing_in
            ClaudeStatus.READY -> R.string.main_ui_ready
            ClaudeStatus.ERROR -> R.string.markdown_content_error
        })
    fun entry(detail: String? = null, action: AgentStatusAction? = null) = AgentStatusEntry(
        AgentKind.CLAUDE, label, state.dotColor, detail, action,
        pending = state == ClaudeStatus.CHECKING || state == ClaudeStatus.SIGNING_IN,
        isReady = state == ClaudeStatus.READY,
        isSignedOut = state == ClaudeStatus.SIGNED_OUT
    )
    return when (state) {
        ClaudeStatus.CHECKING -> entry(stringResource(R.string.main_ui_looking_for_the_claude_code_cli_and))
        ClaudeStatus.BRIDGE_OFFLINE -> entry(error ?: stringResource(R.string.main_ui_the_local_server_is_not_running), AgentStatusAction(stringResource(R.string.servers_settings_screen_start), onRestartServer))
        ClaudeStatus.BRIDGE_OUTDATED -> entry(error, AgentStatusAction(stringResource(R.string.codex_settings_screen_restart), onRestartServer))
        ClaudeStatus.NOT_INSTALLED -> entry(stringResource(R.string.main_ui_the_claude_cli_is_not_installed_on), AgentStatusAction(stringResource(R.string.jarvis_overlay_set_up), onSetup))
        ClaudeStatus.SIGNED_OUT -> entry(stringResource(R.string.main_ui_sign_in_with_your_claude_subscription_or), AgentStatusAction(stringResource(R.string.claude_settings_screen_sign_in), onSignIn))
        ClaudeStatus.SIGNING_IN -> entry(stringResource(R.string.main_ui_finish_signing_in_in_your_browser), AgentStatusAction(stringResource(R.string.jarvis_overlay_open), onSignIn))
        ClaudeStatus.READY -> entry(
            listOfNotNull(auth?.email, auth?.subscriptionType?.let { claudePlanLabel(it) }, status?.version?.substringBefore(" "))
                .joinToString(" · ").ifBlank { null }
        )
        ClaudeStatus.ERROR -> entry(error, AgentStatusAction(stringResource(R.string.chat_screen_retry), onRetry))
    }
}

@Composable
fun openCodeStatusEntry(
    state: ai.antigem.data.agent.opencode.OpenCodeState,
    status: ai.antigem.data.agent.opencode.OpenCodeStatus?,
    serverRestarting: Boolean,
    onSetup: () -> Unit,
    onRestartServer: () -> Unit,
    onRetry: () -> Unit
): AgentStatusEntry {
    val color = when (state) {
        OpenCodeState.READY -> SystemStatus.READY.dotColor
        OpenCodeState.CHECKING -> SystemStatus.STARTING.dotColor
        OpenCodeState.OFFLINE, OpenCodeState.DISABLED, OpenCodeState.OUTDATED -> SystemStatus.OFFLINE.dotColor
        else -> ClaudeStatus.ERROR.dotColor
    }
    if (serverRestarting) {
        return AgentStatusEntry(AgentKind.OPENCODE, stringResource(R.string.main_ui_restarting), SystemStatus.STARTING.dotColor, stringResource(R.string.main_ui_the_local_server_is_restarting), pending = true)
    }
    val label = stringResource(when (state) {
            OpenCodeState.CHECKING -> R.string.bridge_ui_checking
            OpenCodeState.READY -> R.string.main_ui_ready
            OpenCodeState.NOT_INSTALLED -> R.string.android_dev_screen_not_installed
            OpenCodeState.DISABLED -> R.string.main_ui_turned_off_on_the_server
            OpenCodeState.OUTDATED -> R.string.main_ui_server_update_needed
            OpenCodeState.OFFLINE -> R.string.main_ui_server_offline
            OpenCodeState.ERROR -> R.string.markdown_content_error
        })
    fun entry(detail: String? = null, action: AgentStatusAction? = null) = AgentStatusEntry(
        AgentKind.OPENCODE, label, color, detail, action,
        pending = state == OpenCodeState.CHECKING,
        isReady = state == OpenCodeState.READY
    )
    return when (state) {
        OpenCodeState.CHECKING -> entry(stringResource(R.string.main_ui_starting_waiting_for_the_server))
        OpenCodeState.READY -> entry(listOfNotNull(status?.version?.let { "OpenCode $it" }, if (status?.running == true) stringResource(R.string.claude_chat_controls_running_2) else stringResource(R.string.main_ui_starts_with_the_first_message)).joinToString(" · "))
        OpenCodeState.NOT_INSTALLED -> entry(stringResource(R.string.main_ui_opencode_is_not_installed_yet_the_app), AgentStatusAction(stringResource(R.string.claude_settings_screen_install), onSetup))
        OpenCodeState.DISABLED -> entry(stringResource(R.string.main_ui_the_server_was_started_with_opencode_off), AgentStatusAction(stringResource(R.string.codex_settings_screen_restart), onRestartServer))
        OpenCodeState.OUTDATED -> entry(stringResource(R.string.main_ui_this_server_has_no_opencode_support_yet), AgentStatusAction(stringResource(R.string.codex_settings_screen_restart), onRestartServer))
        OpenCodeState.OFFLINE -> entry(stringResource(R.string.main_ui_the_server_is_not_running), AgentStatusAction(stringResource(R.string.servers_settings_screen_start), onRestartServer))
        OpenCodeState.ERROR -> entry(status?.error, AgentStatusAction(stringResource(R.string.chat_screen_retry), onRetry))
    }
}

@Composable
fun codexStatusEntry(
    state: ai.antigem.data.agent.codex.CodexState,
    status: ai.antigem.data.agent.codex.CodexStatus?,
    account: ai.antigem.data.agent.codex.CodexAccount?,
    serverRestarting: Boolean,
    onSetup: () -> Unit,
    onSignIn: () -> Unit,
    onRestartServer: () -> Unit,
    onRetry: () -> Unit
): AgentStatusEntry {
    val color = when (state) {
        CodexState.READY -> SystemStatus.READY.dotColor
        CodexState.CHECKING -> SystemStatus.STARTING.dotColor
        CodexState.SIGNED_OUT -> ClaudeStatus.SIGNED_OUT.dotColor
        CodexState.OFFLINE, CodexState.DISABLED, CodexState.OUTDATED -> SystemStatus.OFFLINE.dotColor
        else -> ClaudeStatus.ERROR.dotColor
    }
    if (serverRestarting) {
        return AgentStatusEntry(AgentKind.CODEX, stringResource(R.string.main_ui_restarting), SystemStatus.STARTING.dotColor, stringResource(R.string.main_ui_the_local_server_is_restarting), pending = true)
    }
    val label = stringResource(when (state) {
            CodexState.CHECKING -> R.string.bridge_ui_checking
            CodexState.READY -> R.string.main_ui_ready
            CodexState.SIGNED_OUT -> R.string.main_ui_not_signed_in
            CodexState.NOT_INSTALLED -> R.string.android_dev_screen_not_installed
            CodexState.DISABLED -> R.string.main_ui_turned_off_on_the_server
            CodexState.OUTDATED -> R.string.main_ui_server_update_needed
            CodexState.OFFLINE -> R.string.main_ui_server_offline
            CodexState.ERROR -> R.string.markdown_content_error
        })
    fun entry(detail: String? = null, action: AgentStatusAction? = null) = AgentStatusEntry(
        AgentKind.CODEX, label, color, detail, action,
        pending = state == CodexState.CHECKING,
        isReady = state == CodexState.READY,
        isSignedOut = state == CodexState.SIGNED_OUT
    )
    return when (state) {
        CodexState.CHECKING -> entry(stringResource(R.string.main_ui_starting_waiting_for_the_server))
        CodexState.READY -> entry(
            listOfNotNull(account?.email, account?.plan?.replaceFirstChar { it.uppercase() }, status?.version?.let { "Codex $it" })
                .joinToString(" · ").ifBlank { null }
        )
        CodexState.SIGNED_OUT -> entry(stringResource(R.string.main_ui_sign_in_with_your_chatgpt_account_to), AgentStatusAction(stringResource(R.string.claude_settings_screen_sign_in), onSignIn))
        CodexState.NOT_INSTALLED -> entry(
            if (status?.termux_package == true) stringResource(R.string.main_ui_codex_is_not_installed_yet_pkg_install)
            else stringResource(R.string.main_ui_codex_is_not_installed_yet_the_app),
            AgentStatusAction(stringResource(R.string.claude_settings_screen_install), onSetup)
        )
        CodexState.DISABLED -> entry(stringResource(R.string.main_ui_the_server_was_started_with_codex_off), AgentStatusAction(stringResource(R.string.codex_settings_screen_restart), onRestartServer))
        CodexState.OUTDATED -> entry(stringResource(R.string.main_ui_this_server_has_no_codex_support_yet), AgentStatusAction(stringResource(R.string.codex_settings_screen_restart), onRestartServer))
        CodexState.OFFLINE -> entry(stringResource(R.string.main_ui_the_server_is_not_running), AgentStatusAction(stringResource(R.string.servers_settings_screen_start), onRestartServer))
        CodexState.ERROR -> entry(status?.error, AgentStatusAction(stringResource(R.string.chat_screen_retry), onRetry))
    }
}

fun claudePlanLabel(raw: String): String = when (raw.lowercase()) {
    "pro" -> "Pro"
    "max" -> "Max"
    "team" -> "Team"
    "enterprise" -> "Enterprise"
    else -> raw.replaceFirstChar { it.uppercase() }
}

/**
 * Connection dot for one or two agents. With two different states the upper half is [top] (Antigravity) and the
 * lower half [bottom] (Claude Code); the same state (or one agent) draws one solid dot.
 */
@Composable
fun AgentStatusDot(top: Color, bottom: Color?, modifier: Modifier = Modifier, size: Dp = 11.dp, pulsing: Boolean = false) =
    AgentStatusDot(listOfNotNull(top, bottom), modifier, size, pulsing)

/**
 * Connection dot for up to three agents, one slice each in the order given (Antigravity, Claude Code, OpenCode):
 * one agent is a solid dot, two are an upper and a lower half, three are thirds (top, lower right, lower left).
 * Agents in the same state draw one solid dot.
 */
@Composable
fun AgentStatusDot(colors: List<Color>, modifier: Modifier = Modifier, size: Dp = 11.dp, pulsing: Boolean = false) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "agent_dot")
        transition.animateFloat(1f, 0.4f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "agent_dot_alpha").value
    } else 1f
    val slices = colors.ifEmpty { listOf(SystemStatus.OFFLINE.dotColor) }
    Canvas(modifier.size(size).graphicsLayer { this.alpha = alpha }) {
        when {
            slices.distinct().size == 1 -> drawCircle(slices.first())
            slices.size == 2 -> {
                drawArc(slices[0], startAngle = 180f, sweepAngle = 180f, useCenter = true)
                drawArc(slices[1], startAngle = 0f, sweepAngle = 180f, useCenter = true)
            }
            else -> {
                // the first slice is centred on top (-90°), then clockwise
                val sweep = 360f / slices.size
                slices.forEachIndexed { i, c -> drawArc(c, startAngle = -90f - sweep / 2 + i * sweep, sweepAngle = sweep, useCenter = true) }
            }
        }
    }
}

/** Round agent mark (tinted with the agent's color) with an optional status badge. */
@Composable
fun AgentMark(agent: AgentKind, modifier: Modifier = Modifier, size: Dp = 32.dp, statusColor: Color? = null) {
    val accent = agent.accent()
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            when (agent) {
                AgentKind.AGY -> Icon(Icons.Outlined.RocketLaunch, null, tint = accent, modifier = Modifier.size(size * 0.52f))
                AgentKind.CLAUDE -> Text("✻", color = accent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.5f).sp)
                AgentKind.OPENCODE -> Text("</>", color = accent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.3f).sp, maxLines = 1)
                AgentKind.CODEX -> Text(">_", color = accent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp, maxLines = 1)
            }
        }
        if (statusColor != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.34f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
        }
    }
}

/** One agent row: mark, name, state, detail and its action. */
@Composable
fun AgentStatusRow(entry: AgentStatusEntry, modifier: Modifier = Modifier, onActionDone: () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AgentMark(entry.agent, size = 34.dp, statusColor = entry.color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.agent.label(), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
                Text(entry.label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = entry.color, maxLines = 1)
            }
            entry.detail?.let {
                Text(
                    it,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        entry.action?.let { action ->
            Spacer(Modifier.width(8.dp))
            AgentActionPill(action.label, entry.agent.accent()) {
                action.onClick()
                onActionDone()
            }
        }
    }
}

@Composable
fun AgentActionPill(text: String, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

/**
 * Top-bar status: the (split) dot; tapping opens each agent's state with its fix (sign in, set up, restart)
 * and the server logs.
 */
@Composable
fun AgentStatusIndicator(
    entries: List<AgentStatusEntry>,
    onOpenServerLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val agy = entries.find { it.agent == AgentKind.AGY }
    val claude = entries.find { it.agent == AgentKind.CLAUDE }
    val openCode = entries.find { it.agent == AgentKind.OPENCODE }
    val codex = entries.find { it.agent == AgentKind.CODEX }
    // one slice per enabled agent
    val colors = listOfNotNull(agy, claude, openCode, codex).map { it.color }
    Box(
        modifier = modifier.size(36.dp).clip(CircleShape).clickable { open = true },
        contentAlignment = Alignment.Center
    ) {
        AgentStatusDot(
            colors = colors,
            size = if (colors.distinct().size > 1) 12.dp else 11.dp,
            pulsing = entries.any { it.pending }
        )
        AgentStatusMenu(entries, expanded = open, onDismiss = { open = false }, onOpenServerLogs = onOpenServerLogs)
    }
}

/**
 * The agents' status popup (each agent's state with its fix, the server logs and, with [onRestartServer], a restart
 * of the server, which many did not find inside the logs); anchored to its parent.
 */
@Composable
fun AgentStatusMenu(
    entries: List<AgentStatusEntry>,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenServerLogs: () -> Unit,
    onRestartServer: (() -> Unit)? = null
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        shadowElevation = 8.dp,
        modifier = Modifier.width(300.dp)
    ) {
        Text(
            "AGENTS",
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)
        )
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            entries.forEach { AgentStatusRow(it) { onDismiss() } }
        }
        HorizontalDivider(
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    onDismiss()
                    onOpenServerLogs()
                }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.agent_status_ui_server_logs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
        }
        if (onRestartServer != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        onRestartServer()
                    }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.RestartAlt, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.agent_status_ui_restart_server), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Centered prompt for an agent that is not ready (empty chat screen). */
@Composable
fun AgentSetupPrompt(
    agent: AgentKind,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    statusColor: Color? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {}
) {
    val accent = agent.accent()
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AgentMark(agent, size = 60.dp, statusColor = statusColor)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 20.sp
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(
                onClick = onAction,
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
            ) { Text(actionLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
        if (secondaryLabel != null) {
            androidx.compose.material3.TextButton(onClick = onSecondary) {
                Text(secondaryLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }
    }
}
