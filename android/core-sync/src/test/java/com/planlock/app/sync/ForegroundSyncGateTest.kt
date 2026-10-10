package com.planlock.app.sync

import org.junit.Assert.*
import org.junit.Test

class ForegroundSyncGateTest {
    @Test fun initialAndRepeatedResumeDoNotRequest() {
        val gate = ForegroundSyncGate()
        assertFalse(gate.resumed())
        gate.stopped(changingConfigurations = false)
        assertTrue(gate.resumed())
        assertFalse(gate.resumed())
        gate.stopped(changingConfigurations = false)
        assertTrue(gate.resumed())
    }

    @Test fun rotationDoesNotRequest() {
        val gate = ForegroundSyncGate()
        gate.stopped(changingConfigurations = true)
        assertFalse(gate.resumed())
    }

    @Test fun newActivityObserverConsumesTheProcessOwnedPendingReturn() {
        val processGate = ForegroundSyncGate()
        val oldObserverStop = { processGate.stopped(changingConfigurations = false) }
        oldObserverStop()
        val replacementObserverResume = { processGate.resumed() }
        assertTrue(replacementObserverResume())
        assertFalse(replacementObserverResume())
    }

    @Test fun configurationStopDoesNotEraseAPendingReturn() {
        val gate = ForegroundSyncGate()
        gate.stopped(changingConfigurations = false)
        gate.stopped(changingConfigurations = true)
        assertTrue(gate.resumed())
    }
}
