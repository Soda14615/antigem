package ai.antigem.ui.settings

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import ai.antigem.ui.components.bouncyVerticalScroll
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.BuildConfig
import ai.antigem.data.local.LocalServerManager
import ai.antigem.data.local.LocalServerStatus
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.remote.RemoteServerManager
import ai.antigem.data.ssh.SshSessionFactory
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.theme.QuotaGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

// ==========================================
// SUB-SCREEN 2: SERVERS & CONNECTIVITY
// Local (the phone runs the bridge) or Remote (a VPS runs it and the app manages it over SSH)
// ==========================================

private fun describeNetworkError(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "unknown host name"
    is java.net.SocketTimeoutException -> "timed out (firewall or wrong port?)"
    is java.net.ConnectException -> "connection refused (is the service running and the port open?)"
    is javax.net.ssl.SSLException -> "TLS problem: ${e.message?.take(80)}"
    is java.net.SocketException, is java.io.EOFException ->
        "the connection was closed (a proxy that requires the access header closes it when the header is missing or wrong: check \"Proxy access header\")"
    else -> {
        val m = e.message.orEmpty()
        if (m.contains("unexpected end of stream", true) || m.contains("connection reset", true))
            "the connection was closed with no answer: a proxy that requires the access header does this when the header is missing or the value is wrong (check \"Proxy access header\" against the proxy)"
        else m.take(100).ifBlank { e.javaClass.simpleName }
    }
}

/** HTTP reachability of the hub address (any answer below 500 means something answers; 502-504 is a proxy that cannot reach the hub). */
private suspend fun probeHub(url: String, edge: Pair<String, String>? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        edge?.let { conn.setRequestProperty(it.first, it.second) }
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val code = try { conn.responseCode } finally { conn.disconnect() }
        when {
            code in 502..504 -> false to "the proxy answered $code: it cannot reach the hub (is agy --hub running on that port?)"
            code in 200..499 -> true to "answered HTTP $code"
            else -> false to "answered HTTP $code"
        }
    } catch (e: Exception) {
        false to describeNetworkError(e)
    }
}

