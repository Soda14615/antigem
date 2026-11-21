package ai.antigem.ui.settings.chroot

import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.LocalServerManager
import ai.antigem.data.local.LocalTerminalManager
import ai.antigem.data.local.chroot.ChrootBind
import ai.antigem.data.local.chroot.ChrootConfig
import ai.antigem.data.local.chroot.ChrootDnsMode
import ai.antigem.data.local.chroot.ChrootExitBehavior
import ai.antigem.data.local.chroot.ChrootInstall
import ai.antigem.data.local.chroot.ChrootInstaller
import ai.antigem.data.local.chroot.ChrootManager
import ai.antigem.data.local.chroot.ChrootNsMode
import ai.antigem.data.local.chroot.ChrootPackageBundle
import ai.antigem.data.local.chroot.ChrootScripts
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.theme.AppColors
import ai.antigem.theme.ClaudeTerracotta
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ===================================================================== Where agents run: the phone's two runtimes

/** Where agents run → This phone · Ubuntu chroot: the installs (which one is used), Manage, Install or find. */
@Composable
fun ChrootRuntimeCard(
    cardBg: Color,
    cardBorder: BorderStroke,
    onManage: (String) -> Unit,
    /** A chroot was installed, adopted or picked in the setup page (it is the one in use now). */
    onInstalled: (ChrootInstall) -> Unit
) {
    val context = LocalContext.current
    val installs by ChrootManager.installs.collectAsState()
    val activePath by ChrootManager.activePath.collectAsState()
    val found by ChrootManager.found.collectAsState()
    var showSetup by remember { mutableStateOf(false) }
    // the state of each install (running, mounted, stopped): one quick root call
    LaunchedEffect(installs.size) { if (installs.isNotEmpty()) ChrootManager.scan(context) }
    if (showSetup) {
        ChrootSetupDialog(onDismiss = { showSetup = false }) { inst ->
            showSetup = false
            onInstalled(inst)
        }
    }
    ChrootSection("Ubuntu chroot", when {
            // the normal build has no Linux bridge: the setup page offers the chroot build
            !ai.antigem.BuildConfig.HAS_CHROOT -> "需要一台已 root 的手机，以及 AntiGem 的 chroot 版本（同一个 App，带有 chroot 的桥接，覆盖安装在本版本之上）"
            installs.isEmpty() -> "尚未安装：需要一台已 root 的手机（Magisk、KernelSU 或 APatch）"
            else -> "通过 root 运行真正的 Ubuntu（apt、glibc、sudo）。点按任一项即可管理：状态、挂载、文件夹、软件包"
        },
        Icons.Outlined.Storage, AppColors.success, cardBg, cardBorder) {
        installs.forEach { inst ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (installs.size > 1) {
                    androidx.compose.material3.RadioButton(
                        selected = inst.path == activePath,
                        onClick = { ChrootManager.setSelected(context, inst.path) },
                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                    )
                }
                Column(Modifier.weight(1f).clickable { onManage(inst.path) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(inst.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                        val f = found?.firstOrNull { it.path == inst.path }
                        when {
                            f == null -> Unit
                            f.running -> { Spacer(Modifier.width(6.dp)); ChrootTag("运行中", AppColors.success) }
                            f.mounted -> { Spacer(Modifier.width(6.dp)); ChrootTag("已挂载，无进程运行", AppColors.warning) }
                            else -> { Spacer(Modifier.width(6.dp)); ChrootTag("已停止", AppColors.textSecondary) }
                        }
                        if (inst.path == activePath && AuthPreferences(context).isChrootModeSync()) { Spacer(Modifier.width(6.dp)); ChrootTag("使用中", AppColors.info) }
                        if (inst.legacy) { Spacer(Modifier.width(6.dp)); ChrootTag("第一版", AppColors.warning) }
                    }
                    Text("${inst.os} · ${inst.user}", fontSize = 11.5.sp, color = AppColors.textSecondary)
                    Text(inst.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary)
                }
                TextButton(onClick = { onManage(inst.path) }) { Text("管理 ›", color = ClaudeTerracotta, fontSize = 12.5.sp) }
            }
            HorizontalDivider(color = AppColors.border.copy(alpha = 0.35f))
        }
        if (installs.size > 1) ChrootNote("将使用选中的这一项。更改在桥接重启后生效（保存，或下方重启）。")
        Spacer(Modifier.height(6.dp))
        Button(onClick = { showSetup = true }, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
            Text(when {
                !ai.antigem.BuildConfig.HAS_CHROOT -> "获取 chroot 版本"
                installs.isEmpty() -> "安装或查找 Ubuntu chroot"
                else -> "安装另一个 / 查找已有的"
            })
        }
    }
}

