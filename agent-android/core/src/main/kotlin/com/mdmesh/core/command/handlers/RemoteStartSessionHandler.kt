package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import com.mdmesh.proto.RemoteStartSessionPayload
import com.mdmesh.remote.RemoteControlSession

/**
 * Handles `remote.startSession` command by dispatching to [RemoteControlSession.start].
 */
class RemoteStartSessionHandler(
    private val session: RemoteControlSession,
    private val eventSink: EventSink? = null,
) : CommandHandler {

    override val type: String = "remote.startSession"

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payloadJson = command.payload ?: return CommandResults.failed(command, "missing payload")
        val payload = runCatching {
            ProtocolJson.json.decodeFromString<RemoteStartSessionPayload>(payloadJson.toString())
        }.getOrElse {
            eventSink?.record("remote.startSession", "Invalid payload: ${it.message}")
            return CommandResults.failed(command, "invalid payload: ${it.message}")
        }

        eventSink?.record("remote.startSession", "Received request for screen sharing (session: ${payload.sessionId}, mode: ${payload.mode})")

        val mode = if (payload.mode.equals("control", ignoreCase = true)) {
            RemoteControlSession.Mode.CONTROL
        } else {
            RemoteControlSession.Mode.VIEW
        }

        val result = session.start(payload.sessionId, mode)
        return if (result.isSuccess) {
            eventSink?.record("remote.startSession", "Screen sharing started (session: ${payload.sessionId})")
            CommandResults.done(command, "remote session started: ${payload.sessionId}")
        } else {
            val err = result.exceptionOrNull()?.message ?: "start session failed"
            eventSink?.record("remote.startSession", "Screen sharing failed: $err")
            CommandResults.failed(command, err)
        }
    }
}
