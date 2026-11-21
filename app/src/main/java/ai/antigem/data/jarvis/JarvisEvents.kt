package ai.antigem.data.jarvis

import android.app.Application
import android.content.Context
import android.util.Log
import ai.antigem.data.remote.PromptExpansion
import ai.antigem.domain.model.ToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * News for the Jarvis managers: a task finished, failed, got stuck, wants an approval or asks something, or the
 * autopilot nudges an unfinished mission. News is queued per manager and handed over in ONE message once that
 * manager is free (everything that came in meanwhile, together), as a structured block the model cannot confuse with
 * the user's words or with another chat's: `<jarvis_events>` with one `<event>` per item and the sub-agent's own reply
 * inside `<task_reply>`. The chat shows only a one-line label (and renders it as a card), never the instructions.
 *
 * It also guards the managers: one that sleeps or polls while it waits for its tasks (a `sleep 60`, a wait loop,
 * ScheduleWakeup…) is stopped so the news reaches it, and the autopilot keeps an unfinished mission going.
 */
object JarvisEvents {
    private const val TAG = "JarvisEvents"

    data class Event(
        /** task_done, task_failed, task_stalled, task_approval, task_question, nudge, autopilot_paused */
        val kind: String,
        val taskId: String = "",
        val title: String = "",
        val agent: String = "",
        val team: String = "",
        val goal: String = "",
        /** The sub-agent's reply, the error, the question or the action waiting for approval. */
        val body: String = "",
        val key: String = "",
        val risk: String = "",
        /** Length of the whole reply when [body] is only its end (task_done), else 0. */
        val replyChars: Int = 0,
        val at: Long = System.currentTimeMillis(),
        /** Stable across replay after process death; task transitions supply a deterministic id. */
        val id: String = java.util.UUID.randomUUID().toString()
    )

    data class Delivery(val events: List<Event>, val batchId: String)

    private val queues = ConcurrentHashMap<String, MutableList<Event>>()
    /** Why the manager's last turn was stopped by the guard (told once, with the next news). */
    private val guardNotes = ConcurrentHashMap<String, String>()
    /** A message to a text manager was just sent: it is not sent another one until it shows as working (or a while passed). */
    private val sentAt = ConcurrentHashMap<String, Long>()
    private val busyBefore = ConcurrentHashMap<String, Boolean>()
    private val idleSince = ConcurrentHashMap<String, Long>()
    private val lastNudgeAt = ConcurrentHashMap<String, Long>()
    private val waitSeen = ConcurrentHashMap<String, Pair<String, Long>>()
    private val agyCheckedAt = ConcurrentHashMap<String, Long>()
    /** Last reply already read out (text managers with voice replies). */
    private val spokenReply = ConcurrentHashMap<String, String>()
    /** jarvis_speak was used in the text manager's current turn (its reply is not read out again). */
    private val spokeInTurn: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    @Volatile private var voiceFlushPending = false
    /** News sent to a text manager that is not confirmed yet (its chat must start working or show a new message). */
    private data class InFlight(
        val batch: List<Event>,
        val at: Long,
        val messagesBefore: Int,
        val batchId: String = java.util.UUID.randomUUID().toString(),
        val accepted: Boolean = false
    )
    private val inFlight = ConcurrentHashMap<String, InFlight>()
    /** Recently acknowledged IDs prevent replayed approvals/results from being queued a second time. */
    private val deliveredEventIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    /** Voice news was dropped because no voice agent is set (told once per streak). */
    @Volatile private var droppedVoiceNotice = false
    @Volatile var holdVoiceUntil = 0L
    private var started = false
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun start(application: Application, scope: CoroutineScope) {
        if (started) return
        started = true
        prefs = application.getSharedPreferences("jarvis_event_outbox", Context.MODE_PRIVATE)
        load()
        JarvisTasks.replayUnreportedEvents()
        scope.launch { loop() }
    }

    // ------------------------------------------------------------------ queue

