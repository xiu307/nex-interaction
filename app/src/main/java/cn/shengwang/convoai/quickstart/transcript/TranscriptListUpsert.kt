package cn.shengwang.convoai.quickstart.transcript

import io.agora.convoai.convoaiApi.Transcript

/**
 * 按 [Transcript.turnId] + [Transcript.type] 去重更新：同键则替换，否则追加。
 * 供 UI 层展示列表与 [cn.shengwang.convoai.quickstart.ui.AgentChatViewModel] 共用，便于单测。
 */
fun List<Transcript>.upsertTranscript(transcript: Transcript): List<Transcript> {
    val mutable = toMutableList()
    val existingIndex =
        mutable.indexOfFirst { it.turnId == transcript.turnId && it.type == transcript.type }
    if (existingIndex >= 0) {
        mutable[existingIndex] = transcript
    } else {
        mutable.add(transcript)
    }
    return mutable
}
