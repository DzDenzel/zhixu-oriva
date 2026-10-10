package com.planlock.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginContributionRuntimeTest {
    @Test fun singleApplicationActionRejectsMissingInputBeforeCallingHandler() {
        val handler = Handler()
        val inputRow = row.copy(input = ContributionActionInput.APPLICATION_SINGLE)
        val plugins = listOf(loaded("a", handler, listOf(inputRow)))
        assertEquals(PluginActionStatus.UNAVAILABLE, dispatchContributionAction(plugins, "a", PluginActionEvent(42, "test")).status)
        assertEquals(0, handler.calls)
        assertEquals(PluginActionStatus.EXECUTED, dispatchContributionAction(plugins, "a", PluginActionEvent(42, "test", "example.app", "Example", 2_000_000L)).status)
        assertEquals(1, handler.calls)
    }
    private val row = PluginContribution.ActionRow("relax", PluginSlots.PLAN_DETAIL_PROTECTION, "测试动作", "test")
    private class Handler : PlanLockPlugin, PluginActionHandler {
        var calls = 0
        var crash = false
        override fun onDecision(decision: PluginDecision, capabilities: PluginCapabilities) = Unit
        override fun onAction(event: PluginActionEvent): String? {
            calls++
            if (crash) error("contained")
            return "完成 ${event.planId}"
        }
    }
    private fun loaded(id: String, instance: PlanLockPlugin = Handler(), rows: List<PluginContribution> = listOf(row)) = LoadedPlugin(
        PluginManifest(id, id, "1.0.0", 1, "sample.Entry", "official", PluginSourceType.OFFICIAL, contributions = rows), instance,
    )

    @Test fun projectionIsDeclarativeSortedAndDisappearsWithLoadedPlugin() {
        assertEquals(listOf("a", "z"), projectContributions(listOf(loaded("z"), loaded("a")), row.slot).map { it.pluginId })
        assertTrue(projectContributions(emptyList(), row.slot).isEmpty())
        assertTrue(projectContributions(listOf(loaded("a")), "unregistered").isEmpty())
        assertTrue(projectContributions(listOf(loaded("legacy", rows = emptyList())), row.slot).isEmpty())
    }

    @Test fun declaredActionCarriesPlanContext() {
        val handler = Handler()
        val result = dispatchContributionAction(listOf(loaded("a", handler)), "a", PluginActionEvent(42, "test"))
        assertEquals(PluginActionStatus.EXECUTED, result.status)
        assertEquals("完成 42", result.message)
        assertEquals(1, handler.calls)
    }

    @Test fun staleAndUndeclaredActionsNeverReachPlugin() {
        val handler = Handler()
        assertEquals(PluginActionStatus.UNAVAILABLE, dispatchContributionAction(emptyList(), "a", PluginActionEvent(42, "test")).status)
        assertEquals(PluginActionStatus.UNAVAILABLE, dispatchContributionAction(listOf(loaded("a", handler)), "a", PluginActionEvent(42, "undeclared")).status)
        assertEquals(0, handler.calls)
    }

    @Test fun oldDexContractNeedsNoNewMethod() {
        val old = object : PlanLockPlugin { override fun onDecision(decision: PluginDecision, capabilities: PluginCapabilities) = Unit }
        assertEquals(PluginActionStatus.UNAVAILABLE, dispatchContributionAction(listOf(loaded("old", old)), "old", PluginActionEvent(1, "test")).status)
    }

    @Test fun actionExceptionIsContained() {
        val handler = Handler().apply { crash = true }
        assertEquals(PluginActionStatus.FAILED_OPEN, dispatchContributionAction(listOf(loaded("a", handler)), "a", PluginActionEvent(42, "test")).status)
    }
}
