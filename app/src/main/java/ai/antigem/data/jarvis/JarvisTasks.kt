package ai.antigem.data.jarvis

import android.app.Application
import android.content.Context
import android.util.Log
import ai.antigem.data.agent.ChatSessionStore
import ai.antigem.data.agent.claude.ClaudeBridgeClient
import ai.antigem.data.agent.claude.ClaudeChatBackend
import ai.antigem.data.remote.AgyHubClient
import ai.antigem.data.remote.PromptExpansion
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.MessageRole
import ai.antigem.domain.model.ToolCall
import ai.antigem.domain.model.ToolType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Jarvis as a manager: work the user hands over ("build the release and tell me when it's done") runs as a task in its
 * own chat. Every Claude task gets its own backend that stays attached to that chat, so its approvals, questions and
 * end are seen live even while other chats are on screen; Antigravity tasks are watched through the hub. Changes of a
 * task (finished, failed, stuck) are sent to [JarvisController.onTaskEvent], which tells the user or the Jarvis model.
 */
object JarvisTasks {
    private const val TAG = "JarvisTasks"
    // Keep running tasks regardless of count; retain recent terminal tasks separately.
    private const val KEEP_FINISHED = 30
    /** How much of a finished task's reply goes into its news (its end; the whole reply can be read with jarvis_read_chat). */
    const val RESULT_MAX = 3500
    /** A task that never showed as running within this time after a message is taken as finished. */
    private const val START_GRACE_MS = 25_000L

    enum class State(val label: String) {
        STARTING("starting"), RUNNING("running"), NEEDS_APPROVAL("waiting for approval"), NEEDS_ANSWER("asking a question"),
        STALLED("no progress"), DONE("done"), FAILED("failed"), STOPPED("stopped");

        val active: Boolean get() = this == STARTING || this == RUNNING || this == NEEDS_APPROVAL || this == NEEDS_ANSWER || this == STALLED
        val needsUser: Boolean get() = this == NEEDS_APPROVAL || this == NEEDS_ANSWER
    }

    data class QueuedFollowUp(val id: String = UUID.randomUUID().toString(), val message: String, val interrupt: Boolean = false)

    data class Task(
        val id: String,
        val agent: AgentKind,
        val title: String,
        val goal: String,
        val project: String = "",
        val state: State = State.STARTING,
        val createdAt: Long = System.currentTimeMillis(),
        /** When the current message was sent (the start grace counts from here). */
        val turnAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        /** Last time the chat visibly moved (new text, a tool started or finished). */
        val progressAt: Long = System.currentTimeMillis(),
        val seenRunning: Boolean = false,
        val followUps: Int = 0,
        /** One line about the latest change, for the panel ("Finished", "Wants to run: npm test"). */
        val note: String = "",
        /** The task's last reply (cut to its end when long), once it finished. */
        val result: String = "",
        /** Length of the whole last reply ([result] is cut when this is longer). */
        val resultChars: Int = 0,
        /** The current state was already reported (no repeat). */
        val reported: Boolean = true,
        /** An existing chat the user named, not one Jarvis started. */
        val adopted: Boolean = false,
        /** The Jarvis manager that started it and gets its news: [JarvisManagers.VOICE] or a Jarvis text chat's id. */
        val owner: String = JarvisManagers.VOICE,
        /** The manager's team that works on it (empty: none). */
        val team: String = "",
        /** Isolated checkout metadata. A null workspace means the task uses the shared project directory. */
        val workspace: JarvisWorktreeManager.Workspace? = null,
        /** User or manager follow-ups queued behind the current provider turn. */
        val queuedFollowUps: List<QueuedFollowUp> = emptyList(),
        val queuedRetryAt: Long = 0L,
        val queuedAttempts: Int = 0,
        val followUpInFlightId: String = "",
        val followUpInFlightAt: Long = 0L,
        val archived: Boolean = false
    ) {
        val active: Boolean get() = state.active
    }

    private lateinit var app: Application
    private var started = false
    private var prefs: android.content.SharedPreferences? = null

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    /** Newest first. */
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    private val _anyActive = MutableStateFlow(false)
    /** Some task is working (keeps the screen on, shows on the orb). */
    val anyActive: StateFlow<Boolean> = _anyActive.asStateFlow()

    /** Claude task chats → their own backend, attached to that chat. */
    private val claudeBackends = ConcurrentHashMap<String, ClaudeChatBackend>()
    /** OpenCode task chats → their own backend (follows that chat's events), like the Claude ones. */
    private val openCodeBackends = ConcurrentHashMap<String, ai.antigem.data.agent.opencode.OpenCodeChatBackend>()
    private val codexBackends = ConcurrentHashMap<String, ai.antigem.data.agent.codex.CodexChatBackend>()
    private val attachedAt = ConcurrentHashMap<String, Long>()
    private val taskSendLocks = ConcurrentHashMap<String, Mutex>()
    private val progressSig = ConcurrentHashMap<String, String>()
    /** Approvals waiting in Antigravity chats that are not on screen (tasks and the Jarvis chat), from the hub. */
    private val agyPending = ConcurrentHashMap<String, List<Pair<ToolCall, String>>>()
    private val agyCheckedAt = ConcurrentHashMap<String, Long>()
    private val hub by lazy { AgyHubClient() }
    private lateinit var scope: CoroutineScope

    private val vm get() = JarvisController.vm

    fun start(application: Application, s: CoroutineScope) {
        if (started) return
        started = true
        app = application
        scope = s
        prefs = application.getSharedPreferences("jarvis_tasks", Context.MODE_PRIVATE)
        load()
        scope.launch { loop() }
    }

    /** Replays terminal task notifications that were not durably enqueued before the previous process stopped. */
    fun replayUnreportedEvents() {
        if (!JarvisPreferences.enabled.value) return
        _tasks.value.filter { !it.reported && it.state in setOf(State.DONE, State.FAILED, State.STALLED) }.forEach { task ->
            val kind = when (task.state) {
                State.DONE -> "done"
                State.FAILED -> "failed"
                else -> "stalled"
            }
            val detail = when (task.state) {
                State.DONE -> task.result
                State.FAILED -> task.note.removePrefix("Failed:").trim()
                else -> task.note
            }
            JarvisController.onTaskEvent(task, kind, detail)
            val current = find(task.id)
            if (current != null && current.turnAt == task.turnAt && current.state == task.state && !current.reported) {
                put(current.copy(reported = true))
            }
        }
    }

