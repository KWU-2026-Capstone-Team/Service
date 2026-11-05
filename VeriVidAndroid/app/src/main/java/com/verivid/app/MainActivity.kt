package com.verivid.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.verivid.app.ui.VeriVidApp
import com.verivid.app.ui.VeriVidTheme
import java.io.File

data class RecordingResult(val path: String, val durationMs: Long)

class MainActivity : ComponentActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private var recordingResult by mutableStateOf<RecordingResult?>(null)

    private val projectionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, CaptureOverlayService::class.java).apply {
                action = CaptureOverlayService.ACTION_PREPARE
                putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "다른 앱에서 VeriVid 버튼을 눌러주세요.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "화면 캡처 권한이 필요해요.", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) requestNotificationThenProjection()
        else Toast.makeText(this, "다른 앱 위 표시 권한이 필요해요.", Toast.LENGTH_SHORT).show()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { launchProjectionPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        readRecordingResult(intent)

        setContent {
            VeriVidTheme {
                VeriVidApp(
                    recordingResult = recordingResult,
                    onPrepareCapture = ::beginCapturePermissionFlow,
                    onPlayRecording = ::playRecording
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRecordingResult(intent)
    }

    private fun readRecordingResult(intent: Intent) {
        val path = intent.getStringExtra(EXTRA_RECORDING_PATH) ?: return
        recordingResult = RecordingResult(path, intent.getLongExtra(EXTRA_RECORDING_DURATION, 0L))
    }

    private fun beginCapturePermissionFlow() {
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        requestNotificationThenProjection()
    }

    private fun requestNotificationThenProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else launchProjectionPermission()
    }

    private fun launchProjectionPermission() {
        projectionPermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun playRecording(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "녹화 파일을 찾을 수 없어요.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(viewIntent) }
            .onFailure { Toast.makeText(this, "영상을 재생할 앱을 찾을 수 없어요.", Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val EXTRA_RECORDING_PATH = "recording_path"
        const val EXTRA_RECORDING_DURATION = "recording_duration"
    }
}
