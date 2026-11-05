package com.verivid.app.data

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verivid.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException

enum class GamePhase { SETUP, LOADING, WATCHING, SUBMITTING, REVEALING, RESULT, ERROR, MEDIA_ERROR }
data class GameState(
    val phase: GamePhase = GamePhase.SETUP, val game: GameSummary? = null,
    val round: GameRound? = null, val reveal: GameReveal? = null, val report: GameReport? = null,
    val video: File? = null, val videoReady: Boolean = false, val playbackEnded: Boolean = false,
    val replayCount: Int = 0, val replaySignal: Int = 0, val failure: ApiFailure? = null,
    val activeId: String? = null, val history: List<GameSummary> = emptyList(),
    val historyLoading: Boolean = false, val historyError: String? = null
)
private data class PendingGameAnswer(val id: String, val round: Int, val verdict: GameVerdict, val confidence: GameConfidence, val seconds: Double)

class GameController(application: Application) : AndroidViewModel(application) {
    private val api = ServiceConnection.get(application)
    private val prefs = application.getSharedPreferences("game-progress", 0)
    private val prefix = "${BuildConfig.API_BASE_URL}:"
    private val cache = File(application.cacheDir, "game-video").apply { mkdirs() }
    private val mutable = MutableStateFlow(GameState(activeId = prefs.getString("${prefix}active", null)))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var listJob: Job? = null
    private var startedAt = 0L
    init {
        // Only this feature's generated, direct-child media cache; never user files.
        cache.listFiles()?.filter { it.isFile && it.name.startsWith("round-") && it.extension == "mp4" }?.forEach { it.delete() }
    }
    private fun update(change: (GameState) -> GameState) { mutable.value = change(mutable.value) }