    /** The task with [id], or the one whose id starts with it (models often shorten ids; at least 4 characters). */
    fun find(id: String?): Task? {
        val i = id?.trim().orEmpty()
        if (i.isEmpty()) return null
        val list = _tasks.value
        return list.firstOrNull { it.id == i } ?: if (i.length >= 4) list.firstOrNull { it.id.startsWith(i) } else null
    }
    fun isTask(id: String?): Boolean = id != null && _tasks.value.any { it.id == id }
    fun activeTasks(): List<Task> = _tasks.value.filter { it.active }
    /** The tasks of one Jarvis manager (newest first); a task whose Jarvis chat is gone belongs to the voice chat. */
    fun ownedBy(owner: String): List<Task> = _tasks.value.filter { JarvisManagers.ownerOrVoice(it.owner) == owner }

    /** Task backends Jarvis can read approvals and questions from. */
    fun claudeTaskBackends(): List<Pair<Task, ClaudeChatBackend>> =
        _tasks.value.filter { it.agent == AgentKind.CLAUDE }.mapNotNull { t -> claudeBackends[t.id]?.let { t to it } }

    fun backendOf(id: String): ClaudeChatBackend? = claudeBackends[id]

    /** OpenCode task backends Jarvis reads approvals from. */
    fun openCodeTaskBackends(): List<Pair<Task, ai.antigem.data.agent.opencode.OpenCodeChatBackend>> =
        _tasks.value.filter { it.agent == AgentKind.OPENCODE }.mapNotNull { t -> openCodeBackends[t.id]?.let { t to it } }

    fun openCodeBackendOf(id: String): ai.antigem.data.agent.opencode.OpenCodeChatBackend? = openCodeBackends[id]

    fun codexTaskBackends(): List<Pair<Task, ai.antigem.data.agent.codex.CodexChatBackend>> =
        _tasks.value.filter { it.agent == AgentKind.CODEX }.mapNotNull { t -> codexBackends[t.id]?.let { t to it } }

    fun codexBackendOf(id: String): ai.antigem.data.agent.codex.CodexChatBackend? = codexBackends[id]

    // ------------------------------------------------------------------ starting and steering

    private fun newClaudeBackend() = ClaudeChatBackend(app, scope, ChatSessionStore(), vm.claudePrefs, ClaudeBridgeClient())

    /**
     * A backend for one OpenCode task chat. It never changes the user's OpenCode defaults, and OpenCode's question form
     * is denied: nobody sees a task's chat, so the agent asks in its reply instead (Jarvis reads that out).
     */
    private fun newOpenCodeBackend() = ai.antigem.data.agent.opencode.OpenCodeChatBackend(
        app, scope, ChatSessionStore(), vm.openCodePrefs, ai.antigem.data.agent.opencode.OpenCodeBridgeClient(),
        rememberChoices = false,
        extraRules = listOf(kotlinx.serialization.json.buildJsonObject {
            put("action", kotlinx.serialization.json.JsonPrimitive("question"))
            put("resource", kotlinx.serialization.json.JsonPrimitive("*"))
            put("effect", kotlinx.serialization.json.JsonPrimitive("deny"))
        })
    ).also { it.start() }

