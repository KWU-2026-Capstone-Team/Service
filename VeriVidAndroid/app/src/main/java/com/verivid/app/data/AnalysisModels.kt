package com.verivid.app.data

import org.json.JSONObject

enum class TrafficLight { GREEN, YELLOW, RED }

data class FaceSample(val frameIndex: Int, val seconds: Double)
data class AnalysisArtifact(val kind: String, val path: String)
data class ModelResult(
    val spatial: Double, val temporal: Double, val fused: Double,
    val nFaces: Int, val trafficLight: TrafficLight, val primarySignal: String,
    val reason: String, val policyVersion: String, val modelVersion: String,
    val calibrationVersion: String, val isDemo: Boolean, val warnings: List<String>,
    val samples: List<FaceSample>, val paddedFrames: Int, val durationSeconds: Double?
)
data class AnalysisSummary(val id: String, val filename: String, val status: String, val stage: String, val createdAt: String)
data class AnalysisDetail(
    val summary: AnalysisSummary, val result: ModelResult?, val error: ApiFailure?,
    val artifacts: List<AnalysisArtifact>
)
class ApiFailure(val code: String, override val message: String, val httpStatus: Int = 0) : Exception(message)

/** Service main@3a44aa3: policy comes from the server, never from client thresholds. */
object AnalysisJson {
    fun summary(json: JSONObject) = AnalysisSummary(
        json.getString("id"), json.getString("filename"), json.getString("status"),
        json.getString("stage"), json.getString("created_at")
    )

    fun detail(json: JSONObject): AnalysisDetail {
        val summary = summary(json)
        val result = json.optJSONObject("result")?.let { raw ->
            val policy = raw.optJSONObject("policy") ?: throw ApiFailure("INVALID_RESULT", "서버가 신호등 판정 정보를 반환하지 않았어요.")
            fun score(key: String): Double = raw.getDouble(key).also {
                if (!it.isFinite() || it !in 0.0..1.0) throw ApiFailure("INVALID_RESULT", "유효하지 않은 모델 점수예요.")
            }
            val spatial = score("spatial"); val temporal = score("temporal"); val fused = score("fused")
            if (kotlin.math.abs(fused - (spatial + temporal) / 2) > 1e-7)
                throw ApiFailure("INVALID_RESULT", "서버의 융합 점수가 일치하지 않아요.")
            val metadata = raw.optJSONObject("metadata") ?: JSONObject()
            val samples = metadata.optJSONArray("samples")
            ModelResult(
                spatial, temporal, fused, raw.getInt("n_faces").also {
                    if (it !in 4..16) throw ApiFailure("INVALID_RESULT", "얼굴 프레임 정보가 올바르지 않아요.")
                },
                try { TrafficLight.valueOf(policy.getString("traffic_light")) }
                catch (e: IllegalArgumentException) { throw ApiFailure("INVALID_RESULT", "지원하지 않는 신호등 상태예요.") },
                policy.getString("primary_signal"), policy.getString("reason"), policy.getString("policy_version"),
                raw.getString("model_version"), raw.getString("calibration_version"), raw.getBoolean("is_demo"),
                raw.optJSONArray("warnings")?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList(),
                samples?.let { array -> List(array.length()) {
                    val item = array.getJSONObject(it)
                    FaceSample(item.getInt("frame_index"), item.getDouble("timestamp_seconds").also { time ->
                        if (!time.isFinite() || time < 0) throw ApiFailure("INVALID_RESULT", "프레임 시간이 올바르지 않아요.")
                    })
                } } ?: emptyList(),
                metadata.optInt("padded_frames", 0),
                if (metadata.has("duration_seconds")) metadata.getDouble("duration_seconds") else null
            )
        }
        if (summary.status == "COMPLETED" && result == null)
            throw ApiFailure("INVALID_RESULT", "완료된 분석의 결과가 비어 있어요.")
        val error = json.optJSONObject("error")?.let { raw ->
            val code = raw.optString("code", "ANALYSIS_FAILED")
            val message = if (code == "INSUFFICIENT_FACE_DATA") {
                val details = raw.optJSONObject("details")
                "얼굴 프레임이 부족해요 (${details?.optInt("detected", 0) ?: 0}/${details?.optInt("required", 4) ?: 4}). 얼굴이 잘 보이는 다른 영상을 선택해주세요."
            } else raw.optString("message", "분석하지 못했어요.")
            ApiFailure(code, message)
        }
        val artifacts = json.optJSONArray("artifacts")
        return AnalysisDetail(summary, result, error, artifacts?.let { array -> List(array.length()) {
            val item = array.getJSONObject(it)
            AnalysisArtifact(item.getString("kind"), item.getString("url"))
        } } ?: emptyList())
    }
}
