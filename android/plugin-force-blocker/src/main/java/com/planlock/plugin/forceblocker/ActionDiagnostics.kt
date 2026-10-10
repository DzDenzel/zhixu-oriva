package com.planlock.plugin.forceblocker

import com.planlock.plugin.PluginDecision

/** Plugin-side diagnostics observe the host decision and never reclassify packages. */
internal object ExemptionDiagnostics {
    fun snapshot(decision: PluginDecision): PluginDecision = decision
}

internal object ExemptionGapAnalyzer {
    fun hasDecisionGap(decision: PluginDecision): Boolean = decision.packageName.isBlank()
}

internal object RuntimeExemptionGuard {
    fun allowAction(decision: PluginDecision): Boolean = decision.shouldTrack && !ExemptionGapAnalyzer.hasDecisionGap(decision)
}