    @Synchronized
    fun post(owner: String, e: Event) {
        val key = JarvisManagers.ownerOrVoice(owner)
        if (e.id in deliveredEventIds) return
        val q = queues.getOrPut(key) { java.util.Collections.synchronizedList(ArrayList()) }
        // A task event can be replayed after a crash between outbox persistence and marking its source task
        // reported. Check both sides of the queue so replay cannot enqueue the same delivery twice.
        if (inFlight[key]?.batch?.any { it.id == e.id } == true) return
        synchronized(q) {
            // a newer state of the same task replaces the older news of the same kind (no stale duplicates)
            if (q.none { it.id == e.id }) {
                q.removeAll { it.taskId.isNotBlank() && it.taskId == e.taskId && it.kind == e.kind && it.key == e.key }
                q += e
            }
        }
        persist()
        if (e.kind == "task_done" || e.kind == "task_failed") JarvisManagers.resetNudges(key)
    }

    fun pending(owner: String): Int = (queues[JarvisManagers.ownerOrVoice(owner)]?.size ?: 0) +
        (inFlight[JarvisManagers.ownerOrVoice(owner)]?.batch?.size ?: 0)

    /** A delegated manager must not finish before its children\'s result delivery is complete. */
    fun hasPendingWork(owner: String): Boolean = pending(owner) > 0

    @Synchronized
    fun drain(owner: String): List<Event> {
        val key = JarvisManagers.ownerOrVoice(owner)
        val q = queues[key] ?: return emptyList()
        val out = synchronized(q) { q.toList().also { q.clear() } }
        if (out.isNotEmpty()) persist()
        return out
    }

    /** Atomically moves a manager's pending events into its durable delivery record before provider dispatch. */
    @Synchronized
    fun beginDelivery(owner: String, messagesBefore: Int): Delivery? {
        val key = JarvisManagers.ownerOrVoice(owner)
        if (inFlight.containsKey(key)) return null
        val q = queues[key] ?: return null
        val batch = synchronized(q) { q.toList().also { q.clear() } }
        if (batch.isEmpty()) return null
        val flight = InFlight(batch, System.currentTimeMillis(), messagesBefore)
        inFlight[key] = flight
        persist()
        return Delivery(flight.batch, flight.batchId)
    }

    @Synchronized
    fun markDeliveryAccepted(owner: String, batchId: String) {
        val key = JarvisManagers.ownerOrVoice(owner)
        val current = inFlight[key] ?: return
        if (current.batchId != batchId) return
        inFlight[key] = current.copy(accepted = true)
        persist()
    }

    @Synchronized
    fun acknowledgeDelivery(owner: String, batchId: String) {
        val key = JarvisManagers.ownerOrVoice(owner)
        if (inFlight[key]?.batchId != batchId) return
        val flight = inFlight.remove(key) ?: return
        synchronized(deliveredEventIds) {
            flight.batch.forEach { deliveredEventIds += it.id }
            while (deliveredEventIds.size > 1024) deliveredEventIds.remove(deliveredEventIds.first())
        }
        persist()
    }

    @Synchronized
    fun retryDelivery(owner: String, batchId: String, reason: String) {
        val key = JarvisManagers.ownerOrVoice(owner)
        val flight = inFlight[key]?.takeIf { it.batchId == batchId } ?: return
        inFlight.remove(key)
        val q = queues.getOrPut(key) { java.util.Collections.synchronizedList(ArrayList()) }
        synchronized(q) { q.addAll(0, flight.batch.filterNot { e -> q.any { it.id == e.id } }) }
        Log.w(TAG, "news to $key will retry after dispatch failure: ${reason.take(160)}")
        persist()
    }

    /** Redirects queued and in-flight events to voice as one durable update if their text manager was deleted. */
    @Synchronized
    private fun redirectToVoice(owner: String) {
        val source = owner
        val moved = buildList {
            queues[source]?.let { q -> synchronized(q) { addAll(q); q.clear() } }
            inFlight.remove(source)?.batch?.let(::addAll)
        }.distinctBy { it.id }
        queues.remove(source)
        if (moved.isNotEmpty()) {
            val target = queues.getOrPut(JarvisManagers.VOICE) { java.util.Collections.synchronizedList(ArrayList()) }
            synchronized(target) {
                val ids = target.mapTo(HashSet()) { it.id }
                target.addAll(moved.filterNot { it.id in ids })
            }
        }
        persist()
    }

