package ai.antigem.ui.pc

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.pc.PcConnect
import ai.antigem.data.pc.PcConnect.PairedPc
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The pairing port VS Code uses when the user types the address by hand (it is shown next to the code there). */
private const val DEFAULT_PAIR_PORT = 1250

private val PcAccent: Color get() = ai.antigem.theme.AppColors.accentTeal

/** Live "can the phone reach it" for each paired PC (TCP to its SSH port), refreshed while shown. */
@Composable
private fun rememberReachability(pcs: List<PairedPc>): Map<String, Boolean?> {
    var state by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    LaunchedEffect(pcs.map { it.id to it.orderedAddresses() }) {
        while (true) {
            val next = mutableMapOf<String, Boolean?>()
            for (pc in pcs) next[pc.id] = PcConnect.reachableAddress(pc, 1_200) != null
            state = next
            delay(10_000)
        }
    }
    return state
}

private fun lastUsedText(pc: PairedPc): String {
    if (pc.lastUsedAt <= 0) return "Never used"
    val mins = (System.currentTimeMillis() - pc.lastUsedAt) / 60_000
    return when {
        mins < 1 -> "Used just now"
        mins < 60 -> "Used ${mins}m ago"
        mins < 60 * 24 -> "Used ${mins / 60}h ago"
        else -> "Used ${mins / (60 * 24)}d ago"
    }
}

private fun osIcon(os: String) = when (os) {
    "darwin" -> Icons.Outlined.LaptopMac
    "win32" -> Icons.Outlined.DesktopWindows
    else -> Icons.Outlined.Computer
}

/** Asks before switching (the app reopens) and switches. */
@Composable
private fun SwitchConfirm(target: PairedPc?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val back = PcConnect.ownModeLabel()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(if (target != null) osIcon(target.os) else Icons.Outlined.PhoneAndroid, null, tint = PcAccent) },
        title = { Text(if (target != null) stringResource(R.string.connect_pc_screen_use, target.name.toString()) else "$back?", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Text(
                if (target != null) "AntiGem reopens and connects to ${target.name}: its agents and chats show here. " +
                    "Agents running on this phone stop; nothing on the PC is stopped."
                else stringResource(R.string.connect_pc_screen_antigem_reopens_with_your_own_settings_again),
                fontSize = 13.5.sp, lineHeight = 19.sp
            )
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch { PcConnect.switchTo(context, target?.id) }
            }) { Text(if (busy) stringResource(R.string.connect_pc_screen_reopening) else stringResource(R.string.connect_pc_screen_reopen_now), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_cancel)) } }
    )
}

// ------------------------------------------------------------------------------------------------ settings page

