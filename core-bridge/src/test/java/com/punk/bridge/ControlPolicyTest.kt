package com.punk.bridge

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ControlPolicyTest {
    @After fun reset() { ControlPolicy.cancelAll(); ControlPolicy.applyMode(false) }
    @Test fun autonomousNeedsNoAppApproval() = runBlocking { ControlPolicy.applyMode(false); assertTrue(ControlPolicy.authorize("shell_exec","1",false)) }
    @Test fun supervisedReadsAreAvailable() = runBlocking { ControlPolicy.applyMode(true); assertTrue(ControlPolicy.authorize("device_status","2",true)) }
    @Test fun supervisedWriteWaitsForExactDecision() = runBlocking {
        ControlPolicy.applyMode(true)
        val result=async { ControlPolicy.authorize("shell_exec","3",false) }; yield()
        assertFalse(result.isCompleted); assertEquals("local:3",ControlPolicy.pending.value.single().id)
        ControlPolicy.respond("local:3",false); assertFalse(result.await()); assertTrue(ControlPolicy.pending.value.isEmpty())
    }
    @Test fun approvalIsConsumedOnce() = runBlocking {
        ControlPolicy.applyMode(true)
        val allowed=async { ControlPolicy.authorize("shell_exec","4",false) }; yield()
        ControlPolicy.respond("local:4",true); assertTrue(allowed.await())
        val another=async { ControlPolicy.authorize("shell_exec","5",false) }; yield()
        assertFalse(another.isCompleted); ControlPolicy.cancelAll(); assertFalse(another.await())
    }
}
