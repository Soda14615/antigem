package ai.antigem.ui.settings.chroot

import ai.antigem.data.local.chroot.ChrootBind
import ai.antigem.data.local.chroot.ChrootConfig
import ai.antigem.data.local.chroot.ChrootDnsMode
import ai.antigem.data.local.chroot.ChrootFound
import ai.antigem.data.local.chroot.ChrootInstall
import ai.antigem.data.local.chroot.ChrootInstallRequest
import ai.antigem.data.local.chroot.ChrootInstallStep
import ai.antigem.data.local.chroot.ChrootInstaller
import ai.antigem.data.local.chroot.ChrootManager
import ai.antigem.data.local.chroot.ChrootNsMode
import ai.antigem.data.local.chroot.ChrootPackageBundle
import ai.antigem.data.local.chroot.ChrootScripts
import ai.antigem.data.local.chroot.ChrootSource
import ai.antigem.data.local.chroot.UbuntuRelease
import ai.antigem.theme.AppColors
import ai.antigem.theme.ClaudeTerracotta
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** The full-page Ubuntu chroot setup, shown over whatever opened it (first-launch installer, Settings). */
@Composable
fun ChrootSetupDialog(onDismiss: () -> Unit, restore: Boolean = false, onDone: (ChrootInstall) -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = AppColors.background) {
            Box(Modifier.systemBarsPadding().imePadding()) {
                // the normal build has no Linux bridge for the chroot: every way in (first-launch installer, Settings)
                // lands on the page that installs the chroot build instead
                if (ai.antigem.BuildConfig.HAS_CHROOT) ChrootSetupScreen(onClose = onDismiss, restore = restore, onDone = onDone)
                else ChrootBuildScreen(onClose = onDismiss)
            }
        }
    }
}

private enum class SourceChoice { PREBUILT, OFFICIAL_2404, OFFICIAL_2604, OFFICIAL_2204, URL, FILE }
private enum class LocationChoice { DEFAULT, APP, CUSTOM }

