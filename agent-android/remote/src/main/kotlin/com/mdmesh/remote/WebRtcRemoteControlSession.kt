package com.mdmesh.remote

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.mdmesh.proto.IceCandidateDto
import com.mdmesh.proto.RemoteSignalDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live screen-viewing [RemoteControlSession] powered by WebRTC and MediaProjection.
 */
class WebRtcRemoteControlSession(
    private val context: Context,
    private val sendSignal: (suspend (RemoteSignalDto) -> Unit)? = null,
    private val fetchSignals: (suspend () -> List<RemoteSignalDto>)? = null,
    private val mediaProjectionData: Intent? = null,
) : RemoteControlSession {

    @Volatile private var activeSessionId: String? = null
    private val isRunning = AtomicBoolean(false)
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var signalingJob: Job? = null

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    override suspend fun start(sessionId: String, mode: RemoteControlSession.Mode): Result<Unit> {
        if (isRunning.getAndSet(true)) {
            if (activeSessionId == sessionId) return Result.success(Unit)
            stop(activeSessionId ?: "")
        }

        activeSessionId = sessionId
        return runCatching {
            initWebRtcAndStartCapture(sessionId)
            startSignalingLoop(sessionId)
            Unit
        }.onFailure {
            stop(sessionId)
        }
    }

    override suspend fun stop(sessionId: String): Result<Unit> {
        if (activeSessionId != null && activeSessionId != sessionId) {
            return Result.success(Unit)
        }
        activeSessionId = null
        isRunning.set(false)

        signalingJob?.cancel()
        signalingJob = null

        cleanupWebRtc()
        return Result.success(Unit)
    }

    override fun isActive(): Boolean = isRunning.get() && activeSessionId != null

    private fun initWebRtcAndStartCapture(sessionId: String) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val factoryOptions = PeerConnectionFactory.Options()
        factory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch {
                    val dto = RemoteSignalDto(
                        sessionId = sessionId,
                        type = "iceCandidate",
                        candidate = IceCandidateDto(
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex
                        )
                    )
                    runCatching { sendSignal?.invoke(dto) }
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("Failed to create PeerConnection")

        val intentData = mediaProjectionData ?: MediaProjectionDataStore.projectionData
        if (intentData != null) {
            ScreenCaptureService.startService(context)
            val screenCapturer = ScreenCapturerAndroid(intentData, object : MediaProjection.Callback() {
                override fun onStop() {
                    scope.launch { stop(sessionId) }
                }
            })
            capturer = screenCapturer
            surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", null)
            videoSource = factory?.createVideoSource(screenCapturer.isScreencast)
            surfaceTextureHelper?.let { helper ->
                videoSource?.let { vSource ->
                    screenCapturer.initialize(helper, context, vSource.capturerObserver)
                    screenCapturer.startCapture(720, 1280, 30)
                }
            }
        } else {
            // Prompt for MediaProjection capture consent via Activity
            val promptIntent = Intent(context, ScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(promptIntent) }
            videoSource = factory?.createVideoSource(true)
        }

        videoSource?.let { vSource ->
            val vTrack = factory?.createVideoTrack("ARDAMSv0", vSource)
            videoTrack = vTrack
            if (vTrack != null) {
                peerConnection?.addTrack(vTrack, listOf("ARDAMS"))
            }
        }

        // Create WebRTC Offer
        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                val localSdp = sdp ?: return
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        scope.launch {
                            val offerDto = RemoteSignalDto(
                                sessionId = sessionId,
                                type = "offer",
                                sdp = localSdp.description
                            )
                            runCatching { sendSignal?.invoke(offerDto) }
                        }
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, localSdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, mediaConstraints)
    }

    private fun startSignalingLoop(sessionId: String) {
        val getter = fetchSignals ?: return
        signalingJob = scope.launch {
            while (isActive && isRunning.get()) {
                val signals = runCatching { getter() }.getOrNull()
                if (signals != null) {
                    for (sig in signals) {
                        handleIncomingSignal(sig)
                    }
                }
                delay(500)
            }
        }
    }

    private fun handleIncomingSignal(signal: RemoteSignalDto) {
        when (signal.type) {
            "answer" -> {
                val sdpStr = signal.sdp ?: return
                val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpStr)
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, remoteSdp)
            }
            "iceCandidate" -> {
                val cand = signal.candidate ?: return
                val iceCandidate = IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.candidate)
                peerConnection?.addIceCandidate(iceCandidate)
            }
            "stop" -> {
                scope.launch { stop(signal.sessionId) }
            }
        }
    }

    private fun cleanupWebRtc() {
        runCatching { ScreenCaptureService.stopService(context) }
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null

        runCatching { videoTrack?.dispose() }
        videoTrack = null

        runCatching { videoSource?.dispose() }
        videoSource = null

        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        runCatching { peerConnection?.close() }
        peerConnection = null

        runCatching { factory?.dispose() }
        factory = null
    }
}
