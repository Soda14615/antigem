package ai.antigem.ui.agents

import ai.antigem.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import ai.antigem.data.remote.dto.PermissionScopes
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.ToolCall
import ai.antigem.domain.model.ToolType
import ai.antigem.ui.chat.PendingToolApproval

/**
 * Antigravity's pending tool approvals as approval-panel items: allow once, always allow (conversation /
 * workspace / everywhere), or deny with a reason.
 */
fun agyApprovalItems(
    context: android.content.Context,
    pending: List<PendingToolApproval>,
    onApprove: (toolCall: ToolCall, messageId: String, scope: String) -> Unit,
    onReject: (toolCall: ToolCall, messageId: String, reason: String?) -> Unit
): List<ApprovalItem> = pending.map { approval ->
    val call = approval.toolCall
    val (icon, title) = when (call.toolType) {
        ToolType.BASH -> Icons.Outlined.Terminal to context.getString(R.string.main_ui_run_a_command)
        ToolType.EDIT_FILE -> Icons.Outlined.EditNote to context.getString(R.string.main_ui_edit_a_file)
        ToolType.VIEW_FILE, ToolType.LIST_DIR, ToolType.FIND, ToolType.GREP_SEARCH -> Icons.Outlined.FolderOpen to context.getString(R.string.main_ui_read_files)
        ToolType.SEARCH_WEB -> Icons.Outlined.TravelExplore to context.getString(R.string.main_ui_search_the_web)
        ToolType.READ_URL -> Icons.Outlined.Language to context.getString(R.string.main_ui_open_a_web_page)
        ToolType.GENERATE_IMAGE -> Icons.Outlined.Image to context.getString(R.string.main_ui_generate_an_image)
        ToolType.MCP -> Icons.Outlined.Extension to context.getString(R.string.main_ui_use, call.name.substringAfterLast("__"))
        else -> Icons.Outlined.Shield to context.getString(R.string.main_ui_allow, call.name)
    }
    val body = call.command.ifBlank { call.output }
    fun scope(label: String, description: String, value: String) =
        ApprovalChoice(label, description) { onApprove(call, approval.messageId, value) }
    ApprovalItem(
        key = call.id,
        agent = AgentKind.AGY,
        icon = icon,
        title = title,
        subtitle = call.name,
        preview = if (call.toolType == ToolType.BASH) "$ $body" else body,
        primary = ApprovalChoice(context.getString(R.string.app_permissions_dialog_allow)) { onApprove(call, approval.messageId, PermissionScopes.ONCE) },
        always = listOf(
            scope(context.getString(R.string.main_ui_for_this_conversation), context.getString(R.string.main_ui_don_t_ask_again_in_this_chat), PermissionScopes.CONVERSATION),
            scope(context.getString(R.string.main_ui_in_this_workspace), context.getString(R.string.main_ui_don_t_ask_again_in_this_project), PermissionScopes.PROJECT),
            scope(context.getString(R.string.main_ui_everywhere), context.getString(R.string.main_ui_don_t_ask_again_anywhere), PermissionScopes.GLOBAL)
        ),
        denyPlaceholder = context.getString(R.string.main_ui_tell_antigravity_what_to_do_instead_optional),
        onDeny = { onReject(call, approval.messageId, it) }
    )
}