    private fun action(phase: GamePhase = GamePhase.LOADING, block: suspend () -> Unit) {
        if (work?.isActive == true) return
        update { it.copy(phase = phase, failure = null) }
        work = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val error = failure(e)
                if (error.httpStatus == 401 || error.code == "GAME_NOT_FOUND") { clearActive(); clearPending() }
                update { it.copy(phase = GamePhase.ERROR, failure = error) }
            }
        }
    }

    fun create(rounds: Int, difficulty: GameDifficulty) = action {
        if (pending() != null) throw ApiFailure("ANSWER_PENDING", "직전 게임에 확인할 답변이 있어요. 진행 중인 게임을 먼저 이어해주세요.")
        val id = api.startGame(rounds, difficulty)
        setActive(id)
        load(id)
    }

    fun resume(id: String) = action { load(id) }
    private suspend fun load(id: String) {
        releaseVideo()
        val game = api.game(id)
        update { it.copy(game = game, round = null, reveal = null, report = null, videoReady = false) }
        val pending = pending()?.takeIf { it.id == id }
        if (pending != null) {
            val prior = api.gameRound(id, pending.round)
            if (prior.reveal != null) {
                clearPending()
                update { it.copy(phase = GamePhase.REVEALING, round = prior, reveal = prior.reveal) }
                return
            }
            setActive(id)
            throw ApiFailure("ANSWER_PENDING", "직전 답변의 전송 여부를 확인하지 못했어요. 같은 답변으로 다시 확인해주세요.")
        }
        if (game.status == "COMPLETED") {
            val report = api.gameReport(id)
            if (mutable.value.activeId == id) clearActive()
            update { it.copy(phase = GamePhase.RESULT, report = report) }
            return
        }
        if (game.status != "ACTIVE") throw ApiFailure("GAME_UNAVAILABLE", "진행할 수 없는 게임이에요.")
        setActive(id)
        val number = game.nextRound ?: throw ApiFailure("INVALID_GAME_RESULT", "다음 라운드 정보가 없어요.")
        val round = api.gameRound(id, number)
        update { it.copy(round = round, replayCount = prefs.getInt(replayKey(id, number), 0), playbackEnded = false) }
        if (round.reveal != null) {
            update { it.copy(phase = GamePhase.REVEALING, reveal = round.reveal) }
            return
        }
        download(id, round)
    }

    private suspend fun download(id: String, round: GameRound) {
        val target = File(cache, "round-${java.util.UUID.randomUUID()}.mp4")
        try {
            val video = api.gameVideo(id, round, target)
            startedAt = 0L
            update { it.copy(phase = GamePhase.WATCHING, video = video, videoReady = false, playbackEnded = false) }
        } catch (e: CancellationException) { target.delete(); throw e }
        catch (e: Exception) {
            target.delete()
            update { it.copy(phase = GamePhase.MEDIA_ERROR, failure = failure(e), videoReady = false) }
        }
    }

    fun mediaPrepared(path: String) {
        if (mutable.value.phase != GamePhase.WATCHING || mutable.value.video?.path != path) return
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        update { it.copy(videoReady = true) }
    }
    fun mediaEnded(path: String) { if (mutable.value.video?.path == path) update { it.copy(playbackEnded = true) } }
    fun mediaFailed(path: String) {
        if (mutable.value.phase == GamePhase.WATCHING && mutable.value.video?.path == path) update {
            it.copy(phase = GamePhase.MEDIA_ERROR, videoReady = false, failure = ApiFailure("VIDEO_UNAVAILABLE", "영상을 재생하지 못했어요. 재시도하거나 이 라운드를 건너뛸 수 있어요."))
        }
    }
    fun replay() {
        val current = mutable.value
        val game = current.game ?: return; val round = current.round ?: return
        if (current.phase != GamePhase.WATCHING || !current.videoReady || current.replayCount >= 1) return
        prefs.edit().putInt(replayKey(game.id, round.number), 1).apply()
        update { it.copy(replayCount = 1, replaySignal = it.replaySignal + 1, playbackEnded = false) }
    }

    fun answer(verdict: GameVerdict, confidence: GameConfidence) {
        val current = mutable.value
        if (current.phase != GamePhase.WATCHING || !current.videoReady || pending() != null) return
        val game = current.game ?: return; val round = current.round ?: return
        val seconds = ((SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0) / 1000.0).coerceAtMost(86400.0)
        val answer = PendingGameAnswer(game.id, round.number, verdict, confidence, seconds)
        val saved = JSONObject().put("id", answer.id).put("round", answer.round).put("verdict", verdict.name)
            .put("confidence", confidence.name).put("seconds", seconds).toString()
        if (!prefs.edit().putString("${prefix}pending", saved).commit()) {
            update { it.copy(phase = GamePhase.ERROR, failure = ApiFailure("SAVE_FAILED", "답변을 저장하지 못했어요. 다시 시도해주세요.")) }
            return
        }
        sendPending()
    }
    private fun sendPending() = action(GamePhase.SUBMITTING) {
        val answer = pending() ?: throw ApiFailure("ANSWER_MISSING", "다시 확인할 답변이 없어요.")
        val reveal = api.answerGame(answer.id, answer.round, answer.verdict, answer.confidence, answer.seconds)
        clearPending()
        releaseVideo()
        val summary = refreshedSummary(answer.id)
        update { it.copy(phase = GamePhase.REVEALING, reveal = reveal, game = summary ?: it.game) }
    }

    fun next() { mutable.value.game?.id?.let { id -> action { load(id) } } }
    fun retry() {
        if (pending() != null) sendPending()
        else mutable.value.game?.id?.let(::resume) ?: showSetup()
    }
    fun retryVideo() {
        val current = mutable.value; val game = current.game ?: return; val round = current.round ?: return
        action { releaseVideo(); download(game.id, round) }
    }
    fun skipUnavailableVideo() {
        val current = mutable.value
        if (current.phase != GamePhase.MEDIA_ERROR) return
        val game = current.game ?: return; val round = current.round ?: return
        action(GamePhase.SUBMITTING) {
            val reveal = api.skipGameRound(game.id, round.number)
            releaseVideo()
            val summary = refreshedSummary(game.id)
            update { it.copy(phase = GamePhase.REVEALING, reveal = reveal, game = summary ?: it.game) }
        }
    }

    fun showSetup() {
        work?.cancel(); work = null
        releaseVideo()
        update { it.copy(phase = GamePhase.SETUP, game = null, round = null, reveal = null, report = null, failure = null) }
        refreshHistory()
    }
    fun refreshHistory() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            update { it.copy(historyLoading = true, historyError = null) }
            try { val rows = api.games(); update { it.copy(history = rows, historyLoading = false) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(historyLoading = false, historyError = failure(e).message) } }
        }
    }
    private fun setActive(id: String) {
        prefs.edit().putString("${prefix}active", id).apply()
        update { it.copy(activeId = id) }
    }
    private fun clearActive() {
        prefs.edit().remove("${prefix}active").apply()
        update { it.copy(activeId = null) }
    }
    private fun pending(): PendingGameAnswer? = prefs.getString("${prefix}pending", null)?.let { saved ->
        runCatching { val row = JSONObject(saved); PendingGameAnswer(row.getString("id"), row.getInt("round"),
            GameVerdict.valueOf(row.getString("verdict")), GameConfidence.valueOf(row.getString("confidence")), row.getDouble("seconds")) }.getOrNull()
    }
    private fun clearPending() { prefs.edit().remove("${prefix}pending").apply() }
    private suspend fun refreshedSummary(id: String): GameSummary? = try { api.game(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { null } // Successful answer is not converted into a failed answer by a summary refresh.
    private fun replayKey(id: String, number: Int) = "${prefix}replay:$id:$number"
    private fun releaseVideo() { mutable.value.video?.delete(); update { it.copy(video = null, videoReady = false) } }
    override fun onCleared() { releaseVideo(); super.onCleared() }
    private fun failure(e: Exception) = when (e) {
        is ApiFailure -> if (e.code == "GAME_DATASET_NOT_READY") ApiFailure(e.code, "선택한 난이도·라운드 수에 맞는 영상이 부족해요. 전체 난이도 또는 5문제로 바꿔주세요.", e.httpStatus) else e
        is IOException -> ApiFailure("CONNECTION_FAILED", "게임 서버에 연결하지 못했어요. 서버가 켜져 있는지 확인해주세요.")
        else -> ApiFailure("GAME_CLIENT_ERROR", "게임을 불러오지 못했어요. 다시 시도해주세요.")
    }
}
