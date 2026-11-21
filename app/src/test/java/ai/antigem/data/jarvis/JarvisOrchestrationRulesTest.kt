package ai.antigem.data.jarvis

import org.junit.Assert.*
import org.junit.Test

class JarvisOrchestrationRulesTest {
    @Test fun acceptsNestedRolesAndRejectsInvalidGraphs() {
        val graph = mapOf("Lead" to listOf("Planner"), "Planner" to listOf("Expert"), "Expert" to emptyList())
        assertTrue(JarvisOrchestrationRules.validRoleGraph(graph))
        assertFalse(JarvisOrchestrationRules.validRoleGraph(graph + ("Expert" to listOf("Lead"))))
        assertFalse(JarvisOrchestrationRules.validRoleGraph(graph + ("Expert" to listOf("Missing"))))
        assertFalse(JarvisOrchestrationRules.validRoleGraph(mapOf("Lead" to listOf("lead"))))
    }

    @Test fun activeAndProtectedTasksSurviveHistoryLimit() {
        data class Item(val id: Int, val active: Boolean, val protected: Boolean)
        val items = (1..80).map { Item(it, it > 40, it == 39) }
        val kept = JarvisOrchestrationRules.retain(items, 3, { it.active }, { it.protected })
        assertEquals((1..3).toList() + listOf(39) + (41..80).toList(), kept.map { it.id })
    }

    @Test fun managerCannotFinishWhileChildrenOrEventsRemain() {
        assertTrue(JarvisOrchestrationRules.childWorkPending(true, false))
        assertTrue(JarvisOrchestrationRules.childWorkPending(false, true))
        assertFalse(JarvisOrchestrationRules.childWorkPending(false, false))
        assertFalse(JarvisOrchestrationRules.canCompleteIdle(true, true, true, true, true))
        assertFalse(JarvisOrchestrationRules.canCompleteIdle(true, true, true, false, false))
        assertTrue(JarvisOrchestrationRules.canCompleteIdle(true, true, true, true, false))
    }

    @Test fun idleWithoutObservedRunOrFreshReplyCannotSucceed() {
        assertFalse(JarvisOrchestrationRules.canCompleteIdle(false, false, false, false, false))
        assertTrue(JarvisOrchestrationRules.canCompleteIdle(false, true, false, false, false))
        assertTrue(JarvisOrchestrationRules.canCompleteIdle(true, false, false, false, false))
    }
}