/** Where agents run → This phone · Termux: installed or not, install, and the full app wipe. */
@Composable
fun TermuxRuntimeCard(cardBg: Color, cardBorder: BorderStroke, onInstall: () -> Unit, onFactoryReset: () -> Unit,
                      /** The install finished (the phone switched to Termux). */
                      onInstalled: () -> Unit = {}) {
    val context = LocalContext.current
    // follows the installer: the card shows the result as soon as an install finishes
    val installerState by LocalEnvironmentManager.installerState.collectAsState()
    val installed = remember(installerState) { LocalEnvironmentManager.isTermuxInstalled(context) }
    LaunchedEffect(installerState) {
        if (installerState is ai.antigem.data.local.LocalInstallerState.Success) onInstalled()
    }
    ChrootSection("AntiGem", if (installed) "AntiGem 内置的 Linux 环境，使用独立文件：无需 root，软件包用 pkg install 安装"
        else "未安装：AntiGem 内置的 Linux 环境（无需 root）",
        Icons.Outlined.Terminal, AppColors.accentIndigo, cardBg, cardBorder) {
        if (!installed) {
            Button(onClick = onInstall, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
                Text("安装 AntiGem 的环境")
            }
        } else {
            OutlinedButton(onClick = onFactoryReset, border = BorderStroke(1.dp, AppColors.danger.copy(alpha = 0.5f)), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Outlined.Delete, null, tint = AppColors.danger, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("删除所有应用数据（恢复出厂设置）…", color = AppColors.danger)
            }
            ChrootNote("清除 AntiGem 的全部内容：内置环境、主目录、聊天记录和设置。位于" +
                "${ChrootManager.BASE_DIR} 的 Ubuntu chroot 会保留（可在其专属页面删除）。")
        }
    }
}