/** Health + token check of the bridge, using the token typed in the field (even before it is saved). */
private suspend fun probeBridge(base: String, token: String, edge: Pair<String, String>? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .build()
    try {
        val health = client.newCall(Request.Builder().url("${base.trimEnd('/')}/api/health").apply { edge?.let { header(it.first, it.second) } }.build()).execute()
        health.use {
            if (!it.isSuccessful) return@withContext false to "health check answered HTTP ${it.code}"
        }
        val header = AuthPreferences.buildFramedHeader(token)
        val info = client.newCall(
            Request.Builder().url("${base.trimEnd('/')}/api/server/info").header(header, "true").apply { edge?.let { header(it.first, it.second) } }.build()
        ).execute()
        info.use {
            when (it.code) {
                200 -> {
                    val json = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull()
                    val os = json?.optString("os").orEmpty()
                    val arch = json?.optString("arch").orEmpty()
                    val home = json?.optString("home").orEmpty()
                    true to "$os/$arch" + if (home.isNotBlank()) ", home $home" else ""
                }
                401 -> false to "reachable, but the token was rejected: either it differs from the one the server was started with, or the proxy drops headers with an underscore (nginx needs underscores_in_headers on;)"
                404 -> true to "reachable (older bridge without server info)"
                429 -> false to "blocked for a while after too many wrong tokens, try again later"
                else -> false to "info answered HTTP ${it.code}"
            }
        }
    } catch (e: Exception) {
        false to describeNetworkError(e)
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun ServersSubScreen(
    currentHubUrl: String,
    currentBridgeUrl: String,
    isServerOnline: Boolean,
    isBridgeOnline: Boolean = false,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSaveServerUrls: (String, String) -> Unit,
    /** Opens the Termux environment's installer. */
    onInstallTermux: () -> Unit = {},
    /** Opens the page of the Ubuntu chroot at this path. */
    onManageChroot: (String) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val authPrefs = remember(context) { AuthPreferences(context) }

    val initSecurityToken = remember { authPrefs.getSecurityTokenSync() }
    var securityToken by remember { mutableStateOf(initSecurityToken) }

    var remote by remember { mutableStateOf(AuthPreferences.currentRemoteMode) }
    // only with remote: reach the server's bridge/hub through the SSH connection instead of a domain or open ports
    var tunnel by remember { mutableStateOf(AuthPreferences.currentRemoteMode && AuthPreferences.currentRemoteTunnel) }
    // on this phone: the Termux environment or the rooted Ubuntu chroot
    var phoneChroot by remember { mutableStateOf(authPrefs.isChrootModeSync()) }
    var showFactoryReset by remember { mutableStateOf(false) }
    var hubText by remember { mutableStateOf(currentHubUrl.ifBlank { AuthPreferences.DEFAULT_HUB_URL }) }
    var bridgeText by remember { mutableStateOf(currentBridgeUrl.ifBlank { AuthPreferences.DEFAULT_BRIDGE_HTTP_URL }) }

    // local binaries
    // every path field is empty unless the user set a path: empty = the default (shown as the field's hint)
    fun custom(value: String, default: String) = value.trim().takeIf { it != default.trim() }.orEmpty()
    var bridgeBinPath by remember { mutableStateOf(custom(authPrefs.getAgyBridgeBinaryPathSync(), authPrefs.getDefaultBridgeBinaryPath())) }
    var agyBinPath by remember { mutableStateOf(custom(authPrefs.getAgyBinaryPathSync(), authPrefs.getDefaultAgyBinaryPath())) }
    var claudeBinPath by remember { mutableStateOf(custom(authPrefs.getClaudeBinaryPathSync(), authPrefs.getDefaultClaudeBinaryPath())) }
    var openCodeBinPath by remember { mutableStateOf(custom(authPrefs.getOpenCodeBinaryPathSync(), authPrefs.getDefaultOpenCodeBinaryPath())) }
    var codexBinPath by remember { mutableStateOf(custom(authPrefs.getCodexBinaryPathSync(), authPrefs.getDefaultCodexBinaryPath())) }
    // the Ubuntu chroot's own agent paths (blank = the default, where the agent's installer puts it)
    var chrootBridgeBin by remember { mutableStateOf(authPrefs.getChrootBridgeBinSync()) }
    var chrootAgyBin by remember { mutableStateOf(authPrefs.getChrootAgyBinSync()) }
    var chrootClaudeBin by remember { mutableStateOf(authPrefs.getChrootClaudeBinSync()) }
    var chrootOpenCodeBin by remember { mutableStateOf(authPrefs.getChrootOpenCodeBinSync()) }
    var chrootCodexBin by remember { mutableStateOf(authPrefs.getChrootCodexBinSync()) }

    // SSH (loaded from the saved settings)
    var sshHost by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var sshUser by remember { mutableStateOf("") }
    var sshPass by remember { mutableStateOf("") }
    var sshKey by remember { mutableStateOf(AuthPreferences.currentSshPrivateKey) }
    var sshPassphrase by remember { mutableStateOf(AuthPreferences.currentSshKeyPassphrase) }

    // server side paths and ports (blank = automatic)
    // a blank saved value means "the default", so the default itself is shown (and saved) instead of an empty box
    fun storedBridgeBin() = custom(authPrefs.getRemoteBridgeBinSync(), RemoteServerManager.defaultBridgeBin(authPrefs.getRemoteInstallDirSync()))
    fun storedAgyBin() = custom(authPrefs.getRemoteAgyBinSync(), RemoteServerManager.DEFAULT_AGY_BIN)
    fun storedClaudeBin() = custom(authPrefs.getRemoteClaudeBinSync(), RemoteServerManager.DEFAULT_CLAUDE_BIN)
    fun storedOpenCodeBin() = custom(authPrefs.getRemoteOpenCodeBinSync(), RemoteServerManager.DEFAULT_OPENCODE_BIN)
    fun storedCodexBin() = custom(authPrefs.getRemoteCodexBinSync(), RemoteServerManager.DEFAULT_CODEX_BIN)
    var remoteBridgeBin by remember { mutableStateOf(storedBridgeBin()) }
    var remoteAgyBin by remember { mutableStateOf(storedAgyBin()) }
    var remoteClaudeBin by remember { mutableStateOf(storedClaudeBin()) }
    var remoteOpenCodeBin by remember { mutableStateOf(storedOpenCodeBin()) }
    var remoteCodexBin by remember { mutableStateOf(storedCodexBin()) }
    var remoteInstallDir by remember { mutableStateOf(authPrefs.getRemoteInstallDirSync()) }
    val (initListenBridge, initListenHub, initListenBind) = remember { authPrefs.getRemoteListenSync() }
    var listenBridge by remember { mutableStateOf(initListenBridge) }
    var listenHub by remember { mutableStateOf(initListenHub) }
    var listenBind by remember { mutableStateOf(initListenBind) }
    var showAdvanced by remember { mutableStateOf(false) }

    // Server mode: an extra header the reverse proxy checks before it lets a request through to the bridge or the hub
    val (initEdgeName, initEdgeValue) = remember { authPrefs.getEdgeHeaderSync() }
    var edgeName by remember { mutableStateOf(initEdgeName) }
    var edgeValue by remember { mutableStateOf(initEdgeValue) }
    fun edgeOrNull(): Pair<String, String>? =
        if (remote && !tunnel && edgeName.trim().isNotEmpty() && edgeValue.trim().isNotEmpty()) edgeName.trim() to edgeValue.trim() else null

    var hubTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingHub by remember { mutableStateOf(false) }
    var bridgeTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingBridge by remember { mutableStateOf(false) }
    var sshTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingSsh by remember { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }
    var saveIsError by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showServerLog by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }
    var showSaveProfile by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    var profileWithSecrets by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var profileToUpdate by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var loadedProfile by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var profileTick by remember { mutableIntStateOf(0) }

    fun snapshot() = listOf(
        remote, tunnel, phoneChroot, hubText.trim(), bridgeText.trim(), bridgeBinPath.trim(), agyBinPath.trim(), claudeBinPath.trim(), openCodeBinPath.trim(), codexBinPath.trim(),
        chrootBridgeBin.trim(), chrootAgyBin.trim(), chrootClaudeBin.trim(), chrootOpenCodeBin.trim(), chrootCodexBin.trim(),
        securityToken.trim(), sshHost.trim(), sshPort.trim(), sshUser.trim(), sshPass, sshKey.trim(), sshPassphrase,
        remoteBridgeBin.trim(), remoteAgyBin.trim(), remoteClaudeBin.trim(), remoteOpenCodeBin.trim(), remoteCodexBin.trim(), remoteInstallDir.trim(),
        listenBridge.trim(), listenHub.trim(), listenBind.trim(), edgeName.trim(), edgeValue.trim()
    ).joinToString("\u0001")

    // Each mode keeps its own saved settings (JSON), so switching back and forth does not make you type everything again.
    fun profileJson(forRemote: Boolean, withSecrets: Boolean): org.json.JSONObject = org.json.JSONObject().apply {
        put("hub", hubText.trim()); put("bridge", bridgeText.trim())
        if (forRemote) {
            put("tunnel", tunnel)
            put("sshHost", sshHost.trim()); put("sshPort", sshPort.trim()); put("sshUser", sshUser.trim())
            put("edgeName", edgeName.trim())
            if (withSecrets) { put("sshPass", sshPass); put("sshKey", sshKey.trim()); put("sshPassphrase", sshPassphrase); put("edgeValue", edgeValue.trim()) }
            put("bridgeBin", remoteBridgeBin.trim()); put("agyBin", remoteAgyBin.trim()); put("claudeBin", remoteClaudeBin.trim())
            put("openCodeBin", remoteOpenCodeBin.trim())
            put("codexBin", remoteCodexBin.trim())
            put("installDir", remoteInstallDir.trim())
            put("listenBridge", listenBridge.trim()); put("listenHub", listenHub.trim()); put("listenBind", listenBind.trim())
        } else {
            put("bridgeBin", bridgeBinPath.trim()); put("agyBin", agyBinPath.trim()); put("claudeBin", claudeBinPath.trim())
            put("openCodeBin", openCodeBinPath.trim())
            put("codexBin", codexBinPath.trim())
        }
    }

    fun applyProfile(forRemote: Boolean, o: org.json.JSONObject) {
        hubText = o.optString("hub", hubText); bridgeText = o.optString("bridge", bridgeText)
        tunnel = forRemote && o.optBoolean("tunnel", false)
        if (forRemote) {
            sshHost = o.optString("sshHost"); sshPort = o.optString("sshPort", "22").ifBlank { "22" }
            sshUser = o.optString("sshUser")
            if (o.has("sshPass")) { sshPass = o.optString("sshPass"); sshKey = o.optString("sshKey"); sshPassphrase = o.optString("sshPassphrase") }
            edgeName = o.optString("edgeName").ifBlank { AuthPreferences.DEFAULT_EDGE_HEADER_NAME }
            if (o.has("edgeValue")) edgeValue = o.optString("edgeValue")
            remoteInstallDir = o.optString("installDir").ifBlank { ".antigem" }
            remoteBridgeBin = custom(o.optString("bridgeBin"), RemoteServerManager.defaultBridgeBin(remoteInstallDir))
            remoteAgyBin = custom(o.optString("agyBin"), RemoteServerManager.DEFAULT_AGY_BIN)
            remoteClaudeBin = custom(o.optString("claudeBin"), RemoteServerManager.DEFAULT_CLAUDE_BIN)
            remoteOpenCodeBin = custom(o.optString("openCodeBin"), RemoteServerManager.DEFAULT_OPENCODE_BIN)
            remoteCodexBin = custom(o.optString("codexBin"), RemoteServerManager.DEFAULT_CODEX_BIN)
            listenBridge = o.optString("listenBridge"); listenHub = o.optString("listenHub"); listenBind = o.optString("listenBind")
        } else {
            bridgeBinPath = custom(o.optString("bridgeBin", bridgeBinPath), authPrefs.getDefaultBridgeBinaryPath())
            agyBinPath = custom(o.optString("agyBin", agyBinPath), authPrefs.getDefaultAgyBinaryPath())
            claudeBinPath = custom(o.optString("claudeBin", claudeBinPath), authPrefs.getDefaultClaudeBinaryPath())
            openCodeBinPath = custom(o.optString("openCodeBin", openCodeBinPath), authPrefs.getDefaultOpenCodeBinaryPath())
            codexBinPath = custom(o.optString("codexBin", codexBinPath), authPrefs.getDefaultCodexBinaryPath())
        }
    }

    /** Local (phone), Server (domain or open ports) or Server through an SSH tunnel. */
    fun selectMode(r: Boolean, t: Boolean) {
        if (r == remote && t == tunnel) return
        saveFeedback = null; hubTestStatus = null; bridgeTestStatus = null
        val bridgeLoopback = AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT).orEmpty()))
        val hubLoopback = AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT).orEmpty()))
        when {
            !r -> { hubText = AuthPreferences.DEFAULT_HUB_URL; bridgeText = AuthPreferences.DEFAULT_BRIDGE_HTTP_URL }
            // the tunnel needs 127.0.0.1 addresses: start from the default ports (= what the server listens on)
            t -> {
                if (!bridgeLoopback) bridgeText = AuthPreferences.DEFAULT_BRIDGE_HTTP_URL
                if (!hubLoopback) hubText = AuthPreferences.DEFAULT_HUB_URL
            }
            // a phone address makes no sense for a server reached directly: start from empty boxes
            bridgeLoopback -> { hubText = ""; bridgeText = "" }
        }
        remote = r; tunnel = r && t
    }

    /** This phone, with the Termux environment or the Ubuntu chroot: each has its own default ports (an address set by hand stays). */
    fun selectPhone(chroot: Boolean) {
        if (!remote && chroot == phoneChroot) return
        val wasRemote = remote
        selectMode(false, false)
        phoneChroot = chroot
        val h = hubText.trim().trimEnd('/')
        val b = bridgeText.trim().trimEnd('/')
        if (wasRemote || h.isEmpty() || h == AuthPreferences.defaultLocalHubUrl(!chroot)) hubText = AuthPreferences.defaultLocalHubUrl(chroot)
        if (wasRemote || b.isEmpty() || b == AuthPreferences.defaultLocalBridgeUrl(!chroot)) bridgeText = AuthPreferences.defaultLocalBridgeUrl(chroot)
    }

    var savedSnapshot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val bridgeHost = AuthPreferences.hostOf(currentBridgeUrl)
        val storedHost = authPrefs.termuxSshHost.first()
        sshHost = if (AuthPreferences.currentRemoteMode && AuthPreferences.isLoopbackHost(storedHost) && bridgeHost.isNotBlank() && !AuthPreferences.isLoopbackHost(bridgeHost)) bridgeHost
        else if (AuthPreferences.currentRemoteMode) storedHost else ""
        sshPort = (if (AuthPreferences.currentRemoteMode) authPrefs.termuxSshPort.first() else 22).toString()
        sshUser = if (AuthPreferences.currentRemoteMode) authPrefs.termuxSshUser.first() else ""
        sshPass = if (AuthPreferences.currentRemoteMode) authPrefs.termuxSshPass.first() else ""
        savedSnapshot = snapshot()
    }
    val hasUnsavedChanges = savedSnapshot != null && snapshot() != savedSnapshot

    androidx.activity.compose.BackHandler(enabled = hasUnsavedChanges) { showUnsavedDialog = true }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull().orEmpty()
            if (SshSessionFactory.looksLikePrivateKey(text)) {
                sshKey = text.trim()
                saveFeedback = null
            } else {
                Toast.makeText(context, context.getString(R.string.settings_invalid_key), Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            icon = { Icon(Icons.Outlined.Info, null, tint = ClaudeTerracotta, modifier = Modifier.size(28.dp)) },
            title = { Text(stringResource(R.string.servers_settings_screen_unsaved_server_settings), fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    stringResource(R.string.servers_settings_screen_you_have_unsaved_changes_in_the_server),
                    fontSize = 13.5.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            },
            confirmButton = {
                Button(
                    onClick = { showUnsavedDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(8.dp)
                ) { Text(stringResource(R.string.servers_settings_screen_keep_editing), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    remote = AuthPreferences.currentRemoteMode
                    tunnel = AuthPreferences.currentRemoteMode && AuthPreferences.currentRemoteTunnel
                    hubText = currentHubUrl
                    bridgeText = currentBridgeUrl
                    securityToken = initSecurityToken
                    bridgeBinPath = custom(authPrefs.getAgyBridgeBinaryPathSync(), authPrefs.getDefaultBridgeBinaryPath())
                    agyBinPath = custom(authPrefs.getAgyBinaryPathSync(), authPrefs.getDefaultAgyBinaryPath())
                    claudeBinPath = custom(authPrefs.getClaudeBinaryPathSync(), authPrefs.getDefaultClaudeBinaryPath())
                    openCodeBinPath = custom(authPrefs.getOpenCodeBinaryPathSync(), authPrefs.getDefaultOpenCodeBinaryPath())
                    codexBinPath = custom(authPrefs.getCodexBinaryPathSync(), authPrefs.getDefaultCodexBinaryPath())
                    sshKey = AuthPreferences.currentSshPrivateKey
                    sshPassphrase = AuthPreferences.currentSshKeyPassphrase
                    remoteBridgeBin = storedBridgeBin()
                    remoteAgyBin = storedAgyBin()
                    remoteClaudeBin = storedClaudeBin()
                    remoteOpenCodeBin = storedOpenCodeBin()
                    remoteCodexBin = storedCodexBin()
                    remoteInstallDir = authPrefs.getRemoteInstallDirSync()
                    listenBridge = initListenBridge; listenHub = initListenHub; listenBind = initListenBind
                    edgeName = initEdgeName; edgeValue = initEdgeValue
                    coroutineScope.launch {
                        sshHost = authPrefs.termuxSshHost.first().takeIf { AuthPreferences.currentRemoteMode } ?: ""
                        sshPort = (authPrefs.termuxSshPort.first().takeIf { AuthPreferences.currentRemoteMode } ?: 22).toString()
                        sshUser = authPrefs.termuxSshUser.first().takeIf { AuthPreferences.currentRemoteMode } ?: ""
                        sshPass = authPrefs.termuxSshPass.first().takeIf { AuthPreferences.currentRemoteMode } ?: ""
                    }
                }) { Text(stringResource(R.string.git_source_control_view_discard_changes), color = MaterialTheme.colorScheme.error) }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            icon = { Icon(Icons.Outlined.RestartAlt, null, tint = ClaudeTerracotta, modifier = Modifier.size(28.dp)) },
            title = { Text(stringResource(R.string.servers_settings_screen_reopen_antigem), fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    if (remote) stringResource(R.string.servers_settings_screen_settings_saved_reopening_the_app_connects_over)
                    else stringResource(R.string.servers_settings_screen_settings_saved_reopening_the_app_restarts_the),
                    fontSize = 13.5.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestartDialog = false
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        if (intent != null) {
                            context.startActivity(Intent.makeRestartActivityTask(intent.component))
                            Runtime.getRuntime().exit(0)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(8.dp)
                ) { Text(stringResource(R.string.servers_settings_screen_reopen_now), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }, shape = RoundedCornerShape(8.dp)) {
                    Text(stringResource(R.string.bridge_ui_later), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showProfileMenu) {
        val list = remember(profileTick) { authPrefs.listProfiles(true) + authPrefs.listProfiles(false) }
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showProfileMenu = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.servers_settings_screen_saved_profiles), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.servers_settings_screen_tap_one_to_load_it_its_mode), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                if (list.isEmpty()) {
                    Text(stringResource(R.string.servers_settings_screen_nothing_saved_yet_fill_in_the_settings), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 24.dp))
                }
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { o ->
                        val r = o.optBoolean("remote")
                        val accent = if (r) QuotaGreen else ClaudeTerracotta
                        val where = AuthPreferences.hostOf(o.optString("bridge")).ifBlank { AuthPreferences.hostOf(o.optString("hub")) }
                            .let { h -> if (r && o.optString("sshUser").isNotBlank()) "${o.optString("sshUser")}@${o.optString("sshHost").ifBlank { h }}" else h }
                        Surface(
                            onClick = {
                                remote = r
                                applyProfile(r, o)
                                loadedProfile = r to o.optString("name")
                                showProfileMenu = false
                                saveFeedback = "Loaded profile \"${o.optString("name")}\". Tap Save below to apply it."
                                saveIsError = false
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = cardBg,
                            border = cardBorder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (r) Icons.Outlined.Dns else Icons.Outlined.Terminal, null, tint = accent, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(o.optString("name"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                        Spacer(Modifier.width(8.dp))
                                        Surface(shape = RoundedCornerShape(4.dp), color = accent.copy(alpha = 0.15f)) {
                                            Text(if (r && o.optBoolean("tunnel")) stringResource(R.string.servers_settings_screen_ssh_tunnel) else if (r) stringResource(R.string.servers_settings_badge_server) else stringResource(R.string.servers_settings_badge_local), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = accent,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                        }
                                    }
                                    if (where.isNotBlank()) Text(where, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1)
                                }
                                TextButton(enabled = r == remote, onClick = { profileToUpdate = o }) {
                                    Text(stringResource(R.string.claude_settings_screen_update), fontSize = 12.sp)
                                }
                                TextButton(onClick = { profileToDelete = r to o.optString("name") }) {
                                    Text(stringResource(R.string.browser_screen_delete), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveProfile) {
        AlertDialog(
            onDismissRequest = { showSaveProfile = false },
            title = { Text(if (remote) stringResource(R.string.servers_settings_screen_save_server_profile) else stringResource(R.string.servers_settings_screen_save_local_profile), fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column {
                    OutlinedTextField(profileName, { profileName = it }, label = { Text(stringResource(R.string.servers_settings_screen_profile_name)) }, singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
                    if (remote) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = profileWithSecrets, onCheckedChange = { profileWithSecrets = it })
                            Column {
                                Text(stringResource(R.string.servers_settings_screen_include_ssh_password_and_key), fontSize = 13.sp)
                                Text(stringResource(R.string.servers_settings_screen_off_by_default_anyone_using_this_phone), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                    if (authPrefs.listProfiles(remote).any { it.optString("name") == profileName.trim() } && profileName.isNotBlank()) {
                        Text(stringResource(R.string.servers_settings_screen_a_profile_with_this_name_will_be), fontSize = 11.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = profileName.isNotBlank(),
                    onClick = {
                        authPrefs.saveProfile(remote, profileName.trim(), profileJson(remote, remote && profileWithSecrets))
                        showSaveProfile = false; profileTick++
                        saveFeedback = "Profile \"${profileName.trim()}\" saved on this phone."; saveIsError = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(8.dp)
                ) { Text(stringResource(R.string.claude_providers_page_save), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showSaveProfile = false }) { Text(stringResource(R.string.accounts_ui_cancel)) } },
            shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface
        )
    }

    profileToUpdate?.let { o ->
        val uRemote = o.optBoolean("remote")
        val uName = o.optString("name")
        val hadSecrets = o.has("sshPass")
        AlertDialog(
            onDismissRequest = { profileToUpdate = null },
            title = { Text(stringResource(R.string.servers_settings_screen_update_profile), fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    "“$uName”将被表单中当前的值覆盖" +
                        (if (uRemote) (if (hadSecrets) "，包括 SSH 密码和密钥（之前就有）。" else "，不含 SSH 密码和密钥（之前也没有）。") else "。"),
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        authPrefs.saveProfile(uRemote, uName, profileJson(uRemote, uRemote && hadSecrets))
                        profileToUpdate = null; profileTick++
                        saveFeedback = "Profile \"$uName\" updated."; saveIsError = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(8.dp)
                ) { Text(stringResource(R.string.claude_settings_screen_update), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { profileToUpdate = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } },
            shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface
        )
    }

    profileToDelete?.let { (delRemote, name) ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text(stringResource(R.string.servers_settings_screen_delete_profile), fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Text(stringResource(R.string.servers_settings_screen_will_be_removed_from_this_phone, name.toString(), (if (delRemote) "server" else "local").toString()), fontSize = 13.5.sp) },
            confirmButton = {
                Button(
                    onClick = { authPrefs.deleteProfile(delRemote, name); profileToDelete = null; profileTick++ },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), shape = RoundedCornerShape(8.dp)
                ) { Text(stringResource(R.string.browser_screen_delete), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { profileToDelete = null }) { Text(stringResource(R.string.accounts_ui_cancel)) } },
            shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showServerLog) ServerLogDialog(context) { showServerLog = false }

    val subtle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Column(
        modifier = Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ----- Where does the server run? -----
        Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Dns, null, tint = QuotaGreen, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("智能体运行在", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            when {
                                !remote && phoneChroot -> "这台手机，在已 root 的 Ubuntu chroot 中：桥接、智能体和终端（apt、glibc、sudo）。"
                                !remote -> "这台手机，在 AntiGem 的内置环境中：桥接、智能体和终端（无需 root）。"
                                tunnel -> "你的服务器，通过 SSH 连接访问：无需开放端口、域名或反向代理。"
                                else -> "桥接和智能体运行在你的服务器上，通过域名或开放端口访问。应用通过 SSH 管理它们。"
                            },
                            fontSize = 11.5.sp, color = subtle
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("This phone", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = subtle)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeOption("AntiGem", !remote && !phoneChroot, Modifier.weight(1f)) { selectPhone(false) }
                    ModeOption("Ubuntu chroot", !remote && phoneChroot, Modifier.weight(1f)) { selectPhone(true) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Your server", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = subtle)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeOption("Server", remote && !tunnel, Modifier.weight(1f)) { selectMode(true, false) }
                    ModeOption("SSH tunnel", remote && tunnel, Modifier.weight(1f)) { selectMode(true, true) }
                }

                // ----- Saved profiles (saved only when the user taps "Save as profile") -----
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { profileTick++; showProfileMenu = true },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                    ) {
                        val n = remember(profileTick) { authPrefs.listProfiles(true).size + authPrefs.listProfiles(false).size }
                        Text(stringResource(R.string.servers_settings_screen_saved_profiles_2, n.toString()), fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { profileName = loadedProfile?.takeIf { it.first == remote }?.second.orEmpty(); showSaveProfile = true }, shape = RoundedCornerShape(8.dp)) {
                        Text(stringResource(R.string.servers_settings_screen_save_as_profile), fontSize = 12.sp)
                    }
                }
                Text(stringResource(R.string.servers_settings_screen_picking_a_profile_switches_to_its_mode), fontSize = 10.5.sp, color = subtle, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // ----- the chosen runtime of this phone -----
        if (!remote) {
            if (phoneChroot) {
                ai.antigem.ui.settings.chroot.ChrootRuntimeCard(cardBg, cardBorder, onManage = onManageChroot, onInstalled = {
                    // installed and made the runtime: the saved addresses moved to the chroot's ports
                    hubText = AuthPreferences.currentHubUrl
                    bridgeText = AuthPreferences.currentBridgeHttpUrl
                    savedSnapshot = snapshot()
                    if (!AuthPreferences.currentRemoteMode) showRestartDialog = true
                })
            } else {
                ai.antigem.ui.settings.chroot.TermuxRuntimeCard(cardBg, cardBorder, onInstall = onInstallTermux, onFactoryReset = { showFactoryReset = true },
                    onInstalled = {
                        // the install switched the phone to Termux (and its addresses) already
                        phoneChroot = false
                        hubText = AuthPreferences.currentHubUrl
                        bridgeText = AuthPreferences.currentBridgeHttpUrl
                        savedSnapshot = snapshot()
                    })
            }
        }
        FactoryResetDialogs(showFactoryReset) { showFactoryReset = false }

        // ----- The two addresses -----
        Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.servers_settings_screen_server_addresses), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    when {
                        remote && tunnel -> "Use 127.0.0.1 with the port the bridge and the hub listen on at the server (default ${BuildConfig.BRIDGE_PORT} and ${BuildConfig.HUB_PORT}). " +
                            "The app starts them on the server's 127.0.0.1 only and forwards the same ports on this phone to them over SSH."
                        remote -> stringResource(R.string.servers_settings_screen_two_addresses_e_g_https_bridge_example)
                        else -> stringResource(R.string.servers_settings_screen_where_this_phone_s_bridge_and_hub)
                    },
                    fontSize = 11.5.sp, color = subtle
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = bridgeText,
                    onValueChange = { bridgeText = it; saveFeedback = null; bridgeTestStatus = null },
                    label = { Text(stringResource(R.string.servers_settings_screen_ide_bridge_address)) },
                    placeholder = { Text(if (remote && !tunnel) "https://bridge.example.com" else AuthPreferences.DEFAULT_BRIDGE_HTTP_URL) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = hubText,
                    onValueChange = { hubText = it; saveFeedback = null; hubTestStatus = null },
                    label = { Text(stringResource(R.string.servers_settings_screen_agy_hub_address)) },
                    placeholder = { Text(if (remote && !tunnel) "https://hub.example.com" else AuthPreferences.DEFAULT_HUB_URL) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (remote && tunnel) stringResource(R.string.servers_settings_screen_test_hub_and_test_bridge_work_once)
                    else "https:// needs no port. Without https://, a missing port becomes ${BuildConfig.BRIDGE_PORT} (bridge) / ${BuildConfig.HUB_PORT} (hub).",
                    fontSize = 11.sp, color = subtle
                )

                if (remote && !tunnel) {
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.servers_settings_screen_ports_on_the_server), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "上面的地址是手机连接的目标（反向代理）。下面这些是桥接服务和 hub 在服务器上实际监听的端口。" +
                            "若使用 nginx 或 Apache，请填 ${BuildConfig.BRIDGE_PORT} 和 ${BuildConfig.HUB_PORT}（留空 = 自动）。",
                        fontSize = 11.sp, color = subtle
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(listenBridge, { listenBridge = it.filter(Char::isDigit); saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_bridge_listens_on)) },
                            placeholder = { Text(BuildConfig.BRIDGE_PORT.toString()) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(listenHub, { listenHub = it.filter(Char::isDigit); saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_hub_listens_on)) },
                            placeholder = { Text(BuildConfig.HUB_PORT.toString()) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(listenBind, { listenBind = it; saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_bridge_bind_address)) },
                        placeholder = { Text(stringResource(R.string.servers_settings_bind_placeholder)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                }

                Spacer(Modifier.height(12.dp))
                StatusLine(isServerOnline || hubTestStatus?.startsWith("✓") == true, "Hub", AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT) ?: "invalid address")
                hubTestStatus?.let { Text(it, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (it.startsWith("✓")) QuotaGreen else Color.Red, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(6.dp))
                StatusLine(isBridgeOnline || bridgeTestStatus?.startsWith("✓") == true, "Bridge", AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT) ?: "invalid address")
                bridgeTestStatus?.let { Text(it, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (it.startsWith("✓")) QuotaGreen else Color.Red, modifier = Modifier.padding(top = 4.dp)) }
                ai.antigem.ui.agents.BridgeVersionLine(Modifier.padding(top = 6.dp))

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val url = AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT)
                            if (url == null) { hubTestStatus = "✗ Not a valid address"; return@OutlinedButton }
                            isTestingHub = true
                            hubTestStatus = "Contacting $url…"
                            coroutineScope.launch {
                                val (ok, msg) = probeHub(url, edgeOrNull())
                                isTestingHub = false
                                hubTestStatus = if (ok) "✓ Hub reachable ($msg)" else "✗ Hub: $msg"
                            }
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !isTestingHub
                    ) {
                        if (isTestingHub) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                        Text(stringResource(R.string.servers_settings_screen_test_hub), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val url = AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT)
                            if (url == null) { bridgeTestStatus = "✗ Not a valid address"; return@OutlinedButton }
                            isTestingBridge = true
                            bridgeTestStatus = "Contacting $url…"
                            coroutineScope.launch {
                                val (ok, msg) = probeBridge(url, securityToken, edgeOrNull())
                                isTestingBridge = false
                                bridgeTestStatus = if (ok) "✓ Bridge reachable ($msg)" else "✗ Bridge: $msg"
                            }
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !isTestingBridge
                    ) {
                        if (isTestingBridge) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                        Text(stringResource(R.string.servers_settings_screen_test_bridge), fontSize = 12.sp)
                    }
                }
            }
        }

        if (remote) {
            // ----- Proxy access header: a second lock in front of the bridge and the hub (no proxy in tunnel mode) -----
            if (!tunnel) Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, null, tint = QuotaGreen, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.servers_settings_screen_proxy_access_header_second_layer), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "可选。应用会给发往桥接服务和 hub 的每个请求加上此请求头。让 nginx / Apache / Caddy 拒绝" +
                                    "不带该值的请求，陌生人就无法访问桥接服务或 agy。如果你的代理不校验此项，请留空。",
                                fontSize = 11.5.sp, color = subtle
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = edgeName, onValueChange = { edgeName = it.trim(); saveFeedback = null },
                        label = { Text(stringResource(R.string.servers_settings_screen_header_name)) }, singleLine = true, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = edgeValue, onValueChange = { edgeValue = it.trim(); saveFeedback = null },
                        label = { Text(stringResource(R.string.servers_settings_screen_header_value_secret)) }, singleLine = true, shape = RoundedCornerShape(10.dp),
                        placeholder = { Text(stringResource(R.string.servers_settings_screen_empty_off)) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { edgeValue = AuthPreferences.generateEdgeHeaderValue(); saveFeedback = null },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                        ) { Icon(Icons.Outlined.Shuffle, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.servers_settings_screen_generate), fontSize = 12.sp) }
                        OutlinedButton(
                            enabled = edgeValue.isNotBlank(),
                            onClick = {
                                val nginxVar = "http_" + edgeName.trim().lowercase().replace('-', '_')
                                val snippet = "map \$$nginxVar \$antigem_key_ok { default 0; \"${edgeValue.trim()}\" 1; }\n\n" +
                                    "# put this in every server { } block of the bridge and of the hub:\nif (\$antigem_key_ok = 0) { return 444; }"
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("nginx rule", snippet))
                                Toast.makeText(context, context.getString(R.string.settings_nginx_rule_copied), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                        ) { Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.servers_settings_screen_copy_nginx_rule), fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "若设置了值，代理必须校验同一个值，否则所有请求都会被拒绝。“测试 Hub”和“测试桥接”也会用到它。" +
                            "它只保护经过代理的流量，且仅限 https://。",
                        fontSize = 11.sp, color = subtle
                    )
                }
            }

            // ----- SSH -----
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.servers_settings_screen_ssh_access), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                stringResource(R.string.servers_settings_screen_used_to_install_start_and_stop_the),
                                fontSize = 11.5.sp, color = subtle
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sshHost, onValueChange = { sshHost = it; sshTestStatus = null; saveFeedback = null },
                            label = { Text(stringResource(R.string.servers_settings_screen_ssh_host)) }, placeholder = { Text(stringResource(R.string.servers_settings_screen_server_host_or_ip)) },
                            modifier = Modifier.weight(2f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        OutlinedTextField(
                            value = sshPort, onValueChange = { sshPort = it.filter(Char::isDigit); sshTestStatus = null; saveFeedback = null },
                            label = { Text(stringResource(R.string.cloud_preview_port)) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sshUser, onValueChange = { sshUser = it; sshTestStatus = null; saveFeedback = null },
                        label = { Text(stringResource(R.string.servers_settings_screen_user)) }, placeholder = { Text(stringResource(R.string.servers_settings_screen_not_root_if_you_can_avoid_it)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sshPass, onValueChange = { sshPass = it; sshTestStatus = null; saveFeedback = null },
                        label = { Text(stringResource(R.string.servers_settings_screen_password_optional_when_a_key_is_set)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sshKey, onValueChange = { sshKey = it; sshTestStatus = null; saveFeedback = null },
                        label = { Text(stringResource(R.string.servers_settings_screen_private_key_recommended)) },
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                        modifier = Modifier.fillMaxWidth().height(110.dp), minLines = 3, maxLines = 4, shape = RoundedCornerShape(10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                            Text(stringResource(R.string.servers_settings_screen_choose_key_file), fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val text = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                                if (SshSessionFactory.looksLikePrivateKey(text)) { sshKey = text.trim(); saveFeedback = null }
                                else Toast.makeText(context, context.getString(R.string.settings_no_private_key), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                        ) { Text(stringResource(R.string.servers_settings_screen_paste_key), fontSize = 12.sp) }
                    }
                    if (sshKey.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = sshPassphrase, onValueChange = { sshPassphrase = it; saveFeedback = null },
                            label = { Text(stringResource(R.string.servers_settings_screen_key_passphrase_if_it_has_one)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                    sshTestStatus?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = if (it.startsWith("✓")) QuotaGreen else Color.Red)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val host = sshHost.trim()
                                if (host.isBlank() || sshUser.isBlank()) { sshTestStatus = "✗ Enter the host and the user first"; return@OutlinedButton }
                                isTestingSsh = true
                                sshTestStatus = "Connecting to $host…"
                                val prevKey = AuthPreferences.currentSshPrivateKey
                                val prevPass = AuthPreferences.currentSshKeyPassphrase
                                AuthPreferences.currentSshPrivateKey = sshKey.trim()
                                AuthPreferences.currentSshKeyPassphrase = sshPassphrase
                                coroutineScope.launch {
                                    val res = RemoteServerManager.testSsh(host, sshPort.toIntOrNull() ?: 22, sshUser.trim(), sshPass)
                                    AuthPreferences.currentSshPrivateKey = prevKey
                                    AuthPreferences.currentSshKeyPassphrase = prevPass
                                    isTestingSsh = false
                                    sshTestStatus = res.fold(
                                        { "✓ SSH works: " + it.replace('\n', ' ') },
                                        { "✗ ${it.message}" }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), enabled = !isTestingSsh
                        ) {
                            if (isTestingSsh) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                            Text(stringResource(R.string.servers_settings_screen_test_ssh), fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                SshSessionFactory.forgetHostKey(sshHost.trim(), sshPort.toIntOrNull() ?: 22)
                                Toast.makeText(context, context.getString(R.string.settings_host_key_forgotten), Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                        ) { Text(stringResource(R.string.servers_settings_screen_forget_host_key), fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.servers_settings_screen_the_server_s_ssh_key_is_pinned),
                        fontSize = 11.sp, color = subtle
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).let { it },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.servers_settings_screen_advanced_server_paths_and_ports), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) stringResource(R.string.claude_settings_screen_hide) else stringResource(R.string.claude_settings_screen_show), fontSize = 12.sp) }
                    }
                    if (showAdvanced) {
                        Text(
                            "这些是服务器上的路径（~ 表示 SSH 用户的主目录）。应用不会上传桥接程序：请自行把 agy_ide_bridge" +
                                "（linux amd64 / arm64 版本）复制到桥接路径，并对其执行 chmod +x。",
                            fontSize = 11.sp, color = subtle
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteBridgeBin, { remoteBridgeBin = it; saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_bridge_binary_on_the_server)) },
                            placeholder = { Text(RemoteServerManager.defaultBridgeBin(remoteInstallDir)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteAgyBin, { remoteAgyBin = it; saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_agy_binary_on_the_server)) },
                            placeholder = { Text(RemoteServerManager.DEFAULT_AGY_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteClaudeBin, { remoteClaudeBin = it; saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_claude_binary_on_the_server)) },
                            placeholder = { Text(RemoteServerManager.DEFAULT_CLAUDE_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteOpenCodeBin, { remoteOpenCodeBin = it; saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_opencode_binary_on_the_server)) },
                            placeholder = { Text(RemoteServerManager.DEFAULT_OPENCODE_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteCodexBin, { remoteCodexBin = it; saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_codex_binary_on_the_server)) },
                            placeholder = { Text(RemoteServerManager.DEFAULT_CODEX_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(remoteInstallDir, {
                            // while the bridge path is still the default of the old folder, it follows the folder
                            if (remoteBridgeBin == RemoteServerManager.defaultBridgeBin(remoteInstallDir)) remoteBridgeBin = RemoteServerManager.defaultBridgeBin(it)
                            remoteInstallDir = it; saveFeedback = null
                        }, label = { Text(stringResource(R.string.servers_settings_screen_bridge_folder_in_the_server_home)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    }
                }
            }

            // ----- user port forwards: server port -> this phone's 127.0.0.1 (dev servers and the like) -----
            val reservedPorts = buildSet {
                if (tunnel) {
                    AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT)?.let { runCatching { java.net.URI(it).port }.getOrNull() }?.takeIf { it > 0 }?.let(::add)
                    AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT)?.let { runCatching { java.net.URI(it).port }.getOrNull() }?.takeIf { it > 0 }?.let(::add)
                }
                add(ai.antigem.ui.browser.BrowserExtensionManager.instance.hostPort())
                add(ai.antigem.data.remote.AndroidLocalBridgeServer.instance.boundPort)
            }
            PortForwardsCard(cardBg, cardBorder, subtle, reservedPorts)

            // ----- live server control (only once Remote mode is active) -----
            if (AuthPreferences.currentRemoteMode) RemoteServerCard(cardBg, cardBorder, subtle, onShowLog = {
                RemoteServerManager.refreshLog(context)
                showServerLog = true
            })
        } else if (phoneChroot) {
            // ----- the Ubuntu chroot's agent paths (paths inside the chroot) -----
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Ubuntu chroot 中的智能体路径", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("仅供高级使用：留空则使用默认值（灰色显示）", fontSize = 11.5.sp, color = subtle)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(chrootBridgeBin, { chrootBridgeBin = it; saveFeedback = null }, label = { Text("IDE 桥接") },
                        placeholder = { Text(ai.antigem.data.local.chroot.ChrootManager.BRIDGE_IN_CHROOT) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(chrootAgyBin, { chrootAgyBin = it; saveFeedback = null }, label = { Text("AGY Hub（agy）") },
                        placeholder = { Text(RemoteServerManager.DEFAULT_AGY_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(chrootClaudeBin, { chrootClaudeBin = it; saveFeedback = null }, label = { Text("Claude Code") },
                        placeholder = { Text(RemoteServerManager.DEFAULT_CLAUDE_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(chrootOpenCodeBin, { chrootOpenCodeBin = it; saveFeedback = null }, label = { Text("OpenCode") },
                        placeholder = { Text(RemoteServerManager.DEFAULT_OPENCODE_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(chrootCodexBin, { chrootCodexBin = it; saveFeedback = null }, label = { Text("Codex") },
                        placeholder = { Text(RemoteServerManager.DEFAULT_CODEX_BIN) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("• 启动时传给桥接（--bin、--claude-bin、--opencode-bin、--codex-bin）。路径有误时，桥接也会在常用目录中查找智能体。",
                        fontSize = 11.sp, color = subtle)
                    Text("• 从应用内安装（设置 → 各智能体 → 安装）会使用智能体官方的 Linux 安装脚本，它们会装到这些默认位置。",
                        fontSize = 11.sp, color = subtle)
                    Text("• IDE 桥接：AntiGem 每次启动都会把本应用版本的桥接放到 ${ai.antigem.data.local.chroot.ChrootManager.BRIDGE_IN_CHROOT}。填其他路径则改为运行你自己的桥接。",
                        fontSize = 11.sp, color = subtle)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            chrootBridgeBin = ""; chrootAgyBin = ""; chrootClaudeBin = ""; chrootOpenCodeBin = ""; chrootCodexBin = ""
                            saveFeedback = "智能体路径已重置为默认值（点击保存以应用）。"
                            saveIsError = false
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重置为默认值", fontSize = 12.sp)
                    }
                }
            }
        } else {
            // ----- local binaries (AntiGem's built-in environment) -----
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("AntiGem 中的智能体路径", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("仅供高级使用：留空则使用默认值（灰色显示）", fontSize = 11.5.sp, color = subtle)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(bridgeBinPath, { bridgeBinPath = it; saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_ide_bridge_binary_path)) },
                        placeholder = { Text(authPrefs.getDefaultBridgeBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(agyBinPath, { agyBinPath = it; saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_agy_hub_binary_path)) },
                        placeholder = { Text(authPrefs.getDefaultAgyBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(claudeBinPath, { claudeBinPath = it; saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_claude_code_binary_path)) },
                        placeholder = { Text(authPrefs.getDefaultClaudeBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(openCodeBinPath, { openCodeBinPath = it; saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_opencode_binary_path)) },
                        placeholder = { Text(authPrefs.getDefaultOpenCodeBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(codexBinPath, { codexBinPath = it; saveFeedback = null }, label = { Text(stringResource(R.string.servers_settings_screen_codex_binary_path)) },
                        placeholder = { Text(authPrefs.getDefaultCodexBinaryPath()) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.servers_settings_screen_passed_to_the_bridge_on_launch_bin),
                        fontSize = 11.sp, color = subtle
                    )
                    Text(
                        stringResource(R.string.servers_settings_screen_claude_code_the_default_is_antigem_s),
                        fontSize = 11.sp, color = subtle
                    )
                    Text(
                        stringResource(R.string.servers_settings_screen_opencode_the_default_is_where_its_installer),
                        fontSize = 11.sp, color = subtle
                    )
                    Text(
                        stringResource(R.string.servers_settings_screen_codex_the_default_is_the_termux_package),
                        fontSize = 11.sp, color = subtle
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            hubText = AuthPreferences.defaultLocalHubUrl(phoneChroot)
                            bridgeText = AuthPreferences.defaultLocalBridgeUrl(phoneChroot)
                            bridgeBinPath = ""; agyBinPath = ""; claudeBinPath = ""; openCodeBinPath = ""; codexBinPath = ""
                            saveFeedback = "Settings reset to defaults (click Save to apply)."
                            saveIsError = false
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.servers_settings_screen_reset_all_to_defaults), fontSize = 12.sp)
                    }
                }
            }
        }

        // ----- Security token -----
        Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.servers_settings_screen_security_token_header_obfuscation), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (remote && tunnel) stringResource(R.string.servers_settings_screen_the_secret_that_protects_the_bridge_the)
                            else if (remote) stringResource(R.string.servers_settings_screen_the_secret_that_protects_the_bridge_the_2)
                            else stringResource(R.string.servers_settings_screen_12_char_token_framing_localhost_requests_to),
                            fontSize = 11.5.sp, color = subtle
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = securityToken,
                    onValueChange = {
                        securityToken = it.take(AuthPreferences.TOKEN_LENGTH).filter { c -> c.isLetterOrDigit() }
                        saveFeedback = null
                    },
                    label = { Text(stringResource(R.string.servers_settings_screen_12_character_secret_token)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp),
                    trailingIcon = {
                        Text(
                            "${securityToken.length}/12", fontSize = 11.sp,
                            color = if (securityToken.length == AuthPreferences.TOKEN_LENGTH) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
                val framedHeader = AuthPreferences.buildFramedHeader(securityToken)
                Text(stringResource(R.string.servers_settings_screen_http_framed_header, framedHeader.toString()), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { securityToken = AuthPreferences.generateRandomToken(); saveFeedback = null },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Shuffle, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.servers_settings_screen_randomize), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Framed Security Header", framedHeader))
                            Toast.makeText(context, context.getString(R.string.settings_copied_header, framedHeader), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.servers_settings_screen_copy_header), fontSize = 12.sp)
                    }
                }
            }
        }

        saveFeedback?.let {
            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp), color = (if (saveIsError) Color.Red else QuotaGreen).copy(alpha = 0.12f)) {
                Text(it, fontSize = 12.sp, color = if (saveIsError) Color.Red else QuotaGreen, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(10.dp))
            }
        }

        Button(
            onClick = {
                val hub = AuthPreferences.normalizeServerUrl(hubText, BuildConfig.HUB_PORT)
                val bridge = AuthPreferences.normalizeServerUrl(bridgeText, BuildConfig.BRIDGE_PORT)
                val problem = when {
                    !remote && phoneChroot && ai.antigem.data.local.chroot.ChrootManager.activeInstall(context) == null ->
                        "Install the Ubuntu chroot first (Install or find, above), or choose AntiGem."
                    bridge == null -> "The IDE Bridge address is not valid."
                    hub == null -> "The AGY Hub address is not valid."
                    remote && tunnel && (!AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(bridge)) || !AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(hub))) ->
                        "In SSH tunnel mode both addresses are 127.0.0.1 with the port the service listens on at the server, e.g. http://127.0.0.1:${BuildConfig.BRIDGE_PORT}."
                    remote && tunnel && (bridge.startsWith("https://") || hub.startsWith("https://")) ->
                        "In SSH tunnel mode use http:// (SSH already encrypts the connection)."
                    remote && tunnel && runCatching { java.net.URI(bridge).port == java.net.URI(hub).port }.getOrDefault(false) ->
                        "The bridge and the hub need two different ports."
                    remote && AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(bridge)) && sshHost.isBlank() ->
                        "A remote server needs its address (not 127.0.0.1) or an SSH host."
                    remote && sshHost.isBlank() -> "Enter the SSH host."
                    remote && sshUser.isBlank() -> "Enter the SSH user."
                    remote && sshPass.isBlank() && !SshSessionFactory.looksLikePrivateKey(sshKey) -> "Enter an SSH password or a private key."
                    remote && sshKey.isNotBlank() && !SshSessionFactory.looksLikePrivateKey(sshKey) -> "The SSH key is not a valid private key."
                    remote && !tunnel && edgeValue.isNotBlank() && !AuthPreferences.isValidEdgeName(edgeName.trim()) ->
                        "The access header name may only contain letters, digits and dashes."
                    remote && !tunnel && edgeValue.isNotBlank() && !AuthPreferences.isValidEdgeValue(edgeValue.trim()) ->
                        "The access header value must be 8 to 200 visible characters, without quotes or backslashes."
                    else -> null
                }
                if (problem != null || hub == null || bridge == null) {
                    saveFeedback = problem ?: "Check the addresses."
                    saveIsError = true
                    return@Button
                }
                hubText = hub
                bridgeText = bridge
                onSaveServerUrls(hub, bridge)
                val tokenToSave = if (securityToken.length == AuthPreferences.TOKEN_LENGTH) securityToken else AuthPreferences.generateRandomToken()
                securityToken = tokenToSave
                coroutineScope.launch {
                    authPrefs.saveSecurityToken(tokenToSave)
                    val wasChroot = authPrefs.isChrootModeSync()
                    authPrefs.saveRemoteModeSync(remote)
                    authPrefs.saveRemoteTunnelSync(remote && tunnel)
                    if (!remote) {
                        if (phoneChroot) ai.antigem.data.local.chroot.ChrootManager.activeInstall(context)?.let {
                            ai.antigem.data.local.chroot.ChrootManager.activate(context, it)
                        } else ai.antigem.data.local.chroot.ChrootManager.useTermux(context)
                    }
                    // the chroot's bridge outlives the app: stop it when the agents no longer run there
                    if (wasChroot && (remote || !phoneChroot)) ai.antigem.data.local.chroot.ChrootManager.stopBridge(context)
                    if (remote) {
                        authPrefs.saveSshSettings(sshHost.trim(), sshPort.toIntOrNull() ?: 22, sshUser.trim(), sshPass)
                        authPrefs.saveSshKeySync(sshKey, sshPassphrase)
                        authPrefs.saveRemoteBinariesSync(remoteBridgeBin, remoteAgyBin, remoteClaudeBin, remoteOpenCodeBin, remoteCodexBin)
                        authPrefs.saveRemoteInstallDirSync(remoteInstallDir)
                        authPrefs.saveRemoteListenSync(listenBridge, listenHub, listenBind)
                        authPrefs.saveEdgeHeaderSync(edgeName, edgeValue)
                    } else {
                        authPrefs.saveAgyBridgeBinaryPath(bridgeBinPath.trim().ifBlank { authPrefs.getDefaultBridgeBinaryPath() })
                        authPrefs.saveAgyBinaryPath(agyBinPath.trim().ifBlank { authPrefs.getDefaultAgyBinaryPath() })
                        authPrefs.saveClaudeBinaryPath(claudeBinPath.trim().ifBlank { authPrefs.getDefaultClaudeBinaryPath() })
                        authPrefs.saveOpenCodeBinaryPath(openCodeBinPath.trim().ifBlank { authPrefs.getDefaultOpenCodeBinaryPath() })
                        authPrefs.saveCodexBinaryPath(codexBinPath.trim().ifBlank { authPrefs.getDefaultCodexBinaryPath() })
                        authPrefs.saveChrootBinariesSync(chrootBridgeBin, chrootAgyBin, chrootClaudeBin, chrootOpenCodeBin, chrootCodexBin)
                    }
                }
                savedSnapshot = snapshot()
                saveFeedback = "✓ Server, connection and security settings saved!"
                saveIsError = false
                showRestartDialog = true
            },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
        ) {
            Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.servers_settings_screen_save_server_security_settings), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ModeOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) QuotaGreen.copy(alpha = 0.16f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label, fontSize = 12.sp, maxLines = 1,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * The user's own forwards: a port on the server (a dev server from `npm run dev`, a database admin page ...) made
 * reachable on this phone's 127.0.0.1 over the SSH connection. Saved and applied right away, no Save button needed.
 */
@Composable
private fun PortForwardsCard(cardBg: Color, cardBorder: BorderStroke, subtle: Color, reservedPorts: Set<Int>) {
    val context = LocalContext.current
    val prefs = remember(context) { AuthPreferences(context) }
    var list by remember { mutableStateOf(prefs.getPortForwardsSync()) }
    var serverPort by remember { mutableStateOf("") }
    var localPort by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val tunnels by RemoteServerManager.tunnels.collectAsState()
    val ssh by RemoteServerManager.sshState.collectAsState()
    val live = AuthPreferences.currentRemoteMode

    fun persist(next: List<AuthPreferences.PortForward>) {
        list = next
        prefs.savePortForwardsSync(next)
        RemoteServerManager.resyncTunnels()
    }

    Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns, null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.servers_settings_screen_port_forwards_server_this_phone), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "在本机访问服务器上的端口，例如用 npm run dev 在 5173 端口启动的开发服务器：" +
                            "然后在这里打开 http://127.0.0.1:5173。经由 SSH 连接转发，修改立即生效。",
                        fontSize = 11.5.sp, color = subtle
                    )
                }
            }

            if (list.isNotEmpty()) Spacer(Modifier.height(10.dp))
            list.forEachIndexed { index, f ->
                val active = live && tunnels.any { it.startsWith("phone 127.0.0.1:${f.localPort} ") }
                val failed = live && tunnels.any { it.contains("phone port ${f.localPort} not forwarded") }
                val dot = when {
                    !f.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    active -> QuotaGreen
                    failed -> Color.Red
                    else -> ai.antigem.theme.AppColors.warning
                }
                val state = when {
                    !f.enabled -> "off"
                    active -> "active"
                    failed -> "could not open the phone port"
                    !live -> "starts once Server mode is saved"
                    !ssh.connected -> "waiting for SSH"
                    else -> "opening…"
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(8.dp), shape = CircleShape, color = dot) {}
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name.ifBlank { stringResource(R.string.servers_settings_port_label, f.serverPort) }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text(stringResource(R.string.servers_settings_screen_server_http_127_0_0_1, f.serverPort.toString(), f.localPort.toString(), state.toString()), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = subtle)
                    }
                    androidx.compose.material3.IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Forwarded address", "http://127.0.0.1:${f.localPort}"))
                        Toast.makeText(context, context.getString(R.string.settings_copied_forwarding_address, f.localPort), Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.servers_settings_screen_copy_address), modifier = Modifier.size(17.dp)) }
                    Switch(
                        checked = f.enabled,
                        onCheckedChange = { on -> persist(list.toMutableList().also { it[index] = f.copy(enabled = on) }) },
                        colors = SwitchDefaults.colors(checkedTrackColor = QuotaGreen)
                    )
                    androidx.compose.material3.IconButton(onClick = { persist(list.filterIndexed { i, _ -> i != index }) }) {
                        Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.browser_screen_remove), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(19.dp))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(serverPort, { serverPort = it.filter(Char::isDigit).take(5); error = null }, label = { Text(stringResource(R.string.servers_settings_screen_server_port)) },
                    placeholder = { Text("5173") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(localPort, { localPort = it.filter(Char::isDigit).take(5); error = null }, label = { Text(stringResource(R.string.servers_settings_screen_phone_port)) },
                    placeholder = { Text(serverPort.ifBlank { stringResource(R.string.servers_settings_port_same) }) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40); error = null }, label = { Text(stringResource(R.string.servers_settings_screen_name_optional)) },
                    placeholder = { Text(stringResource(R.string.servers_settings_screen_vite_dev_server)) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(10.dp))
                Button(
                    onClick = {
                        val sp = serverPort.toIntOrNull()
                        val lp = localPort.toIntOrNull() ?: sp
                        error = when {
                            sp == null || sp !in 1..65535 -> "Enter the server port (1-65535)."
                            lp == null || lp !in 1024..65535 -> "The phone port must be 1024-65535 (lower ports need root)."
                            lp in reservedPorts -> "Phone port $lp is already used by the app (bridge, hub or tools). Pick another phone port."
                            list.any { it.localPort == lp } -> "Phone port $lp is already in the list."
                            else -> null
                        }
                        if (error == null && sp != null && lp != null) {
                            persist(list + AuthPreferences.PortForward(sp, lp, name.trim(), true))
                            serverPort = ""; localPort = ""; name = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(8.dp)
                ) { Text(stringResource(R.string.browser_screen_add), fontWeight = FontWeight.Bold) }
            }
            error?.let { Text(it, fontSize = 11.5.sp, color = Color.Red, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(6.dp))
            Text(
                "服务器上的程序必须监听 127.0.0.1 或 0.0.0.0（以 Vite 为例：若它选了别的地址，用 npm run dev -- --host 127.0.0.1）。" +
                    "只有本机可以使用转发端口；服务器上不会对外开放任何端口。",
                fontSize = 11.sp, color = subtle
            )
        }
    }
}

@Composable
private fun StatusLine(online: Boolean, label: String, address: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(8.dp), shape = CircleShape, color = if (online) QuotaGreen else Color.Red) {}
        Spacer(Modifier.width(6.dp))
        Text("$label: $address", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

/** Live controls of the SSH-managed server: connection, progress, start/stop and the MCP tunnels. */
@Composable
private fun RemoteServerCard(cardBg: Color, cardBorder: BorderStroke, subtle: Color, onShowLog: () -> Unit) {
    val context = LocalContext.current
    val status by LocalServerManager.status.collectAsState()
    val ssh by RemoteServerManager.sshState.collectAsState()
    val detail by RemoteServerManager.detail.collectAsState()
    val tunnels by RemoteServerManager.tunnels.collectAsState()

    val (label, color) = when (val s = status) {
        is LocalServerStatus.Running -> "Bridge running on the server" to QuotaGreen
        is LocalServerStatus.Starting -> "Starting…" to ai.antigem.theme.AppColors.warning
        is LocalServerStatus.Stopping -> "Stopping…" to ai.antigem.theme.AppColors.warning
        is LocalServerStatus.Stopped -> "Bridge stopped" to ai.antigem.theme.AppColors.textSecondary
        is LocalServerStatus.Error -> s.message to Color.Red
        is LocalServerStatus.Idle -> "Not started" to ai.antigem.theme.AppColors.textSecondary
    }

    Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.servers_settings_screen_server_control), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Surface(Modifier.padding(top = 4.dp).size(8.dp), shape = CircleShape, color = color) {}
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = color)
                    if (detail.isNotBlank()) Text(detail, fontSize = 11.5.sp, color = subtle)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "SSH: " + ssh.message,
                fontSize = 11.5.sp, color = if (ssh.connected) QuotaGreen else subtle
            )
            if (tunnels.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.servers_settings_screen_ssh_tunnels), fontSize = 11.sp, color = subtle)
                tunnels.forEach { Text("• $it", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = subtle) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { LocalServerManager.startServer(context, forceRestart = false) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text(stringResource(R.string.servers_settings_screen_start), fontSize = 12.sp) }
                OutlinedButton(onClick = { LocalServerManager.restartServer(context) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text(stringResource(R.string.codex_settings_screen_restart), fontSize = 12.sp) }
                OutlinedButton(onClick = { LocalServerManager.stopServer() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) { Text(stringResource(R.string.chat_input_bar_stop), fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onShowLog, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) { Text(stringResource(R.string.servers_settings_screen_server_log), fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ServerLogDialog(context: Context, onDismiss: () -> Unit) {
    val log by RemoteServerManager.log.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.servers_settings_screen_server_log), fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.height(380.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)) {
                Text(
                    log.ifBlank { "(empty: the bridge has not written anything yet or SSH is not connected)" },
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accounts_ui_close)) } },
        dismissButton = { TextButton(onClick = { RemoteServerManager.refreshLog(context) }) { Text(stringResource(R.string.agent_sheet_ui_refresh)) } },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}
