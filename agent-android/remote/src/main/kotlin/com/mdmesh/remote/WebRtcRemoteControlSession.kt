package com.mdmesh.remote

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.mdmesh.proto.IceCandidateDto
import com.mdmesh.proto.RemoteSignalDto
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
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

private const val TAG = "WebRtcRemoteControlSession"

/**
 * Live screen-viewing [RemoteControlSession] powered by WebRTC and MediaProjection.
 */
class WebRtcRemoteControlSession(
    private val context: Context,
    private val sendSignal: (suspend (RemoteSignalDto) -> Unit)? = null,
    private val fetchSignals: (suspend (String) -> List<RemoteSignalDto>)? = null,
    private val mediaProjectionData: Intent? = null,
    private val onEventLog: ((String, String?) -> Unit)? = null,
) : RemoteControlSession {

    private val eglBase: EglBase by lazy { EglBase.create() }
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
        Log.d(TAG, "Starting WebRTC remote session: $sessionId with mode: $mode")
        if (isRunning.getAndSet(true)) {
            if (activeSessionId == sessionId) return Result.success(Unit)
            stop(activeSessionId ?: "")
        }

        activeSessionId = sessionId
        return runCatching {
            initWebRtcAndStartCapture(sessionId)
            startSignalingLoop(sessionId)
            Log.d(TAG, "Successfully started WebRTC remote session: $sessionId")
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to start WebRTC remote session: $sessionId", e)
            stop(sessionId)
        }
    }

    override suspend fun stop(sessionId: String): Result<Unit> {
        Log.d(TAG, "Stopping WebRTC remote session: $sessionId")
        if (activeSessionId != null && activeSessionId != sessionId) {
            return Result.success(Unit)
        }
        activeSessionId = null
        isRunning.set(false)

        signalingJob?.cancel()
        signalingJob = null

        cleanupWebRtc()
        Log.d(TAG, "WebRTC remote session stopped: $sessionId")
        return Result.success(Unit)
    }

    override fun isActive(): Boolean = isRunning.get() && activeSessionId != null

    private fun initWebRtcAndStartCapture(sessionId: String) {
        Log.d(TAG, "Initializing PeerConnectionFactory and PeerConnection for session: $sessionId")
        factory = getOrCreateFactory(context, eglBase.eglBaseContext)

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Local ICE candidate gathered for session $sessionId: ${candidate.sdpMid}")
                onEventLog?.invoke("webrtc.ice", "Local ICE candidate gathered (${candidate.sdpMid})")
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
                    runCatching {
                        sendSignal?.invoke(dto)
                        Log.d(TAG, "Sent local ICE candidate to server for session: $sessionId")
                    }.onFailure { e ->
                        Log.e(TAG, "Failed to send local ICE candidate for session: $sessionId", e)
                    }
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state changed for session $sessionId: $state")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state changed for session $sessionId: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state changed for session $sessionId: $state")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("Failed to create PeerConnection")

        videoSource = factory?.createVideoSource(true)

        val intentData = mediaProjectionData ?: MediaProjectionDataStore.projectionData
        if (intentData != null) {
            setupScreenCapturer(intentData, sessionId)
        } else {
            Log.w(TAG, "No MediaProjection data found; registering onDataAvailable listener and prompting consent activity for session: $sessionId")
            MediaProjectionDataStore.onDataAvailable = { data ->
                Log.d(TAG, "MediaProjection data granted by user for session: $sessionId")
                onEventLog?.invoke("remote.startSession", "Screen capture permission granted on device")
                setupScreenCapturer(data, sessionId)
            }
            // Prompt for MediaProjection capture consent via Activity
            val promptIntent = Intent(context, ScreenCapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            runCatching {
                context.startActivity(promptIntent)
                Log.d(TAG, "Successfully requested ScreenCapturePermissionActivity launch for session: $sessionId")
                onEventLog?.invoke("remote.startSession", "Launched screen capture consent prompt on device")
            }.onFailure { e ->
                Log.e(TAG, "Failed to start ScreenCapturePermissionActivity for session: $sessionId", e)
                onEventLog?.invoke("remote.startSession", "Failed to launch consent prompt: ${e.message}")
            }
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

        Log.d(TAG, "Creating WebRTC SDP offer for session: $sessionId")
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                val localSdp = sdp ?: return
                Log.d(TAG, "WebRTC offer created successfully for session: $sessionId")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set successfully for session: $sessionId")
                        onEventLog?.invoke("webrtc.offer", "Created and sent WebRTC SDP offer")
                        scope.launch {
                            val offerDto = RemoteSignalDto(
                                sessionId = sessionId,
                                type = "offer",
                                sdp = localSdp.description
                            )
                            runCatching {
                                sendSignal?.invoke(offerDto)
                                Log.d(TAG, "WebRTC offer sent to server for session: $sessionId")
                            }.onFailure { e ->
                                Log.e(TAG, "Failed to send WebRTC offer to server for session: $sessionId", e)
                                onEventLog?.invoke("webrtc.offer", "Failed to send offer: ${e.message}")
                            }
                        }
                    }
                    override fun onCreateFailure(p0: String?) {
                        Log.e(TAG, "Failed to set local SDP description for session $sessionId: $p0")
                    }
                    override fun onSetFailure(p0: String?) {
                        Log.e(TAG, "Failed to set local SDP description for session $sessionId: $p0")
                    }
                }, localSdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {
                Log.e(TAG, "Failed to create WebRTC SDP offer for session $sessionId: $p0")
            }
            override fun onSetFailure(p0: String?) {
                Log.e(TAG, "Failed to create WebRTC SDP offer for session $sessionId: $p0")
            }
        }, mediaConstraints)
    }

    private fun startSignalingLoop(sessionId: String) {
        val getter = fetchSignals ?: return
        Log.d(TAG, "Starting signaling loop for session: $sessionId")
        signalingJob = scope.launch {
            while (isActive && isRunning.get()) {
                val signals = runCatching { getter(sessionId) }
                    .onFailure { e -> Log.e(TAG, "Error polling signals for session $sessionId: ${e.message}", e) }
                    .getOrNull()
                if (signals != null && signals.isNotEmpty()) {
                    Log.d(TAG, "Fetched ${signals.size} signal(s) for session: $sessionId")
                    for (sig in signals) {
                        handleIncomingSignal(sig)
                    }
                }
                delay(500)
            }
        }
    }

    private fun handleIncomingSignal(signal: RemoteSignalDto) {
        Log.d(TAG, "Handling incoming signal '${signal.type}' for session: ${signal.sessionId}")
        when (signal.type) {
            "answer" -> {
                val sdpStr = signal.sdp ?: return
                Log.d(TAG, "Setting remote SDP answer for session: ${signal.sessionId}")
                onEventLog?.invoke("webrtc.answer", "Received remote SDP answer from browser")
                val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpStr)
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "Remote SDP answer set successfully for session: ${signal.sessionId}")
                        onEventLog?.invoke("webrtc.answer", "Remote SDP answer set successfully")
                    }
                    override fun onCreateFailure(p0: String?) {
                        Log.e(TAG, "Failed to set remote SDP answer for session ${signal.sessionId}: $p0")
                        onEventLog?.invoke("webrtc.answer", "Failed to set remote SDP answer: $p0")
                    }
                    override fun onSetFailure(p0: String?) {
                        Log.e(TAG, "Failed to set remote SDP answer for session ${signal.sessionId}: $p0")
                        onEventLog?.invoke("webrtc.answer", "Failed to set remote SDP answer: $p0")
                    }
                }, remoteSdp)
            }
            "iceCandidate" -> {
                val cand = signal.candidate ?: return
                Log.d(TAG, "Adding remote ICE candidate for session: ${signal.sessionId}")
                onEventLog?.invoke("webrtc.ice", "Added remote ICE candidate (${cand.sdpMid})")
                val iceCandidate = IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.candidate)
                peerConnection?.addIceCandidate(iceCandidate)
            }
            "stop" -> {
                Log.d(TAG, "Received stop signal for session: ${signal.sessionId}")
                onEventLog?.invoke("webrtc.stop", "Received stop signal from browser")
                scope.launch { stop(signal.sessionId) }
            }
        }
    }

    private fun setupScreenCapturer(intentData: Intent, sessionId: String) {
        if (capturer != null) return
        Log.d(TAG, "Starting ScreenCaptureService and MediaProjection for session: $sessionId")
        runCatching {
            ScreenCaptureService.startService(context)
            val screenCapturer = ScreenCapturerAndroid(intentData, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system/user for session: $sessionId")
                    onEventLog?.invoke("webrtc.capturer", "MediaProjection stopped by system/user")
                    scope.launch { stop(sessionId) }
                }
            })
            capturer = screenCapturer
            val helper = surfaceTextureHelper ?: SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext).also {
                surfaceTextureHelper = it
            }
            val vSource = videoSource ?: factory?.createVideoSource(screenCapturer.isScreencast).also {
                videoSource = it
            }
            if (vSource != null && helper != null) {
                screenCapturer.initialize(helper, context, vSource.capturerObserver)
                screenCapturer.startCapture(720, 1280, 30)
                Log.d(TAG, "ScreenCapturerAndroid started capturing frames for session: $sessionId")
                onEventLog?.invoke("webrtc.capturer", "Capturing frames (720x1280 @ 30fps)")
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to initialize ScreenCapturerAndroid for session: $sessionId", e)
            onEventLog?.invoke("webrtc.capturer", "Capturer error: ${e.message}")
        }
    }

    private fun cleanupWebRtc() {
        Log.d(TAG, "Cleaning up WebRTC resources")
        MediaProjectionDataStore.onDataAvailable = null
        MediaProjectionDataStore.projectionData = null
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

        factory = null
    }

    companion object {
        @Volatile private var isInitialized = false
        @Volatile private var sharedFactory: PeerConnectionFactory? = null

        @Synchronized
        private fun getOrCreateFactory(context: Context, eglContext: EglBase.Context): PeerConnectionFactory {
            if (!isInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                isInitialized = true
            }
            var f = sharedFactory
            if (f == null) {
                val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
                val decoderFactory = DefaultVideoDecoderFactory(eglContext)
                val factoryOptions = PeerConnectionFactory.Options()
                f = PeerConnectionFactory.builder()
                    .setOptions(factoryOptions)
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()
                sharedFactory = f
            }
            return f
        }
    }
}
