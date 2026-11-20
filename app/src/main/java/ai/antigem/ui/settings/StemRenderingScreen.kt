package ai.antigem.ui.settings

import ai.antigem.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.context.GlobalContext
import ai.antigem.data.render.RenderBundle
import ai.antigem.data.render.RenderComponent
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.MarkdownBlockView
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.parseMarkdownBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → STEM & diagrams: download the render bundle (one zip from the app's GitHub release), switch each kind
 * of block on or off, tell agents about them, and read the guide (its examples render live).
 */
@Composable
fun StemRenderingScreen() {
    val context = LocalContext.current
    remember { RenderBundle.init(context) }
    val installed by RenderBundle.installed.collectAsState()
    val status by RenderBundle.status.collectAsState()
    val active by RenderBundle.active.collectAsState()
    val tellAgents by RenderBundle.tellAgents.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var cacheTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(installed, cacheTick) { cacheBytes = withContext(Dispatchers.IO) { RenderBundle.cacheSizeBytes() } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow

    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "聊天学习笔记的扩展块：化学式、分子结构、函数图像、几何、物理仿真以及 " +
                "TikZ 图形（电路、受力分析图、光学）。全部在本机离线运行。",
            fontSize = 12.5.sp, lineHeight = 17.sp, color = muted
        )
        Spacer(Modifier.height(16.dp))

        // ----- the bundle -----
        Surface(shape = RoundedCornerShape(16.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.stem_rendering_screen_offline_bundle), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${RenderBundle.BUNDLE_TAG} · ${mb(RenderBundle.BUNDLE_SIZE)} download" + if (installed) " · installed" else "",
                            fontSize = 12.sp, color = if (installed) QuotaGreen else muted
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "SmilesDrawer、function-plot、JSXGraph、Matter.js、TikZJax（TeX 加 circuitikz、pgfplots、chemfig）以及 " +
                        "Mermaid，均为固定版本，来自 AntiGem 的 GitHub Release。下载后按固定校验和核对，再存入应用的私有存储。",
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                // heavy: warn before (and after) installing
                val warn = ai.antigem.theme.AppColors.warning
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(warn.copy(alpha = 0.12f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Outlined.Warning, null, tint = warn, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "比较吃手机性能。每个图表块都在独立的 WebView 中运行，TikZ 图形会在手机上跑 TeX" +
                            "（首次需要几秒并占用较多内存，且逐个执行；之后会缓存）。" +
                            "图表较多的聊天可能卡顿或滚动不畅，旧机型尤为明显。" +
                            "下方不用的项可以关掉。",
                        fontSize = 12.5.sp, lineHeight = 17.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
                when (val s = status) {
                    is RenderBundle.Status.Downloading -> {
                        val fraction = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.stem_rendering_screen_downloading_of, (mb(s.done)).toString(), (mb(s.total)).toString()), fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))
                            TextButton(onClick = { RenderBundle.cancel() }) { Text(stringResource(R.string.accounts_ui_cancel)) }
                        }
                    }
                    RenderBundle.Status.Extracting -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.stem_rendering_screen_unpacking), fontSize = 12.sp, color = muted)
                    }
                    else -> {
                        if (s is RenderBundle.Status.Failed) {
                            Text(s.message, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(6.dp))
                        }
                        if (installed) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.browser_screen_remove)) }
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = { RenderBundle.clearCache(); cacheTick++ },
                                    enabled = cacheBytes > 0
                                ) { Text(stringResource(R.string.stem_rendering_screen_clear_tikz_cache, (mb(cacheBytes)).toString())) }
                            }
                        } else {
                            Button(onClick = { RenderBundle.download(context) }) {
                                Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (s is RenderBundle.Status.Failed) stringResource(R.string.checkpoint_restore_sheet_try_again) else stringResource(R.string.stem_rendering_screen_download_bundle))
                            }
                        }
                    }
                }
            }
        }

        // ----- what to use -----
        Spacer(Modifier.height(22.dp))
        Text(stringResource(R.string.stem_rendering_screen_what_to_use), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "初始全部关闭。关闭时：该块会以代码形式显示，也不会告知智能体。" +
                if (installed) "" else " 下载离线包后即可开启依赖它的部分。",
            fontSize = 12.sp, color = muted
        )
        Spacer(Modifier.height(10.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                RenderComponent.entries.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.zhTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(c.zhSummary, fontSize = 12.sp, lineHeight = 16.sp, color = muted)
                            val note = when {
                                !c.fromBundle -> "${c.library} · 内置，无需下载"
                                !installed -> "${c.library} ${c.version} · 需要离线包"
                                else -> "${c.library} ${c.version}"
                            }
                            Text(note, fontSize = 11.5.sp, color = if (c in active) QuotaGreen else muted)
                        }
                        Spacer(Modifier.width(10.dp))
                        // bundle parts can be switched on only once the bundle is downloaded
                        Switch(
                            checked = c in active,
                            onCheckedChange = { RenderBundle.setEnabled(c, it) },
                            enabled = !c.fromBundle || installed
                        )
                    }
                }
            }
        }

        // ----- agents -----
        Spacer(Modifier.height(22.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.stem_rendering_screen_tell_agents_about_these_blocks), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.stem_rendering_screen_global_context_first_message_of_each_chat), fontSize = 12.sp, color = muted)
                    }
                    Switch(checked = tellAgents, onCheckedChange = { RenderBundle.setTellAgents(it) })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "简要列出本机可用的块及其确切版本，供智能体在回答中使用" +
                        (if (GlobalContext.runsOnPhone) "，并说明完整指南在本机的位置（仅在需要时读取）" else "") +
                        "。内容变化后聊天会重新获取。Jarvis 不会获取。",
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                TextButton(onClick = { showPrompt = !showPrompt }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (showPrompt) stringResource(R.string.global_context_screen_hide_the_text) else stringResource(R.string.global_context_screen_show_exactly_what_is_sent))
                }
                if (showPrompt) {
                    val text = remember(active, tellAgents, installed) {
                        if (!tellAgents) "(off: nothing is sent)"
                        else RenderBundle.promptSection(canReadFiles = GlobalContext.runsOnPhone) ?: "(nothing switched on)"
                    }
                    SelectionContainer {
                        Text(
                            text,
                            fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(10.dp)
                        )
                    }
                }
            }
        }

        // ----- guide -----
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = { showGuide = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.stem_rendering_screen_guide_syntax_and_live_examples))
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.stem_rendering_screen_remove_the_bundle)) },
            text = { Text(stringResource(R.string.stem_rendering_screen_blocks_that_need_it_show_as_code)) },
            confirmButton = {
                TextButton(onClick = { RenderBundle.remove(); confirmRemove = false; cacheTick++ }) { Text(stringResource(R.string.browser_screen_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.accounts_ui_cancel)) } }
        )
    }
    if (showGuide) StemGuideDialog(onDismiss = { showGuide = false })
}