    private fun Event.toJson() = JSONObject().apply {
        put("id", id); put("kind", kind); put("task_id", taskId); put("title", title); put("agent", agent)
        put("team", team); put("goal", goal); put("body", body); put("key", key); put("risk", risk)
        put("reply_chars", replyChars); put("at", at)
    }

    private fun parseEvent(o: JSONObject) = Event(
        kind = o.optString("kind"), taskId = o.optString("task_id"), title = o.optString("title"),
        agent = o.optString("agent"), team = o.optString("team"), goal = o.optString("goal"),
        body = o.optString("body"), key = o.optString("key"), risk = o.optString("risk"),
        replyChars = o.optInt("reply_chars"), at = o.optLong("at", System.currentTimeMillis()),
        id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
    )

    @Synchronized
    private fun persist() {
        val root = JSONObject()
        root.put("queues", JSONArray().apply {
            queues.forEach { (owner, q) ->
                val events = synchronized(q) { JSONArray().apply { q.forEach { put(it.toJson()) } } }
                if (events.length() > 0) put(JSONObject().put("owner", owner).put("events", events))
            }
        })
        root.put("in_flight", JSONArray().apply {
            inFlight.forEach { (owner, f) ->
                put(JSONObject().put("owner", owner).put("at", f.at).put("messages_before", f.messagesBefore)
                    .put("batch_id", f.batchId).put("accepted", f.accepted)
                    .put("events", JSONArray().apply { f.batch.forEach { put(it.toJson()) } }))
            }
        })
        root.put("delivered_ids", JSONArray().apply { synchronized(deliveredEventIds) { deliveredEventIds.forEach { put(it) } } })
        val store = prefs ?: return
        check(store.edit().putString("outbox", root.toString()).commit()) {
            "无法持久保存 Jarvis 任务事件。"
        }
    }

