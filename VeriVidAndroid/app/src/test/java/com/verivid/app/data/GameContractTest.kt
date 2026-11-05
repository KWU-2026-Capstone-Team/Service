package com.verivid.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GameContractTest {
    private class Store(override var token: String? = "session-token") : SessionStore
    private fun reveal(ai: Boolean = true) = JSONObject("""
        {"round":1,"answer":"REAL","ground_truth":"FAKE","user_correct":false,"ai_correct":${if (ai) "true" else "null"},
         "ai":${if (ai) """{"verdict":"FAKE","spatial":0.9,"temporal":0.8,"model_version":"real-model-test"}""" else "null"},
         "explanation":"팀 해설","cues":["팀 제공 단서"],"tips":["일반 요령"],"points":0,"streak":0,"skipped":false}
    """)
    private fun summary() = """{"id":"g1","status":"ACTIVE","total_rounds":5,"answered_rounds":1,"next_round":2,"difficulty":"all","score":100,"max_streak":1,"created_at":"2026-11-05T00:00:00Z"}"""
    @Test fun createAcceptsActualMinimalResponse() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"g1","status":"ACTIVE","total_rounds":5,"next_round":1}"""))
            assertEquals("g1", DeeptectorApi(server.url("/").toString(), Store()).startGame(5, GameDifficulty.hard))
            val call = server.takeRequest(); assertEquals("POST", call.method); assertEquals("/api/v1/games", call.path)
            val body = JSONObject(call.body.readUtf8()); assertEquals(5, body.getInt("rounds")); assertEquals("hard", body.getString("difficulty"))
            assertFalse(body.has("score")); assertFalse(body.has("ground_truth"))
        } finally { server.shutdown() }
    }
    @Test fun unsubmittedRoundDoesNotExposeAnswerInClientModel() {
        val raw = JSONObject("""{"round":1,"video_url":"/api/v1/games/g1/rounds/1/video","answered":false}""")
        raw.put("result", reveal())
        val parsed = GameJson.round(raw)
        assertNull(parsed.reveal); assertFalse(parsed.answered)
    }
    @Test fun revealSeparatesSourceTruthAndAiJudgment() {
        val data = GameJson.reveal(reveal())
        assertEquals(GameVerdict.FAKE, data.groundTruth); assertEquals(GameVerdict.REAL, data.answer)
        assertFalse(data.userCorrect!!); assertTrue(data.aiCorrect!!)
        assertEquals(listOf("팀 제공 단서"), data.cues); assertEquals(listOf("일반 요령"), data.tips)
    }
    @Test fun absentAiDoesNotBecomeZeroOrFalse() {
        val data = GameJson.reveal(reveal(false))
        assertNull(data.ai); assertNull(data.aiCorrect)
    }
    @Test fun answerUsesServerScoringAndSameRetryBody() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            repeat(2) { server.enqueue(MockResponse().setBody(reveal().toString())) }
            val api = DeeptectorApi(server.url("/").toString(), Store())
            repeat(2) { assertEquals(0, api.answerGame("g1", 1, GameVerdict.REAL, GameConfidence.HIGH, 2.5).points) }
            val first = server.takeRequest(); val second = server.takeRequest()
            assertEquals("/api/v1/games/g1/rounds/1/answer", first.path)
            assertEquals("Bearer session-token", first.getHeader("Authorization"))
            val firstBody = first.body.readUtf8(); assertEquals(firstBody, second.body.readUtf8())
            val body = JSONObject(firstBody); assertEquals("REAL", body.getString("verdict")); assertEquals("HIGH", body.getString("confidence"))
            assertFalse(body.has("points")); assertFalse(body.has("user_correct"))
        } finally { server.shutdown() }
    }
    @Test fun skipIsExplicitMediaFailureNotFabricatedAnswer() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val raw = reveal(false).put("skipped", true).put("answer", JSONObject.NULL).put("user_correct", JSONObject.NULL)
            server.enqueue(MockResponse().setBody(raw.toString()))
            val result = DeeptectorApi(server.url("/").toString(), Store()).skipGameRound("g1", 1)
            assertTrue(result.skipped); assertNull(result.answer); assertNull(result.userCorrect)
            val call = server.takeRequest(); assertEquals("/api/v1/games/g1/rounds/1/skip", call.path)
            assertEquals("VIDEO_UNAVAILABLE", JSONObject(call.body.readUtf8()).getString("reason"))
        } finally { server.shutdown() }
    }
    @Test fun datasetShortageIsNotSilentlyReducedRoundCount() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"GAME_DATASET_NOT_READY","message":"부족"}}"""))
            try { DeeptectorApi(server.url("/").toString(), Store()).startGame(15, GameDifficulty.easy); fail("Expected 409") }
            catch (e: ApiFailure) { assertEquals("GAME_DATASET_NOT_READY", e.code); assertEquals(409, e.httpStatus) }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun gameHistoryUsesExistingAnalysisSession() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"items":[${summary()}]}"""))
            assertEquals(100, DeeptectorApi(server.url("/").toString(), Store()).games().single().score)
            val call = server.takeRequest(); assertEquals("/api/v1/games?limit=50&offset=0", call.path)
            assertEquals("Bearer session-token", call.getHeader("Authorization")); assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun foreignVideoPathRejectedBeforeSessionCreation() = runBlocking {
        val api = DeeptectorApi("https://team.example/", Store(null))
        val target = File.createTempFile("game-test", ".mp4")
        try {
            try { api.gameVideo("g1", GameRound(1, "https://other.example/video", false, null), target); fail("Allowed foreign host") }
            catch (e: ApiFailure) { assertEquals("INVALID_PATH", e.code) }
        } finally { target.delete() }
    }
    @Test fun authenticatedVideoStoredOnlyInCallerCacheFile() = runBlocking {
        val server = MockWebServer(); server.start(); val target = File.createTempFile("game-test", ".mp4")
        try {
            val video = byteArrayOf(0, 0, 0, 16, 102, 116, 121, 112)
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(video)))
            DeeptectorApi(server.url("/").toString(), Store()).gameVideo("g1", GameRound(1, "/api/v1/games/g1/rounds/1/video", false, null), target)
            assertArrayEquals(video, target.readBytes())
            val request = server.takeRequest(); assertEquals("Bearer session-token", request.getHeader("Authorization"))
        } finally { target.delete(); server.shutdown() }
    }
    @Test fun noAiEvaluationsReportStaysUnavailable() {
        val report = JSONObject("""{"id":"g1","total_rounds":1,"user_score":0,"ai_score":null,"user_accuracy":0.0,"ai_accuracy":null,
            "score":0,"max_streak":0,"grade":"D","average_time_seconds":2.5,"skipped_rounds":0,"played_rounds":1,"ai_evaluated_rounds":0,"rounds":[]}""")
        val result = GameJson.report(report)
        assertNull(result.aiCorrect); assertNull(result.aiAccuracy); assertEquals(0, result.aiEvaluatedRounds)
    }
    @Test fun emptyDeleteResponseAccepted() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(204))
            DeeptectorApi(server.url("/").toString(), Store()).deleteGame("g1")
            assertEquals("DELETE", server.takeRequest().method)
        } finally { server.shutdown() }
    }
    @Test fun answerDoesNotReuseIdleVideoOrReadConnection() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(summary()))
            server.enqueue(MockResponse().setBody(reveal().toString()))
            val api = DeeptectorApi(server.url("/").toString(), Store())
            api.game("g1")
            api.answerGame("g1", 1, GameVerdict.REAL, GameConfidence.MEDIUM, 3.0)
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
}
