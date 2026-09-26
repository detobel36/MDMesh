package com.mdmesh.remote

import com.mdmesh.proto.RemoteControlTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlTierDetectorTest {

    @Test
    fun `detects view tier when screen capture is available`() {
        val detector = RemoteControlTierDetector(
            screenCaptureAvailable = true,
            inputInjectionAvailable = false,
        )

        val cap = detector.capability()
        assertEquals(RemoteControlTier.VIEW, cap.tier)
        assertTrue(cap.screenCapture)
        assertEquals(listOf("webrtc"), cap.transport)
    }

    @Test
    fun `detects control tier when screen capture and input injection are available`() {
        val detector = RemoteControlTierDetector(
            screenCaptureAvailable = true,
            inputInjectionAvailable = true,
        )

        val cap = detector.capability()
        assertEquals(RemoteControlTier.CONTROL, cap.tier)
        assertTrue(cap.screenCapture)
        assertTrue(cap.inputInjection)
        assertEquals(listOf("webrtc"), cap.transport)
    }

    @Test
    fun `detects none tier when screen capture is unavailable`() {
        val detector = RemoteControlTierDetector(
            screenCaptureAvailable = false,
            inputInjectionAvailable = false,
        )

        val cap = detector.capability()
        assertEquals(RemoteControlTier.NONE, cap.tier)
        assertTrue(cap.transport.isEmpty())
    }
}
