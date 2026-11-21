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
            else -> error("Could not check whether the project uses Git: ${gitError(result)}")
        }
    }

    /** Reusing a task ID for the same Git repository reopens its worktree and original comparison base. */
    suspend fun create(context: Context, projectPath: String, taskId: String): Workspace = withContext(Dispatchers.IO) {
        require(taskId.isNotBlank()) { "A task ID is required to create a Git worktree." }
        val project = localProject(projectPath)
        val repository = File(git(context, project, "Resolve the project's Git root", "rev-parse", "--show-toplevel")).canonicalFile
        val commonDir = File(git(context, repository, "Resolve the Git repository", "rev-parse", "--path-format=absolute", "--git-common-dir")).canonicalFile
        val repoFolder = File(context.filesDir.canonicalFile, "jarvis-worktrees/${digest(commonDir.path)}")
        val taskKey = digest(taskId)
        val branch = "antigem/jarvis/$taskKey"
        val baseRef = "refs/antigem/jarvis/base/$taskKey"

        creationLocks[(taskKey.hashCode() ushr 1) % creationLocks.size].withLock {
            val worktreeRoot = File(repoFolder, taskKey)
            val worktree = worktreeRoot.canonicalFile
            check(worktree.path == worktreeRoot.absolutePath && !worktree.isInside(repository)) {
                "The task checkout must be outside the source project and may not be a symbolic link."
            }
            val storedBase = resolveCommit(context, repository, baseRef)
            val existingBranch = resolveCommit(context, repository, "refs/heads/$branch")
            check(storedBase != null || (existingBranch == null && !worktree.exists())) {
                "A Git branch or directory already occupies the checkout for task $taskId."
            }
            val base = storedBase ?: git(context, repository, "Read the project's current commit. Make an initial commit first", "rev-parse", "--verify", "HEAD^{commit}")
            check(commitId.matches(base)) { "Git returned an invalid comparison base for task $taskId." }
            val relative = project.relativeTo(repository)
            if (relative.path.isNotEmpty()) {
                val type = git(context, repository, "Find the project directory at the base commit", "cat-file", "-t", "$base:${relative.invariantSeparatorsPath}")
                check(type == "tree") { "The selected project must be a directory tracked at the base commit." }
            }
            if (storedBase == null) {
                // The base survives process death before or after checkout, and remains pinned after new task commits.
                git(context, repository, "Record the task's Git base commit", "update-ref", baseRef, base, "")
            }
            if (worktree.exists()) {
                checkWorktree(context, worktree, commonDir, branch)
            } else {
                check(repoFolder.mkdirs() || repoFolder.isDirectory) { "Could not create the task checkout directory: ${repoFolder.path}" }
                if (existingBranch == null) {
                    git(context, repository, "Create an isolated Git worktree", "worktree", "add", "-b", branch, "--", worktree.path, base)
                } else {
                    git(context, repository, "Reopen the existing task branch", "worktree", "add", "--", worktree.path, branch)
                }
                checkWorktree(context, worktree, commonDir, branch)
            }
            val cwd = File(worktree, relative.path).canonicalFile
            check(cwd.isDirectory && cwd.isInside(worktree)) { "The selected project directory is missing from the task checkout." }
            Workspace(repository.path, worktree.path, cwd.path, branch, base)
        }
    }

    /** Compares the current checkout with its original commit, including staged, unstaged and committed edits. */
    suspend fun diff(context: Context, workspace: Workspace): Diff = withContext(Dispatchers.IO) {
        require(commitId.matches(workspace.baseCommit)) { "The task has an invalid Git comparison base." }
        val checkout = File(workspace.checkoutRoot).canonicalFile
        val root = File(context.filesDir, "jarvis-worktrees").canonicalFile
        check(checkout != root && checkout.isInside(root) && checkout.isDirectory) { "The task worktree is no longer available." }
        val actualRoot = File(git(context, checkout, "Find the task worktree", "rev-parse", "--show-toplevel")).canonicalFile
        check(actualRoot == checkout) { "The task checkout is no longer a Git worktree." }

        val preview = File.createTempFile("jarvis-diff-", ".patch", context.cacheDir)
        try {
            git(context, checkout, "Compare the task worktree with its base commit", "diff", "--no-color", "--no-ext-diff",
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
            val untracked = git(context, checkout, "List the task's untracked files", "ls-files", "--others", "--exclude-standard", "-z")
                .split('\u0000').filter { it.isNotEmpty() }
            Diff(patch.take(PATCH_PREVIEW_CHARS), untracked, patch.length > PATCH_PREVIEW_CHARS)
        } finally {
            preview.delete()
        }
    }

    private suspend fun checkWorktree(context: Context, worktree: File, commonDir: File, branch: String) {
        check(worktree.isDirectory) { "The task's Git checkout is not a directory: ${worktree.path}" }
        val actualRoot = File(git(context, worktree, "Check the task worktree path", "rev-parse", "--show-toplevel")).canonicalFile
        val actualCommon = File(git(context, worktree, "Check the task repository", "rev-parse", "--path-format=absolute", "--git-common-dir")).canonicalFile
        val head = git(context, worktree, "Check the task branch", "symbolic-ref", "--quiet", "HEAD")
        check(actualRoot == worktree && actualCommon == commonDir && head == "refs/heads/$branch") {
            "The task checkout points to a different Git worktree or branch: ${worktree.path}"
        }
    }

    private suspend fun resolveCommit(context: Context, directory: File, ref: String): String? {
        val result = runGit(context, directory, "rev-parse", "--verify", "--quiet", "$ref^{commit}")
        if (result.exitCode == 1 && result.output.isEmpty()) return null
        check(result.exitCode == 0 && commitId.matches(result.output)) { "Could not read Git ref $ref: ${result.output.take(500)}" }
        return result.output
    }

    private suspend fun git(context: Context, directory: File, operation: String, vararg args: String): String {
        val result = runGit(context, directory, *args)
        check(result.exitCode == 0) { "$operation: ${gitError(result)}" }
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
        check(!AuthPreferences.isRemoteEndpoint()) { "Git worktrees for teams require AntiGem's local environment." }
        val path = File(projectPath.removePrefix("file://"))
        require(path.isAbsolute && path.isDirectory) { "Choose an existing local project directory: $projectPath" }
        return path.canonicalFile
    }

    private fun gitError(result: LocalCommandResult): String =
        result.output.trim().take(500).ifBlank { "Git exited with ${result.exitCode}" }

    private fun File.isInside(parent: File): Boolean = this == parent || path.startsWith(parent.path.trimEnd('/') + "/")

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // Short path components also keep provider transcript paths derived from the cwd within filesystem limits.
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(CHECKOUT_KEY_BYTES).joinToString("") { "%02x".format(it) }
}
