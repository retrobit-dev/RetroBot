package ai.retrobot.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownishTest {

    @Test
    fun `plain text becomes a single segment`() {
        val segments = parseSegments("just some text")

        assertEquals(1, segments.size)
        assertTrue(segments[0] is Segment.Text)
        assertEquals("just some text", (segments[0] as Segment.Text).text)
    }

    @Test
    fun `a fenced block becomes a code segment`() {
        val segments = parseSegments("before\n```kotlin\nval x = 1\n```\nafter")

        assertEquals(3, segments.size)
        assertTrue(segments[1] is Segment.Code)
        assertEquals("kotlin", (segments[1] as Segment.Code).language)
        assertEquals("val x = 1", (segments[1] as Segment.Code).text)
    }

    @Test
    fun `a fence without a language is still recognised`() {
        val segments = parseSegments("```\nplain code\n```")

        assertEquals(1, segments.size)
        assertTrue(segments[0] is Segment.Code)
        assertEquals(null, (segments[0] as Segment.Code).language)
    }

    @Test
    fun `an unterminated fence does not lose the content`() {
        val segments = parseSegments("```\nstill streaming")

        assertEquals(1, segments.size)
        assertTrue(segments[0] is Segment.Code)
        assertEquals("still streaming", (segments[0] as Segment.Code).text)
    }

    @Test
    fun `empty content produces no segments`() {
        assertEquals(0, parseSegments("").size)
    }
}
