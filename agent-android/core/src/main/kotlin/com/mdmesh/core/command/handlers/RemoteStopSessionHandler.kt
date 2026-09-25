package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import com.mdmesh.proto.RemoteStopSessionPayload
import com.mdmesh.remote.RemoteControlSession

/**
 * Handles `remote.stopSession` command by dispatching to [RemoteControlSession.stop].
 */
class RemoteStopSessionHandler(
    private val session: RemoteControlSession,
    private val eventSink: EventSink? = null,
) : CommandHandler {

    override val type: String = "remote.stopSession"

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payloadJson = command.payload ?: return CommandResults.failed(command, "missing payload")
        val payload = runCatching {
            ProtocolJson.json.decodeFromString<RemoteStopSessionPayload>(payloadJson.toString())
        }.getOrElse {
            return CommandResults.failed(command, "invalid payload: ${it.message}")
        }

        eventSink?.record("remote.stopSession", "Received request to stop screen sharing (session: ${payload.sessionId})")
        val result = session.stop(payload.sessionId)
        return if (result.isSuccess) {
            eventSink?.record("remote.stopSession", "Screen sharing stopped: ${payload.sessionId}")
            CommandResults.done(command, "remote session stopped: ${payload.sessionId}")
        } else {
            val err = result.exceptionOrNull()?.message ?: "stop session failed"
            eventSink?.record("remote.stopSession", "Failed to stop screen sharing: $err")
            CommandResults.failed(command, err)
        }
    }
}
