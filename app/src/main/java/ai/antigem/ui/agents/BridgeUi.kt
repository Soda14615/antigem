package ai.antigem.ui.agents

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.local.LocalServerManager
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.remote.BridgeInfo
import ai.antigem.data.remote.RemoteServerManager

private val Ok: androidx.compose.ui.graphics.Color get() = ai.antigem.theme.AppColors.success
private val Warn: androidx.compose.ui.graphics.Color get() = ai.antigem.theme.AppColors.warning

/** Asks to update the IDE bridge when its version is not the app's. Shown once per version pair unless "Later". */
@Composable
fun BridgeVersionDialog() {
    val context = LocalContext.current
    val bridge by BridgeInfo.version.collectAsState()
    val snoozed by BridgeInfo.snoozedFor.collectAsState()
    val b = bridge ?: return
    // Connect to PC: the bridge is AntiGem's in VS Code, versioned with the extension, not this app
    if (AuthPreferences.pcOverride != null) return
    val cmp = BridgeInfo.compareBridgeToApp(b)
    // "Later" hides it until the app is opened again: an outdated bridge must not be forgotten
    if (cmp == 0 || snoozed == b) return

    val remote = AuthPreferences.currentRemoteMode
    val where = if (remote) stringResource(R.string.main_ui_on_your_server) else stringResource(R.string.main_ui_on_this_phone)
    val text = when {
        cmp > 0 -> stringResource(R.string.main_ui_the_ide_bridge_is_version_newer_than, where, b, BridgeInfo.appVersion)
        remote -> stringResource(R.string.main_ui_the_ide_bridge_is_version_but_this, where, b, BridgeInfo.appVersion, RemoteServerManager.bridgeBinaryPath(context))
        else -> stringResource(R.string.main_ui_the_ide_bridge_is_version_but_this_2, where, b, BridgeInfo.appVersion)
    }
    AlertDialog(
        onDismissRequest = { BridgeInfo.snooze(b) },
        title = { Text(stringResource(R.string.bridge_ui_update_the_ide_bridge), fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = { Text(text, fontSize = 13.sp, lineHeight = 18.sp) },
        confirmButton = {
            if (cmp < 0) {
                Button(onClick = {
                    BridgeInfo.snooze(b)
                    BridgeInfo.reset()
                    LocalServerManager.restartServer(context)
                }) { Text(stringResource(R.string.agent_status_ui_restart_server)) }
            }
        },
        dismissButton = { TextButton(onClick = { BridgeInfo.snooze(b) }) { Text(stringResource(R.string.bridge_ui_later)) } }
    )
}

/** One line for the Servers screen: the connected bridge's version and whether it matches the app. */
@Composable
fun BridgeVersionLine(modifier: Modifier = Modifier) {
    val bridge by BridgeInfo.version.collectAsState()
    val b = bridge
    val (text, color) = when {
        b == null -> stringResource(R.string.main_ui_bridge_version_not_connected) to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        BridgeInfo.compareBridgeToApp(b) == 0 -> stringResource(R.string.main_ui_bridge_version_matches_the_app, b) to Ok
        BridgeInfo.compareBridgeToApp(b) < 0 -> stringResource(R.string.main_ui_bridge_version_the_app_is_update_the, b, BridgeInfo.appVersion) to Warn
        else -> stringResource(R.string.main_ui_bridge_version_newer_than_the_app_update, b, BridgeInfo.appVersion) to Warn
    }
    Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = color, modifier = modifier)
}

/** Settings > Antigravity > Antigravity CLI: the agy CLI on the bridge's machine (this phone or the server): version and install. */
@Composable
fun AgyCliCard(modifier: Modifier = Modifier) {
    val cli by BridgeInfo.agyCli.collectAsState()
    val error by BridgeInfo.agyError.collectAsState()
    val job by BridgeInfo.job.collectAsState()
    val checking by BridgeInfo.checking.collectAsState()
    val remote = AuthPreferences.currentRemoteMode
    val subtle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    LaunchedEffect(Unit) { BridgeInfo.refreshAgyCli() }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Antigravity CLI（agy）", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { BridgeInfo.refreshAgyCli() }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.agent_sheet_ui_refresh)) }
            }
            val running = job?.running == true
            when {
                error != null -> Text(error!!, fontSize = 12.sp, color = Color.Red)
                cli == null -> Text(stringResource(R.string.bridge_ui_checking), fontSize = 12.5.sp, color = subtle)
                cli!!.installed -> {
                    Text(cli!!.version.ifBlank { stringResource(R.string.main_ui_installed_version_unknown) }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(cli!!.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = subtle)
                }
                else -> {
                    Text(
                        stringResource(R.string.bridge_ui_agy_is_not_installed_on, (if (remote) stringResource(R.string.main_ui_the_server) else stringResource(R.string.main_ui_this_device)).toString()),
                        fontSize = 12.5.sp, color = Warn
                    )
                    if (cli!!.path.isNotBlank()) Text(cli!!.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = subtle)
                }
            }
            if (cli?.installed == false && error == null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { BridgeInfo.installAgy() }, enabled = !running) { Text(stringResource(R.string.bridge_ui_install_agy)) }
                    if (running) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (cli?.installed == true) {
                val context = LocalContext.current
                val updatedTo by BridgeInfo.agyUpdatedTo.collectAsState()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { BridgeInfo.updateAgy(context) }, enabled = !running) {
                        Text(if (running && job?.kind == "update") stringResource(R.string.bridge_ui_checking) else stringResource(R.string.bridge_ui_check_for_updates))
                    }
                    if (running) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                // a newer agy was installed: the server was restarted so the hub runs it
                updatedTo?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.bridge_ui_updated_to_agy_the_server_restarted_to, it.toString()), fontSize = 12.sp, color = Ok)
                }
            }
            job?.let { j ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "${j.kind}: " + if (j.running) stringResource(R.string.main_ui_running) else stringResource(R.string.main_ui_finished_exit, j.exitCode.toString()),
                    fontSize = 12.sp,
                    color = if (!j.running && j.exitCode != 0) Color.Red else subtle
                )
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        j.log.lines().takeLast(30).joinToString("\n"),
                        fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
                        modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(8.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (!remote && ai.antigem.BuildConfig.TARGET_ABI == "x86_64") stringResource(R.string.upgrade_201_agy_x64_install_note)
                else stringResource(R.string.main_ui_installed_with_google_s_official_installer_antigravity, if (remote) stringResource(R.string.main_ui_the_server) else stringResource(R.string.main_ui_this_device)),
                fontSize = 11.sp, color = subtle
            )
        }
    }
}