    private fun load() {
        val raw = prefs?.getString("outbox", null)?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val root = JSONObject(raw)
            root.optJSONArray("queues")?.let { items ->
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val owner = item.optString("owner")
                    if (owner.isBlank()) continue
                    val events = item.optJSONArray("events") ?: JSONArray()
                    val q = ArrayList<Event>(events.length())
                    for (j in 0 until events.length()) events.optJSONObject(j)?.let { q += parseEvent(it) }
                    if (q.isNotEmpty()) queues[owner] = java.util.Collections.synchronizedList(q)
                }
            }
            root.optJSONArray("in_flight")?.let { items ->
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val owner = item.optString("owner")
                    if (owner.isBlank()) continue
                    val events = item.optJSONArray("events") ?: JSONArray()
                    val batch = (0 until events.length()).mapNotNull { events.optJSONObject(it)?.let(::parseEvent) }
                    if (batch.isNotEmpty()) inFlight[owner] = InFlight(
                        batch, item.optLong("at", System.currentTimeMillis()), item.optInt("messages_before", -1),
                        item.optString("batch_id").ifBlank { java.util.UUID.randomUUID().toString() }, item.optBoolean("accepted")
                    )
                }
            }
            root.optJSONArray("delivered_ids")?.let { ids ->
                synchronized(deliveredEventIds) {
                    for (i in 0 until ids.length()) ids.optString(i).takeIf { it.isNotBlank() }?.let { deliveredEventIds += it }
                    while (deliveredEventIds.size > 1024) deliveredEventIds.remove(deliveredEventIds.first())
                }
            }
        }.onFailure { Log.e(TAG, "Could not restore event outbox: ${it.message}") }
    }

    fun takeGuardNote(owner: String): String? = guardNotes.remove(owner)

    fun onVoiceFlushTaken() { voiceFlushPending = false }

    fun markSpoke(owner: String) { spokeInTurn += owner }

    // ------------------------------------------------------------------ the message

    private fun kindWord(kind: String) = when (kind) {
        "task_done" -> "已完成"
        "task_failed" -> "失败"
        "task_stalled" -> "卡住"
        "task_approval" -> "需要审批"
        "task_question" -> "有疑问"
        "nudge" -> "继续推进"
        "autopilot_paused" -> "自动驾驶已暂停"
        else -> kind
    }

    /** The one line the chat shows for the whole batch. */
    fun label(events: List<Event>): String {
        val parts = events.map { e -> if (e.title.isBlank()) "任务：${kindWord(e.kind)}" else "${e.title.take(30)}：${kindWord(e.kind)}" }
        val head = if (events.size == 1) "⚡ Jarvis · " else "⚡ Jarvis · ${events.size} 条更新 — "
        return head + parts.take(4).joinToString(" · ") + if (parts.size > 4) " · 另有 ${parts.size - 4} 条" else ""
    }

    private fun attr(s: String) = s.replace("&", "&amp;").replace("\"", "'").replace("<", "‹").replace(">", "›").replace("\n", " ").trim()

    /** Text inside a section: its closing tag cannot appear in it. */
    private fun inner(s: String) = s.replace(Regex("</(task_reply|task_goal|error|question|approval|nudge|event|jarvis_events)>"), "‹/$1›").trim()

    private fun Event.toXml(n: Int): String = buildString {
        append("<event n=\"$n\" kind=\"$kind\"")
        if (taskId.isNotBlank()) append(" task_id=\"${attr(taskId)}\"")
        if (title.isNotBlank()) append(" task=\"${attr(title)}\"")
        if (team.isNotBlank()) append(" team=\"${attr(team)}\"")
        if (agent.isNotBlank()) append(" agent=\"${attr(agent)}\"")
        append(">\n")
        if (goal.isNotBlank()) append("<task_goal>\n${inner(goal.take(600))}\n</task_goal>\n")
        when (kind) {
            "task_done" -> append(when {
                body.isBlank() -> "<task_reply>(no text reply; use jarvis_task_get)</task_reply>\n"
                // a long reply comes as its end: say so, so it is not taken as the whole report
                replyChars > body.length -> "<task_reply cut=\"only the last ${body.length - 1} of $replyChars characters\">\n${inner(body)}\n</task_reply>\n"
                else -> "<task_reply>\n${inner(body)}\n</task_reply>\n"
            })
            "task_failed" -> append("<error>\n${inner(body.ifBlank { "未知错误" })}\n</error>\n")
            "task_stalled" -> append("<error>仍在运行，但已 ${JarvisPreferences.stallMinutes.value} 分钟没有可见进展。</error>\n")
            "task_approval" -> append("<approval key=\"${attr(key)}\" risk=\"${attr(risk)}\">\nwants to ${inner(body)}\n</approval>\n")
            "task_question" -> append("<question>\n${inner(body)}\n</question>\n")
            "nudge" -> append("<nudge>\n${inner(body)}\n</nudge>\n")
            "autopilot_paused" -> append("<nudge>\n${inner(body)}\n</nudge>\n")
        }
        append("</event>\n")
    }

    private fun handling(owner: String, events: List<Event>): String = buildString {
        val voice = owner == JarvisManagers.VOICE
        val kinds = events.map { it.kind }.toSet()
        append("These are automatic messages from the AntiGem app about work YOU delegated; they are not the user's words. ")
        append("Each <event> is separate: read every <task_reply> as that sub-agent's own report.\n")
        if (voice) append("- Tell the user with ONE jarvis_speak covering all events together (one or two short spoken sentences), not one call per event.\n")
        else if (JarvisManagers.isDelegated(owner)) append("- Process the child results and continue the assigned mission. Report the verified final result to your parent only after the mission is complete.\n")
        else append("- Write the user a short update in this chat (a line per event, plain and concrete).\n")
        if ("task_done" in kinds) append("- Done: check the result against the task goal and the mission. Do not trust \"done\" blindly: verify what matters (read the files, run the tests, or send a reviewer team). Good → mark the mission step done (jarvis_mission_update) and delegate the next step. Not good enough → jarvis_task_reply with precise feedback.\n")
        if (events.any { it.kind == "task_done" && it.replyChars > it.body.length }) append("- A <task_reply cut=…> is only the END of a long reply (usually its conclusion), which is often enough. " +
            "When the missing part matters (you hand the work on to another team, you check its details, or the user asks what it said), first read the whole reply: " +
            "jarvis_read_chat(id=<task_id>, role=\"assistant\", limit=1, max_chars=30000). Never pass a cut report on as if it were complete.\n")
        if ("task_failed" in kinds || "task_stalled" in kinds) append("- Failed / stuck: find out why (jarvis_task_get), then redirect it with jarvis_task_reply, or start a fresh task with a better plan. Ask the user before anything big or risky.\n")
        if ("task_approval" in kinds) append("- Approval: if it clearly serves the task's goal and is not risky, approve it with jarvis_resolve_permission(key, approve=true). Risky or unclear: " +
            (if (voice) "read it back to the user with jarvis_ask first." else "ask the user in your reply and decide after their answer.") + "\n")
        if ("task_question" in kinds) append("- Question: if you or the user already know the answer, give it with jarvis_task_answer; otherwise ask the user, then answer.\n")
        if ("nudge" in kinds) append("- Nudge: your mission is not done and nothing is running. Continue: delegate the next step, verify, or if everything in done_when is met, call jarvis_mission_update(status=\"done\") and report. If you are blocked, say exactly what you need from the user.\n")
        if ("autopilot_paused" in kinds) append("- Autopilot paused itself after several nudges without progress. Tell the user what is blocking and what you need.\n")
        append("- Start independent tasks in parallel (several jarvis_task_start calls in one turn).\n")
        append("- Then END YOUR TURN. Never wait for tasks: no sleep / timeout / watch / wait loops, no ScheduleWakeup or Monitor, no repeated jarvis_task_get / jarvis_task_list / jarvis_read_chat to see if they finished. The next news arrives by itself as a message like this one.")
    }

    /** The whole message for a batch: label for the chat, structured events, the manager's brief, how to handle it. */
    fun format(
        owner: String,
        events: List<Event>,
        guardNote: String?,
        batchId: String = java.util.UUID.randomUUID().toString()
    ): String {
        val body = buildString {
            append(OPEN).append("${events.size}\" batch_id=\"${attr(batchId)}\" from=\"AntiGem app (automatic, not typed by the user)\">\n")
            events.forEachIndexed { i, e -> append(e.toXml(i + 1)) }
            append("</jarvis_events>\n\n")
            if (!guardNote.isNullOrBlank()) append("<app_note>\n$guardNote\n</app_note>\n\n")
            append(JarvisManagers.brief(owner)).append("\n\n")
            append("<how_to_handle>\n").append(handling(owner, events)).append("\n</how_to_handle>")
        }
        return PromptExpansion.labeled(label(events), body)
    }

    // ------------------------------------------------------------------ reading it back (the chat's event card)

    data class Parsed(val kind: String, val title: String, val team: String, val agent: String, val taskId: String, val text: String)

    private val eventRe = Regex("<event ([^>]*)>([\\s\\S]*?)</event>")
    private val attrRe = Regex("(\\w+)=\"([^\"]*)\"")
    private val sectionRe = Regex("<(task_reply|error|question|approval|nudge)[^>]*>([\\s\\S]*?)</\\1>")

    /** How a real news block starts (the rules every message carries only mention "<jarvis_events>"). */
    private const val OPEN = "<jarvis_events count=\""

    fun isEventMessage(raw: String): Boolean = raw.contains(OPEN)

    /** The events of a message the app sent (null when it is not one). */
    fun parse(raw: String): List<Parsed>? {
        val start = raw.indexOf(OPEN)
        if (start < 0) return null
        val end = raw.indexOf("</jarvis_events>", start).takeIf { it > 0 } ?: raw.length
        val block = raw.substring(start, end)
        return eventRe.findAll(block).map { m ->
            val attrs = attrRe.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2].replace("&amp;", "&") }
            val text = sectionRe.findAll(m.groupValues[2]).joinToString("\n\n") { it.groupValues[2].trim() }
            Parsed(attrs["kind"].orEmpty(), attrs["task"].orEmpty(), attrs["team"].orEmpty(), attrs["agent"].orEmpty(), attrs["task_id"].orEmpty(), text)
        }.toList()
    }

    // ------------------------------------------------------------------ the loop: delivery, guard, autopilot, voice replies

    /**
     * A loop that only waits: `watch …` as a command, or a shell while / until loop with a sleep inside its body
     * (`while …; do …; sleep 5; done`). A Python `while True:` with time.sleep, or a sleep after the loop, is not one.
     */
    private val waitLoop = Regex(
        "((^|[;&|(]\\s*)watch\\s)|(\\b(while|until)\\b[^\\n]*?;\\s*do\\b[\\s\\S]*?\\bsleep\\s+\\d[\\s\\S]*?\\bdone\\b)",
        RegexOption.IGNORE_CASE
    )
    private val sleepCmd = Regex("(^|[\\s;&|(`])sleep\\s+(\\d+(?:\\.\\d+)?)([smhd]?)", RegexOption.IGNORE_CASE)

    /** A command that waits: a wait loop, or a sleep of 10 seconds or more (a short pause inside real work is fine). */
    fun isWaitCommand(cmd: String): Boolean {
        if (waitLoop.containsMatchIn(cmd)) return true
        return sleepCmd.findAll(cmd).any { m ->
            val n = m.groupValues[2].toDoubleOrNull() ?: 0.0
            val secs = when (m.groupValues[3].lowercase()) { "m" -> n * 60; "h" -> n * 3600; "d" -> n * 86400; else -> n }
            secs >= 10
        }
    }

    /** A tool that only waits (a long sleep, a wait loop, a wake-up timer). */
    fun isWaitTool(t: ToolCall): Boolean {
        val n = t.name.lowercase()
        if (n.contains("schedulewakeup") || n == "monitor" || n.contains("sleep") || n == "wait") return true
        val shell = n == "bash" || n == "shell" || n.contains("terminal") || n.contains("command") || n == "run_command"
        return shell && isWaitCommand(t.command)
    }

    private suspend fun loop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(1500)
            try {
                if (!JarvisController.isAttached || !JarvisPreferences.enabled.value) continue
                val owners = (JarvisManagers.textKeys() + JarvisManagers.VOICE + queues.keys).distinct()
                for (o in owners) runCatching { tick(o) }.onFailure { Log.w(TAG, "tick $o: ${it.message}") }
            } catch (e: Exception) {
                Log.w(TAG, "loop: ${e.message}")
            }
        }
    }

    private fun ownsActive(owner: String) = JarvisTasks.ownedBy(owner).any { it.active }

    private suspend fun tick(owner: String) {
        val now = System.currentTimeMillis()
        val voice = owner == JarvisManagers.VOICE
        if (!voice && !JarvisManagers.isTextManager(owner)) {
            // that Jarvis chat is gone: its news (and its tasks) belong to the voice chat now
            redirectToVoice(owner)
            return
        }
        val chatOnly = !JarvisPreferences.voiceAssistant.value
        if (voice && (JarvisPreferences.agentKind == null || chatOnly) && pending(owner) > 0) {
            // Keep events durable until a manager session can receive them.
            val n = pending(owner)
            if (!droppedVoiceNotice) {
                droppedVoiceNotice = true
                val why = if (chatOnly) "Jarvis 已设为仅聊天（设置 → Jarvis → 悬浮按钮与语音）"
                    else "未设置语音智能体（设置 → Jarvis → 语音智能体）"
                JarvisController.showProblem("有 $n 条发给语音 Jarvis 的更新无法送达：$why。")
            }
            return
        }
        if (voice) droppedVoiceNotice = false
        val queued = queues[owner]?.toList().orEmpty()
        val oldest = queued.minOfOrNull { it.at }
        val active = ownsActive(owner)
        var busy = if (voice) JarvisController.isVoiceChatBusy() else JarvisDriver.isBusy(owner)
        // a Jarvis text chat with something going on keeps a live backend (its state, messages and approvals stay current)
        if (!voice && (queued.isNotEmpty() || active || busy || inFlight.containsKey(owner))) {
            JarvisDriver.ensure(owner)
            busy = busy || JarvisDriver.isBusy(owner)
            val offScreen = JarvisController.vm.currentConversation.value?.id != owner
            if (busy && offScreen && JarvisDriver.agentOf(owner) == ai.antigem.domain.model.AgentKind.AGY) JarvisTasks.refreshAgyPending(owner, 4000)
        }

        // ---- turn edges (text managers: read the reply out when voice replies are on)
        val was = busyBefore.put(owner, busy) ?: false
        if (busy) { idleSince.remove(owner); if (!was) sentAt.remove(owner) }
        else if (was || idleSince[owner] == null) idleSince[owner] = now
        // news sent to a text chat counts as delivered once the chat works on it (or shows a new message); a send that
        // silently did nothing (the agent offline, the chat not loaded) puts it back in the queue
        inFlight[owner]?.let { f ->
            val grew = if (voice) JarvisPreferences.currentChatMessages.value != f.messagesBefore
                else (JarvisDriver.liveMessages(owner)?.size ?: -1) > f.messagesBefore && f.messagesBefore >= 0
            when {
                busy || grew -> acknowledgeDelivery(owner, f.batchId)
                now - f.at > 30_000 -> {
                    Log.w(TAG, "news to $owner was not taken up: queued again")
                    retryDelivery(owner, f.batchId, "manager turn did not start within 30 seconds")
                    sentAt[owner] = now
                }
            }
        }
        if (!voice && was && !busy) onTextTurnEnded(owner)

        // ---- the guard: waiting for tasks inside a turn
        if (busy && (queued.isNotEmpty() || active)) guard(owner, queued.isNotEmpty(), now) else waitSeen.remove(owner)

        // ---- delivery
        if (queued.isNotEmpty() && oldest != null && now - oldest >= JarvisPreferences.eventBatchMs.value.toLong().coerceIn(0, 30_000)) {
            if (voice) {
                if (now >= holdVoiceUntil && !voiceFlushPending) { voiceFlushPending = true; JarvisController.requestEventFlush() }
            } else if (!busy && inFlight[owner] == null && now - (sentAt[owner] ?: 0L) > 25_000) {
                val before = JarvisDriver.liveMessages(owner)?.size ?: -1
                val delivery = beginDelivery(owner, before)
                if (delivery != null) {
                    sentAt[owner] = now
                    JarvisManagers.markSent(owner)
                    spokeInTurn -= owner
                    JarvisDriver.send(owner, format(owner, delivery.events, takeGuardNote(owner), delivery.batchId)).onSuccess {
                        markDeliveryAccepted(owner, delivery.batchId)
                    }.onFailure { e ->
                        retryDelivery(owner, delivery.batchId, e.message.orEmpty())
                        sentAt[owner] = now
                    }
                }
            }
        }

        // ---- a text chat with nothing going on for a while does not need its own backend any more
        val m = JarvisManagers.of(owner)
        if (!voice && queued.isEmpty() && !active && !busy && !m.mission.autopilot && now - (idleSince[owner] ?: now) > 180_000 && JarvisDriver.isAttached(owner)) {
            JarvisDriver.release(owner)
        }

        // ---- autopilot
        if (m.mission.isActive && m.mission.autopilot && !busy && queued.isEmpty() && !active && inFlight[owner] == null) {
            val idleFor = now - (idleSince[owner] ?: now)
            val sinceNudge = now - (lastNudgeAt[owner] ?: 0L)
            val sinceSent = now - (sentAt[owner] ?: 0L)
            if (idleFor > 30_000 && sinceNudge > 60_000 && sinceSent > 25_000 && !(voice && JarvisController.isUserTalking())) {
                // looked at now: the next look is a minute away, whether it nudges or not
                lastNudgeAt[owner] = now
                if (waitingForUser(owner)) return
                val max = JarvisPreferences.autopilotMaxNudges.value.coerceIn(1, 20)
                if (m.mission.nudges >= max) {
                    JarvisManagers.update(owner) { it.copy(mission = it.mission.copy(autopilot = false, nudges = 0)) }
                    post(owner, Event("autopilot_paused", body = "自动驾驶连续提醒了你 $max 次却没有任何进展，因此已暂停。"))
                } else {
                    JarvisManagers.update(owner) { it.copy(mission = it.mission.copy(nudges = it.mission.nudges + 1)) }
                    val left = m.mission.steps.filter { it.status != "done" }.joinToString("; ") { "${it.n}. ${it.text}" }.take(400)
                    post(owner, Event("nudge", body = "使命「${m.mission.goal.take(120)}」尚未完成，也没有团队在工作。" +
                        (if (left.isNotBlank()) " 待办步骤：$left" else "") + "（第 ${m.mission.nudges + 1} 次提醒，共 $max 次）"))
                }
            }
        }
    }

    /** The manager's last words asked the user something: the answer is theirs to give, the autopilot does not push. */
    private suspend fun waitingForUser(owner: String): Boolean {
        val last = if (owner == JarvisManagers.VOICE) JarvisController.said.value else runCatching { JarvisDriver.lastReply(owner) }.getOrDefault("")
        return last.trimEnd().endsWith("?")
    }

    private suspend fun guard(owner: String, hasNews: Boolean, now: Long) {
        val mode = JarvisPreferences.waitGuard.value
        if (mode == "off") return
        val voice = owner == JarvisManagers.VOICE
        val agy = (if (voice) JarvisPreferences.currentChatAgent.value else JarvisDriver.agentOf(owner).name) == ai.antigem.domain.model.AgentKind.AGY.name
        // Antigravity's steps come from the server: look at most every 15 s
        if (agy && now - (agyCheckedAt[owner] ?: 0L) < 15_000) return
        if (agy) agyCheckedAt[owner] = now
        val tool = if (voice) JarvisController.voiceRunningTool() else JarvisDriver.runningTool(owner)
        if (tool == null || !isWaitTool(tool)) { waitSeen.remove(owner); return }
        val seen = waitSeen[owner]?.takeIf { it.first == tool.id } ?: (tool.id to now).also { waitSeen[owner] = it }
        val waited = now - seen.second
        val what = tool.command.trim().take(60).ifBlank { tool.name }
        val note = "You were waiting inside your turn (`$what`) for work you delegated. Never do that: delegate, then end your turn. " +
            "The app delivers every result to you by itself as a <jarvis_events> message."
        if (mode == "remind") { guardNotes[owner] = note; return }
        if ((hasNews && waited > 3_000) || waited > 12_000) {
            Log.i(TAG, "stopping $owner: it waits ($what)")
            waitSeen.remove(owner)
            guardNotes[owner] = "The app stopped your previous turn. $note"
            if (voice) JarvisController.stopVoiceTurnForNews() else JarvisDriver.stop(owner)
        }
    }

    private suspend fun onTextTurnEnded(owner: String) {
        val m = JarvisManagers.get(owner) ?: return
        JarvisManagers.setStatus(owner, null)
        if (!m.voiceOn) { spokeInTurn -= owner; return }
        if (spokeInTurn.remove(owner)) return
        delay(600)
        val reply = JarvisDriver.lastReply(owner)
        if (reply.isBlank() || spokenReply[owner] == reply) return
        spokenReply[owner] = reply
        if (reply.contains(JarvisSkill.MCP_MISSING)) return
        JarvisController.announce(JarvisController.spokenForm(reply))
    }
}