// ===================================================================== Where agents run → Ubuntu chroot (its own page)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChrootManageScreen(
    path: String?,
    cardBg: Color,
    cardBorder: BorderStroke,
    onOpenTerminal: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installs by ChrootManager.installs.collectAsState()
    val activePath by ChrootManager.activePath.collectAsState()
    val install = installs.firstOrNull { it.path == (path ?: activePath) } ?: installs.firstOrNull()
    val status by ChrootManager.status.collectAsState()
    val task by ChrootManager.task.collectAsState()
    val probe by ChrootManager.probe.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var showProcesses by remember { mutableStateOf(false) }
    var confirmUnmount by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteText by remember { mutableStateOf("") }
    var deleteMounts by remember { mutableStateOf<Result<List<Pair<Int, String>>>?>(null) }
    var exitBehavior by remember { mutableStateOf(ChrootManager.exitBehavior(context)) }

    if (install == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("未安装任何 Ubuntu chroot。", color = AppColors.textSecondary)
        }
        return
    }
    val isActiveRuntime = AuthPreferences(context).isChrootModeSync() && install.path == activePath
    // this page's numbers (the shared ones may be of another install)
    val s = status?.takeIf { it.path == install.path }
    var usage by remember(install.path) { mutableStateOf<ai.antigem.data.local.chroot.ChrootUsage?>(null) }
    var usageBusy by remember(install.path) { mutableStateOf(false) }

    // backups: a file the user picks (one per format), a folder for the clean image, restore through the setup page
    var showRestore by remember { mutableStateOf(false) }
    var backupFormat by remember { mutableStateOf(ChrootManager.BackupFormat.GZIP) }
    fun startBackup(uri: android.net.Uri, format: ChrootManager.BackupFormat) {
        ChrootManager.runTask(context, "正在备份 ${install.name}") { log ->
            ChrootManager.backup(context, install, uri, format, clean = false, totalKbHint = usage?.totalKb, onLine = log)
        }
    }
    val gzLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(ChrootManager.BackupFormat.GZIP.mime)
    ) { uri -> if (uri != null) startBackup(uri, ChrootManager.BackupFormat.GZIP) }
    val xzLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(ChrootManager.BackupFormat.XZ.mime)
    ) { uri -> if (uri != null) startBackup(uri, ChrootManager.BackupFormat.XZ) }
    val imageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        if (tree != null) ChrootManager.runTask(context, "正在导出干净镜像") { log ->
            ChrootManager.exportCleanImage(context, install, tree, usage?.totalKb, log)
        }
    }
    if (showRestore) {
        ChrootSetupDialog(onDismiss = { showRestore = false }, restore = true) { inst ->
            showRestore = false
            message = "已恢复为 ${inst.name}：在“智能体运行位置”中选择它即可使用。" to false
        }
    }

    fun measure() {
        if (usageBusy) return
        usageBusy = true
        scope.launch { usage = ChrootManager.refreshUsage(context, install); usageBusy = false }
    }

    LaunchedEffect(install.path) {
        ChrootManager.refreshStatus(context, install)
        if (probe == null) ChrootManager.probe(context)
        measure()
    }

    fun action(label: String, block: suspend () -> ChrootManager.SuResult?) {
        if (busy != null) return
        busy = label
        message = null
        scope.launch {
            val r = try { block() } catch (e: Exception) { ChrootManager.SuResult(-1, emptyList(), e.message ?: "$e") }
            busy = null
            if (r != null) message = (if (r.ok) "$label：完成" else "$label 失败：${r.message()}") to !r.ok
            ChrootManager.refreshStatus(context, install)
        }
    }

    /** After a start: the bridge comes up in the background, so the page follows it for a while. */
    suspend fun followBridge(): ChrootManager.SuResult {
        repeat(16) {
            kotlinx.coroutines.delay(1500)
            val st = ChrootManager.refreshStatus(context, install)
            if (st?.bridgePid != null) return ChrootManager.SuResult(0, emptyList(), "")
        }
        return ChrootManager.SuResult(1, emptyList(), "桥接未能启动：请查看“设置 → 智能体运行位置 → 服务器控制”，或查看其日志")
    }

    // ---------------- dialogs
    confirmUnmount?.let { mp ->
        AlertDialog(
            onDismissRequest = { confirmUnmount = null },
            title = { Text("取消挂载？") },
            text = { Text("$mp\n\nchroot 中正在使用它的程序可能停止工作，直到 chroot 重启。") },
            confirmButton = { Button(onClick = { confirmUnmount = null; action("取消挂载") { ChrootManager.unmount(context, mp, install) } },
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)) { Text("取消挂载") } },
            dismissButton = { TextButton(onClick = { confirmUnmount = null }) { Text("取消") } }
        )
    }
    if (confirmDelete) {
        LaunchedEffect(Unit) {
            deleteMounts = null
            deleteMounts = ChrootManager.mountsEverywhere(context, install)
        }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${install.name}？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("将删除 ${install.path} 中的全部内容：用户主目录、已安装的软件包、智能体登录信息，以及在其中进行的聊天" +
                        (usage?.let { "（${formatKb(it.totalKb)}）" } ?: "") + "。", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("当前其中已挂载的内容（手机的所有命名空间）：", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    when (val m = deleteMounts) {
                        null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                            Spacer(Modifier.width(8.dp))
                            Text("检查中…", fontSize = 12.sp, color = AppColors.textSecondary)
                        }
                        else -> m.fold(
                            onSuccess = { list ->
                                if (list.isEmpty()) Text("没有。", fontSize = 12.sp, color = AppColors.success)
                                list.forEach { (pid, mp) ->
                                    Text("• ${mp.removePrefix(install.path).ifEmpty { "/" }}" + if (pid == 1) "（Android 的命名空间）" else "（进程 $pid 的命名空间）",
                                        fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = AppColors.warning)
                                }
                                if (list.isNotEmpty()) ChrootNote("会先取消挂载。它们所展示的内容（手机存储、/system、共享文件夹）绝不会被删除：" +
                                    "只要仍有挂载，删除就会被拒绝，且绝不会跨入其他文件系统。")
                            },
                            onFailure = { Text("无法检查：${it.message}", fontSize = 12.sp, color = AppColors.danger) }
                        )
                    }
                    if (isActiveRuntime) ChrootNote("它正在使用中：桥接和智能体会停止，手机切回 AntiGem 的内置环境。删除完成后 AntiGem 会重新打开。", AppColors.warning)
                    Spacer(Modifier.height(10.dp))
                    Text("输入 ${install.name} 以确认：", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(deleteText, { deleteText = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        val wasActive = isActiveRuntime
                        ChrootManager.runTask(context, "正在删除 ${install.name}") { log ->
                            if (wasActive) LocalServerManager.stopServerCompletely()
                            ChrootManager.delete(context, install, log).also { r ->
                                // the phone is back on AntiGem's environment: reopen so everything starts there
                                if (r.ok && wasActive) ChrootManager.reopenApp(context)
                                else if (r.ok) scope.launch { onDeleted() }
                            }
                        }
                    },
                    enabled = deleteText.trim() == install.name && deleteMounts != null,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.danger)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
    task?.let { t ->
        AlertDialog(
            onDismissRequest = { if (!t.running) ChrootManager.clearTask() },
            title = { Text(t.title) },
            text = {
                Column {
                    if (t.running) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                        Spacer(Modifier.width(8.dp))
                        Text("运行中…", fontSize = 12.5.sp)
                    } else Text(if (t.exitCode == 0) "已完成" else "失败（退出码 ${t.exitCode}）",
                        color = if (t.exitCode == 0) AppColors.success else AppColors.danger, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 8.dp))
                    ChrootLog(t.lines, maxHeight = 360)
                }
            },
            confirmButton = { TextButton(onClick = { ChrootManager.clearTask() }, enabled = !t.running) { Text("关闭") } }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = chrootColumnSpacing) {
        // ---------------- overview: state, size, the main actions, delete
        val procs = s?.processCount ?: 0
        val mounted = s != null && s.error == null && (s.holderPid != null || s.mounts.isNotEmpty())
        val (stateText, stateColor) = when {
            s == null -> "检查中…" to AppColors.textSecondary
            s.error != null -> s.error to AppColors.danger
            procs > 0 -> "运行中 · $procs 个进程" + (s.sinceEpochSec?.let { " · 已运行 ${formatAge(it)}" } ?: "") to AppColors.success
            mounted -> "已挂载，无进程运行" to AppColors.warning
            else -> "已停止" to AppColors.textSecondary
        }
        ChrootSection(install.name, "${install.os} · ${install.arch} · 用户 ${install.user}" + if (install.uid >= 0) "（uid ${install.uid}）" else "",
            Icons.Outlined.Memory, stateColor, cardBg, cardBorder) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stateText, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = stateColor, modifier = Modifier.weight(1f, fill = false))
                if (isActiveRuntime) { Spacer(Modifier.width(8.dp)); ChrootTag("使用中", AppColors.success) }
                if (install.legacy) { Spacer(Modifier.width(8.dp)); ChrootTag("第一版", AppColors.warning) }
            }
            val free = runCatching { android.os.StatFs("/data").availableBytes }.getOrDefault(-1L)
            Text(
                (usage?.let { "占用 ${formatKb(it.totalKb)}" } ?: if (usageBusy) "正在测量大小…" else "大小未知") +
                    (if (free >= 0) " · /data 可用 ${formatBytes(free)}" else ""),
                fontSize = 12.5.sp, color = AppColors.onBackground, modifier = Modifier.padding(top = 2.dp)
            )
            if (isActiveRuntime && s != null && s.error == null) {
                Text(if (s.bridgePid != null) "桥接运行中（pid ${s.bridgePid}）" else "桥接未运行", fontSize = 12.sp,
                    color = if (s.bridgePid != null) AppColors.success else AppColors.textSecondary)
            }
            Text(install.path, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary)
            if (install.legacy) ChrootNote("由第一版 chroot 安装。通过“智能体运行位置 → 安装另一个 / 查找已有的 → 接管”，可让 " +
                "AntiGem 完整管理它（设置、AntiGem 的 uid），或在下方删除它。", AppColors.warning)
            message?.let { (text, isError) ->
                Text(text, fontSize = 12.sp, color = if (isError) AppColors.danger else AppColors.success, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (procs > 0 || mounted) {
                    Button(onClick = {
                        action("停止") {
                            // stops every process (the bridge too) and unmounts: quick, no waiting for the bridge
                            ChrootManager.stop(context, install).also { if (isActiveRuntime) LocalServerManager.onChrootStopped() }
                        }
                    }, enabled = busy == null, colors = ButtonDefaults.buttonColors(containerColor = AppColors.warning), shape = RoundedCornerShape(10.dp)) {
                        Text("停止")
                    }
                }
                if (procs == 0 || (isActiveRuntime && s?.bridgePid == null)) {
                    Button(onClick = {
                        action("启动") {
                            if (isActiveRuntime) {
                                // the server starts the chroot and its bridge
                                LocalServerManager.startServer(context, forceRestart = true)
                                followBridge()
                            } else {
                                val r = ChrootManager.start(context, install)
                                ChrootManager.SuResult(if (r.ok) 0 else 1, emptyList(), r.message)
                            }
                        }
                    }, enabled = busy == null, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("启动")
                    }
                }
                if (isActiveRuntime && s?.bridgePid != null) {
                    OutlinedButton(onClick = {
                        action("重启桥接") { LocalServerManager.restartServerAndWait(context); followBridge() }
                    }, enabled = busy == null, shape = RoundedCornerShape(10.dp)) { Text("重启桥接") }
                }
                if (isActiveRuntime) {
                    OutlinedButton(onClick = { LocalTerminalManager.createNewSession(context, sessionTitle = install.user); onOpenTerminal() },
                        shape = RoundedCornerShape(10.dp)) { Text("终端") }
                    OutlinedButton(onClick = {
                        LocalTerminalManager.createNewSession(context, initialCommand = "sudo -i", sessionTitle = "root"); onOpenTerminal()
                    }, shape = RoundedCornerShape(10.dp)) { Text("root 终端") }
                }
                TextButton(onClick = { action("刷新") { ChrootManager.refreshStatus(context, install); null } }, enabled = busy == null) {
                    Text("刷新", color = ClaudeTerracotta)
                }
            }
            if (busy != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                Spacer(Modifier.width(8.dp))
                Text("$busy…", fontSize = 12.sp, color = AppColors.textSecondary)
            }
            HorizontalDivider(color = AppColors.border.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { deleteText = ""; confirmDelete = true }, enabled = busy == null,
                    border = BorderStroke(1.dp, AppColors.danger.copy(alpha = 0.5f)), shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Outlined.Delete, null, tint = AppColors.danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除此 Ubuntu…", color = AppColors.danger)
                }
                if (!isActiveRuntime) TextButton(onClick = { ChrootManager.forget(context, install.path); onDeleted() }) {
                    Text("移除记录（保留文件）", color = AppColors.textSecondary, fontSize = 12.5.sp)
                }
            }
        }

        // ---------------- mounts
        ChrootSection("挂载", "当前其中已挂载的内容" + (s?.let { if (it.holderPid != null) "（在其私有命名空间中）" else if (it.mounts.isNotEmpty()) "（在 Android 的命名空间中）" else "" } ?: ""),
            Icons.Outlined.ViewList, AppColors.accentIndigo, cardBg, cardBorder) {
            val mounts = s?.mounts.orEmpty()
            if (mounts.isEmpty()) Text("没有挂载任何内容。", fontSize = 12.5.sp, color = AppColors.textSecondary)
            mounts.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.mountPoint.removePrefix(install.path).ifEmpty { "/（rootfs 绑定）" }, fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                        Text("${m.fsType} · ${m.source}${if (m.readOnly) " · 只读" else ""}", fontSize = 11.sp, color = AppColors.textSecondary)
                    }
                    if (m.mountPoint != install.path) TextButton(onClick = { confirmUnmount = m.mountPoint }, enabled = busy == null) {
                        Text("取消挂载", color = AppColors.danger, fontSize = 12.sp)
                    }
                }
            }
        }

        // ---------------- settings (chroot.conf), shared folders included
        val conf = s?.config
        if (conf != null && s.error == null) ChrootConfigSection(install, conf, cardBg, cardBorder, busy == null) { newConf ->
            action("保存设置") { ChrootManager.saveConfig(context, install, newConf) }
        }

        // ---------------- storage details
        ChrootSection("存储", usage?.let { "共 ${formatKb(it.totalKb)}" } ?: "空间都用在哪里", Icons.Outlined.Storage, AppColors.info, cardBg, cardBorder) {
            val u = usage
            if (u != null && u.totalKb > 0) {
                val parts = listOf("home" to "主目录", "usr" to "程序（/usr）", "var/lib/apt/lists" to "apt 软件包列表",
                    "var/cache/apt" to "apt 下载缓存", "opt" to "/opt", "tmp" to "/tmp", "root" to "root 主目录")
                    .mapNotNull { (k, label) -> u.partsKb[k]?.takeIf { it > 0 }?.let { label to it } }
                val other = (u.totalKb - parts.sumOf { it.second }).coerceAtLeast(0)
                (parts + ("其他" to other)).sortedByDescending { it.second }.forEach { (label, kb) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row {
                            Text(label, fontSize = 12.sp, color = AppColors.onBackground, modifier = Modifier.weight(1f))
                            Text(formatKb(kb), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                        }
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (kb.toFloat() / u.totalKb).coerceIn(0f, 1f) }, color = AppColors.info,
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                        )
                    }
                }
            } else if (usageBusy) Text("测量中…", fontSize = 12.5.sp, color = AppColors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { measure() }, enabled = !usageBusy, shape = RoundedCornerShape(10.dp)) {
                    Text(if (usageBusy) "测量中…" else "重新测量")
                }
                OutlinedButton(onClick = {
                    ChrootManager.runTask(context, "正在清理 apt 缓存") { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.CLEAN_SCRIPT, log) }
                }, shape = RoundedCornerShape(10.dp)) { Text("清理 apt 缓存") }
            }
        }

        // ---------------- backup & restore
        ChrootSection("备份与恢复", "一个包含此 Ubuntu 全部内容的文件（保留所有者和权限）。它绝不包含" +
            "/sdcard、USB 驱动器、/proc 或共享文件夹。", Icons.Outlined.Backup, AppColors.info, cardBg, cardBorder) {
            ChrootManager.BackupFormat.entries.forEach { f ->
                ChrootRadioRow(f.label, null, backupFormat == f) { backupFormat = f }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    val fileName = "${install.name}-$date.${backupFormat.ext}"
                    if (backupFormat == ChrootManager.BackupFormat.GZIP) gzLauncher.launch(fileName) else xzLauncher.launch(fileName)
                }, enabled = task?.running != true, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
                    Text("备份…")
                }
                OutlinedButton(onClick = { showRestore = true }, shape = RoundedCornerShape(10.dp)) { Text("恢复备份…") }
            }
            ChrootNote("运行中也可以备份。恢复会把备份作为新副本安装（可选择其名称和文件夹）；它会保留" +
                "用户、主目录、设置和软件包。")
            if (mounted && s?.holderPid == null) ChrootNote("它的挂载位于 Android 命名空间中（全局模式或由旧启动器遗留）：备份前请先停止它。", AppColors.warning)
            HorizontalDivider(color = AppColors.border.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 10.dp))
            Text("干净镜像（用于分享）", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
            ChrootNote("不包含主目录、登录信息与历史记录、AntiGem 的文件、日志以及 apt 列表。选择一个文件夹：" +
                "会在其中写入 ${ai.antigem.data.local.chroot.ChrootInstaller.prebuiltAsset()} 及其 .sha256，供 \"AntiGem Ubuntu (prebuilt)\"源" +
                "下载的 GitHub 发布 ${ai.antigem.data.local.chroot.ChrootInstaller.PREBUILT_TAG} 直接使用。速度较慢（xz）：" +
                "需要几分钟。")
            OutlinedButton(onClick = { imageLauncher.launch(null) }, enabled = task?.running != true, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(top = 6.dp)) { Text("导出干净镜像…") }
        }

        // ---------------- packages
        ChrootPackagesSection(install, cardBg, cardBorder)

        // ---------------- user
        ChrootUserSection(install, cardBg, cardBorder)

        // ---------------- exit
        ChrootSection("AntiGem 退出时", "从菜单或通知中退出（退出对话框可以覆盖一次）。把 App 划掉不会停止任何东西。",
            Icons.Outlined.ExitToApp, AppColors.warning, cardBg, cardBorder) {
            ChrootExitBehavior.entries.forEach { b ->
                ChrootRadioRow(b.label, b.description, exitBehavior == b) {
                    exitBehavior = b
                    ChrootManager.setExitBehavior(context, b)
                }
            }
        }

        // ---------------- processes
        ChrootSection("进程", "其中有 $procs 个进程在运行", Icons.Outlined.Memory, AppColors.accentTeal, cardBg, cardBorder) {
            TextButton(onClick = { showProcesses = !showProcesses }) { Text(if (showProcesses) "隐藏" else "显示", color = ClaudeTerracotta) }
            if (showProcesses) s?.processes.orEmpty().forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.pid}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary, modifier = Modifier.width(56.dp))
                    Text(p.command, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.onBackground, maxLines = 2, modifier = Modifier.weight(1f))
                    TextButton(onClick = { action("结束 ${p.pid}") { ChrootManager.killProcess(context, p.pid, install) } }, enabled = busy == null) {
                        Text("结束", color = AppColors.danger, fontSize = 11.5.sp)
                    }
                }
            }
        }

        // ---------------- repair, about
        ChrootSection("修复", "重新配置网络组、用户（AntiGem 的 uid）、DNS 和桥接", Icons.Outlined.Build, AppColors.accentAlt, cardBg, cardBorder) {
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, "正在修复 ${install.name}") { log ->
                    val st = ChrootManager.start(context, install, withBridge = true, onLine = log)
                    if (!st.ok) return@runTask ChrootManager.SuResult(1, emptyList(), st.message)
                    log("== the bridge: ${if (st.bridgeUpdated) "updated" else "current"}")
                    ChrootManager.execAsRoot(context, install.path,
                        ChrootScripts.userScript(install.user, android.os.Process.myUid(), "", sudoNoPassword = true, keepSudoers = true), log)
                }
            }, shape = RoundedCornerShape(10.dp)) { Text("修复") }
        }
        ChrootSection("关于此手机", null, Icons.Outlined.Info, AppColors.textSecondary, cardBg, cardBorder) {
            val p = probe
            ChrootKeyValue("Root", p?.rootManager ?: "…")
            ChrootKeyValue("挂载工具", p?.let { if (it.canPrivateNs) "unshare + nsenter (${it.busybox ?: "toybox"})" else "不支持 unshare/nsenter" } ?: "…")
            ChrootKeyValue("SELinux", p?.selinux ?: "…")
            ChrootKeyValue("内核", p?.kernel ?: "…")
            ChrootKeyValue("来源", install.source)
            ChrootKeyValue("控制脚本", ChrootManager.ctlFile(context).absolutePath, mono = true)
        }
    }
}

