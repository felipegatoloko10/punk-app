package com.punk.bridge

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull

/** Cancellation is a request; a remote acknowledgement is only safe after work and resources settle. */
object CancellationBarrier {
    suspend fun stop(jobs: List<Job>, stopResources: () -> Unit, awaitResources: suspend () -> Boolean, timeoutMs: Long = 5_000): Boolean {
        jobs.forEach { it.cancel() }
        return try {
            stopResources()
            withTimeoutOrNull(timeoutMs) { jobs.joinAll(); awaitResources() } ?: false
        } catch (_: Exception) { false }
    }
}