private fun mb(bytes: Long): String = if (bytes <= 0) "0 MB" else "%.1f MB".format(bytes / 1_048_576.0)

/** The guide, drawn by the chat's own renderer: every example shows as it will in a chat. */
@Composable
private fun StemGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember { parseMarkdownBlocks(RenderBundle.guideText(context), emptyList()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, stringResource(R.string.accounts_ui_close)) }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.stem_rendering_screen_stem_diagrams_guide), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    items(blocks) { block -> MarkdownBlockView(block = block) }
                }
            }
        }
    }
}

/**
 * Labels shown in Settings → STEM & diagrams. [RenderComponent.title] and [RenderComponent.summary] stay untouched
 * on the enum: the ```lang markers, library names and versions are what the parser and the bundle key off.
 */
private val RenderComponent.zhTitle: String
    get() = when (this) {
        RenderComponent.CHEM -> "化学方程式"
        RenderComponent.SMILES -> "分子结构"
        RenderComponent.FUNCTION_PLOT -> "函数图像"
        RenderComponent.JSXGRAPH -> "交互几何"
        RenderComponent.MATTER -> "物理模拟"
        RenderComponent.TIKZ -> "TikZ、电路与插图"
        RenderComponent.MERMAID -> "离线 Mermaid"
    }

private val RenderComponent.zhSummary: String
    get() = when (this) {
        RenderComponent.CHEM -> "\\ce{…} 反应式与化学式，写在 \$…\$ 内，原生绘制"
        RenderComponent.SMILES -> "```smiles 块：由 SMILES 字符串绘制二维结构"
        RenderComponent.FUNCTION_PLOT -> "```functionplot 块：函数曲线、导数、切线、面积"
        RenderComponent.JSXGRAPH -> "```jsxgraph 块：可拖拽的几何图形、轨迹、图像"
        RenderComponent.MATTER -> "```matter 块：可交互的二维刚体模拟"
        RenderComponent.TIKZ -> "```tikz 块：TikZ、circuitikz、pgfplots、chemfig、tikz-cd（TeX 编译，缓存一次）"
        RenderComponent.MERMAID -> "Mermaid 图表使用内置副本，不再联网加载"
    }
