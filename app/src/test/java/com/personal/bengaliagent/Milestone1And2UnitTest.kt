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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
    fun `key validator checks provider formats and redacts secrets`() {
        val validGemini = "AIzaSyA123456789012345678901234567890"
        val invalidGemini = "short_key"
        assertTrue(KeyValidator.validateFormat(ProviderType.GEMINI, validGemini).isValidFormat)
        assertFalse(KeyValidator.validateFormat(ProviderType.GEMINI, invalidGemini).isValidFormat)

        val validGroq = "gsk_123456789012345678901234567890123456"
        assertTrue(KeyValidator.validateFormat(ProviderType.GROQ, validGroq).isValidFormat)

        val validOpenRouter = "sk-or-v1-123456789012345678901234567890"
        assertTrue(KeyValidator.validateFormat(ProviderType.OPENROUTER, validOpenRouter).isValidFormat)

        val logLine = "Error with key $validGemini and $validGroq"
        val redacted = KeyValidator.redactSecrets(logLine)
        assertFalse(redacted.contains(validGemini))
        assertFalse(redacted.contains(validGroq))
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
    fun `live model discovery parses OpenAI-compatible models and filters non-chat models`() = runTest {
        val mockJson = """
            {
              "data": [
                {"id": "llama-3.3-70b-versatile"},
                {"id": "whisper-large-v3"},
                {"id": "llama-3.1-8b-instant"}
              ]
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val client = LlmProviderClient(
            baseUrlOverride = mapOf(ProviderType.GROQ to mockServer.url("/v1").toString().removeSuffix("/"))
        )
        val validGroqKey = "gsk_123456789012345678901234567890123456"
        val result = client.fetchAvailableModels(ProviderType.GROQ, validGroqKey)

        assertTrue(result.isFromLiveApi)
        assertEquals(listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"), result.models)
        assertEquals("llama-3.3-70b-versatile", result.recommendedModel)
    }

    @Test
    fun `connection test distinguishes 401 invalid key from 200 success`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"OK"}}]}"""
            )
        )

        val client = LlmProviderClient(
            baseUrlOverride = mapOf(ProviderType.OPENROUTER to mockServer.url("/v1").toString().removeSuffix("/"))
        )
        val key = "sk-or-v1-123456789012345678901234567890"

        val failedResult = client.testConnection(ProviderType.OPENROUTER, key, "google/gemini-2.5-flash")
        assertFalse(failedResult.success)
        assertEquals(ErrorCategory.INVALID_KEY, failedResult.errorCategory)

        val okResult = client.testConnection(ProviderType.OPENROUTER, key, "google/gemini-2.5-flash")
        assertTrue(okResult.success)
        assertEquals(ErrorCategory.NONE, okResult.errorCategory)
    }
}
