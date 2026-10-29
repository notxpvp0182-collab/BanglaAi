package com.personal.bengaliagent

import com.personal.bengaliagent.coding.CodingWorkspaceManager
import com.personal.bengaliagent.research.WebResearchClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Milestone8To10UnitTest {

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
    fun `web research client parses Wikipedia OpenSearch JSON and wraps untrusted snippets`() = runTest {
        val openSearchResponse = """
            [
              "বাংলাদেশ",
              ["বাংলাদেশ", "বাংলাদেশের ইতিহাস"],
              ["দক্ষিণ এশিয়ার একটি রাষ্ট্র", "বাংলাদেশের সংক্ষিপ্ত ইতিহাস"],
              ["https://bn.wikipedia.org/wiki/Bangladesh", "https://bn.wikipedia.org/wiki/History_of_Bangladesh"]
            ]
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(openSearchResponse))

        val client = WebResearchClient(
            wikiApiBaseOverride = mockServer.url("/w/api.php").toString()
        )
        val bundle = client.performBoundedSearch("বাংলাদেশ সম্পর্কে অনলাইনে খুঁজে তথ্য দাও")

        assertTrue(bundle.searchPerformed)
        assertEquals(2, bundle.sources.size)
        assertEquals("https://bn.wikipedia.org/wiki/Bangladesh", bundle.sources[0].url)

        val promptContext = bundle.toPromptContextString()
        assertTrue(promptContext.contains("<untrusted_web_result>"))
        assertTrue(promptContext.contains("https://bn.wikipedia.org/wiki/Bangladesh"))
    }

    @Test
    fun `coding workspace manager flags destructive shell commands and allows safe ones`() {
        val dangerous = CodingWorkspaceManager.evaluateShellCommandSafety("rm -rf ~/project/*")
        assertTrue(dangerous.isDestructiveOrHighRisk)

        val safe = CodingWorkspaceManager.evaluateShellCommandSafety("python3 main.py --help")
        assertFalse(safe.isDestructiveOrHighRisk)
    }
}
