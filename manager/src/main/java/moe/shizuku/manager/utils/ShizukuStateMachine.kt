package moe.shizuku.manager.utils

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.adb.AdbArm
import moe.shizuku.manager.service.WatchdogManager
import rikka.shizuku.Shizuku

/**
 * Centralized state machine for the Shizuku server lifecycle.
 *
 * Replaces scattered pingBinder() checks with a single source of truth. The
 * state is driven by the binder received/dead listeners and by explicit
 * lifecycle calls, so a restart is not mistaken for a crash.
 *
 * States:
 * - STARTING: the server is being launched
 * - RUNNING: the binder is alive
 * - STOPPING: a stop was requested, waiting for the binder to die
 * - STOPPED: the binder is dead and the stop was expected
 * - CRASHED: the binder died unexpectedly
 */
object ShizukuStateMachine {

    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    private const val TAG = "ShizukuStateMachine"

    private val state = AtomicReference<State>(State.STOPPED)
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val listenersRegistered = AtomicBoolean(false)

    init {
        registerListeners()
        if (Shizuku.pingBinder()) {
            state.set(State.RUNNING)
        }
    }

    private fun registerListeners() {
        if (!listenersRegistered.compareAndSet(false, true)) return
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener { set(State.RUNNING) }
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener { setDead() }
        )
    }

    fun get(): State = state.get()

    private fun transition(transform: (State) -> State) {
        var oldState: State
        var newState: State
        do {
            oldState = state.get()
            newState = transform(oldState)
            if (oldState == newState) return
        } while (!state.compareAndSet(oldState, newState))

        Log.i(TAG, "State transition: $oldState -> $newState")
        // Iterate over a snapshot so a listener that adds or removes listeners
        // cannot disturb this notification.
        java.util.ArrayList<(State) -> Unit>().apply {
            listeners.forEach { add(it) }
        }.forEach { it(newState) }
    }

    fun set(newState: State) = transition { newState }

    /**
     * Called before the server is started.
     *
     * When the server is currently running this is an intentional restart: the
     * binder death that follows is expected and must not be reported as a
     * crash.
     */
    fun onStartRequested() {
        if (isRunning() || Shizuku.pingBinder()) {
            WatchdogManager.expectDeath()
        }
        set(State.STARTING)
    }

    /**
     * Called when a start attempt failed or timed out. Reconciles the state
     * with what the binder actually reports.
     */
    fun onStartFailed() {
        set(if (Shizuku.pingBinder()) State.RUNNING else State.STOPPED)
    }

    /** Called before the server is stopped. */
    fun onStopRequested() {
        set(State.STOPPING)
    }

    /**
     * Called when the binder died.
     *
     * A requested stop ends in STOPPED (and disarms USB debugging when that
     * is configured). A death during an expected restart keeps the state at
     * STARTING, and an unexpected death is a CRASH.
     */
    fun setDead() = transition {
        when {
            it == State.STOPPING || WatchdogManager.isUserStopRequested() -> {
                disarmUsbDebugging()
                State.STOPPED
            }
            it == State.RUNNING -> {
                if (WatchdogManager.isExpectingDeathActive()) State.STARTING else State.CRASHED
            }
            it == State.STARTING -> State.STARTING
            else -> it
        }
    }

    private fun disarmUsbDebugging() {
        try {
            val context = moe.shizuku.manager.ShizukuApplication.appContext
            if (moe.shizuku.manager.ShizukuSettings.getAutoDisableUsbDebugging()) {
                AdbArm.disarmUsbDebugging(context)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to auto-disable USB debugging", e)
        }
    }

    /** Refreshes the state from the binder. */
    fun update(): State {
        val currentState = if (Shizuku.pingBinder()) State.RUNNING else State.STOPPED
        set(currentState)
        return currentState
    }

    fun isRunning(): Boolean = get() == State.RUNNING

    fun isDead(): Boolean = get() == State.STOPPED || get() == State.CRASHED

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state.get())
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    /** Emits the current state immediately, then every transition. */
    fun asFlow(): Flow<State> = callbackFlow {
        val listener: (State) -> Unit = { trySend(it).isSuccess }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }

    /** Suspends until the server is running, or the timeout elapses. */
    suspend fun awaitRunning(timeoutMs: Long = 10_000L): Boolean {
        if (isRunning() || Shizuku.pingBinder()) {
            if (!isRunning()) set(State.RUNNING)
            return true
        }
        return withTimeoutOrNull(timeoutMs) {
            asFlow().first { it == State.RUNNING }
            true
        } ?: (isRunning() || Shizuku.pingBinder())
    }

    /** Suspends until the server stopped, or the timeout elapses. */
    suspend fun awaitStopped(timeoutMs: Long = 5_000L): Boolean {
        if (isDead() && !Shizuku.pingBinder()) {
            return true
        }
        return withTimeoutOrNull(timeoutMs) {
            asFlow().first { it == State.STOPPED || it == State.CRASHED }
            true
        } ?: (isDead() || !Shizuku.pingBinder())
    }
}