@Composable
fun ConnectPcScreen(cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PcConnect.init(context) }
    val enabled by PcConnect.enabled.collectAsState()
    val pcs by PcConnect.pcs.collectAsState()
    val activeId by PcConnect.activeId.collectAsState()
    val active = pcs.firstOrNull { it.id == activeId }?.takeIf { enabled }
    val reach = rememberReachability(if (enabled) pcs else emptyList())

    var showAdd by remember { mutableStateOf(false) }
    var switchTarget by remember { mutableStateOf<PairedPc?>(null) }
    var switchBack by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PairedPc?>(null) }
    var removing by remember { mutableStateOf<PairedPc?>(null) }

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // the feature switch: on only makes it available, it does not connect
        Card(cardBg, cardBorder) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(PcAccent.copy(alpha = 0.14f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Computer, null, tint = PcAccent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.connect_pc_screen_connect_to_pc), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        stringResource(R.string.connect_pc_screen_use_the_agents_running_on_your_pc),
                        fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = enabled, onCheckedChange = { on ->
                    if (!on && active != null) confirmOff = true else PcConnect.setEnabled(on)
                })
            }
            if (!enabled) Hint(stringResource(R.string.connect_pc_screen_turning_this_on_adds_a_pc_button))
        }

        if (enabled) {
            // what the app uses now
            Card(cardBg, cardBorder) {
                Text(stringResource(R.string.connect_pc_screen_now_using), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(if (active != null) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        active?.name ?: if (ai.antigem.data.preferences.AuthPreferences.storedRemoteMode) stringResource(R.string.connect_pc_screen_your_server_settings_servers) else stringResource(R.string.connect_pc_screen_this_phone),
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f)
                    )
                    if (active != null) OutlinedButton(onClick = { switchBack = true }) { Text(PcConnect.ownModeLabel()) }
                }
            }

            // paired PCs
            Card(cardBg, cardBorder) {
                Text(stringResource(R.string.connect_pc_screen_paired_pcs), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                if (pcs.isEmpty()) Hint(stringResource(R.string.connect_pc_screen_no_pc_yet_tap_add_a_pc))
                pcs.forEach { pc ->
                    PcRow(
                        pc = pc,
                        inUse = pc.id == active?.id,
                        reachable = reach[pc.id],
                        onConnect = { switchTarget = pc },
                        onRename = { renaming = pc },
                        onRemove = { removing = pc }
                    )
                }
                Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.connect_pc_screen_add_a_pc))
                }
            }

            // how to get the PC ready
            Card(cardBg, cardBorder) {
                Text(stringResource(R.string.connect_pc_screen_on_the_pc_once), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Step(1, "Install AntiGem in VS Code and open it once (it starts the agents).")
                Step(2, "Turn on the PC's SSH server. Linux: sudo systemctl enable --now sshd (Ubuntu: install openssh-server first). " +
                    "macOS: System Settings › General › Sharing › Remote Login. Windows: Settings › Optional features › OpenSSH Server.")
                Step(3, "In AntiGem on the PC open Connect phone. It shows a 6-digit code.")
                Step(4, "Here: Add a PC, pick it from the list and type the code. Both must be on the same Wi-Fi.")
                Hint(stringResource(R.string.connect_pc_screen_the_phone_gets_its_own_key_for))
            }
        }
    }

    if (showAdd) AddPcDialog(onDismiss = { showAdd = false }, onConnect = { pc -> showAdd = false; switchTarget = pc })
    switchTarget?.let { SwitchConfirm(it) { switchTarget = null } }
    if (switchBack) SwitchConfirm(null) { switchBack = false }
    if (confirmOff) {
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text(stringResource(R.string.connect_pc_screen_turn_off_connect_to_pc), fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Text(stringResource(R.string.connect_pc_screen_you_are_using_antigem_reopens_with_your, (active?.name).toString()), fontSize = 13.5.sp) },
            confirmButton = {
                Button(onClick = {
                    PcConnect.setEnabled(false)
                    scope.launch { PcConnect.switchTo(context, null) }
                }) { Text(stringResource(R.string.connect_pc_screen_turn_off_reopen)) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
    renaming?.let { pc ->
        var name by remember(pc.id) { mutableStateOf(pc.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.connect_pc_screen_rename_pc), fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.browser_screen_name)) }) },
            confirmButton = { Button(enabled = name.isNotBlank(), onClick = { PcConnect.rename(pc.id, name); renaming = null }) { Text(stringResource(R.string.claude_providers_page_save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
    removing?.let { pc ->
        val inUse = pc.id == active?.id
        AlertDialog(
            onDismissRequest = { removing = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.claude_providers_page_remove, pc.name.toString()), fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    (if (inUse) "You are using it now: switch back first. " else "") +
                        "This phone forgets the PC and its key for it. To block this phone completely, also remove it on the PC " +
                        "(VS Code › AntiGem › Connect phone).",
                    fontSize = 13.5.sp, lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    enabled = !inUse,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = { PcConnect.remove(pc.id); removing = null }
                ) { Text(stringResource(R.string.browser_screen_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
}

@Composable
private fun PcRow(pc: PairedPc, inUse: Boolean, reachable: Boolean?, onConnect: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (inUse) QuotaGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        border = if (inUse) BorderStroke(1.dp, QuotaGreen.copy(alpha = 0.4f)) else null
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(osIcon(pc.os), null, tint = PcAccent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(pc.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pc.user}@${pc.lastAddress}", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (label, color) = when {
                        inUse -> "In use" to QuotaGreen
                        reachable == true -> "Reachable" to QuotaGreen
                        reachable == false -> "Not reachable" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        else -> "Checking…" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    }
                    Box(Modifier.size(6.dp).background(color, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text("$label · ${lastUsedText(pc)}" + if (pc.shellAllowed) " · terminal on PC" else "",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            if (!inUse) TextButton(onClick = onConnect) { Text(stringResource(R.string.agy_cloud_ui_connect)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.text_selection_more), Modifier.size(20.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.agy_cloud_ui_rename)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.browser_screen_remove)) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ adding a PC

private sealed interface AddStep {
    data object Pick : AddStep
    data class Code(val name: String, val host: String, val port: Int) : AddStep
    data class Working(val name: String, val text: String) : AddStep
    data class Done(val pc: PairedPc) : AddStep
    data class Failed(val name: String, val host: String, val port: Int, val message: String, val pairedPc: PairedPc? = null) : AddStep
}

/** Find the PC on the Wi-Fi (or type its address), type the code VS Code shows, pair. */
@Composable
fun AddPcDialog(onDismiss: () -> Unit, onConnect: (PairedPc) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { PcConnect.init(context) }
    var step by remember { mutableStateOf<AddStep>(AddStep.Pick) }

    Dialog(onDismissRequest = { if (step !is AddStep.Working) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 620.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Computer, null, tint = PcAccent)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.connect_pc_screen_add_a_pc), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    if (step !is AddStep.Working) IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.accounts_ui_close)) }
                }
                when (val s = step) {
                    AddStep.Pick -> PickPc { name, host, port -> step = AddStep.Code(name, host, port) }
                    is AddStep.Code -> CodeEntry(s.name, s.host, s.port, onBack = { step = AddStep.Pick }) { code ->
                        step = AddStep.Working(s.name, "Connecting…")
                        scope.launch {
                            step = try {
                                AddStep.Done(PcConnect.pair(s.host, s.port, code) { text -> step = AddStep.Working(s.name, text) })
                            } catch (e: PcConnect.PairingException) {
                                // paired but the SSH test failed: the PC is saved, say what is wrong
                                val saved = PcConnect.list().lastOrNull()?.takeIf { e.message?.startsWith("Paired") == true }
                                AddStep.Failed(s.name, s.host, s.port, e.message ?: "Pairing failed", saved)
                            } catch (e: Exception) {
                                AddStep.Failed(s.name, s.host, s.port, e.message ?: e.javaClass.simpleName)
                            }
                        }
                    }
                    is AddStep.Working -> {
                        Text(stringResource(R.string.connect_pc_screen_pairing_with, s.name.toString()), fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Hint(s.text)
                    }
                    is AddStep.Done -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = QuotaGreen)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.connect_pc_screen_paired_with, s.pc.name.toString()), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                        Hint(stringResource(R.string.connect_pc_screen_connect_now_to_use_its_agents_here))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.bridge_ui_later)) }
                            Button(onClick = { onConnect(s.pc) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.connect_pc_screen_connect_now)) }
                        }
                    }
                    is AddStep.Failed -> {
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)) {
                            Text(s.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                        }
                        if (s.pairedPc != null) Hint(stringResource(R.string.connect_pc_screen_is_saved_fix_the_ssh_problem_on, s.pairedPc.name.toString()))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { step = AddStep.Pick }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.agent_approval_panel_back)) }
                            Button(onClick = { step = AddStep.Code(s.name, s.host, s.port) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.checkpoint_restore_sheet_try_again)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickPc(onPick: (name: String, host: String, port: Int) -> Unit) {
    val context = LocalContext.current
    var nearby by remember { mutableStateOf<List<PcConnect.NearbyPc>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            PcConnect.discover(context).collect { nearby = it }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            searchError = e.message
        }
    }

    Text(stringResource(R.string.connect_pc_screen_on_the_pc_open_antigem_in_vs), fontSize = 13.sp, lineHeight = 18.sp)
    if (nearby.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(searchError ?: stringResource(R.string.connect_pc_screen_looking_for_pcs_on_this_wi_fi), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
    }
    nearby.forEach { pc ->
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
            modifier = Modifier.fillMaxWidth().clickable { onPick(pc.name, pc.host, pc.port) }
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(osIcon(pc.os), null, tint = PcAccent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pc.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(pc.host, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                }
                Icon(Icons.Outlined.ChevronRight, null)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    if (!manual) {
        TextButton(onClick = { manual = true }) { Text(stringResource(R.string.connect_pc_screen_not_listed_enter_the_address_shown_on)) }
        Hint(stringResource(R.string.connect_pc_screen_some_routers_hide_devices_from_each_other))
    } else {
        val parsed = parseAddress(address)
        OutlinedTextField(
            value = address, onValueChange = { address = it.trim().take(60) }, singleLine = true,
            label = { Text(stringResource(R.string.connect_pc_screen_address_shown_on_the_pc)) }, placeholder = { Text("192.168.1.10:$DEFAULT_PAIR_PORT") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth()
        )
        Button(enabled = parsed != null, onClick = { parsed?.let { (h, p) -> onPick(h, h, p) } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_question_panel_next)) }
    }
}

/** "192.168.1.10" / "192.168.1.10:1250" / "[fe80::1]:1250" -> host and pairing port. */
private fun parseAddress(text: String): Pair<String, Int>? {
    val t = text.trim()
    if (t.isEmpty() || t.contains(' ') || t.contains('/')) return null
    if (t.startsWith("[")) {
        val host = t.substringAfter('[').substringBefore(']').ifBlank { return null }
        val port = t.substringAfter("]:", "").toIntOrNull() ?: DEFAULT_PAIR_PORT
        return host to port
    }
    val parts = t.split(':')
    return when (parts.size) {
        1 -> t to DEFAULT_PAIR_PORT
        2 -> parts[0].ifBlank { return null } to (parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null)
        else -> t to DEFAULT_PAIR_PORT // a bare IPv6 address
    }
}

@Composable
private fun CodeEntry(name: String, host: String, port: Int, onBack: () -> Unit, onPair: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Text(stringResource(R.string.connect_pc_screen_pair_with, name.toString()), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    Text("$host:$port", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    OutlinedTextField(
        value = code,
        onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
        singleLine = true,
        label = { Text(stringResource(R.string.connect_pc_screen_6_digit_code_shown_on_the_pc)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 22.sp, letterSpacing = 6.sp),
        modifier = Modifier.fillMaxWidth()
    )
    Hint(stringResource(R.string.connect_pc_screen_the_code_is_only_valid_while_connect))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.agent_approval_panel_back)) }
        Button(enabled = code.length == 6, onClick = { onPair(code) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.connect_pc_screen_pair)) }
    }
}

// ------------------------------------------------------------------------------------------------ side bar

/** The side bar's PC button (next to Themes): green dot while a PC is in use. */
@Composable
fun PcDrawerButton(onClick: () -> Unit) {
    val pcs by PcConnect.pcs.collectAsState()
    val activeId by PcConnect.activeId.collectAsState()
    val inUse = pcs.any { it.id == activeId }
    Surface(onClick = onClick, shape = RoundedCornerShape(8.dp), color = Color.Transparent, modifier = Modifier.padding(end = 4.dp)) {
        Box {
            Icon(
                Icons.Outlined.Computer, contentDescription = stringResource(R.string.connect_pc_screen_connect_to_pc),
                tint = if (inUse) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(8.dp).size(20.dp)
            )
            if (inUse) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(7.dp).background(QuotaGreen, CircleShape))
        }
    }
}

