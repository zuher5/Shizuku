package moe.shizuku.manager.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuStateMachineTest {

    @Before
    fun setUp() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
    }

    @Test
    fun testInitialStoppedState() {
        assertEquals(ShizukuStateMachine.State.STOPPED, ShizukuStateMachine.get())
        assertFalse(ShizukuStateMachine.isRunning())
        assertTrue(ShizukuStateMachine.isDead())
    }

    @Test
    fun testRunningState() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.RUNNING)
        assertEquals(ShizukuStateMachine.State.RUNNING, ShizukuStateMachine.get())
        assertTrue(ShizukuStateMachine.isRunning())
        assertFalse(ShizukuStateMachine.isDead())
    }

    @Test
    fun testStartingState() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        assertEquals(ShizukuStateMachine.State.STARTING, ShizukuStateMachine.get())
        assertFalse(ShizukuStateMachine.isRunning())
        assertFalse(ShizukuStateMachine.isDead())
    }

    @Test
    fun testStoppingState() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
        assertEquals(ShizukuStateMachine.State.STOPPING, ShizukuStateMachine.get())
        assertFalse(ShizukuStateMachine.isRunning())
        assertFalse(ShizukuStateMachine.isDead())
    }

    @Test
    fun testGracefulStopTransitionsToStopped() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.RUNNING)
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
        ShizukuStateMachine.setDead()
        assertEquals(ShizukuStateMachine.State.STOPPED, ShizukuStateMachine.get())
        assertFalse(ShizukuStateMachine.isRunning())
        assertTrue(ShizukuStateMachine.isDead())
    }

    @Test
    fun testUnexpectedDeathTransitionsToCrashed() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.RUNNING)
        ShizukuStateMachine.setDead()
        assertEquals(ShizukuStateMachine.State.CRASHED, ShizukuStateMachine.get())
        assertFalse(ShizukuStateMachine.isRunning())
        assertTrue(ShizukuStateMachine.isDead())
    }

    @Test
    fun testListenerNotificationAndRemoval() {
        val observed = CopyOnWriteArrayList<ShizukuStateMachine.State>()
        val listener: (ShizukuStateMachine.State) -> Unit = { observed.add(it) }

        ShizukuStateMachine.addListener(listener)
        // Listener receives initial state immediately
        assertEquals(listOf(ShizukuStateMachine.State.STOPPED), observed)

        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        ShizukuStateMachine.set(ShizukuStateMachine.State.RUNNING)

        assertEquals(
            listOf(
                ShizukuStateMachine.State.STOPPED,
                ShizukuStateMachine.State.STARTING,
                ShizukuStateMachine.State.RUNNING
            ),
            observed
        )

        ShizukuStateMachine.removeListener(listener)
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)

        // No new events after removal
        assertEquals(3, observed.size)
    }

    @Test
    fun testConcurrentStateUpdates() {
        val threadCount = 8
        val iterations = 50
        val latch = CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            Thread {
                try {
                    for (j in 0 until iterations) {
                        if (j % 2 == 0) {
                            ShizukuStateMachine.set(ShizukuStateMachine.State.RUNNING)
                        } else {
                            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val finalState = ShizukuStateMachine.get()
        assertTrue(finalState == ShizukuStateMachine.State.RUNNING || finalState == ShizukuStateMachine.State.STOPPED)
    }
}
