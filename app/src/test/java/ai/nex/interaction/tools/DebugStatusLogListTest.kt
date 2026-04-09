package ai.nex.interaction.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugStatusLogListTest {

    @Test
    fun append_empty_message_unchanged() {
        val list = listOf("a")
        assertEquals(list, list.appendDebugStatusLine(""))
    }

    @Test
    fun append_drops_oldest_when_over_max() {
        val base = (1..20).map { "line$it" }
        val out = base.appendDebugStatusLine("new", maxLines = 20)
        assertEquals(20, out.size)
        assertEquals("line2", out.first())
        assertEquals("new", out.last())
    }
}
