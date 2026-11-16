package com.notesis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConnectionTest {
    @Test
    fun `a token is long, random and fit for a header`() {
        val tokens = (1..50).map { newToken() }
        assertEquals(50, tokens.toSet().size)
        for (t in tokens) {
            assertEquals(47, t.length)
            assertTrue(t.startsWith("nts_"))
            assertTrue(t, t.drop(4).all { it.isLetterOrDigit() || it == '-' || it == '_' })
        }
    }

    @Test
    fun `a masked token gives little away`() {
        val token = newToken()
        val masked = maskedToken(token)
        assertEquals("nts_", masked.take(4))
        assertFalse(masked.contains(token.drop(8)))
        assertEquals("••••", maskedToken("abcd"))
    }

    @Test
    fun `the snippets carry the address and the token, and parse`() {
        val url = "http://127.0.0.1:47821/mcp"
        val command = claudeCodeCommand(url, "nts_abc")
        assertEquals("claude mcp add --transport http notesis $url --header \"Authorization: Bearer nts_abc\"", command)
        val json = JSONObject(mcpClientJson(url, "nts_abc")).getJSONObject("mcpServers").getJSONObject("notesis")
        assertEquals(url, json.getString("url"))
        assertEquals("Bearer nts_abc", json.getJSONObject("headers").getString("Authorization"))
        assertTrue(mcpClientJson(url, "t").contains("http://127.0.0.1"))
    }

    @Test
    fun `the help lists every command and the rules`() {
        val help = agentHelp()
        for (usage in COMMAND_USAGE.values) assertTrue(usage, help.contains(usage))
        assertTrue(help.contains("takes changes and shows them at once"))
        assertTrue(help.contains("trash"))
        assertNotEquals("", help)
    }
}
