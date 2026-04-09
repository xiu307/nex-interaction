package cn.shengwang.convoai.quickstart.session

import android.content.Context
import androidx.core.content.edit
import cn.shengwang.convoai.quickstart.AgentApp

/**
 * 本地用户 RTC/RTM 数字 UID（持久化）及与之配对的 Agent UID、随机频道名。
 *
 * 从 [cn.shengwang.convoai.quickstart.ui.AgentChatViewModel] companion 抽出，逻辑与原先一致，
 * 仅降低 ViewModel 单文件体积，便于后续单测与复用。
 */
object ConversationSessionIdentity {

    private const val USER_PREFS_NAME = "agent_chat_prefs"
    private const val KEY_LOCAL_USER_ID = "local_user_id"
    private const val INVALID_UID = -1

    val userId: Int = getOrCreateLocalUserId()

    val agentUid: Int = generateUniqueUid(userId)

    fun generateRandomChannelName(): String =
        "channel_kotlin_${generateRandomUid()}"

    private fun getOrCreateLocalUserId(): Int {
        val sharedPreferences = AgentApp.instance().getSharedPreferences(
            USER_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val cachedUserId = sharedPreferences.getInt(KEY_LOCAL_USER_ID, INVALID_UID)
        if (cachedUserId != INVALID_UID) {
            return cachedUserId
        }
        val newUserId = generateRandomUid()
        sharedPreferences.edit {
            putInt(KEY_LOCAL_USER_ID, newUserId)
        }
        return newUserId
    }

    private fun generateUniqueUid(excludeUid: Int): Int {
        var uid: Int
        do {
            uid = generateRandomUid()
        } while (uid == excludeUid)
        return uid
    }

    private fun generateRandomUid(): Int = (100000..999999).random()
}