    /**
     * Starts [goal] in a new chat of [agent]. [projectPath] null = the active project. [owner] gets the task's news,
     * [brief] goes hidden in front of the goal (how a sub-agent of Jarvis works, the team's role). Returns the task.
     */
    suspend fun start(
        agent: AgentKind, goal: String, title: String?, modelId: String?, projectPath: String?,
        owner: String = JarvisManagers.VOICE, team: String = "", brief: String? = null,
        /** Reasoning effort for Claude Code / Codex (already checked against the model); null = the model's default. */
        effort: String? = null,
        childProfiles: List<JarvisManagers.Team> = emptyList(),
        workspaceIsolation: String = "auto"
    ): Task {
        val text = goal.trim()
        require(text.isNotEmpty()) { "The task needs a goal." }
        val isolation = workspaceIsolation.trim().lowercase().ifBlank { "auto" }
        require(isolation in setOf("auto", "shared", "worktree")) { "Workspace isolation must be auto, shared or worktree." }
        val launchId = UUID.randomUUID().toString()
        val workspace = when {
            isolation == "shared" -> null
            projectPath.isNullOrBlank() -> {
                require(isolation != "worktree") { "Select a local Git project before requesting a task worktree." }
                null
            }
            isolation == "auto" && ai.antigem.data.preferences.AuthPreferences.isRemoteEndpoint() -> null
            isolation == "auto" && !JarvisWorktreeManager.isGitRepository(app, projectPath) -> null
            else -> JarvisWorktreeManager.create(app, projectPath, launchId)
        }
        val effectiveProjectPath = workspace?.workingDirectory ?: projectPath
        var created: Task? = null
        fun prepare(id: String): String {
            val task = Task(id = id, agent = agent,
                title = title?.trim()?.takeIf { it.isNotEmpty() }?.take(60) ?: shortTitle(text),
                goal = text.take(600), project = projectPath.orEmpty(), note = "Started",
                owner = JarvisManagers.ownerOrVoice(owner), team = team.trim(), workspace = workspace)
            if (childProfiles.isNotEmpty()) JarvisManagers.registerDelegated(id, agent, task.owner, childProfiles, text)
            created = task
            put(task, durable = true)
            val hiddenNote = listOfNotNull(brief?.takeIf { it.isNotBlank() },
                JarvisManagers.delegatedNote(id).takeIf { it.isNotBlank() },
                ai.antigem.data.context.GlobalContext.taskBody()).joinToString("\n\n")
            return (if (hiddenNote.isNotBlank()) PromptExpansion.hidden(hiddenNote) else "") + PromptExpansion.expand(text)
        }
        try {
        val id = when (agent) {
            AgentKind.CODEX -> {
                val b = newCodexBackend()
                if (b.state.value.isBlocking) b.refreshStatus()
                if (b.state.value.isBlocking) { b.stop(); error("Codex is not available: ${b.state.value.label}.") }
                // Codex gives the chat its id when it is created: create it now so the task knows it
                val id = runCatching { b.createChatNow(effectiveProjectPath, modelId) }.getOrElse { b.stop(); throw it }
                effort?.let { b.selectEffort(it) }
                codexBackends[id] = b
                attachedAt[id] = System.currentTimeMillis()
                b.sendMessage(prepare(id))
                scope.launch {
                    delay(4000)
                    runCatching { vm.codexBackend.refreshThreads() }
                    if (!title.isNullOrBlank()) {
                        delay(4000)
                        runCatching { b.renameConversation(id, title.trim().take(60)) }
                    }
                }
                id
            }
            AgentKind.OPENCODE -> {
                val b = newOpenCodeBackend()
                if (b.state.value.isBlocking) b.refreshStatus()
                if (b.state.value.isBlocking) { b.stop(); error("OpenCode is not available: ${b.state.value.label}.") }
                b.startNewChat()
                delay(150)
                val id = b.currentConversation.value?.id ?: run { b.stop(); error("Could not create the chat") }
                effectiveProjectPath?.let { b.setWorkspaceOfNewChat(it) }
                modelId?.takeIf { it.isNotBlank() }?.let { b.selectModel(it) }
                openCodeBackends[id] = b
                attachedAt[id] = System.currentTimeMillis()
                b.sendMessage(prepare(id))
                scope.launch {
                    delay(4000)
                    runCatching { vm.openCodeBackend.refreshSessions() }
                    if (!title.isNullOrBlank()) {
                        delay(4000)
                        runCatching { b.renameConversation(id, title.trim().take(60)) }
                    }
                }
                id
            }
            AgentKind.CLAUDE -> {
                val b = newClaudeBackend()
                b.startNewChat()
                delay(150)
                val id = b.currentConversation.value?.id ?: error("Could not create the chat")
                effectiveProjectPath?.let { b.setWorkspaceOfNewChat(it) }
                modelId?.takeIf { it.isNotBlank() }?.let { b.selectModel(it) }
                effort?.let { b.setEffort(it) }
                claudeBackends[id] = b
                attachedAt[id] = System.currentTimeMillis()
                b.sendMessage(prepare(id))
                scope.launch {
                    delay(4000)
                    runCatching { vm.claudeBackend.refreshSessions() }
                    if (!title.isNullOrBlank()) {
                        delay(4000)
                        runCatching { b.renameConversation(id, title.trim().take(60)) }
                    }
                }
                id
            }
            AgentKind.AGY -> {
                val id = UUID.randomUUID().toString()
                val chosen = modelId?.takeIf { it.isNotBlank() } ?: vm.agyBackend.selectedModelId.value
                val enum = AgyHubClient.resolveModelEnum(chosen)
                val ws = effectiveProjectPath ?: (ai.antigem.data.daemon.IdeDaemonManager.activeProject.value?.path
                    ?: ai.antigem.data.local.LocalEnvironmentManager.getHomeDir(app).absolutePath)
                hub.startCascade(id, enum, if (ws.startsWith("file://")) ws else "file://$ws").getOrThrow()
                vm.agyBackend.rememberChatModel(id, chosen)
                hub.sendUserPrompt(id, prepare(id), enum).getOrThrow()
                scope.launch { delay(2500); runCatching { vm.agyBackend.syncAgyConversations(force = true) } }
                id
            }
        }
        return checkNotNull(created).also { check(it.id == id) }
        } catch (e: Throwable) {
            created?.let { task ->
                if (task.agent == AgentKind.AGY) runCatching { hub.cancelCascadeInvocation(task.id) }
                release(task.id)
                put(task.copy(state = State.FAILED, note = "Launch failed: ${e.message.orEmpty().take(160)}", reported = true))
                if (JarvisManagers.isDelegated(task.id)) {
                    JarvisManagers.remove(task.id)
                    JarvisPreferences.removeChatId(task.id)
                }
            }
            throw e
        }
    }

    /** Watches an existing chat the user named (a message was just sent to it). Its news goes to [owner]. */
    fun adopt(id: String, agent: AgentKind, message: String, owner: String = JarvisManagers.VOICE) {
        val now = System.currentTimeMillis()
        val existing = find(id)
        if (existing != null && existing.id == id) {
            put(existing.copy(state = State.STARTING, turnAt = now, updatedAt = now, progressAt = now, seenRunning = false,
                followUps = existing.followUps + 1, note = "Follow-up sent", reported = true))
            return
        }
        val conv = vm.allConversations.value.find { it.id == id }
        put(Task(
            id = id, agent = agent, title = conv?.title?.takeIf { it.isNotBlank() && it != "New Chat" }?.take(60) ?: shortTitle(message),
            goal = message.trim().take(600), project = conv?.workspaceUri?.removePrefix("file://").orEmpty(),
            note = "消息已发送", adopted = true, owner = JarvisManagers.ownerOrVoice(owner)
        ))
    }

    /** Sends a follow-up, a correction or a new direction to the task. With [interrupt] a running task is stopped first. */
    suspend fun reply(id: String, message: String, interrupt: Boolean): String {
        var t = find(id) ?: return "No task with that id. Use jarvis_task_list."
        val text = message.trim()
        if (text.isEmpty()) return "The message is empty."
        val lock = taskSendLocks.getOrPut(t.id) { Mutex() }
        return lock.withLock {
            t = find(t.id) ?: return@withLock "No task with that id. Use jarvis_task_list."
            val running = isRunningNow(t) || t.state.active
            if (running && !interrupt) {
                if (t.queuedFollowUps.size >= 20) return@withLock "The task already has 20 queued follow-ups. Wait for it to finish before adding more."
                put(t.copy(queuedFollowUps = t.queuedFollowUps + QueuedFollowUp(message = text), note = "Follow-up queued (${t.queuedFollowUps.size + 1})"), durable = true)
                return@withLock "Queued behind the current turn. The task will receive it automatically when it finishes."
            }
            if (running && interrupt) {
                if (t.queuedFollowUps.size >= 20) return@withLock "The task already has 20 queued follow-ups. Wait for it to finish before adding more."
                val urgent = QueuedFollowUp(message = text, interrupt = true)
                put(t.copy(queuedFollowUps = listOf(urgent) + t.queuedFollowUps,
                    note = "Stopping current turn; follow-up queued"), durable = true)
                val stopped = stop(t.id, quiet = true)
                if (stopped != "Stopped.") {
                    find(t.id)?.let { current -> put(current.copy(queuedFollowUps = current.queuedFollowUps.filterNot { it.id == urgent.id }), durable = true) }
                    return@withLock stopped
                }
                return@withLock "Stop requested. The follow-up is queued and will be sent after the provider confirms the turn has stopped."
            }
            if (t.queuedFollowUps.isNotEmpty()) {
                if (t.queuedFollowUps.size >= 20) return@withLock "The task already has 20 queued follow-ups. Wait for it to finish before adding more."
                put(t.copy(state = State.STARTING, queuedFollowUps = t.queuedFollowUps + QueuedFollowUp(message = text),
                    queuedRetryAt = 0L, queuedAttempts = 0, note = "Retrying queued follow-ups"), durable = true)
                return@withLock "Queued follow-ups resumed in order. The task will receive them automatically."
            }
            sendFollowUpNow(t, text)
        }
    }

