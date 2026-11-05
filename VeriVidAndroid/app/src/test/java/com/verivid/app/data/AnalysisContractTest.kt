package com.verivid.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AnalysisContractTest {
    private fun completed(light: String = "RED", demo: Boolean = false) = JSONObject("""
        {"id":"test-id","filename":"video.mp4","status":"COMPLETED","stage":"COMPLETED","created_at":"2026-11-05T00:00:00Z",
          "result":{"spatial":0.918,"temporal":0.528,"fused":0.723,"n_faces":16,"model_version":"dual-branch-test",
          "calibration_version":"test","is_demo":$demo,"warnings":[],"metadata":{"samples":[{"frame_index":8,"timestamp_seconds":1.0}],"padded_frames":0},
          "policy":{"traffic_light":"$light","primary_signal":"SPATIAL","reason":"FUSED_THRESHOLD","policy_version":"traffic-v1-experimental"}},"artifacts":[]}
    """)
    @Test fun serverTrafficLightsArePreserved() {
        TrafficLight.values().forEach { assertEquals(it, AnalysisJson.detail(completed(it.name)).result!!.trafficLight) }
    }
    @Test fun readsOnlyActualBranchScoresAndSamples() {
        val result = AnalysisJson.detail(completed()).result!!
        assertEquals(.918, result.spatial, 0.0); assertEquals(.528, result.temporal, 0.0)
        assertEquals(.723, result.fused, 0.0); assertEquals(1.0, result.samples.single().seconds, 0.0)
        assertFalse(result.isDemo)
    }
    @Test fun demoIsExplicitNotSilentlyReal() { assertTrue(AnalysisJson.detail(completed(demo = true)).result!!.isDemo) }
    @Test fun refusesMissingPolicy() {
        val value = completed(); value.getJSONObject("result").remove("policy")
        assertThrows(ApiFailure::class.java) { AnalysisJson.detail(value) }
    }
    @Test fun refusesInvalidScores() {
        val value = completed(); value.getJSONObject("result").put("spatial", 1.9)
        assertThrows(ApiFailure::class.java) { AnalysisJson.detail(value) }
    }
    @Test fun refusesInconsistentFusion() {
        val value = completed(); value.getJSONObject("result").put("fused", .8)
        assertThrows(ApiFailure::class.java) { AnalysisJson.detail(value) }
    }
    @Test fun completedNeedsResult() {
        val value = completed(); value.remove("result")
        assertThrows(ApiFailure::class.java) { AnalysisJson.detail(value) }
    }
    @Test fun insufficientFacesIsFailureNotGreen() {
        val value = completed(); value.put("status", "FAILED"); value.remove("result")
        value.put("error", JSONObject("""{"code":"INSUFFICIENT_FACE_DATA","details":{"detected":2,"required":4}}"""))
        val detail = AnalysisJson.detail(value)
        assertNull(detail.result); assertEquals("INSUFFICIENT_FACE_DATA", detail.error!!.code)
        assertTrue(detail.error!!.message.contains("2/4"))
    }
    private class Store(override var token: String? = null) : SessionStore
    @Test fun sessionUploadAndPollUseContract() = runBlocking {
        val server = MockWebServer(); server.start()
        val file = File.createTempFile("contract", ".mp4").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        try {
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"token":"secret-test-token"}"""))
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"id":"test-id","filename":"video.mp4","status":"QUEUED","stage":"QUEUED","created_at":"2026-11-05T00:00:00Z"}"""))
            server.enqueue(MockResponse().setBody(completed().toString()))
            val api = DeeptectorApi(server.url("/").toString(), Store())
            assertEquals("test-id", api.upload(file, "video.mp4").id)
            assertEquals(TrafficLight.RED, api.detail("test-id").result!!.trafficLight)
            val session = server.takeRequest(); assertEquals("/api/v1/sessions", session.path); assertNull(session.getHeader("Authorization"))
            val upload = server.takeRequest(); assertEquals("/api/v1/analyses", upload.path)
            assertEquals("Bearer secret-test-token", upload.getHeader("Authorization"))
            assertTrue(upload.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
            assertTrue(upload.body.readUtf8().contains("name=\"video\""))
            assertEquals("/api/v1/analyses/test-id", server.takeRequest().path)
        } finally { file.delete(); server.shutdown() }
    }
    @Test fun artifactRejectsForeignUrlBeforeSendingToken() = runBlocking {
        val api = DeeptectorApi("https://team.example/", Store("secret"))
        try { api.artifact("https://other.example/image.png"); fail("Foreign artifact allowed") }
        catch (e: ApiFailure) { assertEquals("INVALID_PATH", e.code) }
    }
    @Test fun authenticationErrorClearsSession() = runBlocking {
        val server = MockWebServer(); server.start(); val store = Store("expired")
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"INVALID_SESSION","message":"Expired"}}"""))
            try { DeeptectorApi(server.url("/").toString(), store).history(); fail("Expected failure") }
            catch (e: ApiFailure) { assertEquals(401, e.httpStatus); assertNull(store.token) }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun demoServerIsRejectedBeforeUpload() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"inference_mode":"demo","model_dir_exists":true}"""))
            try { DeeptectorApi(server.url("/").toString(), Store()).checkRealModel(); fail("Demo accepted") }
            catch (e: ApiFailure) { assertEquals("DEMO_MODE", e.code) }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
}
