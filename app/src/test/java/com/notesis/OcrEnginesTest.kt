package com.notesis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrEnginesTest {
    @Test fun `the lean request asks for a low image resolution, little thinking and a capped answer`() {
        val lean = JSONObject(aiOcrRequest("Read it.", "QUJD", AiDetail.LOW, lean = true))
        val parts = lean.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("Read it.", parts.getJSONObject(0).getString("text"))
        val image = parts.getJSONObject(1)
        assertEquals("image/jpeg", image.getJSONObject("inlineData").getString("mimeType"))
        assertEquals("QUJD", image.getJSONObject("inlineData").getString("data"))
        assertEquals("media_resolution_low", image.getJSONObject("mediaResolution").getString("level"))
        val config = lean.getJSONObject("generationConfig")
        assertEquals(AI_OCR_MAX_OUTPUT, config.getInt("maxOutputTokens"))
        assertEquals("minimal", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test fun `the plain request leaves out what a model may refuse`() {
        val plain = JSONObject(aiOcrRequest("Read it.", "QUJD", AiDetail.HIGH, lean = false))
        val image = plain.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1)
        assertFalse(image.has("mediaResolution"))
        assertFalse(plain.getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test fun `an answer gives its text and tokens, and leaves out the model's thoughts`() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"안녕 hello\nx^2"}]},
            "finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":91,"candidatesTokenCount":7,"thoughtsTokenCount":3}}"""
        val result = parseAiOcrResponse(body)
        assertEquals("안녕 hello\nx^2", result.text)
        assertEquals(91, result.inputTokens)
        assertEquals(10, result.outputTokens)
        assertNull(result.error)
    }

    @Test fun `an error or an empty answer says why`() {
        assertEquals("API key not valid", parseAiOcrResponse("""{"error":{"code":400,"message":"API key not valid"}}""").error)
        assertEquals("No text came back (SAFETY)",
            parseAiOcrResponse("""{"candidates":[{"finishReason":"SAFETY"}]}""").error)
        assertEquals("No text found", parseAiOcrResponse("""{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}""").error)
        assertEquals("The answer could not be read", parseAiOcrResponse("not json").error)
    }

    @Test fun `a code fence round the answer is taken off`() {
        assertEquals("| a | b |", parseAiOcrResponse("""{"candidates":[{"content":{"parts":[{"text":"```\n| a | b |\n```"}]}}]}""").text)
    }

    @Test fun `pictures are made smaller to fit, never larger`() {
        assertEquals(768 to 384, aiImageSize(2000, 1000, 768))
        assertEquals(384 to 768, aiImageSize(1000, 2000, 768))
        assertEquals(300 to 200, aiImageSize(300, 200, 768))
        assertEquals(1 to 1, aiImageSize(0, 0, 768))
    }

    @Test fun `only plain model names go into the request path`() {
        assertTrue(validAiModel(AI_OCR_DEFAULT_MODEL))
        assertTrue(validAiModel("gemini-3.5-flash-lite"))
        assertFalse(validAiModel(""))
        assertFalse(validAiModel("../x"))
        assertFalse(validAiModel("model?key=1"))
    }

    @Test fun `text read another way becomes one line to a line`() {
        val lines = ocrLinesOf("first\n\n second  \n", OCR_SOURCE_AI)
        assertEquals(listOf("first", " second"), lines.map { it.readings.single().text })
        assertTrue(lines.all { it.readings.single().language == OCR_SOURCE_AI })
    }

    @Test fun `both ways give each line every distinct reading, the strokes' first`() {
        val ink = listOf(OcrLine(listOf(OcrReading("hello", "en"))), OcrLine(listOf(OcrReading("world", "en"))))
        val picture = listOf(OcrLine(listOf(OcrReading("hello", OCR_SOURCE_PICTURE))), OcrLine(listOf(OcrReading("wor1d", OCR_SOURCE_PICTURE))),
            OcrLine(listOf(OcrReading("extra", OCR_SOURCE_PICTURE))))
        val merged = mergeOcrLines(ink, picture)
        assertEquals(3, merged.size)
        assertEquals(listOf("hello"), merged[0].readings.map { it.text })
        assertEquals(listOf("world", "wor1d"), merged[1].readings.map { it.text })
        assertEquals("en", merged[1].readings.first().language)
        assertEquals(listOf("extra"), merged[2].readings.map { it.text })
    }

    @Test fun `an unknown saved way reads as the strokes way`() {
        assertEquals(OcrEngine.INK, OcrEngine.of(null))
        assertEquals(OcrEngine.AI, OcrEngine.of("ai"))
        assertEquals(OcrEngine.INK, OcrEngine.of("gone"))
        assertEquals(AiDetail.LOW, AiDetail.of(null))
        assertEquals(AiOcrMode.MATH, AiOcrMode.of("math"))
    }

    @Test fun `every way asks for its own output`() {
        AiOcrMode.entries.forEach { assertTrue(aiOcrPrompt(it).length < 200) }
        assertTrue(aiOcrPrompt(AiOcrMode.MATH).contains("LaTeX"))
        assertTrue(aiOcrPrompt(AiOcrMode.TABLE).contains("Markdown"))
    }
}
