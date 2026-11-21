package ai.antigem.data.jarvis

import android.content.Context
import ai.antigem.data.local.LocalCommandResult
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.preferences.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Creates and compares task checkouts with the Git already installed in AntiGem's local environment. */
object JarvisWorktreeManager {
    data class Workspace(
        val repositoryRoot: String,
        val checkoutRoot: String,
        val workingDirectory: String,
        val branch: String,
        val baseCommit: String
    )

    data class Diff(val patch: String, val untrackedFiles: List<String>, val truncated: Boolean)

    private const val PATCH_PREVIEW_CHARS = 200_000
    private const val CHECKOUT_KEY_BYTES = 12
    private val commitId = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
    private val creationLocks = Array(32) { Mutex() }

    /** Report whether an existing local project can use worktree isolation. */
    suspend fun isGitRepository(context: Context, projectPath: String): Boolean = withContext(Dispatchers.IO) {
        val project = localProject(projectPath)
        val result = runGit(context, project, "rev-parse", "--is-inside-work-tree")
        when {
            result.exitCode == 0 && result.output == "true" -> true
            result.exitCode == 0 && result.output == "false" -> false
            // Git's repository-discovery error is distinct from a broken .git pointer or permission error.
            result.exitCode == 128 && result.output.startsWith("fatal: not a git repository (") -> false
            else -> error("无法检查该项目是否使用 Git：${gitError(result)}")
        }
    }

    /** Reusing a task ID for the same Git repository reopens its worktree and original comparison base. */
    suspend fun create(context: Context, projectPath: String, taskId: String): Workspace = withContext(Dispatchers.IO) {
        require(taskId.isNotBlank()) { "创建 Git worktree 需要任务 ID。" }
        val project = localProject(projectPath)
        val repository = File(git(context, project, "解析项目的 Git 根目录", "rev-parse", "--show-toplevel")).canonicalFile
        val commonDir = File(git(context, repository, "解析 Git 仓库", "rev-parse", "--path-format=absolute", "--git-common-dir")).canonicalFile
        val repoFolder = File(context.filesDir.canonicalFile, "jarvis-worktrees/${digest(commonDir.path)}")
        val taskKey = digest(taskId)
        val branch = "antigem/jarvis/$taskKey"
        val baseRef = "refs/antigem/jarvis/base/$taskKey"

        creationLocks[(taskKey.hashCode() ushr 1) % creationLocks.size].withLock {
            val worktreeRoot = File(repoFolder, taskKey)
            val worktree = worktreeRoot.canonicalFile
            check(worktree.path == worktreeRoot.absolutePath && !worktree.isInside(repository)) {
                "任务检出目录必须位于源项目之外，且不能是符号链接。"
            }
            val storedBase = resolveCommit(context, repository, baseRef)
            val existingBranch = resolveCommit(context, repository, "refs/heads/$branch")
            check(storedBase != null || (existingBranch == null && !worktree.exists())) {
                "已有 Git 分支或目录占用了任务 $taskId 的检出位置。"
            }
            val base = storedBase ?: git(context, repository, "读取项目当前提交。请先创建一个初始提交", "rev-parse", "--verify", "HEAD^{commit}")
            check(commitId.matches(base)) { "Git 返回的对比基准对任务 $taskId 无效。" }
            val relative = project.relativeTo(repository)
            if (relative.path.isNotEmpty()) {
                val type = git(context, repository, "在基准提交中查找项目目录", "cat-file", "-t", "$base:${relative.invariantSeparatorsPath}")
                check(type == "tree") { "所选项目必须是在基准提交中被跟踪的目录。" }
            }
            if (storedBase == null) {
                // The base survives process death before or after checkout, and remains pinned after new task commits.
                git(context, repository, "记录任务的 Git 基准提交", "update-ref", baseRef, base, "")
            }
            if (worktree.exists()) {
                checkWorktree(context, worktree, commonDir, branch)
            } else {
                check(repoFolder.mkdirs() || repoFolder.isDirectory) { "无法创建任务检出目录：${repoFolder.path}" }
                if (existingBranch == null) {
                    git(context, repository, "创建独立的 Git worktree", "worktree", "add", "-b", branch, "--", worktree.path, base)
                } else {
                    git(context, repository, "重新打开已有的任务分支", "worktree", "add", "--", worktree.path, branch)
                }
                checkWorktree(context, worktree, commonDir, branch)
            }
            val cwd = File(worktree, relative.path).canonicalFile
            check(cwd.isDirectory && cwd.isInside(worktree)) { "所选项目目录在任务检出中不存在。" }
            Workspace(repository.path, worktree.path, cwd.path, branch, base)
        }
    }