    private suspend fun sendFollowUpNow(t: Task, text: String, deliveryId: String? = null): String {
        // the user's every-message entries allowed for Jarvis go along with each follow-up
        val scopedNote = JarvisManagers.delegatedNote(t.id)
        val deliveryNote = deliveryId?.let { PromptExpansion.hidden("<antigem-task-follow-up id=\"$it\"/>") }.orEmpty()
        val outgoing = ai.antigem.data.context.GlobalContext.taskFollowUp(
            (if (scopedNote.isNotBlank()) PromptExpansion.hidden(scopedNote) else "") + deliveryNote + PromptExpansion.expand(text))
        val previousMission = JarvisManagers.of(t.id).mission
        if (JarvisManagers.isDelegated(t.id) && !t.active) {
            JarvisManagers.update(t.id) { it.copy(mission = it.mission.copy(status = "active", nudges = 0)) }
        }
        val now = System.currentTimeMillis()
        put(t.copy(state = State.STARTING, turnAt = now, updatedAt = now, progressAt = now, seenRunning = false,
            followUps = t.followUps + 1, note = "Follow-up: ${text.take(60)}", result = "", resultChars = 0,
            reported = true, archived = false), durable = true)
        try {
        when (t.agent) {
            AgentKind.CLAUDE -> {
                val onScreen = vm.currentConversation.value?.id == t.id
                if (onScreen) {
                    vm.claudeBackend.sendMessage(outgoing)
                } else {
                    val b = ensureBackend(t.id)
                    if (b.currentConversation.value?.id != t.id) { b.selectConversation(t.id); delay(900) }
                    b.sendMessage(outgoing)
                }
            }
            AgentKind.AGY -> {
                val enum = AgyHubClient.resolveModelEnum(vm.agyBackend.modelOfChat(t.id))
                hub.sendUserPrompt(t.id, outgoing, enum).getOrThrow()
            }
            AgentKind.OPENCODE -> {
                if (vm.currentConversation.value?.id == t.id) {
                    vm.openCodeBackend.sendMessage(outgoing)
                } else {
                    val b = ensureOpenCodeBackend(t.id)
                    if (b.currentConversation.value?.id != t.id) { b.selectConversation(t.id); delay(900) }
                    b.sendMessage(outgoing)
                }
            }
            AgentKind.CODEX -> {
                if (vm.currentConversation.value?.id == t.id) {
                    vm.codexBackend.sendMessage(outgoing)
                } else {
                    val b = ensureCodexBackend(t.id)
                    if (b.currentConversation.value?.id != t.id) { b.selectConversation(t.id); delay(900) }
                    b.sendMessage(outgoing)
                }
            }
        }
        } catch (e: Exception) {
            if (find(t.id)?.turnAt == now) {
                put(t, durable = true)
                if (JarvisManagers.isDelegated(t.id)) JarvisManagers.update(t.id) { it.copy(mission = previousMission) }
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            return "Could not send: ${e.message.orEmpty()}"
        }
        return "Sent to the task."
    }

    /** Sends one durable queued follow-up only after the provider chat is idle. */
    private suspend fun dispatchQueuedFollowUp(taskId: String): Boolean {
        val lock = taskSendLocks.getOrPut(taskId) { Mutex() }
        return lock.withLock {
            var latest = find(taskId) ?: return@withLock false
            if (latest.queuedFollowUps.isEmpty() || isRunningNow(latest) || System.currentTimeMillis() < latest.queuedRetryAt) return@withLock false
            val head = latest.queuedFollowUps.first()
            val now = System.currentTimeMillis()
            // A send may have reached the provider just before process death. The stable marker lives in
            // the provider's user message; acknowledge it after recovery instead of sending it twice.
            if (hasFollowUpMarker(latest, head.id)) {
                put(latest.copy(queuedFollowUps = latest.queuedFollowUps.drop(1), followUpInFlightId = "",
                    followUpInFlightAt = 0L, queuedAttempts = 0, queuedRetryAt = 0L,
                    note = "Queued follow-up delivered"), durable = true)
                return@withLock true
            }
            if (latest.followUpInFlightId == head.id && now - latest.followUpInFlightAt < 30_000L) return@withLock false
            latest = latest.copy(followUpInFlightId = head.id, followUpInFlightAt = now)
            put(latest, durable = true)
            val result = sendFollowUpNow(latest, head.message, head.id)
            if (result.startsWith("Could not send:")) {
                val current = find(taskId) ?: return@withLock false
                val attempts = latest.queuedAttempts + 1
                val detail = result.removePrefix("Could not send:").trim().take(120)
                if (attempts >= 5) {
                    put(current.copy(queuedFollowUps = listOf(head) + current.queuedFollowUps.filterNot { it.id == head.id }, queuedAttempts = attempts,
                        queuedRetryAt = 0L, state = State.FAILED, note = "Queued follow-up failed after $attempts attempts: $detail", reported = false), durable = true)
                } else {
                    val wait = (5_000L shl (attempts - 1)).coerceAtMost(300_000L)
                    put(current.copy(queuedAttempts = attempts, queuedRetryAt = System.currentTimeMillis() + wait, state = State.STARTING,
                        note = "Queued follow-up send failed; retrying in ${wait / 1000}s: $detail", reported = true), durable = true)
                }
                false
            } else {
                val current = find(taskId) ?: return@withLock false
                if (current.followUpInFlightId == head.id) put(current.copy(
                    queuedFollowUps = current.queuedFollowUps.filterNot { it.id == head.id },
                    followUpInFlightId = "", followUpInFlightAt = 0L, queuedAttempts = 0, queuedRetryAt = 0L
                ), durable = true)
                true
            }
        }
    }

    private suspend fun hasFollowUpMarker(task: Task, id: String): Boolean {
        val marker = "antigem-task-follow-up id=\"$id\""
        val messages = delegatedMessages(task.id)?.takeIf { it.isNotEmpty() }
            ?: runCatching { JarvisController.readChat(task.id) }.getOrDefault(emptyList())
        return messages.any { message -> message.role == MessageRole.USER &&
            listOfNotNull(message.content, message.rawContent, message.rawPayload).any { it.contains(marker) } }
    }

    /** Stops what the task is doing. */
    suspend fun stop(id: String, quiet: Boolean = false): String {
        val t = find(id) ?: return "No task with that id."
        if (!quiet && JarvisManagers.isDelegated(t.id)) {
            for (child in ownedBy(t.id).filter { it.active }) {
                val stopped = stop(child.id)
                if (stopped != "Stopped.") return "Could not stop child ${child.title}: $stopped"
            }
        }
        when (t.agent) {
            AgentKind.CLAUDE -> {
                if (vm.currentConversation.value?.id == t.id) vm.claudeBackend.stopStreaming()
                else ensureBackend(t.id).stopStreaming()
            }
            AgentKind.AGY -> hub.cancelCascadeInvocation(t.id).onFailure { return "无法停止：${it.message}" }
            AgentKind.OPENCODE -> (openCodeBackends[t.id] ?: vm.openCodeBackend).interrupt(t.id).onFailure { return "无法停止：${it.message}" }
            AgentKind.CODEX -> (codexBackends[t.id] ?: vm.codexBackend).interrupt(t.id).onFailure { return "无法停止：${it.message}" }
        }
        if (!quiet) put(t.copy(state = State.STOPPED, updatedAt = System.currentTimeMillis(), note = "Stopped", reported = true,
            queuedFollowUps = emptyList(), queuedRetryAt = 0L, queuedAttempts = 0, followUpInFlightId = "", followUpInFlightAt = 0L), durable = true)
        return "Stopped."
    }

    /** Forgets the task (the chat itself stays). */
    fun close(id: String): Boolean {
        val t = find(id) ?: return false
        if (JarvisManagers.isDelegated(t.id) && (t.active || ownedBy(t.id).any { it.active })) return false
        release(t.id)
        _tasks.value = _tasks.value.filter { it.id != t.id }
        save()
        return true
    }

    /** Archives a terminal task while retaining its task, session and worktree metadata. */
    @Synchronized
    fun archive(id: String): Boolean {
        val task = find(id) ?: return false
        val subtree = taskSubtree(task)
        if (subtree.any { it.active || it.queuedFollowUps.isNotEmpty() }) return false
        val now = System.currentTimeMillis()
        val ids = subtree.mapTo(HashSet()) { it.id }
        val previous = _tasks.value
        _tasks.value = previous.map { if (it.id in ids) it.copy(archived = true, updatedAt = now) else it }
        try { save(durable = true) } catch (_: Exception) { _tasks.value = previous; return false }
        return true
    }

    @Synchronized
    fun unarchive(id: String): Boolean {
        val task = find(id) ?: return false
        val subtree = taskSubtree(task)
        if (subtree.any { it.active || it.queuedFollowUps.isNotEmpty() }) return false
        val now = System.currentTimeMillis()
        val ids = subtree.mapTo(HashSet()) { it.id }
        val previous = _tasks.value
        _tasks.value = previous.map { if (it.id in ids) it.copy(archived = false, updatedAt = now) else it }
        try { save(durable = true) } catch (_: Exception) { _tasks.value = previous; return false }
        return true
    }

    private fun taskSubtree(root: Task): List<Task> {
        val seen = hashSetOf(root.id)
        val result = ArrayList<Task>()
        fun visit(parent: String) {
            _tasks.value.filter { JarvisManagers.ownerOrVoice(it.owner) == parent }.forEach { child ->
                if (seen.add(child.id)) { visit(child.id); result += child }
            }
        }
        visit(root.id)
        return result + root
    }

    /** Removes the finished tasks from the list. */
    fun clearFinished() {
        _tasks.value.filter { !it.active }.forEach { release(it.id) }
        _tasks.value = _tasks.value.filter { it.active }
        save()
    }

    /** The task's waiting question, if any (Claude tasks). */
    fun pendingQuestion(id: String): Pair<ClaudeChatBackend, ToolCall>? {
        val b = claudeBackends[id] ?: return null
        val call = b.messages.value.asSequence().filter { it.role == MessageRole.ASSISTANT }.flatMap { it.toolCalls.asSequence() }
            .lastOrNull { it.toolType == ToolType.ASK_CHOICE && it.status == "AWAITING_CHOICE" && it.questionnaire != null } ?: return null
        return b to call
    }

    /** The task's last reply text, whole (its thinking and tool markers out, an error as one closing line). */
    suspend fun fullReply(t: Task): String {
        val msgs = when (t.agent) {
            AgentKind.CLAUDE -> claudeBackends[t.id]?.messages?.value?.takeIf { it.isNotEmpty() } ?: runCatching { JarvisController.readChat(t.id) }.getOrDefault(emptyList())
            AgentKind.OPENCODE -> openCodeBackends[t.id]?.messages?.value?.takeIf { it.isNotEmpty() } ?: runCatching { JarvisController.readChat(t.id) }.getOrDefault(emptyList())
            AgentKind.CODEX -> codexBackends[t.id]?.messages?.value?.takeIf { it.isNotEmpty() } ?: runCatching { JarvisController.readChat(t.id) }.getOrDefault(emptyList())
            AgentKind.AGY -> runCatching { JarvisController.readChat(t.id) }.getOrDefault(emptyList())
        }
        val text = msgs.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }?.content.orEmpty()
        return JarvisText.report(text)
    }

