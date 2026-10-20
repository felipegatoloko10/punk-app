package com.punk.bridge

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CancellationBarrierTest {
    @Test fun waitsForNativeCleanupBeforeAcknowledging() = runBlocking {
        var cleaned=false
        val task=launch { try { awaitCancellation() } finally { withContext(NonCancellable) { delay(25); cleaned=true } } }
        yield()
        val stopped=CancellationBarrier.stop(listOf(task), {}, { cleaned })
        assertTrue(stopped); assertTrue(cleaned)
    }
    @Test fun timeoutDoesNotClaimStopped() = runBlocking {
        assertFalse(CancellationBarrier.stop(emptyList(), {}, { delay(100); true }, 5))
    }
    @Test fun resourceFailureDoesNotClaimStopped() = runBlocking {
        assertFalse(CancellationBarrier.stop(emptyList(), { error("resource still active") }, { true }))
    }
}
