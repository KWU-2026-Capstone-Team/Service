package com.verivid.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptureOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    private var bubbleView: TextView? = null
    private var menuView: LinearLayout? = null
    private var statusView: TextView? = null
    private var timerView: TextView? = null
    private var recordButton: Button? = null
    private var isRecording = false
    private var recordingStartedAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post {
                if (isRecording) releaseRecordingResources()
                stopSelf()
            }
        }
    }

    private val timerTick = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsedSeconds = (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000
            timerView?.text = formatDuration(elapsedSeconds)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_PREPARE) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, buildNotification(false))

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: return START_NOT_STICKY

        projection = projectionManager.getMediaProjection(resultCode, resultData).also {
            it.registerCallback(projectionCallback, handler)
        }
        showFloatingButton()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showFloatingButton() {
        if (bubbleView != null) return
        val bubble = TextView(this).apply {
            text = "V"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            elevation = dp(4).toFloat()
            background = roundedBackground(NAVY, dp(30).toFloat(), Color.WHITE)
            setOnClickListener { toggleMenu() }
        }
        val params = WindowManager.LayoutParams(
            dp(58), dp(58), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(12)
        }
        windowManager.addView(bubble, params)
        bubbleView = bubble
    }

    private fun toggleMenu() {
        if (menuView == null) showMenu() else hideMenu()
    }

    private fun showMenu() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(17))
            elevation = dp(5).toFloat()
            background = roundedBackground(Color.WHITE, dp(22).toFloat(), 0xFFEBEEF2.toInt())
        }

        val title = TextView(this).apply {
            text = "VeriVid"
            textSize = 18f
            setTextColor(INK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val status = TextView(this).apply {
            text = if (isRecording) "● REC" else "녹화할 영상을 준비해주세요"
            textSize = 14f
            setTextColor(if (isRecording) RED else MUTED)
            setPadding(0, dp(10), 0, 0)
        }
        val timer = TextView(this).apply {
            text = if (isRecording) formatDuration((SystemClock.elapsedRealtime() - recordingStartedAt) / 1000) else "00:00:00"
            textSize = 24f
            setTextColor(INK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = if (isRecording) View.VISIBLE else View.GONE
            setPadding(0, dp(4), 0, dp(8))
        }
        val primary = actionButton(if (isRecording) "녹화 중지" else "녹화 시작", true).apply {
            setOnClickListener {
                if (isRecording) stopRecordingAndReturn() else startRecording()
            }
        }
        val returnButton = actionButton("VeriVid로 돌아가기", false).apply {
            setOnClickListener { openApp(null, 0L) }
        }

        panel.addView(title)
        panel.addView(status)
        panel.addView(timer)
        panel.addView(primary)
        panel.addView(returnButton)
        statusView = status
        timerView = timer
        recordButton = primary

        val params = WindowManager.LayoutParams(
            dp(278), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(80)
        }
        windowManager.addView(panel, params)
        menuView = panel
    }

    private fun hideMenu() {
        menuView?.let { runCatching { windowManager.removeView(it) } }
        menuView = null
        statusView = null
        timerView = null
        recordButton = null
    }

    private fun startRecording() {
        if (isRecording || projection == null) return
        val metrics = resources.displayMetrics
        val landscape = metrics.widthPixels > metrics.heightPixels
        val maxWidth = if (landscape) 1920f else 1080f
        val maxHeight = if (landscape) 1080f else 1920f
        val scale = minOf(1f, maxWidth / metrics.widthPixels, maxHeight / metrics.heightPixels)
        val width = (metrics.widthPixels * scale).toInt().let { it - (it % 2) }
        val height = (metrics.heightPixels * scale).toInt().let { it - (it % 2) }
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VeriVid").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        outputFile = File(directory, "video_capture_$timestamp.mp4")

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            mediaRecorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(6_000_000)
                setVideoFrameRate(30)
                setVideoSize(width, height)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
            }
            virtualDisplay = projection?.createVirtualDisplay(
                "VeriVidCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface, null, handler
            )
            mediaRecorder.start()
            recorder = mediaRecorder
            isRecording = true
            recordingStartedAt = SystemClock.elapsedRealtime()
            updateRecordingUi()
            handler.post(timerTick)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(true))
        } catch (error: Exception) {
            runCatching { mediaRecorder.release() }
            outputFile?.delete()
            outputFile = null
            statusView?.text = "녹화를 시작하지 못했어요"
        }
    }

    private fun stopRecordingAndReturn() {
        if (!isRecording) return
        val durationMs = SystemClock.elapsedRealtime() - recordingStartedAt
        val file = outputFile
        releaseRecordingResources()
        if (file != null && file.exists() && file.length() > 0) {
            openApp(file.absolutePath, durationMs)
        }
        stopSelf()
    }

    private fun releaseRecordingResources() {
        handler.removeCallbacks(timerTick)
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        isRecording = false
        updateRecordingUi()
    }

    private fun updateRecordingUi() {
        bubbleView?.apply {
            text = if (isRecording) "REC" else "V"
            textSize = if (isRecording) 12f else 18f
            background = roundedBackground(if (isRecording) RED else NAVY, dp(30).toFloat(), Color.WHITE)
        }
        statusView?.apply {
            text = if (isRecording) "● REC" else "녹화할 영상을 준비해주세요"
            setTextColor(if (isRecording) RED else MUTED)
        }
        timerView?.visibility = if (isRecording) View.VISIBLE else View.GONE
        recordButton?.text = if (isRecording) "녹화 중지" else "녹화 시작"
    }

    private fun openApp(recordingPath: String?, durationMs: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            recordingPath?.let { putExtra(MainActivity.EXTRA_RECORDING_PATH, it) }
            putExtra(MainActivity.EXTRA_RECORDING_DURATION, durationMs)
        }
        startActivity(intent)
    }

    private fun actionButton(label: String, primary: Boolean): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else INK)
        background = roundedBackground(if (primary) BLUE else 0xFFF5F6F8.toInt(), dp(14).toFloat())
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(10)
        }
        layoutParams = params
    }

    private fun roundedBackground(color: Int, radius: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun buildNotification(recording: Boolean): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(com.verivid.app.R.drawable.ic_verivid)
        .setContentTitle(if (recording) getString(R.string.capture_recording) else getString(R.string.capture_ready))
        .setContentText(if (recording) "REC 표시를 눌러 녹화를 중지할 수 있어요." else "플로팅 버튼을 눌러 녹화를 시작하세요.")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideMenu()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        if (isRecording) releaseRecordingResources()
        projection?.unregisterCallback(projectionCallback)
        runCatching { projection?.stop() }
        projection = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PREPARE = "com.verivid.app.action.PREPARE_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "verivid_capture"
        private const val NOTIFICATION_ID = 2107
        private const val NAVY = 0xFF20395C.toInt()
        private const val BLUE = 0xFF6267CD.toInt()
        private const val RED = 0xFFBE3944.toInt()
        private const val INK = 0xFF191F28.toInt()
        private const val MUTED = 0xFF657182.toInt()
    }
}
