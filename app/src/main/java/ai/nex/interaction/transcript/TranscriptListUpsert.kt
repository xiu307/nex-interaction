package ai.nex.interaction.transcript

import ai.conv.core.convoai.Transcript

/**
 * 有就更新没有就插入
 * 按 [Transcript.turnId] + [Transcript.type] + [Transcript.userId] 去重更新：
 * 同键则替换，否则追加。列表保持“到达顺序”，避免多人同轮重排导致观感错乱。
 * 供 UI 层展示列表与 [ai.nex.interaction.ui.AgentChatViewModel] 共用，便于单测。
 */
fun List<Transcript>.upsertTranscript(transcript: Transcript): List<Transcript> {
    val mutable = toMutableList()
    val existingIndex =
        mutable.indexOfFirst {
            it.turnId == transcript.turnId &&
                it.type == transcript.type &&
                it.userId == transcript.userId
        }
    if (existingIndex >= 0) {
        mutable[existingIndex] = transcript
    } else {
        mutable.add(transcript)
    }
    return mutable
}
