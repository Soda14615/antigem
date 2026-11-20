package ai.antigem.ui.claude

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.R
import ai.antigem.theme.ClaudeDarkSurface
import ai.antigem.theme.QuotaAmber
import ai.antigem.theme.QuotaGreen
import ai.antigem.theme.QuotaRed
import ai.antigem.theme.isAppInDarkTheme
import ai.antigem.ui.components.ClaudeAccent

@Composable
fun claudeCardColor(): Color = ai.antigem.theme.AppColors.card

/** Navigation card matching the existing settings cards. */
@Composable
fun ClaudeNavCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String? = null,
    badgeColor: Color = ClaudeAccent,
    tint: Color = ClaudeAccent,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = claudeCardColor()),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (badge != null) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = badgeColor.copy(alpha = 0.12f)) {
                    Text(
                        badge, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = badgeColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), modifier = Modifier.size(20.dp))
        }
    }
}

/** Plain content card. */
@Composable
fun ClaudeCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = claudeCardColor()),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) { Column(Modifier.padding(14.dp)) { content() } }
}

fun usageColor(fraction: Double): Color = when {
    fraction >= 0.9 -> QuotaRed
    fraction >= 0.7 -> QuotaAmber
    else -> QuotaGreen
}

/** One plan-limit bar: label, percent used, reset time. */
@Composable
fun UsageBar(label: String, usedFraction: Double, resetText: String?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val color = usageColor(usedFraction)
    val percent = (usedFraction * 100).toInt()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = if (compact) 10.5.sp else 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
            Text(
                if (resetText != null) stringResource(R.string.claude_ui_kit_percent_used_resets, percent, resetText)
                else stringResource(R.string.claude_ui_kit_percent_used, percent),
                fontSize = if (compact) 10.sp else 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier.fillMaxWidth().height(if (compact) 4.dp else 6.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier.fillMaxWidth(usedFraction.toFloat().coerceIn(0f, 1f)).height(if (compact) 4.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(color)
            )
        }
    }
}

/** Small pill used in the chat controls strip. */
@Composable
fun ControlChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = if (highlighted) color.copy(alpha = 0.14f) else MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, if (highlighted) color.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        }
    }
}

/** "in 2h 14m" / "Tue 09:00" for an ISO-8601 or epoch-seconds reset time. */
fun formatReset(context: Context, iso: String?, epochSeconds: Long? = null): String? {
    val millis = epochSeconds?.let { it * 1000 } ?: iso?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    ?: return null
    val diff = millis - System.currentTimeMillis()
    if (diff <= 0) return context.getString(R.string.claude_ui_kit_reset_now)
    val mins = diff / 60_000
    return when {
        mins < 60 -> context.getString(R.string.claude_ui_kit_reset_in_minutes, mins)
        mins < 24 * 60 -> context.getString(R.string.claude_ui_kit_reset_in_hours_minutes, mins / 60, mins % 60)
        else -> java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }
}

@Composable
fun formatReset(iso: String?, epochSeconds: Long? = null): String? =
    formatReset(LocalContext.current, iso, epochSeconds)

/** Human label for a Claude permission mode. */
fun permissionModeLabel(context: Context, mode: String): String = when (mode) {
    "default" -> context.getString(R.string.claude_ui_kit_mode_manual)
    "acceptEdits" -> context.getString(R.string.claude_ui_kit_mode_accept_edits)
    "plan" -> context.getString(R.string.claude_ui_kit_mode_plan)
    "auto" -> context.getString(R.string.claude_ui_kit_mode_auto)
    "dontAsk" -> context.getString(R.string.claude_ui_kit_mode_dont_ask)
    "bypassPermissions" -> context.getString(R.string.claude_ui_kit_mode_bypass)
    else -> mode
}

@Composable
fun permissionModeLabel(mode: String): String = permissionModeLabel(LocalContext.current, mode)

fun permissionModeDescription(context: Context, mode: String): String = when (mode) {
    "default" -> context.getString(R.string.claude_ui_kit_mode_desc_manual)
    "acceptEdits" -> context.getString(R.string.claude_ui_kit_mode_desc_accept_edits)
    "plan" -> context.getString(R.string.claude_ui_kit_mode_desc_plan)
    "auto" -> context.getString(R.string.claude_ui_kit_mode_desc_auto)
    "dontAsk" -> context.getString(R.string.claude_ui_kit_mode_desc_dont_ask)
    "bypassPermissions" -> context.getString(R.string.claude_ui_kit_mode_desc_bypass)
    else -> ""
}

@Composable
fun permissionModeDescription(mode: String): String = permissionModeDescription(LocalContext.current, mode)

fun effortLabel(context: Context, level: String?): String = when (level) {
    null -> context.getString(R.string.claude_ui_kit_effort_default)
    "low" -> context.getString(R.string.claude_ui_kit_effort_low)
    "medium" -> context.getString(R.string.claude_ui_kit_effort_medium)
    "high" -> context.getString(R.string.claude_ui_kit_effort_high)
    "xhigh" -> context.getString(R.string.claude_ui_kit_effort_xhigh)
    "max" -> context.getString(R.string.claude_ui_kit_effort_max)
    else -> level.replaceFirstChar { it.uppercase() }
}

@Composable
fun effortLabel(level: String?): String = effortLabel(LocalContext.current, level)
