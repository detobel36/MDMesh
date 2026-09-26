package com.mdmesh.proto

import kotlinx.serialization.Serializable

/** Payload for `remote.startSession` command. */
@Serializable
data class RemoteStartSessionPayload(
    val sessionId: String,
    val mode: String = "view",
)

/** Payload for `remote.stopSession` command. */
@Serializable
data class RemoteStopSessionPayload(
    val sessionId: String,
)

/** WebRTC ICE candidate wire model. */
@Serializable
data class IceCandidateDto(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
)

/** Signaling message exchanged between agent, server, and browser. */
@Serializable
data class RemoteSignalDto(
    val sessionId: String,
    val type: String, // "offer", "answer", "iceCandidate", "status", "stop"
    val sdp: String? = null,
    val candidate: IceCandidateDto? = null,
    val status: String? = null, // "active", "connected", "error", "closed"
    val message: String? = null,
)