    /** [text] cut to its last [max] characters: the end of a reply holds the agent's conclusion. */
    fun cutEnd(text: String, max: Int): String = if (text.length > max) "…" + text.takeLast(max).trimStart() else text

    // ------------------------------------------------------------------ Antigravity approvals off screen

    /** Approvals waiting in an Antigravity chat that is not on screen: (tool call, message id). Refreshed by [refreshAgyPending]. */
    fun agyPendingOf(id: String): List<Pair<ToolCall, String>> = agyPending[id].orEmpty()

    /** Reads the chat's steps (at most every [minGapMs]) and remembers the approvals waiting in it. */
    suspend fun refreshAgyPending(id: String, minGapMs: Long = 4000) {
        val now = System.currentTimeMillis()
        if (now - (agyCheckedAt[id] ?: 0L) < minGapMs) return
        agyCheckedAt[id] = now
        val msgs = runCatching { JarvisController.readChat(id) }.getOrNull() ?: return
        val waiting = msgs.filter { it.role == MessageRole.ASSISTANT }.flatMap { m ->
            m.toolCalls.filter { it.status == "PENDING_APPROVAL" && it.toolType != ToolType.ASK_CHOICE && it.stepIndex != null }
                .distinctBy { it.stepIndex }.map { it to m.id }
        }
        if (waiting.isEmpty()) agyPending.remove(id) else agyPending[id] = waiting
    }

