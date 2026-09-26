package com.mdmesh.core.command

import com.mdmesh.core.command.handlers.RemoteStartSessionHandler
import com.mdmesh.core.command.handlers.RemoteStopSessionHandler
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.ProtocolJson
import com.mdmesh.proto.RemoteStartSessionPayload
import com.mdmesh.proto.RemoteStopSessionPayload
import com.mdmesh.remote.RemoteControlSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSessionHandlersTest {

    private class FakeSession : RemoteControlSession {
        var startedId: String? = null
        var startedMode: RemoteControlSession.Mode? = null
        var stoppedId: String? = null
        var active = false

        override suspend fun start(sessionId: String, mode: RemoteControlSession.Mode): Result<Unit> {
            startedId = sessionId
            startedMode = mode
            active = true
            return Result.success(Unit)
        }

        override suspend fun stop(sessionId: String): Result<Unit> {
            stoppedId = sessionId
            active = false
            return Result.success(Unit)
        }

        override fun isActive(): Boolean = active
    }

    @Test
    fun `RemoteStartSessionHandler starts session successfully`() = runBlocking {
        val fakeSession = FakeSession()
        val handler = RemoteStartSessionHandler(fakeSession)

        val payload = RemoteStartSessionPayload(sessionId = "sess-123", mode = "view")
        val payloadJson = ProtocolJson.json.encodeToJsonElement(RemoteStartSessionPayload.serializer(), payload).jsonObject
        val envelope = CommandEnvelope(
            commandId = "cmd-1",
            issuedAt = "2026-09-25T00:00:00Z",
            type = "remote.startSession",
            payload = payloadJson
        )

        val result = handler.handle(envelope)
        assertEquals(CommandStatus.DONE, result.status)
        assertEquals("sess-123", fakeSession.startedId)
        assertEquals(RemoteControlSession.Mode.VIEW, fakeSession.startedMode)
        assertTrue(fakeSession.isActive())
    }

    @Test
    fun `RemoteStopSessionHandler stops session successfully`() = runBlocking {
        val fakeSession = FakeSession()
        fakeSession.start("sess-123", RemoteControlSession.Mode.VIEW)
        val handler = RemoteStopSessionHandler(fakeSession)

        val payload = RemoteStopSessionPayload(sessionId = "sess-123")
        val payloadJson = ProtocolJson.json.encodeToJsonElement(RemoteStopSessionPayload.serializer(), payload).jsonObject
        val envelope = CommandEnvelope(
            commandId = "cmd-2",
            issuedAt = "2026-09-25T00:00:00Z",
            type = "remote.stopSession",
            payload = payloadJson
        )

        val result = handler.handle(envelope)
        assertEquals(CommandStatus.DONE, result.status)
        assertEquals("sess-123", fakeSession.stoppedId)
        assertTrue(!fakeSession.isActive())
    }
}
