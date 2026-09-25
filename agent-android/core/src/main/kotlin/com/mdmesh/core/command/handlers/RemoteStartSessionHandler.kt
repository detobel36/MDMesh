package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
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
) : CommandHandler {

    override val type: String = "remote.startSession"

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payloadJson = command.payload ?: return CommandResults.failed(command, "missing payload")
        val payload = runCatching {
            ProtocolJson.json.decodeFromString<RemoteStartSessionPayload>(payloadJson.toString())
        }.getOrElse {
            return CommandResults.failed(command, "invalid payload: ${it.message}")
        }

        val mode = if (payload.mode.equals("control", ignoreCase = true)) {
            RemoteControlSession.Mode.CONTROL
        } else {
            RemoteControlSession.Mode.VIEW
        }

        val result = session.start(payload.sessionId, mode)
        return if (result.isSuccess) {
            CommandResults.done(command, "remote session started: ${payload.sessionId}")
        } else {
            CommandResults.failed(command, result.exceptionOrNull()?.message ?: "start session failed")
        }
    }
}
