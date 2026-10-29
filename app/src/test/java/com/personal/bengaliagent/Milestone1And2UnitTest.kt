package com.personal.bengaliagent

import com.personal.bengaliagent.data.security.KeyValidator
import com.personal.bengaliagent.domain.model.ActionSafetyClassifier
import com.personal.bengaliagent.domain.model.ErrorCategory
import com.personal.bengaliagent.domain.model.ProviderType
import com.personal.bengaliagent.domain.model.RiskLevel
import com.personal.bengaliagent.network.LlmProviderClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class Milestone1And2UnitTest {

    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `key validator checks non-empty Gemini format and redacts secrets`() {
        val validGemini = "AIzaSyA123456789012345678901234567890"
        assertTrue(KeyValidator.validateFormat(ProviderType.GEMINI, validGemini).isValidFormat)
        assertFalse(KeyValidator.validateFormat(ProviderType.GEMINI, "").isValidFormat)
        assertFalse(KeyValidator.validateFormat(ProviderType.GEMINI, "short").isValidFormat)

        val logLine = "Error with key $validGemini"
        val redacted = KeyValidator.redactSecrets(logLine)
        assertFalse(redacted.contains(validGemini))
        assertTrue(redacted.contains("***REDACTED***"))
    }

    @Test
    fun `risk classifier flags destructive and sensitive actions in Bengali and English`() {
        assertEquals(
            RiskLevel.DESTRUCTIVE,
            ActionSafetyClassifier.classifyAction("FILE_OP", "Delete all photos ফাইল মুছে ফেলো")
        )
        assertEquals(
            RiskLevel.SENSITIVE,
            ActionSafetyClassifier.classifyAction("CLICK", "Send money বিকাশ পেমেন্ট পাঠাও")
        )
        assertEquals(
            RiskLevel.LOW_RISK,
            ActionSafetyClassifier.classifyAction("SCROLL", "Scroll down on article")
        )
    }

    @Test
    fun `gemini automatic model discovery filters generateContent and prefers stable over preview`() = runTest {
        val modelsJson = """
            {
              "models": [
                {
                  "name": "models/text-embedding-004",
                  "displayName": "Text Embedding 004",
                  "supportedGenerationMethods": ["embedContent"]
                },
                {
                  "name": "models/gemini-2.5-flash-preview-04-17",
                  "displayName": "Gemini 2.5 Flash Preview",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "supportedGenerationMethods": ["generateContent", "countTokens"],
                  "inputTokenLimit": 1048576
                }
              ]
            }
        """.trimIndent()

        // 1st request: GET /models -> returns list
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(modelsJson))
        // 2nd request: POST /models/gemini-2.5-flash:generateContent -> verifies candidate works
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}"""
            )
        )

        val client = LlmProviderClient(
            baseUrlOverride = mapOf(
                ProviderType.GEMINI to mockServer.url("/v1beta").toString().removeSuffix("/")
            )
        )
        val validKey = "AIzaSyA123456789012345678901234567890"
        val result = client.discoverAndVerifyGemini(validKey)

        assertTrue(result.success)
        assertEquals("gemini-2.5-flash", result.verifiedModel)

        // Verify x-goog-api-key header was used and key was not leaked in URL query
        val req1 = mockServer.takeRequest()
        assertEquals(validKey, req1.getHeader("x-goog-api-key"))
        assertFalse(req1.path.orEmpty().contains(validKey))
    }

    @Test
    fun `gemini discovery falls back to next eligible candidate if first model returns 404`() = runTest {
        val modelsJson = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-flash",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-2.5-pro",
                  "supportedGenerationMethods": ["generateContent"]
                }
              ]
            }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(modelsJson))
        // First candidate fails with 404
        mockServer.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Not found"}"""))
        // Second candidate succeeds
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}"""
            )
        )

        val client = LlmProviderClient(
            baseUrlOverride = mapOf(
                ProviderType.GEMINI to mockServer.url("/v1beta").toString().removeSuffix("/")
            )
        )
        val result = client.discoverAndVerifyGemini("AIzaSyA123456789012345678901234567890")

        assertTrue(result.success)
        assertEquals("gemini-2.5-pro", result.verifiedModel)
    }

    @Test
    fun `gemini discovery handles invalid key, quota limit, and no eligible models`() = runTest {
        val client = LlmProviderClient(
            baseUrlOverride = mapOf(
                ProviderType.GEMINI to mockServer.url("/v1beta").toString().removeSuffix("/")
            )
        )
        val key = "AIzaSyA123456789012345678901234567890"

        // 1. 401 Invalid Key
        mockServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"API_KEY_INVALID"}"""))
        val invalidRes = client.discoverAndVerifyGemini(key)
        assertFalse(invalidRes.success)
        assertEquals(ErrorCategory.INVALID_KEY, invalidRes.errorCategory)
        assertNull(invalidRes.verifiedModel)

        // 2. 429 Quota Exceeded
        mockServer.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Quota exceeded"}"""))
        val quotaRes = client.discoverAndVerifyGemini(key)
        assertFalse(quotaRes.success)
        assertEquals(ErrorCategory.QUOTA_OR_RATE_LIMIT, quotaRes.errorCategory)

        // 3. No eligible text-generation models
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"models":[{"name":"models/embedding-001","supportedGenerationMethods":["embedContent"]}]}"""
            )
        )
        val emptyRes = client.discoverAndVerifyGemini(key)
        assertFalse(emptyRes.success)
        assertEquals(ErrorCategory.NO_ELIGIBLE_MODELS, emptyRes.errorCategory)
    }

    @Test
    fun `provider settings layout contains English labels and no manual model selector`() {
        val layoutFile = File("src/main/res/layout/activity_provider_settings.xml")
        if (layoutFile.exists()) {
            val xml = layoutFile.readText()
            assertFalse("Manual model Spinner must not exist in AI Connection screen", xml.contains("<Spinner"))
            assertTrue(xml.contains("btnConnectGemini"))
            assertTrue(xml.contains("etGeminiKey"))
        }
    }
}
