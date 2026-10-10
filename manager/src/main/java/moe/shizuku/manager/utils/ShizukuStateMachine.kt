package moe.shizuku.manager.utils

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import rikka.shizuku.Shizuku

object ShizukuStateMachine {

    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    private val state = AtomicReference<State>(State.STOPPED)
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val listenersRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

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

        Log.i("ShizukuStateMachine", "State transition: $oldState -> $newState")
        listeners.forEach { it(newState) }
    }

    fun set(newState: State) = transition { newState }

    fun setDead() {
        var oldState: State
        var newState: State
        do {
            oldState = state.get()
            newState = when (oldState) {
                State.RUNNING -> State.CRASHED
                State.STOPPING -> State.STOPPED
                else -> oldState
            }
            if (oldState == newState) return
        } while (!state.compareAndSet(oldState, newState))

        Log.i("ShizukuStateMachine", "State transition: $oldState -> $newState")
        listeners.forEach { it(newState) }

        if (oldState == State.STOPPING && newState == State.STOPPED) {
            try {
                val context = ShizukuApplication.appContext
                val permissionGranted = context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                val shouldDisableUsbDebugging = permissionGranted && ShizukuSettings.getAutoDisableUsbDebugging()
                if (shouldDisableUsbDebugging) {
                    Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
                }
            } catch (e: Exception) {
                Log.w("ShizukuStateMachine", "Failed to disable USB debugging", e)
            }
        }
    }

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

    fun asFlow(): Flow<State> = callbackFlow {
        val listener: (State) -> Unit = { trySend(it).isSuccess }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }

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
