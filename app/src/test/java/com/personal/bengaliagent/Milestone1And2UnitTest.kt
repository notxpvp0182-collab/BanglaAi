package com.personal.bengaliagent

import com.personal.bengaliagent.data.security.KeyValidator
import com.personal.bengaliagent.domain.model.ActionSafetyClassifier
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
    fun `prefix-free key validator accepts modern and standard keys without prefix restrictions`() {
        val modernKey = "test_credential_token_sample_1234567890"
        val standardKey = "sample_gemini_credential_key_1234567890"
        val grokKey = "sample_grok_credential_key_1234567890"

        assertTrue(KeyValidator.validateFormat(ProviderType.GEMINI, modernKey).isValidFormat)
        assertTrue(KeyValidator.validateFormat(ProviderType.GEMINI, standardKey).isValidFormat)
        assertTrue(KeyValidator.validateFormat(ProviderType.GROK, grokKey).isValidFormat)
        assertFalse(KeyValidator.validateFormat(ProviderType.GEMINI, "").isValidFormat)
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
    fun `gemini and grok dynamic model discovery parses capabilities and live audio support`() = runTest {
        val modelsJson = """
            {
              "models": [
                {
                  "name": "models/text-embedding-004",
                  "displayName": "Text Embedding 004",
                  "supportedGenerationMethods": ["embedContent"]
                },
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "supportedGenerationMethods": ["generateContent", "bidiGenerateContent"],
                  "inputTokenLimit": 1048576
                }
              ]
            }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(modelsJson))
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
        val dummyKey = "dummy_unit_test_credential_key_123456"
        val result = client.discoverAndVerifyGemini(dummyKey)

        assertTrue(result.success)
        assertEquals("gemini-2.5-flash", result.verifiedModel)
        assertEquals("gemini-2.5-flash", result.liveVoiceModel)
    }

    @Test
    fun `grok model discovery works with xAI endpoint`() = runTest {
        val grokModelsJson = """
            {
              "data": [
                {"id": "grok-3-mini-fast", "active": true},
                {"id": "grok-2-realtime", "active": true}
              ]
            }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(grokModelsJson))
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"OK"}}]}"""
            )
        )

        val client = LlmProviderClient(
            baseUrlOverride = mapOf(
                ProviderType.GROK to mockServer.url("/v1").toString().removeSuffix("/")
            )
        )
        val res = client.discoverAndVerifyProvider(ProviderType.GROK, "dummy_grok_unit_test_key_123456")
        assertTrue(res.success)
        assertEquals("grok-2-realtime", res.liveVoiceModel)
    }

    @Test
    fun `main calling screen layout has no chatbot RecyclerView or text EditText`() {
        val mainLayout = File("src/main/res/layout/activity_main.xml")
        if (mainLayout.exists()) {
            val xml = mainLayout.readText()
            assertFalse("Chatbot RecyclerView must be removed", xml.contains("rvTranscript"))
            assertFalse("Chatbot EditText must be removed", xml.contains("etUserInput"))
            assertTrue("Call AI button must exist", xml.contains("btnStartAiCall"))
            assertTrue("Active Call screen must exist", xml.contains("layoutActiveCall"))
        }
    }
}
