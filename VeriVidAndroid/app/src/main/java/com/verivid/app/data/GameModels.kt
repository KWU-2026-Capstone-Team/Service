package com.verivid.app.data

import org.json.JSONArray
import org.json.JSONObject

enum class GameVerdict { REAL, FAKE }
enum class GameConfidence { LOW, MEDIUM, HIGH }
enum class GameDifficulty(val label: String) { all("전체"), easy("쉬움"), medium("보통"), hard("어려움") }
data class GameSummary(
    val id: String, val status: String, val totalRounds: Int, val answeredRounds: Int,
    val nextRound: Int?, val difficulty: String, val score: Int, val maxStreak: Int,
    val createdAt: String
)
data class GameRound(val number: Int, val videoPath: String, val answered: Boolean, val reveal: GameReveal?)
data class GameAi(val verdict: GameVerdict, val spatial: Double?, val temporal: Double?, val modelVersion: String)
data class GameReveal(
    val round: Int, val answer: GameVerdict?, val groundTruth: GameVerdict,
    val userCorrect: Boolean?, val aiCorrect: Boolean?, val ai: GameAi?,
    val explanation: String, val cues: List<String>, val tips: List<String>,
    val points: Int, val streak: Int, val skipped: Boolean
)
data class GameReport(
    val id: String, val totalRounds: Int, val userCorrect: Int, val aiCorrect: Int?,
    val userAccuracy: Double?, val aiAccuracy: Double?, val score: Int, val maxStreak: Int,
    val grade: String, val averageSeconds: Double?, val skippedRounds: Int,
    val playedRounds: Int, val aiEvaluatedRounds: Int, val rounds: List<GameReveal>
)

object GameJson {
    private fun JSONObject.intOrNull(key: String) = if (isNull(key)) null else getInt(key)
    private fun JSONObject.doubleOrNull(key: String) = if (isNull(key)) null else getDouble(key).also {
        if (!it.isFinite()) throw ApiFailure("INVALID_GAME_RESULT", "게임 통계 형식이 올바르지 않아요.")
    }
    private fun JSONObject.boolOrNull(key: String) = if (isNull(key)) null else getBoolean(key)
    private fun JSONArray?.strings() = this?.let { List(length()) { index -> getString(index) } } ?: emptyList()
    fun summary(raw: JSONObject) = GameSummary(
        raw.getString("id"), raw.getString("status"), raw.getInt("total_rounds"),
        raw.getInt("answered_rounds"), raw.intOrNull("next_round"), raw.getString("difficulty"),
        raw.getInt("score"), raw.getInt("max_streak"), raw.optString("created_at")
    )
    fun round(raw: JSONObject): GameRound {
        val terminal = raw.optBoolean("answered") || raw.optBoolean("skipped")
        return GameRound(raw.getInt("round"), raw.getString("video_url"), raw.getBoolean("answered"),
            if (terminal) raw.optJSONObject("result")?.let(::reveal) else null)
    }
    fun reveal(raw: JSONObject): GameReveal {
        val ai = raw.optJSONObject("ai")?.let {
            GameAi(GameVerdict.valueOf(it.getString("verdict")), it.doubleOrNull("spatial"),
                it.doubleOrNull("temporal"), it.getString("model_version"))
        }
        return GameReveal(
            raw.getInt("round"), if (raw.isNull("answer")) null else GameVerdict.valueOf(raw.getString("answer")),
            GameVerdict.valueOf(raw.getString("ground_truth")), raw.boolOrNull("user_correct"),
            if (ai == null) null else raw.boolOrNull("ai_correct"), ai,
            raw.getString("explanation"), raw.optJSONArray("cues").strings(), raw.optJSONArray("tips").strings(),
            raw.getInt("points"), raw.getInt("streak"), raw.optBoolean("skipped")
        )
    }
    fun report(raw: JSONObject): GameReport {
        val played = raw.getInt("played_rounds"); val evaluated = raw.getInt("ai_evaluated_rounds")
        val rows = raw.getJSONArray("rounds")
        val userAccuracy = raw.doubleOrNull("user_accuracy")
        val aiAccuracy = if (evaluated == 0) null else raw.doubleOrNull("ai_accuracy")
        if (played < 0 || evaluated !in 0..played || listOfNotNull(userAccuracy, aiAccuracy).any { it !in 0.0..1.0 })
            throw ApiFailure("INVALID_GAME_RESULT", "게임 결과 통계가 올바르지 않아요.")
        return GameReport(raw.getString("id"), raw.getInt("total_rounds"), raw.getInt("user_score"),
            if (evaluated == 0) null else raw.intOrNull("ai_score"), userAccuracy, aiAccuracy,
            raw.getInt("score"), raw.getInt("max_streak"), raw.getString("grade"), raw.doubleOrNull("average_time_seconds"),
            raw.getInt("skipped_rounds"), played, evaluated, List(rows.length()) { reveal(rows.getJSONObject(it)) })
    }
}