    fun clearAgyPending(id: String) {
        agyPending.remove(id)
        agyCheckedAt.remove(id)
    }

    /** Answers an Antigravity approval of a chat that is not on screen. */
    suspend fun resolveAgy(chatId: String, call: ToolCall, allow: Boolean, reason: String?): Result<Unit> {
        val type = call.interactionType
            ?: if (call.name.startsWith("mcp_") || call.name == "call_mcp_tool" || call.name == "read_resource" || call.name == "list_resources") "mcp" else "permission"
        val r = hub.handleCascadeUserInteraction(
            cascadeId = chatId, stepIndex = call.stepIndex ?: return Result.failure(IllegalStateException("no step")),
            trajectoryId = call.trajectoryId.orEmpty(), allow = allow, scope = "PERMISSION_SCOPE_ONCE",
            userDenyInstruction = if (allow) "" else (reason ?: "The user rejected this."), interactionType = type
        )
        agyPending[chatId] = agyPending[chatId].orEmpty().filter { it.first.stepIndex != call.stepIndex }
        return r
    }

    // ------------------------------------------------------------------ watching

    /**
     * A backend for one Codex task chat. It never changes the user's Codex defaults, and Codex's questions are answered
     * empty: nobody sees a task's chat, so the agent asks in its reply instead (Jarvis reads that out).
     */
    private fun newCodexBackend() = ai.antigem.data.agent.codex.CodexChatBackend(
        app, scope, ChatSessionStore(), vm.codexPrefs, ai.antigem.data.agent.codex.CodexBridgeClient(),
        rememberChoices = false, skipQuestions = true
    ).also { it.start() }

    private fun ensureCodexBackend(id: String): ai.antigem.data.agent.codex.CodexChatBackend = codexBackends.getOrPut(id) {
        attachedAt[id] = System.currentTimeMillis()
        newCodexBackend().also { it.selectConversation(id) }
    }

    private fun ensureBackend(id: String): ClaudeChatBackend = claudeBackends.getOrPut(id) {
        attachedAt[id] = System.currentTimeMillis()
        newClaudeBackend().also { it.selectConversation(id) }
    }

    private fun ensureOpenCodeBackend(id: String): ai.antigem.data.agent.opencode.OpenCodeChatBackend = openCodeBackends.getOrPut(id) {
        attachedAt[id] = System.currentTimeMillis()
        newOpenCodeBackend().also { it.selectConversation(id) }
    }

    private fun release(id: String) {
        claudeBackends.remove(id)?.let { runCatching { it.detach() } }
        openCodeBackends.remove(id)?.let { runCatching { it.stop() } }
        codexBackends.remove(id)?.let { runCatching { it.stop() } }
        attachedAt.remove(id)
        progressSig.remove(id)
        clearAgyPending(id)
    }

    fun delegatedBusy(id: String): Boolean = find(id)?.let { isRunningNow(it) } ?: false

    fun delegatedMessages(id: String) = when (find(id)?.agent) {
        AgentKind.CLAUDE -> claudeBackends[id]?.messages?.value
        AgentKind.OPENCODE -> openCodeBackends[id]?.messages?.value
        AgentKind.CODEX -> codexBackends[id]?.messages?.value
        else -> null
    }

    private fun isRunningNow(t: Task): Boolean = when (t.agent) {
        AgentKind.CLAUDE -> claudeBackends[t.id]?.isCurrentChatActivelyRunning?.value == true ||
            (vm.currentConversation.value?.id == t.id && vm.claudeBackend.isCurrentChatActivelyRunning.value)
        AgentKind.AGY -> agyBusy(t.id)
        AgentKind.OPENCODE -> openCodeBackends[t.id]?.isCurrentChatActivelyRunning?.value == true ||
            (vm.currentConversation.value?.id == t.id && vm.openCodeBackend.isCurrentChatActivelyRunning.value) ||
            vm.openCodeBackend.conversations.value.any { it.id == t.id && it.isRunning }
        AgentKind.CODEX -> codexBackends[t.id]?.isCurrentChatActivelyRunning?.value == true ||
            (vm.currentConversation.value?.id == t.id && vm.codexBackend.isCurrentChatActivelyRunning.value) ||
            vm.codexBackend.conversations.value.any { it.id == t.id && it.isRunning }
    }

    private fun agyBusy(id: String): Boolean {
        val hubOnline = vm.systemConnectionState.value.isHubOnline
        val c = vm.agyBackend.conversations.value.find { it.id == id } ?: return false
        return hubOnline && (c.isRunning || c.notFullyIdle)
    }

    private suspend fun loop() {
        var tick = 0
        while (scope.isActive) {
            delay(1500)
            tick++
            try {
                if (!JarvisController.isAttached || !JarvisPreferences.enabled.value) continue
                for (t in _tasks.value.filter { it.active }) runCatching { check(t, tick) }.onFailure { Log.w(TAG, "check ${t.id}: ${it.message}") }
                // A completion stays pending until its event reached the durable outbox. Retry in-process
                // if the device briefly cannot commit SharedPreferences.
                replayUnreportedEvents()
                _anyActive.value = _tasks.value.any { it.active }
            } catch (e: Exception) {
                Log.w(TAG, "loop: ${e.message}")
            }
        }
    }