    /** Compares the current checkout with its original commit, including staged, unstaged and committed edits. */
    suspend fun diff(context: Context, workspace: Workspace): Diff = withContext(Dispatchers.IO) {
        require(commitId.matches(workspace.baseCommit)) { "该任务的 Git 对比基准无效。" }
        val checkout = File(workspace.checkoutRoot).canonicalFile
        val root = File(context.filesDir, "jarvis-worktrees").canonicalFile
        check(checkout != root && checkout.isInside(root) && checkout.isDirectory) { "该任务的 worktree 已不可用。" }
        val actualRoot = File(git(context, checkout, "查找任务 worktree", "rev-parse", "--show-toplevel")).canonicalFile
        check(actualRoot == checkout) { "该任务的检出已不再是 Git worktree。" }

        val preview = File.createTempFile("jarvis-diff-", ".patch", context.cacheDir)
        try {
            git(context, checkout, "将任务 worktree 与其基准提交对比", "diff", "--no-color", "--no-ext-diff",
                "--no-textconv", "--binary", "--output=${preview.path}", workspace.baseCommit, "--")
            val patch = preview.reader().use { reader ->
                val buffer = CharArray(PATCH_PREVIEW_CHARS + 1)
                var length = 0
                while (length < buffer.size) {
                    val count = reader.read(buffer, length, buffer.size - length)
                    if (count < 0) break
                    length += count
                }
                String(buffer, 0, length)
            }
            val untracked = git(context, checkout, "列出任务中未跟踪的文件", "ls-files", "--others", "--exclude-standard", "-z")
                .split('\u0000').filter { it.isNotEmpty() }
            Diff(patch.take(PATCH_PREVIEW_CHARS), untracked, patch.length > PATCH_PREVIEW_CHARS)
        } finally {
            preview.delete()
        }
    }

    private suspend fun checkWorktree(context: Context, worktree: File, commonDir: File, branch: String) {
        check(worktree.isDirectory) { "任务的 Git 检出不是目录：${worktree.path}" }
        val actualRoot = File(git(context, worktree, "检查任务 worktree 路径", "rev-parse", "--show-toplevel")).canonicalFile
        val actualCommon = File(git(context, worktree, "检查任务仓库", "rev-parse", "--path-format=absolute", "--git-common-dir")).canonicalFile
        val head = git(context, worktree, "检查任务分支", "symbolic-ref", "--quiet", "HEAD")
        check(actualRoot == worktree && actualCommon == commonDir && head == "refs/heads/$branch") {
            "任务检出指向了另一个 Git worktree 或分支：${worktree.path}"
        }
    }

    private suspend fun resolveCommit(context: Context, directory: File, ref: String): String? {
        val result = runGit(context, directory, "rev-parse", "--verify", "--quiet", "$ref^{commit}")
        if (result.exitCode == 1 && result.output.isEmpty()) return null
        check(result.exitCode == 0 && commitId.matches(result.output)) { "无法读取 Git 引用 $ref：${result.output.take(500)}" }
        return result.output
    }

    private suspend fun git(context: Context, directory: File, operation: String, vararg args: String): String {
        val result = runGit(context, directory, *args)
        check(result.exitCode == 0) { "$operation：${gitError(result)}" }
        // executeCommand trims output. For paths and NUL-separated names, keep the original whitespace.
        return result.output
    }

    private suspend fun runGit(context: Context, directory: File, vararg args: String): LocalCommandResult {
        val command = listOf("git", "--no-pager", "-c", "core.hooksPath=/dev/null", "-c", "submodule.recurse=false", "-C", directory.path) + args
        val output = StringBuilder()
        val result = LocalEnvironmentManager.executeCommand(
            command.joinToString(" ") { shellQuote(it) },
            context = context,
            customEnv = mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_NO_LAZY_FETCH" to "1", "LC_ALL" to "C"),
            timeoutSeconds = 120,
            onOutputLine = { output.appendLine(it) }
        )
        return if (result.exitCode == 0) result.copy(output = output.toString().removeSuffix("\n")) else result
    }

    private fun localProject(projectPath: String): File {
        check(!AuthPreferences.isRemoteEndpoint()) { "团队的 Git worktree 需要 AntiGem 的本地环境。" }
        val path = File(projectPath.removePrefix("file://"))
        require(path.isAbsolute && path.isDirectory) { "请选择一个已存在的本地项目目录：$projectPath" }
        return path.canonicalFile
    }

    private fun gitError(result: LocalCommandResult): String =
        result.output.trim().take(500).ifBlank { "Git 退出码为 ${result.exitCode}" }

    private fun File.isInside(parent: File): Boolean = this == parent || path.startsWith(parent.path.trimEnd('/') + "/")

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // Short path components also keep provider transcript paths derived from the cwd within filesystem limits.
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(CHECKOUT_KEY_BYTES).joinToString("") { "%02x".format(it) }
}
