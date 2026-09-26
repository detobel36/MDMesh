package com.mdmesh.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Transparent activity that requests MediaProjection screen capture consent from user/admin
 * and stores the granted Intent for [WebRtcRemoteControlSession].
 */
class ScreenCapturePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenCaptureService.startService(this)
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
        } catch (e: Exception) {
            android.util.Log.e("ScreenCapturePerm", "Failed to launch screen capture intent", e)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        try {
            if (requestCode == REQUEST_CODE) {
                if (resultCode == RESULT_OK && data != null) {
                    ScreenCaptureService.startService(this)
                    MediaProjectionDataStore.projectionData = data
                } else {
                    android.util.Log.w("ScreenCapturePerm", "Screen capture permission not granted, resultCode: $resultCode")
                }
                finish()
            } else {
                super.onActivityResult(requestCode, resultCode, data)
            }
        } catch (e: Exception) {
            android.util.Log.e("ScreenCapturePerm", "Error in onActivityResult: ${e.message}", e)
            finish()
        }
    }

    companion object {
        private const val REQUEST_CODE = 9901
    }
}

object MediaProjectionDataStore {
    @Volatile
    var projectionData: Intent? = null
        set(value) {
            field = value
            if (value != null) {
                onDataAvailable?.invoke(value)
            }
        }

    @Volatile
    var onDataAvailable: ((Intent) -> Unit)? = null
}