@Composable
private fun ChrootConfigSection(install: ChrootInstall, conf: ChrootConfig, cardBg: Color, cardBorder: BorderStroke, enabled: Boolean, onSave: (ChrootConfig) -> Unit) {
    val context = LocalContext.current
    var sdcard by remember(conf) { mutableStateOf(conf.mountSdcard) }
    var sdcardRo by remember(conf) { mutableStateOf(conf.sdcardReadOnly) }
    var storage by remember(conf) { mutableStateOf(conf.mountStorage) }
    var dnsAuto by remember(conf) { mutableStateOf(conf.dnsMode == ChrootDnsMode.AUTO) }
    var dns by remember(conf) { mutableStateOf(conf.customDns.joinToString(", ").ifBlank { "1.1.1.1, 8.8.8.8" }) }
    var shm by remember(conf) { mutableStateOf(conf.shmMb.toString()) }
    var ns by remember(conf) { mutableStateOf(conf.nsMode) }
    var binds by remember(conf) { mutableStateOf(conf.binds) }
    val inApp = ChrootManager.isInAppStorage(context, install.path)
    val dnsList = dns.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    ChrootSection("设置", "保存到 rootfs（.antigem/chroot.conf）；挂载会在 chroot 下次启动时生效",
        Icons.Outlined.Settings, ClaudeTerracotta, cardBg, cardBorder) {
        ChrootSwitchRow("将手机存储挂载到 /sdcard", null, sdcard) { sdcard = it }
        if (sdcard) ChrootSwitchRow("只读 /sdcard", "chroot 中的程序无法修改或删除手机上的文件", sdcardRo) { sdcardRo = it }
        if (sdcard) ChrootSwitchRow("USB 驱动器和 SD 卡（/storage）",
            if (sdcardRo) "在 /sdcard 为只读时关闭（否则可通过 /storage 写入手机存储）"
            else "/storage/… 下的所有卷，也包括 chroot 运行时插入的驱动器",
            storage && !sdcardRo, enabled = !sdcardRo) { storage = it }
        ChrootNote("用于 OTG 上 fastboot / adb / 串口的 USB 设备：/dev/bus/usb，${install.user} 可使用（Android 的 usb 组；旧版安装请先运行一次修复）。")
        ChrootSwitchRow("自动 DNS", "每次启动都使用手机的 DNS 服务器", dnsAuto) { dnsAuto = it }
        if (!dnsAuto) OutlinedTextField(dns, { dns = it }, label = { Text("DNS 服务器") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(shm, { shm = it.filter(Char::isDigit).take(5) }, label = { Text("/dev/shm 大小（MB）") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        Spacer(Modifier.height(6.dp))
        Text("挂载命名空间", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
        ChrootRadioRow(ChrootNsMode.PRIVATE.label, "仅在 chroot 自身的命名空间内挂载：外部无法通过 rootfs 访问 /sdcard。之后插入的 USB 驱动器仍会出现。",
            ns == ChrootNsMode.PRIVATE) { ns = ChrootNsMode.PRIVATE }
        ChrootRadioRow(ChrootNsMode.GLOBAL.label, "挂载对整个手机可见，直到停止。高级选项；不适用于安装在 App 存储中的实例。",
            ns == ChrootNsMode.GLOBAL, enabled = !inApp) { ns = ChrootNsMode.GLOBAL }
        Spacer(Modifier.height(6.dp))
        ChrootFoldersEditor(user = install.user, binds = binds) { binds = it }
        val valid = dnsAuto || (dnsList.isNotEmpty() && dnsList.all { ChrootManager.isDnsServer(it) })
        Button(
            onClick = {
                onSave(conf.copy(
                    nsMode = ns, mountSdcard = sdcard, sdcardReadOnly = sdcardRo, mountStorage = storage,
                    shmMb = shm.toIntOrNull()?.coerceIn(16, 8192) ?: conf.shmMb,
                    dnsMode = if (dnsAuto) ChrootDnsMode.AUTO else ChrootDnsMode.CUSTOM,
                    customDns = if (dnsAuto) conf.customDns else dnsList, binds = binds
                ))
            },
            enabled = enabled && valid, colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
            shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(top = 8.dp)
        ) { Text("保存设置") }
        if (ns != conf.nsMode) ChrootNote("命名空间的更改会在“全部停止 → 启动”后生效。", AppColors.warning)
        if (binds != conf.binds) ChrootNote("先保存，再“全部停止 → 启动”：共享文件夹会在 chroot 启动时挂载。", AppColors.warning)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChrootPackagesSection(install: ChrootInstall, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    var bundles by remember { mutableStateOf(emptySet<ChrootPackageBundle>()) }
    var extra by remember { mutableStateOf("") }
    val names = extra.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
    ChrootSection("软件包", "chroot 内使用 apt（以 root 身份）", Icons.Outlined.Inventory2, AppColors.success, cardBg, cardBorder) {
        ChrootPackageBundle.entries.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(b in bundles, { on -> bundles = if (on) bundles + b else bundles - b },
                    colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta))
                Column(Modifier.weight(1f)) {
                    Text(b.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                    Text(b.description, fontSize = 11.sp, color = AppColors.textSecondary)
                }
            }
        }
        OutlinedTextField(extra, { extra = it }, label = { Text("软件包（以空格分隔）") }, modifier = Modifier.fillMaxWidth())
        val bad = names.filter { !ChrootScripts.isPackageName(it) }
        if (bad.isNotEmpty()) ChrootNote("不是有效的软件包名：${bad.joinToString(" ")}", AppColors.danger)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(
                onClick = {
                    val pkgs = bundles.flatMap { it.packages } + names
                    val node = bundles.any { it.nodeJs }
                    ChrootManager.runTask(context, "正在安装软件包") { log ->
                        ChrootManager.execAsRoot(context, install.path, ChrootScripts.packagesScript(pkgs, node, upgrade = false), log)
                    }
                    bundles = emptySet(); extra = ""
                },
                enabled = (bundles.isNotEmpty() || names.isNotEmpty()) && bad.isEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)
            ) { Text("安装") }
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, "正在更新 Ubuntu") { log ->
                    ChrootManager.execAsRoot(context, install.path, ChrootScripts.packagesScript(emptyList(), nodeJs = false, upgrade = true), log)
                }
            }, shape = RoundedCornerShape(10.dp)) { Text("更新并升级全部") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChrootUserSection(install: ChrootInstall, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    ChrootSection("用户 ${install.user}", "它的 uid 就是 AntiGem 自己的（${android.os.Process.myUid()}），因此它与 App 共享的文件同时属于两者",
        Icons.Outlined.Person, AppColors.accentPink, cardBg, cardBorder) {
        OutlinedTextField(password, { password = it.replace("\n", "") }, label = { Text("新密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = {
                val p = password
                password = ""
                ChrootManager.runTask(context, "正在修改密码") { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.passwordScript(install.user, p), log) }
            }, enabled = password.isNotBlank(), shape = RoundedCornerShape(10.dp)) { Text("设置密码") }
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, "设置免密码 sudo") { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.sudoScript(install.user, true), log) }
            }, shape = RoundedCornerShape(10.dp)) { Text("sudo：免密码") }
            OutlinedButton(onClick = {
                ChrootManager.runTask(context, "设置 sudo 需要密码") { log -> ChrootManager.execAsRoot(context, install.path, ChrootScripts.sudoScript(install.user, false), log) }
            }, shape = RoundedCornerShape(10.dp)) { Text("sudo：需要密码") }
        }
        ChrootNote("“sudo：需要密码”需要先设置密码。")
    }
}

private fun formatAge(sinceEpochSec: Long): String {
    val secs = (System.currentTimeMillis() / 1000 - sinceEpochSec).coerceAtLeast(0)
    return when {
        secs < 60 -> "${secs} 秒"
        secs < 3600 -> "${secs / 60} 分钟"
        secs < 86400 -> "${secs / 3600} 小时 ${(secs % 3600) / 60} 分钟"
        else -> "${secs / 86400} 天 ${(secs % 86400) / 3600} 小时"
    }
}

/** Shown instead of a Termux-only page while the Ubuntu chroot is the phone's runtime. */
@Composable
fun TermuxOnlyNotice(title: String, cardBg: Color, cardBorder: BorderStroke, note: String? = null, onOpenEnvironment: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ChrootSection("$title 依赖 AntiGem 的内置环境", "此手机当前运行的是 Ubuntu chroot。" +
            "$title 仍使用 AntiGem 内置环境的工具，因此暂时在此处不可用。", Icons.Outlined.Info, AppColors.warning, cardBg, cardBorder) {
            if (note != null) ChrootNote(note, AppColors.info)
            OutlinedButton(onClick = onOpenEnvironment, shape = RoundedCornerShape(10.dp)) { Text("智能体运行位置") }
        }
    }
}
