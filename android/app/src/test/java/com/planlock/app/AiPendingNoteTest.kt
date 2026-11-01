package com.planlock.app

import com.planlock.app.sync.BackendConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPendingNoteTest {
    @Test fun onlyAConfirmedSyncMayClaimConnection() {
        assertTrue(aiPendingNote(BackendConnectionState.CONNECTED).startsWith("已连接你的个人后端"))
        assertTrue(aiPendingNote(BackendConnectionState.UNREACHABLE).startsWith("暂时连不上你的个人后端"))
        assertTrue(aiPendingNote(BackendConnectionState.LOCAL_ONLY).startsWith("仅本地使用"))
        BackendConnectionState.entries.filter { it != BackendConnectionState.CONNECTED }
            .forEach { assertFalse(aiPendingNote(it).contains("已连接")) }
    }
}
