package com.verivid.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface SessionStore { var token: String? }

class DeeptectorApi(
    baseUrl: String, private val sessions: SessionStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS).callTimeout(200, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
) {
    private val base = baseUrl.toHttpUrl()
    // GET is idempotent; uploads/session creation must never be replayed implicitly.
    private val readClient = client.newBuilder().retryOnConnectionFailure(true).build()
    // Do not reuse an idle read connection for a non-idempotent POST after a
    // user has watched a clip. Servers can expire that connection while idle.
    // A fresh write connection avoids replaying an upload/session/game request.
    private val writeClient = client.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(false).build()
    private val sessionMutex = Mutex()
    init { require(base.encodedPath == "/" && base.query == null && base.username.isEmpty() && base.password.isEmpty()) { "API 주소에는 경로·인증정보를 넣을 수 없어요." } }

    private fun url(path: String): HttpUrl {
        if (!path.startsWith("/api/v1/") && !path.startsWith("/health/"))
            throw ApiFailure("INVALID_PATH", "허용하지 않는 서버 경로예요.")
        val resolved = base.resolve(path) ?: throw ApiFailure("INVALID_PATH", "잘못된 서버 경로예요.")
        if (resolved.scheme != base.scheme || resolved.host != base.host || resolved.port != base.port ||
            (!resolved.encodedPath.startsWith("/api/v1/") && !resolved.encodedPath.startsWith("/health/")))
            throw ApiFailure("INVALID_PATH", "서버 밖의 자료는 요청하지 않아요.")
        return resolved
    }

    private suspend fun bytes(request: Request, limit: Long = 2 * 1024 * 1024): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = (if (request.method == "GET") readClient else writeClient).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val data = response.use {
                            val body = it.body ?: throw IOException("서버 응답이 비어 있어요.")
                            if (body.contentLength() > limit) throw IOException("서버 응답이 너무 커요.")
                            val source = body.source()
                            if (source.request(limit + 1)) throw IOException("서버 응답이 너무 커요.")
                            val data = source.readByteArray()
                            if (!it.isSuccessful) {
                                val error = runCatching { JSONObject(String(data, Charsets.UTF_8)).optJSONObject("error") }.getOrNull()
                                if (it.code == 401) sessions.token = null
                                throw ApiFailure(error?.optString("code") ?: "HTTP_${it.code}",
                                    if (it.code == 401) "분석 세션이 만료됐어요. 다시 연결해주세요. 이전 세션의 기록은 복구되지 않아요."
                                    else error?.optString("message") ?: "서버 요청에 실패했어요 (${it.code}).", it.code)
                            }
                            data
                        }
                        if (continuation.isActive) continuation.resume(data)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    private suspend fun request(path: String, method: String = "GET", body: okhttp3.RequestBody? = null, authenticated: Boolean = true): JSONObject {
        if (authenticated) ensureSession()
        val request = Request.Builder().url(url(path)).method(method, body).apply {
            if (authenticated) header("Authorization", "Bearer ${sessions.token ?: throw ApiFailure("SESSION_MISSING", "세션이 필요해요.")}")
        }.build()
        val data = bytes(request)
        if (data.isEmpty()) return JSONObject()
        return try { JSONObject(String(data, Charsets.UTF_8)) }
        catch (e: org.json.JSONException) { throw ApiFailure("INVALID_RESPONSE", "서버 응답 형식이 올바르지 않아요.") }
    }

    private suspend fun ensureSession() = sessionMutex.withLock {
        if (sessions.token.isNullOrBlank()) {
            val session = request("/api/v1/sessions", "POST", ByteArray(0).toRequestBody(null), authenticated = false)
            sessions.token = session.getString("token").takeIf { it.isNotBlank() }
                ?: throw ApiFailure("INVALID_RESPONSE", "서버 세션을 만들지 못했어요.")
        }
    }

    suspend fun checkRealModel() {
        request("/health/ready", authenticated = false)
        val info = request("/api/v1/model-info", authenticated = false)
        if (info.optString("inference_mode") != "real")
            throw ApiFailure("DEMO_MODE", "서버가 시연 모드예요. 실제 모델(real) 모드로 실행해주세요.")
        if (!info.optBoolean("model_dir_exists"))
            throw ApiFailure("MODEL_UNAVAILABLE", "서버에서 모델 폴더를 찾지 못했어요.")
        // loaded=false is normal: the worker loads models in a child process.
    }

    suspend fun upload(video: File, displayName: String): AnalysisSummary {
        val safeName = displayName.replace(Regex("[\\r\\n\\\"\\\\/]"), "_").take(180)
        val mime = when (video.extension.lowercase()) {
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            else -> "video/mp4"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("video", safeName, video.asRequestBody(mime.toMediaType())).build()
        return AnalysisJson.summary(request("/api/v1/analyses", "POST", body))
    }
    suspend fun detail(id: String): AnalysisDetail = AnalysisJson.detail(request("/api/v1/analyses/${safeId(id)}"))
    suspend fun history(): List<AnalysisSummary> {
        val rows = request("/api/v1/analyses?limit=100&offset=0").getJSONArray("items")
        return List(rows.length()) { AnalysisJson.summary(rows.getJSONObject(it)) }
    }
    suspend fun artifact(path: String): ByteArray {
        ensureSession()
        if (!Regex("/api/v1/analyses/[A-Za-z0-9-]+/artifacts/[a-z_]+").matches(path))
            throw ApiFailure("INVALID_PATH", "잘못된 분석 이미지 경로예요.")
        return bytes(Request.Builder().url(url(path)).header("Authorization", "Bearer ${sessions.token}").build(), 8 * 1024 * 1024)
    }
    suspend fun startGame(rounds: Int, difficulty: GameDifficulty): String {
        require(rounds in setOf(5, 10, 15))
        return request("/api/v1/games", "POST",
            JSONObject().put("rounds", rounds).put("difficulty", difficulty.name).toString().toRequestBody("application/json".toMediaType())).getString("id")
    }
    suspend fun games(): List<GameSummary> {
        val rows = request("/api/v1/games?limit=50&offset=0").getJSONArray("items")
        return List(rows.length()) { GameJson.summary(rows.getJSONObject(it)) }
    }
    suspend fun game(id: String) = GameJson.summary(request("/api/v1/games/${safeId(id)}"))
    suspend fun gameRound(id: String, round: Int) = GameJson.round(request(gameRoundPath(id, round)))
    suspend fun answerGame(id: String, round: Int, verdict: GameVerdict, confidence: GameConfidence, seconds: Double): GameReveal {
        require(seconds.isFinite() && seconds in 0.0..86400.0)
        val body = JSONObject().put("verdict", verdict.name).put("confidence", confidence.name).put("time_used_seconds", seconds)
        return GameJson.reveal(request("${gameRoundPath(id, round)}/answer", "POST", body.toString().toRequestBody("application/json".toMediaType())))
    }
    suspend fun skipGameRound(id: String, round: Int): GameReveal = GameJson.reveal(request(
        "${gameRoundPath(id, round)}/skip", "POST", "{\"reason\":\"VIDEO_UNAVAILABLE\"}".toRequestBody("application/json".toMediaType())))
    suspend fun gameReport(id: String) = GameJson.report(request("/api/v1/games/${safeId(id)}/result"))
    suspend fun deleteGame(id: String) { request("/api/v1/games/${safeId(id)}", "DELETE") }
    suspend fun gameVideo(id: String, round: GameRound, target: File): File {
        val expected = "${gameRoundPath(id, round.number)}/video"
        if (round.videoPath != expected) throw ApiFailure("INVALID_PATH", "게임 영상 경로가 올바르지 않아요.")
        ensureSession()
        val data = bytes(Request.Builder().url(url(expected)).header("Authorization", "Bearer ${sessions.token}").build(), 32 * 1024 * 1024)
        return withContext(Dispatchers.IO) {
            try {
                ensureActive()
                target.outputStream().use { it.write(data) }
                ensureActive()
                target
            } catch (e: Exception) { target.delete(); throw e }
        }
    }
    private fun gameRoundPath(id: String, round: Int): String {
        require(round in 1..30)
        return "/api/v1/games/${safeId(id)}/rounds/$round"
    }
    private fun safeId(id: String): String {
        if (!Regex("[A-Za-z0-9-]+").matches(id)) throw ApiFailure("INVALID_ID", "잘못된 분석 ID예요.")
        return id
    }
}
