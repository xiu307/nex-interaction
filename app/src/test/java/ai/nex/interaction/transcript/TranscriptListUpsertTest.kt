package ai.nex.interaction.transcript

import ai.conv.core.convoai.Transcript
import ai.conv.core.convoai.TranscriptRenderMode
import ai.conv.core.convoai.TranscriptStatus
import ai.conv.core.convoai.TranscriptType
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptListUpsertTest {

    private fun t(
        turnId: Long,
        type: TranscriptType,
        text: String,
        userId: String = "1000",
    ) = Transcript(
        turnId = turnId,
        userId = userId,
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

    @Test
    fun same_turn_and_type_different_user_kept() {
        val u1 = t(1L, TranscriptType.USER, "u1", userId = "6000")
        val u2 = t(1L, TranscriptType.USER, "u2", userId = "6001")
        val list = emptyList<Transcript>().upsertTranscript(u1).upsertTranscript(u2)
        assertEquals(2, list.size)
        assertEquals("6000", list[0].userId)
        assertEquals("6001", list[1].userId)
    }
}
