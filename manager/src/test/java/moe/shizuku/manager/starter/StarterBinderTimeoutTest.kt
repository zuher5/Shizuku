package moe.shizuku.manager.starter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeoutException

/**
 * A start that waits past its deadline is a different failure from a connection that was never
 * made: one is retried quietly as a connection failure, the other is reported to the user.
 */
class StarterBinderTimeoutTest {

    @Test
    fun theVerdictIsNotASocketTimeout() {
        // A java.util.concurrent.TimeoutException is what a socket read and a discovery deadline
        // throw, and the worker treats that as retryable and silent.
        assertFalse(StarterBinderTimeoutException(BINDER_WAIT_TIMEOUT_MS) is TimeoutException)
    }

    @Test
    fun theVerdictSaysWhatHappenedAndHowLongItWaited() {
        val message = StarterBinderTimeoutException(BINDER_WAIT_TIMEOUT_MS).message.orEmpty()
        assertTrue(message, message.contains("${BINDER_WAIT_TIMEOUT_MS}ms"))
        assertTrue(message, message.contains("no binder"))
    }

    @Test
    fun theDeadlineIsTheOneTheStartScreenPromises() {
        // The screen says "This may take up to 1 minute"; pinned so the message and the wait
        // cannot drift apart.
        assertTrue(BINDER_WAIT_TIMEOUT_MS == 60_000L)
    }
}
