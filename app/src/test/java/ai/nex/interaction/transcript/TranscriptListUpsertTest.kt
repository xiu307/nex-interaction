package ai.nex.interaction.transcript

import ai.nex.interaction.vendor.convoai.Transcript
import ai.nex.interaction.vendor.convoai.TranscriptRenderMode
import ai.nex.interaction.vendor.convoai.TranscriptStatus
import ai.nex.interaction.vendor.convoai.TranscriptType
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptListUpsertTest {

    private fun t(
        turnId: Long,
        type: TranscriptType,
        text: String,
    ) = Transcript(
        turnId = turnId,
        text = text,
        status = TranscriptStatus.IN_PROGRESS,
        type = type,
        renderMode = TranscriptRenderMode.Text,
    )

    @Test
    fun upsert_appends_when_no_match() {
        val a = t(1L, TranscriptType.USER, "a")
        val b = t(2L, TranscriptType.USER, "b")
        val list = emptyList<Transcript>().upsertTranscript(a).upsertTranscript(b)
        assertEquals(2, list.size)
        assertEquals("a", list[0].text)
        assertEquals("b", list[1].text)
    }

    @Test
    fun upsert_replaces_same_turn_and_type() {
        val first = t(1L, TranscriptType.AGENT, "old")
        val updated = t(1L, TranscriptType.AGENT, "new")
        val list = listOf(first).upsertTranscript(updated)
        assertEquals(1, list.size)
        assertEquals("new", list[0].text)
    }

    @Test
    fun same_turnId_different_type_both_kept() {
        val user = t(1L, TranscriptType.USER, "u")
        val agent = t(1L, TranscriptType.AGENT, "a")
        val list = emptyList<Transcript>().upsertTranscript(user).upsertTranscript(agent)
        assertEquals(2, list.size)
    }
}
