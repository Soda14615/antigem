package ai.antigem.data.jarvis

/** Pure orchestration invariants shared by the runtime and its regression tests. */
internal object JarvisOrchestrationRules {
    fun validRoleGraph(graph: Map<String, List<String>>): Boolean {
        val normalized = graph.mapKeys { it.key.lowercase() }.mapValues { (_, values) -> values.map { it.lowercase() } }
        if (normalized.size != graph.size) return false
        val visiting = HashSet<String>()
        val visited = HashSet<String>()
        fun visit(key: String): Boolean {
            if (key in visiting) return false
            if (key in visited) return true
            val children = normalized[key] ?: return false
            visiting += key
            if (!children.all { visit(it) }) return false
            visiting -= key
            visited += key
            return true
        }
        return normalized.keys.all { visit(it) }
    }

    /** Active tasks and explicitly protected history cannot be evicted by the terminal history limit. */
    fun <T> retain(ordered: List<T>, limit: Int, active: (T) -> Boolean, protected: (T) -> Boolean): List<T> {
        var remaining = limit.coerceAtLeast(0)
        return ordered.filter { task ->
            if (active(task) || protected(task)) true
            else if (remaining > 0) { remaining--; true }
            else false
        }
    }

    fun childWorkPending(activeChildren: Boolean, pendingEvents: Boolean): Boolean =
        activeChildren || pendingEvents

    /** An idle provider without a current-turn result is not evidence of success. */
    fun canCompleteIdle(observedRunning: Boolean, freshReply: Boolean, delegated: Boolean, missionDone: Boolean, awaitingChildren: Boolean): Boolean =
        (observedRunning || freshReply) && (!delegated || missionDone && !awaitingChildren)
}
