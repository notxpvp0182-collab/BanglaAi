package com.personal.bengaliagent.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Lightweight in-process command bus connecting Notification actions and Floating Overlay buttons
 * directly to the active session controller in MainActivity/AgentForegroundService.
 */
object AgentSessionBus {
    enum class Command {
        TOGGLE_MUTE,
        TOGGLE_PAUSE,
        STOP_CURRENT_TASK,
        DISABLE_SESSION
    }

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: SharedFlow<Command> = _commands

    fun dispatch(command: Command) {
        _commands.tryEmit(command)
    }
}
