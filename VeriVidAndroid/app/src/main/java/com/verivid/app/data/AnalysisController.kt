package com.verivid.app.data

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verivid.app.BuildConfig
import com.verivid.app.RecordingResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class SelectedVideo(val file: File, val name: String, val durationMs: Long?, val temporary: Boolean)
enum class AnalysisPhase { IDLE, PREPARING, READY, RUNNING, COMPLETED, ERROR }
data class AnalysisState(
    val phase: AnalysisPhase = AnalysisPhase.IDLE, val stage: String = "",
    val video: SelectedVideo? = null, val detail: AnalysisDetail? = null,
    val failure: ApiFailure? = null, val activeId: String? = null,
    val videoAnalysisId: String? = null,
    val history: List<AnalysisSummary> = emptyList(), val historyLoading: Boolean = false,
    val historyError: String? = null
)

class AnalysisController(application: Application) : AndroidViewModel(application) {
    val endpoint: String = BuildConfig.API_BASE_URL
    private val api = ServiceConnection.get(application)
    private val prefs = application.getSharedPreferences("analysis-active", 0)
    private val activeKey = "id:$endpoint"
    private val mutable = MutableStateFlow(AnalysisState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var historyJob: Job? = null
    private val busy get() = mutable.value.phase in listOf(AnalysisPhase.PREPARING, AnalysisPhase.RUNNING)
    private fun update(change: (AnalysisState) -> AnalysisState) { mutable.value = change(mutable.value) }

    init {
        require(BuildConfig.DEBUG || endpoint.startsWith("https://")) { "배포 앱에는 HTTPS 분석 서버가 필요해요." }
        prefs.getString(activeKey, null)?.let { open(it) }
    }

    fun selectRecording(recording: RecordingResult) {
        if (busy) return
        if (mutable.value.video?.file?.absolutePath == File(recording.path).absolutePath) return
        val file = File(recording.path)
        if (!file.isFile || file.length() == 0L) { fail(ApiFailure("FILE_MISSING", "녹화 파일을 찾지 못했어요.")); return }
        releasePreviousCopy()
        update { it.copy(phase = AnalysisPhase.READY, video = SelectedVideo(file, file.name, recording.durationMs, false), detail = null, failure = null, videoAnalysisId = null) }
    }

    fun selectDocument(uri: Uri) {
        if (busy) return
        work = viewModelScope.launch {
            update { it.copy(phase = AnalysisPhase.PREPARING, failure = null, detail = null) }
            try {
                val selected = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    var name = "selected_video.mp4"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                    }
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension !in setOf("mp4", "mov", "avi", "mkv", "webm", "m4v"))
                        throw ApiFailure("UNSUPPORTED_VIDEO_TYPE", "MP4, MOV, AVI, MKV, WEBM, M4V 영상을 선택해주세요.")
                    val directory = File(getApplication<Application>().cacheDir, "analysis-input").apply { mkdirs() }
                    val copy = File.createTempFile("selected-", ".$extension", directory)
                    try {
                        resolver.openInputStream(uri)?.use { input ->
                            copy.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024); var count: Int; var total = 0L
                                while (input.read(buffer).also { count = it } >= 0) {
                                    total += count
                                    if (total > 500L * 1024 * 1024) throw ApiFailure("UPLOAD_TOO_LARGE", "500MB 이하의 영상을 선택해주세요.")
                                    output.write(buffer, 0, count)
                                }
                            }
                        } ?: throw IOException("파일을 읽을 수 없어요.")
                        if (copy.length() == 0L) throw ApiFailure("EMPTY_UPLOAD", "비어 있는 영상이에요.")
                        SelectedVideo(copy, name, null, true)
                    } catch (e: Exception) { copy.delete(); throw e }
                }
                releasePreviousCopy()
                update { it.copy(phase = AnalysisPhase.READY, video = selected, failure = null, videoAnalysisId = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(asFailure(e)) }
        }
    }

    fun analyze() {
        if (busy) return
        val video = mutable.value.video ?: return
        work = viewModelScope.launch {
            update { it.copy(phase = AnalysisPhase.RUNNING, stage = "CONNECTING", failure = null, detail = null, activeId = null) }
            try {
                api.checkRealModel()
                update { it.copy(stage = "UPLOADING") }
                val created = api.upload(video.file, video.name)
                prefs.edit().putString(activeKey, created.id).apply()
                update { it.copy(activeId = created.id, videoAnalysisId = created.id) }
                poll(created.id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(asFailure(e)) }
        }
    }

    fun open(id: String) {
        if (busy) return
        work = viewModelScope.launch {
            update { it.copy(phase = AnalysisPhase.RUNNING, stage = "LOADING", activeId = id, detail = null, failure = null) }
            try { poll(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(asFailure(e)) }
        }
    }

    private suspend fun poll(id: String) {
        repeat(1800) {
            val detail = api.detail(id)
            update { it.copy(stage = detail.summary.stage, detail = detail) }
            when (detail.summary.status) {
                "COMPLETED" -> {
                    if (detail.result?.isDemo != false) throw ApiFailure("DEMO_MODE", "이 기록은 고정 시연값이에요. 실제 분석 결과로 표시하지 않아요.")
                    prefs.edit().remove(activeKey).apply()
                    update { it.copy(phase = AnalysisPhase.COMPLETED, detail = detail) }
                    return
                }
                "FAILED" -> {
                    prefs.edit().remove(activeKey).apply()
                    throw detail.error ?: ApiFailure("ANALYSIS_FAILED", "영상 분석에 실패했어요.")
                }
                "QUEUED", "PROCESSING" -> delay(2000)
                else -> throw ApiFailure("INVALID_STATUS", "지원하지 않는 분석 상태예요.")
            }
        }
        throw ApiFailure("POLL_TIMEOUT", "결과 확인 시간이 길어지고 있어요. 분석 기록에서 다시 확인해주세요.")
    }

    fun refreshHistory() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            update { it.copy(historyLoading = true, historyError = null) }
            try { val entries = api.history(); update { it.copy(history = entries, historyLoading = false) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(historyLoading = false, historyError = asFailure(e).message) } }
        }
    }

    suspend fun artifact(path: String): ByteArray = api.artifact(path)

    fun returnToReady() {
        update { it.copy(phase = if (it.video != null) AnalysisPhase.READY else AnalysisPhase.IDLE, failure = null) }
    }
    private fun releasePreviousCopy() { mutable.value.video?.takeIf { it.temporary }?.file?.delete() }
    private fun fail(error: ApiFailure) {
        if (error.httpStatus in setOf(401, 404)) prefs.edit().remove(activeKey).apply()
        update { it.copy(phase = AnalysisPhase.ERROR, failure = error) }
    }
    private fun asFailure(error: Exception): ApiFailure = when (error) {
        is ApiFailure -> error
        is IOException -> ApiFailure("CONNECTION_FAILED", "분석 서버에 연결하지 못했어요. 서버와 worker가 켜져 있는지 확인해주세요.")
        else -> ApiFailure("CLIENT_ERROR", "요청을 처리하지 못했어요. 파일이나 서버 상태를 확인해주세요.")
    }
}