/** The side bar's PC sheet: what is in use, switch to a paired PC or back, add a PC, open the settings page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcQuickSheet(onDismiss: () -> Unit, onManage: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PcConnect.init(context) }
    val pcs by PcConnect.pcs.collectAsState()
    val activeId by PcConnect.activeId.collectAsState()
    val active = pcs.firstOrNull { it.id == activeId }
    val reach = rememberReachability(pcs)
    var showAdd by remember { mutableStateOf(false) }
    var switchTarget by remember { mutableStateOf<PairedPc?>(null) }
    var switchBack by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.connect_pc_screen_connect_to_pc), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.connect_pc_screen_now_using_2), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Box(Modifier.size(8.dp).background(if (active != null) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(active?.name ?: if (ai.antigem.data.preferences.AuthPreferences.storedRemoteMode) stringResource(R.string.connect_pc_screen_your_server) else stringResource(R.string.connect_pc_screen_this_phone),
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            if (pcs.isEmpty()) Hint(stringResource(R.string.connect_pc_screen_no_pc_paired_yet))
            pcs.forEach { pc ->
                val inUse = pc.id == active?.id
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (inUse) QuotaGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !inUse) { switchTarget = pc }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(osIcon(pc.os), null, tint = PcAccent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pc.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(pc.lastAddress, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        val (label, color) = when {
                            inUse -> "In use" to QuotaGreen
                            reach[pc.id] == true -> "Reachable" to QuotaGreen
                            reach[pc.id] == false -> "Not reachable" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            else -> "…" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        }
                        Box(Modifier.size(7.dp).background(color, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(label, fontSize = 11.5.sp, color = color)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.connect_pc_screen_add_pc))
                }
                if (active != null) OutlinedButton(onClick = { switchBack = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(PcConnect.ownModeLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onManage, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.connect_pc_screen_manage_pcs)) }
        }
    }

    if (showAdd) AddPcDialog(onDismiss = { showAdd = false }, onConnect = { pc -> showAdd = false; switchTarget = pc })
    switchTarget?.let { SwitchConfirm(it) { switchTarget = null } }
    if (switchBack) SwitchConfirm(null) { switchBack = false }
}

// ------------------------------------------------------------------------------------------------ Settings › Servers

/** Settings › Servers while a PC is in use: its form would show the PC's values, so it waits until the switch back. */
@Composable
fun PcServersNotice(cardBg: Color, cardBorder: BorderStroke, onOpenConnectPc: () -> Unit) {
    val active = PcConnect.active()
    var switchBack by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(cardBg, cardBorder) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Computer, null, tint = QuotaGreen)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.connect_pc_screen_connected_to, (active?.name ?: "a PC").toString()), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Hint("Connect to PC is in use, so the agents and chats come from the PC. Your server settings here are kept " +
                "as they were and are used again after you switch back.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenConnectPc, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.connect_pc_screen_connect_to_pc)) }
                Button(onClick = { switchBack = true }, modifier = Modifier.weight(1f)) { Text(PcConnect.ownModeLabel()) }
            }
        }
    }
    if (switchBack) SwitchConfirm(null) { switchBack = false }
}

// ------------------------------------------------------------------------------------------------ bits

@Composable
private fun Card(cardBg: Color, cardBorder: BorderStroke, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = cardBg, border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, lineHeight = 16.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp).background(PcAccent.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PcAccent)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
    }
}