    private suspend fun check(t0: Task, tick: Int) {
        var t = t0
        val now = System.currentTimeMillis()
        val running: Boolean
        var needsApproval = false
        var asking = false
        var error: String? = null
        val sig: String
        when (t.agent) {
            AgentKind.CLAUDE -> {
                val b = ensureBackend(t.id)
                running = b.isCurrentChatActivelyRunning.value
                needsApproval = b.pendingPermissions.value.isNotEmpty()
                asking = pendingQuestion(t.id) != null
                error = b.conversationError.value
                val msgs = b.messages.value
                val last = msgs.lastOrNull()
                sig = "${msgs.size}:${last?.content?.length}:${last?.toolCalls?.size}:${last?.toolCalls?.lastOrNull()?.status}"
            }
            AgentKind.AGY -> {
                running = agyBusy(t.id)
                val c = vm.agyBackend.conversations.value.find { it.id == t.id }
                sig = "${c?.stepCount}:${c?.updatedAt}"
                // a step waiting for approval also stops the progress: only then read the steps from the hub
                if (running && tick % 3 == 0 && now - t.progressAt > 3000) refreshAgyPending(t.id, 4000)
                else if (!running) clearAgyPending(t.id)
                needsApproval = running && agyPendingOf(t.id).isNotEmpty() ||
                    (vm.currentConversation.value?.id == t.id && vm.pendingApprovals.value.isNotEmpty())
            }
            AgentKind.OPENCODE -> {
                val b = ensureOpenCodeBackend(t.id)
                running = b.isCurrentChatActivelyRunning.value
                needsApproval = b.pendingPermissions.value.isNotEmpty()
                val msgs = b.messages.value
                val last = msgs.lastOrNull()
                // OpenCode reports failures as an error card in the reply (provider error, gave up after retries, no answer)
                error = b.conversationError.value ?: last?.takeIf { !running && it.role == MessageRole.ASSISTANT && it.content.contains("<!-- error -->") }
                    ?.let { m -> Regex("\"userMessage\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(m.content)?.groupValues?.get(1) ?: "the provider failed" }
                sig = "${msgs.size}:${last?.content?.length}:${last?.toolCalls?.size}:${last?.toolCalls?.lastOrNull()?.status}"
            }
            AgentKind.CODEX -> {
                val b = ensureCodexBackend(t.id)
                running = b.isCurrentChatActivelyRunning.value
                needsApproval = b.pendingApprovals.value.isNotEmpty()
                val msgs = b.messages.value
                val last = msgs.lastOrNull()
                // Codex reports a failed turn as an error card in the reply (usage limit, connection, model)
                error = b.conversationError.value ?: last?.takeIf { !running && it.role == MessageRole.ASSISTANT && it.content.contains("<!-- error -->") }
                    ?.let { m -> Regex("\"userMessage\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(m.content)?.groupValues?.get(1) ?: "Codex failed" }
                sig = "${msgs.size}:${last?.content?.length}:${last?.toolCalls?.size}:${last?.toolCalls?.lastOrNull()?.status}"
            }
        }
        // A queued follow-up owns the next turn. Do not publish a transient DONE state or let the parent
        // conclude while the recipient still has work waiting in its per-session queue.
        if (!running && t.queuedFollowUps.isNotEmpty() &&
            (t.queuedFollowUps.first().interrupt || !needsApproval && !asking)) {
            if ((t.seenRunning || now - t.turnAt >= START_GRACE_MS) && now >= t.queuedRetryAt) dispatchQueuedFollowUp(t.id)
            else if (t.state != State.STARTING) put(t.copy(state = State.STARTING, note = "Waiting to deliver queued follow-ups"))
            return
        }
        if (progressSig.put(t.id, sig) != sig) t = t.copy(progressAt = now)
        if (running && !t.seenRunning) t = t.copy(seenRunning = true)
        val graceFrom = maxOf(t.turnAt, attachedAt[t.id] ?: 0L)
        val stallMs = JarvisPreferences.stallMinutes.value * 60_000L
        val delegated = JarvisManagers.isDelegated(t.id)
        val waitingForChildren = delegated && JarvisOrchestrationRules.childWorkPending(ownedBy(t.id).any { it.active }, JarvisEvents.hasPendingWork(t.id))
        val missionDone = delegated && JarvisManagers.of(t.id).mission.status == "done"
        // Very fast turns can finish between polls. Verify a real current-turn assistant message instead of treating silence as success.
        val timedOut = now - graceFrom > START_GRACE_MS
        val freshReply = if (!running && !t.seenRunning && timedOut && (t.state != State.STALLED || tick % 10 == 0)) {
            val messages = delegatedMessages(t.id) ?: runCatching { JarvisController.readChat(t.id) }.getOrDefault(emptyList())
            val lastUser = messages.indexOfLast { it.role == MessageRole.USER }
            val lastAssistant = messages.indexOfLast { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }
            lastUser >= 0 && lastAssistant > lastUser && messages[lastAssistant].createdAt >= t.turnAt
        } else false
        val next = when {
            needsApproval -> State.NEEDS_APPROVAL
            asking -> State.NEEDS_ANSWER
            running && stallMs > 0 && now - t.progressAt > stallMs -> State.STALLED
            running -> State.RUNNING
            waitingForChildren -> State.RUNNING
            error != null -> State.FAILED
            JarvisOrchestrationRules.canCompleteIdle(t.seenRunning, freshReply, delegated, missionDone, waitingForChildren) -> State.DONE
            delegated && !missionDone && t.seenRunning -> State.STALLED
            timedOut -> State.STALLED
            else -> State.STARTING
        }
        if (next != t.state) {
            t = t.copy(state = next, updatedAt = now, reported = false, note = when (next) {
                State.NEEDS_APPROVAL -> "Waiting for an approval"
                State.NEEDS_ANSWER -> "Asking a question"
                State.STALLED -> when {
                    delegated && !running && !missionDone -> "Manager is idle with an unfinished mission"
                    !running && !t.seenRunning -> "No confirmed execution or current-turn result"
                    else -> "No progress for ${(now - t.progressAt) / 60_000} min"
                }
                State.RUNNING -> "Working"
                State.DONE -> "Finished"
                State.FAILED -> "Failed: ${error.orEmpty().take(80)}"
                else -> t.note
            })
            // the manager gets the sub-agent's whole report (cut only when very long)
            if (next == State.DONE || next == State.FAILED) {
                val full = fullReply(t)
                t = t.copy(result = cutEnd(full, RESULT_MAX), resultChars = full.length)
            }
        }
        val kind = if (!t.reported) when (t.state) {
            State.DONE -> "done"
            State.FAILED -> "failed"
            State.STALLED -> "stalled"
            else -> null
        } else null
        if (!putIfCurrent(t0, if (kind == null && !t.reported) t.copy(reported = true) else t)) return
        if (kind != null) {
            JarvisController.onTaskEvent(t, kind, when (kind) {
                "failed" -> error.orEmpty().ifBlank { t.note.removePrefix("Failed:").trim() }
                "stalled" -> t.note
                else -> t.result
            })
            val latest = find(t.id)
            if (latest?.turnAt == t.turnAt && latest.state == t.state && !latest.reported) put(latest.copy(reported = true))
            if (!t.state.active) {
                // keep the chat readable, but stop listening to it
                scope.launch {
                    delay(90_000)
                    if (find(t.id)?.active == false) {
                        claudeBackends.remove(t.id)?.let { runCatching { it.detach() } }
                        openCodeBackends.remove(t.id)?.let { runCatching { it.stop() } }
                        codexBackends.remove(t.id)?.let { runCatching { it.stop() } }
                        attachedAt.remove(t.id)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ list and storage

    @Synchronized
    private fun putIfCurrent(expected: Task, updated: Task): Boolean {
        if (_tasks.value.firstOrNull { it.id == expected.id } != expected) return false
        put(updated)
        return true
    }

    @Synchronized
    private fun put(task: Task, durable: Boolean = false) {
        val list = _tasks.value
        val changed = list.firstOrNull { it.id == task.id } != task
        val ordered = (listOf(task) + list.filter { it.id != task.id }).sortedByDescending { it.createdAt }
        _tasks.value = JarvisOrchestrationRules.retain(ordered, KEEP_FINISHED, { it.active },
            { it.archived || JarvisManagers.isDelegated(it.id) || JarvisManagers.isDelegated(it.owner) })
        _anyActive.value = _tasks.value.any { it.active }
        if (changed) save(durable = durable || !task.reported && task.state in setOf(State.DONE, State.FAILED, State.STALLED))
    }

    private fun shortTitle(text: String): String {
        val first = PromptExpansion.collapse(text).lines().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return if (first.length > 48) first.take(48).trimEnd() + "…" else first.ifBlank { "Task" }
    }

    private fun save(durable: Boolean = false) {
        val arr = JSONArray()
        _tasks.value.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("agent", t.agent.name); put("title", t.title); put("goal", t.goal); put("project", t.project)
                put("state", t.state.name); put("created", t.createdAt); put("turn", t.turnAt); put("updated", t.updatedAt)
                put("progress", t.progressAt); put("seen", t.seenRunning); put("reported", t.reported)
                put("follow", t.followUps); put("note", t.note); put("result", t.result.take(RESULT_MAX + 1)); put("chars", t.resultChars); put("adopted", t.adopted)
                put("owner", t.owner); put("team", t.team)
                t.workspace?.let { workspace -> put("workspace", JSONObject().apply {
                    put("repository_root", workspace.repositoryRoot); put("checkout_root", workspace.checkoutRoot)
                    put("working_directory", workspace.workingDirectory); put("branch", workspace.branch); put("base_commit", workspace.baseCommit)
                }) }
                put("queued_follow_ups", JSONArray().apply { t.queuedFollowUps.forEach { put(JSONObject().put("id", it.id).put("message", it.message).put("interrupt", it.interrupt)) } })
                put("queued_retry_at", t.queuedRetryAt); put("queued_attempts", t.queuedAttempts)
                put("follow_up_in_flight_id", t.followUpInFlightId); put("follow_up_in_flight_at", t.followUpInFlightAt)
                put("archived", t.archived)
            })
        }
        val editor = prefs?.edit()?.putString("list", arr.toString()) ?: return
        if (durable) check(editor.commit()) { "Could not durably save Jarvis task state." } else editor.apply()
    }

    private fun load() {
        val raw = prefs?.getString("list", null) ?: return
        val now = System.currentTimeMillis()
        _tasks.value = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val agent = runCatching { AgentKind.valueOf(o.getString("agent")) }.getOrNull() ?: return@mapNotNull null
                val state = runCatching { State.valueOf(o.getString("state")) }.getOrDefault(State.DONE)
                Task(
                    id = o.getString("id"), agent = agent, title = o.optString("title"), goal = o.optString("goal"),
                    project = o.optString("project"),
                    // a task that was working when the app closed is checked again (the chat may still be running)
                    state = if (state.active) State.STARTING else state,
                    createdAt = o.optLong("created", now), turnAt = o.optLong("turn", now),
                    updatedAt = o.optLong("updated", now), progressAt = now, seenRunning = if (state.active) false else o.optBoolean("seen"),
                    followUps = o.optInt("follow"), note = o.optString("note"), result = o.optString("result"), resultChars = o.optInt("chars"),
                    reported = o.optBoolean("reported", true), adopted = o.optBoolean("adopted"),
                    owner = o.optString("owner").ifBlank { JarvisManagers.VOICE }, team = o.optString("team"),
                    workspace = o.optJSONObject("workspace")?.let { w -> JarvisWorktreeManager.Workspace(
                        w.optString("repository_root"), w.optString("checkout_root"), w.optString("working_directory"),
                        w.optString("branch"), w.optString("base_commit")
                    ) },
                    queuedFollowUps = o.optJSONArray("queued_follow_ups")?.let { a ->
                        (0 until a.length()).mapNotNull { index ->
                            when (val item = a.opt(index)) {
                                is JSONObject -> item.optString("message").takeIf { it.isNotBlank() }?.let {
                                    QueuedFollowUp(item.optString("id").ifBlank { UUID.randomUUID().toString() }, it, item.optBoolean("interrupt"))
                                }
                                is String -> item.takeIf { it.isNotBlank() }?.let { QueuedFollowUp(message = it) }
                                else -> null
                            }
                        }.take(20)
                    }.orEmpty(), queuedRetryAt = o.optLong("queued_retry_at"), queuedAttempts = o.optInt("queued_attempts"),
                    followUpInFlightId = o.optString("follow_up_in_flight_id"), followUpInFlightAt = o.optLong("follow_up_in_flight_at"),
                    archived = o.optBoolean("archived")
                )
            }
        }.getOrDefault(emptyList())
        _anyActive.value = _tasks.value.any { it.active }
    }
}