@Composable
fun ChrootSetupScreen(
    onClose: () -> Unit,
    /** Restoring a backup: a file from the phone, installed as a new copy next to the others (not switched to). */
    restore: Boolean = false,
    onDone: (ChrootInstall) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ChrootInstaller.state.collectAsState()
    val logs by ChrootInstaller.logs.collectAsState()
    val probe by ChrootManager.probe.collectAsState()
    val found by ChrootManager.found.collectAsState()
    val installs by ChrootManager.installs.collectAsState()

    var checking by remember { mutableStateOf(false) }
    var prebuiltAvailable by remember { mutableStateOf<Boolean?>(null) }

    var source by remember { mutableStateOf(if (restore) SourceChoice.FILE else SourceChoice.OFFICIAL_2404) }
    var url by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }

    var location by remember { mutableStateOf(LocationChoice.DEFAULT) }
    var name by remember { mutableStateOf(if (restore) "ubuntu-restored" else "ubuntu-24.04") }
    var nameEdited by remember { mutableStateOf(restore) }
    var customPath by remember { mutableStateOf("${ChrootManager.BASE_DIR}/ubuntu") }

    var user by remember { mutableStateOf("ubuntu") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var sudoNoPassword by remember { mutableStateOf(true) }

    var mountSdcard by remember { mutableStateOf(true) }
    var sdcardRo by remember { mutableStateOf(false) }
    var mountStorage by remember { mutableStateOf(true) }
    var dnsAuto by remember { mutableStateOf(true) }
    var dnsCustom by remember { mutableStateOf("1.1.1.1, 8.8.8.8") }
    var nsMode by remember { mutableStateOf(ChrootNsMode.PRIVATE) }
    var sharedFolders by remember { mutableStateOf(emptyList<ChrootBind>()) }

    var bundles by remember { mutableStateOf(setOf(ChrootPackageBundle.PYTHON, ChrootPackageBundle.NODE)) }
    var extraPackages by remember { mutableStateOf("") }

    var adoptTarget by remember { mutableStateOf<ChrootFound?>(null) }
    var adoptBusy by remember { mutableStateOf(false) }
    var adoptError by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileUri = uri
            fileName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()
            source = SourceChoice.FILE
        }
    }

    fun runCheck() {
        if (checking) return
        checking = true
        scope.launch {
            val p = ChrootManager.probe(context)
            if (p.rootOk) {
                ChrootManager.scan(context)
                if (!p.canPrivateNs) nsMode = ChrootNsMode.GLOBAL
            }
            checking = false
        }
    }

    // restoring: the backup file is the first thing to pick
    LaunchedEffect(Unit) { if (restore && fileUri == null) filePicker.launch(arrayOf("*/*")) }
    LaunchedEffect(Unit) {
        if (!state.running && state.result == null && state.error == null) ChrootInstaller.reset()
        runCheck()
        prebuiltAvailable = ChrootInstaller.prebuiltAvailable()
    }

    // the release decides the default name
    LaunchedEffect(source) {
        if (!nameEdited) {
            name = when (source) {
                SourceChoice.OFFICIAL_2604 -> "ubuntu-26.04"
                SourceChoice.OFFICIAL_2204 -> "ubuntu-22.04"
                SourceChoice.PREBUILT, SourceChoice.OFFICIAL_2404 -> "ubuntu-24.04"
                else -> "linux"
            }
        }
    }

    val path = when (location) {
        LocationChoice.DEFAULT -> "${ChrootManager.BASE_DIR}/$name"
        LocationChoice.APP -> ChrootManager.appStorageDir(context, name)
        LocationChoice.CUSTOM -> customPath.trim().trimEnd('/')
    }
    val inAppStorage = ChrootManager.isInAppStorage(context, path)

    // the screen stays on while installing
    // inside a Dialog the context is a wrapper: find the Activity behind it
    val activity = remember(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        c as? android.app.Activity
    }
    DisposableEffect(state.running) {
        val w = activity?.window
        if (state.running) w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val installing = state.running || state.result != null || state.error != null
    BackHandler { onClose() }

    Column(Modifier.fillMaxSize()) {
        // header
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.onBackground) }
            Column(Modifier.weight(1f)) {
                Text(if (restore) "还原 Ubuntu 备份" else "Ubuntu chroot（root）", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.onBackground)
                Text("在这台手机上运行真正的 Ubuntu，供桥接、智能体和终端使用", fontSize = 11.5.sp, color = AppColors.textSecondary)
            }
        }
        HorizontalDivider(color = AppColors.border.copy(alpha = 0.5f))

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = chrootColumnSpacing
        ) {
            if (installing) {
                InstallProgress(state.step, state.progress, state.detail, state.done, state.error, state.result != null, logs)
            } else {
                // ---------------- the phone
                ChrootSection("这台手机", "root、挂载工具与可用空间", Icons.Outlined.Search) {
                    when {
                        checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                            Spacer(Modifier.width(10.dp))
                            Text("正在检查（请接受 root 管理器的授权请求）…", fontSize = 12.5.sp, color = AppColors.onBackground)
                        }
                        probe == null -> Text("尚未检查", fontSize = 12.5.sp, color = AppColors.textSecondary)
                        probe?.rootOk != true -> {
                            Text(probe?.error ?: "没有 root 权限", fontSize = 12.5.sp, color = AppColors.danger)
                        }
                        else -> {
                            val p = probe!!
                            ChrootKeyValue("root 权限", p.rootManager ?: "已授予", AppColors.success)
                            ChrootKeyValue("CPU", "${p.machine ?: "?"} · 内核 ${p.kernel ?: "?"}")
                            ChrootKeyValue("SELinux", p.selinux ?: "?")
                            ChrootKeyValue("挂载工具", if (p.canPrivateNs) "unshare + nsenter (${p.busybox ?: "toybox"})" else "没有 unshare/nsenter：仅支持全局命名空间",
                                if (p.canPrivateNs) AppColors.success else AppColors.warning)
                            val free = runCatching { android.os.StatFs("/data").availableBytes }.getOrDefault(-1L)
                            if (free >= 0) ChrootKeyValue("/data 可用空间", formatBytes(free), if (free < (2L shl 30)) AppColors.warning else AppColors.onBackground)
                        }
                    }
                    if (!checking) {
                        TextButton(onClick = { runCheck() }) { Text(if (probe == null) "检查" else "重新检查", color = ClaudeTerracotta) }
                    }
                }

                // ---------------- installs already on the phone
                val foundList = found.orEmpty()
                if (foundList.isNotEmpty()) {
                    ChrootSection("在本机发现", "直接使用已有的安装，无需重新下载", Icons.Outlined.Storage) {
                        foundList.forEach { f ->
                            val known = installs.firstOrNull { it.path == f.path }
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(f.os.ifBlank { "Linux" }, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground, modifier = Modifier.weight(1f))
                                    if (f.running) ChrootTag("运行中", AppColors.success)
                                    else if (f.mounted) ChrootTag("已挂载，无运行进程", AppColors.warning)
                                    if (!f.hasManifest) { Spacer(Modifier.width(4.dp)); ChrootTag("尚未纳入 AntiGem", AppColors.warning) }
                                }
                                Text("${f.path} · ${f.arch}", fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    if (f.hasManifest) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val inst = known ?: f.toInstall()
                                                    ChrootManager.addOrUpdate(context, inst)
                                                    ChrootManager.activate(context, inst)
                                                    onDone(inst)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)
                                        ) { Text("使用此项", fontSize = 12.5.sp) }
                                    } else {
                                        Button(
                                            onClick = { adoptTarget = f; adoptError = null },
                                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)
                                        ) { Text("接管…", fontSize = 12.5.sp) }
                                    }
                                }
                                if (adoptTarget?.path == f.path) {
                                    AdoptPanel(f, adoptBusy, adoptError) { u ->
                                        adoptBusy = true
                                        adoptError = null
                                        scope.launch {
                                            val r = ChrootManager.adopt(context, f, u)
                                            adoptBusy = false
                                            r.onSuccess { inst ->
                                                ChrootManager.activate(context, inst)
                                                adoptTarget = null
                                                onDone(inst)
                                            }.onFailure { adoptError = it.message }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = AppColors.border.copy(alpha = 0.35f))
                        }
                    }
                }

                // ---------------- source
                ChrootSection("来源", "Ubuntu rootfs 从哪里来", Icons.Outlined.Cloud) {
                    ChrootRadioRow(
                        "AntiGem Ubuntu（预构建）",
                        "已预装 sudo、git、curl、Node.js、Python 和 Android 相关修复的 Ubuntu 24.04：起步最快。",
                        selected = source == SourceChoice.PREBUILT,
                        enabled = prebuiltAvailable == true,
                        tag = when (prebuiltAvailable) { true -> "推荐"; false -> "尚未发布"; null -> "检查中…" },
                        tagColor = if (prebuiltAvailable == true) AppColors.success else AppColors.textSecondary
                    ) { source = SourceChoice.PREBUILT }
                    ChrootRadioRow(UbuntuRelease.NOBLE.label, "来自 cdimage.ubuntu.com 的官方 Ubuntu Base（最新点版本，已核对校验和）· 约 30 MB",
                        source == SourceChoice.OFFICIAL_2404, tag = "LTS") { source = SourceChoice.OFFICIAL_2404 }
                    ChrootRadioRow(UbuntuRelease.RESOLUTE.label, "官方 Ubuntu Base，最新的 LTS", source == SourceChoice.OFFICIAL_2604) { source = SourceChoice.OFFICIAL_2604 }
                    ChrootRadioRow(UbuntuRelease.JAMMY.label, "官方 Ubuntu Base，较旧的 LTS", source == SourceChoice.OFFICIAL_2204) { source = SourceChoice.OFFICIAL_2204 }
                    ChrootRadioRow("其他 URL", "任意 rootfs 的 .tar.gz / .tar.xz（Debian、Kali、Arch ARM 等）", source == SourceChoice.URL) { source = SourceChoice.URL }
                    if (source == SourceChoice.URL) {
                        OutlinedTextField(url, { url = it }, label = { Text("rootfs URL") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(sha, { sha = it }, label = { Text("sha256（可选）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    }
                    ChrootRadioRow("本机上的文件", "AntiGem 备份（保留其用户、家目录、设置和软件包），或任意 rootfs 的 .tar.gz / .tar.xz", source == SourceChoice.FILE) {
                        if (fileUri == null) filePicker.launch(arrayOf("*/*")) else source = SourceChoice.FILE
                    }
                    if (source == SourceChoice.FILE) {
                        OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (fileUri == null) "选择文件" else fileName.ifBlank { "已选择" }, fontSize = 12.5.sp)
                        }
                    }
                }

                // ---------------- location
                ChrootSection("位置", "rootfs 需要 Linux 权限，因此存放在 /data（绝不会放在 /sdcard）", Icons.Outlined.Storage) {
                    ChrootRadioRow("${ChrootManager.BASE_DIR}/<name>", "卸载 AntiGem 或清除其数据后仍会保留，重装后也能重新找到。",
                        location == LocationChoice.DEFAULT, tag = "推荐") { location = LocationChoice.DEFAULT }
                    ChrootRadioRow("AntiGem 的应用存储", "随应用一起删除（卸载或清除数据）。仅使用私有挂载命名空间。",
                        location == LocationChoice.APP) { location = LocationChoice.APP; nsMode = ChrootNsMode.PRIVATE }
                    ChrootRadioRow("/data 上的其他文件夹", null, location == LocationChoice.CUSTOM) { location = LocationChoice.CUSTOM }
                    if (location == LocationChoice.CUSTOM) {
                        OutlinedTextField(customPath, { customPath = it }, label = { Text("文件夹") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    } else {
                        OutlinedTextField(name, { name = it.trim(); nameEdited = true }, label = { Text("名称") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    }
                    ChrootNote("安装到：$path")
                    val pathError = ChrootManager.validateRootPath(path)
                        ?: if (location != LocationChoice.CUSTOM && !ChrootManager.isInstallName(name)) "名称只能使用字母、数字、. _ 和 -。" else null
                    if (pathError != null) ChrootNote(pathError, AppColors.danger)
                    if (installs.any { it.path == path }) ChrootNote("该位置已有 AntiGem 安装，请换一个名称。", AppColors.danger)
                }

                // ---------------- user
                ChrootSection("用户", "桥接、智能体和终端所使用的普通用户（它会获得 AntiGem 自己的 uid）", Icons.Outlined.Person) {
                    if (source == SourceChoice.FILE) ChrootNote("还原 AntiGem 备份时会保留备份中的用户（及其设置）：这些字段仅用于其他 rootfs 文件。", AppColors.info)
                    OutlinedTextField(user, { user = it.trim().lowercase() }, label = { Text("用户名") }, singleLine = true,
                        isError = !ChrootManager.isUserName(user), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        password, { password = it.replace("\n", "") }, label = { Text("密码（可选）") }, singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                    )
                    ChrootSwitchRow("sudo 免密码", "智能体无需询问即可安装软件包（推荐在智能体场景使用）",
                        sudoNoPassword) { sudoNoPassword = it }
                    if (!sudoNoPassword && password.isBlank()) ChrootNote("没有密码时 sudo 将始终无法使用：请设置密码，或保持此项开启。", AppColors.danger)
                }

                // ---------------- options
                ChrootSection("选项", "这些都可以稍后在 chroot 自己的页面中修改", Icons.Outlined.Settings) {
                    ChrootSwitchRow("手机存储挂载到 /sdcard", "chroot 内可访问你的文件（Download、DCIM 等）", mountSdcard) { mountSdcard = it }
                    if (mountSdcard) ChrootSwitchRow("只读", "chroot 中的程序可以读取手机文件，但无法修改或删除", sdcardRo) { sdcardRo = it }
                    if (mountSdcard) ChrootSwitchRow("USB 存储设备与 SD 卡（/storage）",
                        if (sdcardRo) "在 /sdcard 为只读时关闭（否则手机存储会通过 /storage 变为可写）"
                        else "所有卷都挂载到 /storage/…，也包括 chroot 启动后插入的驱动器",
                        mountStorage && !sdcardRo, enabled = !sdcardRo) { mountStorage = it }
                    ChrootNote("USB 设备（fastboot、adb、通过 OTG 连接的串口适配器）始终可以在 /dev/bus/usb 访问；该用户已加入 Android 的 usb 用户组。")
                    ChrootSwitchRow("自动 DNS", "每次启动都使用手机的 DNS 服务器（否则使用自定义服务器）", dnsAuto) { dnsAuto = it }
                    if (!dnsAuto) {
                        OutlinedTextField(dnsCustom, { dnsCustom = it }, label = { Text("DNS 服务器（以逗号分隔）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("挂载命名空间", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                    ChrootRadioRow(ChrootNsMode.PRIVATE.label,
                        "chroot 的挂载点（/dev、/proc、/sdcard 等）只对它自己的进程存在。外部任何程序都无法通过 rootfs 访问你的文件，即使用 root 文件管理器去删除也不行。稍后插入的 USB 存储设备仍会出现。",
                        nsMode == ChrootNsMode.PRIVATE, enabled = probe?.canPrivateNs != false) { nsMode = ChrootNsMode.PRIVATE }
                    ChrootRadioRow(ChrootNsMode.GLOBAL.label,
                        "挂载结果对整台手机可见，直到停止（类似 Linux Deploy）。仅供高级用户：运行期间用其他工具删除 rootfs 文件夹，删除操作会落到 /sdcard 中。",
                        nsMode == ChrootNsMode.GLOBAL, enabled = !inAppStorage) { nsMode = ChrootNsMode.GLOBAL }
                    Spacer(Modifier.height(10.dp))
                    ChrootFoldersEditor(user = user, binds = sharedFolders) { sharedFolders = it }
                }

                // ---------------- packages
                ChrootSection("软件包", "rootfs 就绪后立即通过 apt 安装（之后还可以再添加）", Icons.Outlined.Inventory2) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Checkbox(true, null, enabled = false)
                        Column(Modifier.weight(1f)) {
                            Text(ChrootPackageBundle.ESSENTIALS.title + "（始终安装）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                            Text(ChrootPackageBundle.ESSENTIALS.description, fontSize = 11.sp, color = AppColors.textSecondary)
                        }
                    }
                    ChrootPackageBundle.entries.filter { it != ChrootPackageBundle.ESSENTIALS }.forEach { b ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Checkbox(b in bundles, { on -> bundles = if (on) bundles + b else bundles - b },
                                colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta))
                            Column(Modifier.weight(1f)) {
                                Text(b.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                                Text(b.description, fontSize = 11.sp, color = AppColors.textSecondary)
                            }
                        }
                    }
                    OutlinedTextField(extraPackages, { extraPackages = it }, label = { Text("更多软件包（以空格分隔）") },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                    val bad = extraPackages.split(Regex("[\\s,]+")).filter { it.isNotBlank() && !ChrootScripts.isPackageName(it) }
                    if (bad.isNotEmpty()) ChrootNote("以下不是软件包名：${bad.joinToString(" ")}", AppColors.danger)
                }
            }
        }

        // ---------------- actions
        HorizontalDivider(color = AppColors.border.copy(alpha = 0.5f))
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.result != null -> Button(
                    onClick = { val r = state.result!!; ChrootInstaller.reset(); onDone(r) },
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) { Text("完成", fontWeight = FontWeight.SemiBold) }
                state.running -> {
                    val canCancel = state.step == null || state.step!!.ordinal <= ChrootInstallStep.VERIFY.ordinal
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                            Text("后台继续")
                        }
                        if (canCancel) OutlinedButton(onClick = { ChrootInstaller.cancel() }, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(14.dp)) {
                            Text("取消", color = AppColors.danger)
                        }
                    }
                }
                state.error != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ChrootInstaller.retry(context) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)) { Text("重试") }
                    OutlinedButton(onClick = { ChrootInstaller.reset() }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                        Text("更改选项")
                    }
                }
                else -> {
                    val extra = extraPackages.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
                    val src: ChrootSource? = when (source) {
                        SourceChoice.PREBUILT -> ChrootSource.Prebuilt
                        SourceChoice.OFFICIAL_2404 -> ChrootSource.Official(UbuntuRelease.NOBLE)
                        SourceChoice.OFFICIAL_2604 -> ChrootSource.Official(UbuntuRelease.RESOLUTE)
                        SourceChoice.OFFICIAL_2204 -> ChrootSource.Official(UbuntuRelease.JAMMY)
                        SourceChoice.URL -> url.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { ChrootSource.Url(it, sha.trim().ifBlank { null }) }
                        SourceChoice.FILE -> fileUri?.let { ChrootSource.LocalFile(it, fileName) }
                    }
                    val dnsList = dnsCustom.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                    val problem = when {
                        probe?.rootOk != true -> "需要 root 权限（请在上方检查）。"
                        src == null -> if (source == SourceChoice.FILE) "请选择文件。" else "请输入 http(s) URL。"
                        ChrootManager.validateRootPath(path) != null -> ChrootManager.validateRootPath(path)
                        location != LocationChoice.CUSTOM && !ChrootManager.isInstallName(name) -> "请检查名称。"
                        installs.any { it.path == path } -> "该文件夹已是一份安装。"
                        !ChrootManager.isUserName(user) -> "请检查用户名。"
                        !sudoNoPassword && password.isBlank() -> "请设置密码，或保持 sudo 免密码。"
                        !dnsAuto && (dnsList.isEmpty() || dnsList.any { !ChrootManager.isDnsServer(it) }) -> "请检查 DNS 服务器。"
                        extra.any { !ChrootScripts.isPackageName(it) } -> "请检查软件包名称。"
                        else -> null
                    }
                    if (problem != null) Text(problem, fontSize = 12.sp, color = AppColors.warning)
                    Button(
                        onClick = {
                            // shared folders added for another user name follow the one chosen now
                            val binds = sharedFolders.map { b ->
                                val m = Regex("^/home/[^/]+(/.*)$").find(b.target)
                                if (m != null && !b.target.startsWith("/home/$user/")) b.copy(target = "/home/$user${m.groupValues[1]}") else b
                            }
                            ChrootInstaller.start(
                                context,
                                ChrootInstallRequest(
                                    source = src!!, path = path,
                                    name = if (location == LocationChoice.CUSTOM) path.substringAfterLast('/') else name,
                                    user = user, password = password, sudoNoPassword = sudoNoPassword,
                                    config = ChrootConfig(
                                        nsMode = nsMode, mountSdcard = mountSdcard, sdcardReadOnly = sdcardRo, mountStorage = mountStorage,
                                        dnsMode = if (dnsAuto) ChrootDnsMode.AUTO else ChrootDnsMode.CUSTOM,
                                        customDns = if (dnsAuto) emptyList() else dnsList, binds = binds
                                    ),
                                    bundles = bundles, extraPackages = extra,
                                    // a restored copy sits next to the others; switch to it in Where agents run
                                    activate = !restore
                                )
                            )
                        },
                        enabled = problem == null,
                        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) { Text("安装 Ubuntu chroot", fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                }
            }
        }
    }
}

@Composable
private fun AdoptPanel(found: ChrootFound, busy: Boolean, error: String?, onAdopt: (String) -> Unit) {
    var user by remember { mutableStateOf(found.legacyUser ?: "ubuntu") }
    Surface(shape = RoundedCornerShape(12.dp), color = AppColors.surface, border = BorderStroke(1.dp, AppColors.border.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("AntiGem 会把自身的清单和设置写入这个 rootfs，并让该用户获得 AntiGem 的 uid " +
                "（该用户家目录中的文件会随之迁移）。它正在运行的进程会先被停止。",
                fontSize = 11.5.sp, lineHeight = 15.sp, color = AppColors.textSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(user, { user = it.trim().lowercase() }, label = { Text("用户") }, singleLine = true,
                isError = !ChrootManager.isUserName(user), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
            if (error != null) ChrootNote(error, AppColors.danger)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onAdopt(user) }, enabled = !busy && ChrootManager.isUserName(user),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.onBackground)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "正在接管…" else "接管并使用", fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun InstallProgress(
    step: ChrootInstallStep?,
    progress: Float?,
    detail: String,
    done: Set<ChrootInstallStep>,
    error: String?,
    finished: Boolean,
    logs: List<String>
) {
    ChrootSection(
        when { finished -> "Ubuntu chroot 已就绪"; error != null -> "安装已停止"; else -> "正在安装…" },
        if (finished) "从现在起桥接和终端都会使用它（可在 设置 → 智能体运行位置 中切换）" else null,
        if (finished) Icons.Filled.CheckCircle else if (error != null) Icons.Filled.Error else Icons.Outlined.Inventory2,
        tint = if (finished) AppColors.success else if (error != null) AppColors.danger else ClaudeTerracotta
    ) {
        ChrootInstallStep.entries.forEach { s ->
            val isCurrent = s == step && !finished && error == null
            val isDone = s in done
            val failed = error != null && s == step
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        isDone -> Icon(Icons.Filled.CheckCircle, null, tint = AppColors.success, modifier = Modifier.size(18.dp))
                        failed -> Icon(Icons.Filled.Error, null, tint = AppColors.danger, modifier = Modifier.size(18.dp))
                        isCurrent -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                        else -> Text("·", color = AppColors.textSecondary)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(s.label, fontSize = 13.sp, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isDone || isCurrent) AppColors.onBackground else AppColors.textSecondary)
            }
            if (isCurrent) {
                if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = ClaudeTerracotta,
                    modifier = Modifier.fillMaxWidth().padding(start = 30.dp, top = 2.dp, bottom = 2.dp))
                if (detail.isNotBlank()) Text(detail, fontSize = 11.5.sp, color = AppColors.textSecondary, maxLines = 2,
                    modifier = Modifier.padding(start = 30.dp))
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, fontSize = 12.5.sp, lineHeight = 17.sp, color = AppColors.danger)
        }
    }
    ChrootSection("日志", null) { ChrootLog(logs, maxHeight = 320) }
}
