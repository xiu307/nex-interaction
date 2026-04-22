package ai.nex.interaction.session

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import ai.nex.interaction.AgentApp
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.random.Random

/**
 * 本地用户 RTC/RTM 数字 UID（持久化）及与之配对的 Agent UID、随机频道名。
 *
 * 从 [ai.nex.interaction.ui.AgentChatViewModel] companion 抽出，逻辑与原先一致，
 * 仅降低 ViewModel 单文件体积，便于后续单测与复用。
 */
object ConversationSessionIdentity {

    private const val USER_PREFS_NAME = "agent_chat_prefs"
    private const val KEY_LOCAL_USER_ID = "local_user_id"
    private const val INVALID_UID = -1
    private const val MAX_LOCAL_USER_NUM = 10
    private const val UID_MIN = 1
    private const val UID_MAX = 2_000_000_000
    private const val AGENT_UID_SALT = "convoai_agent_uid_v1"

    val userId: Int = getOrCreateLocalUserId()

    val agentUid: Int = generateAgentUid(setOf(userId))

    fun generateAgentUid(localUserId: Int, totalUserNum: Int): Int {
        val occupied = buildSet {
            val maxOffset = maxOf(MAX_LOCAL_USER_NUM, totalUserNum.coerceAtLeast(1))
            for (uid in localUserId..(localUserId + maxOffset)) {
                add(uid)
            }
        }
        return generateAgentUid(occupied)
    }

    fun generateRandomChannelName(): String =
        "channel_kotlin_${Random.nextInt(100000, 999999)}"

    private fun getOrCreateLocalUserId(): Int {
        val sharedPreferences = AgentApp.instance().getSharedPreferences(
            USER_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val cachedUserId = sharedPreferences.getInt(KEY_LOCAL_USER_ID, INVALID_UID)
        if (cachedUserId != INVALID_UID) {
            return cachedUserId
        }
        // UID 需要跨设备尽量不碰撞：用设备唯一标识（ANDROID_ID）做基底再映射到 Int 范围。
        val newUserId = generateDeviceBasedUid()
        sharedPreferences.edit {
            putInt(KEY_LOCAL_USER_ID, newUserId)
        }
        return newUserId
    }

    private fun generateDeviceBasedUid(): Int = generateDeviceSaltedUid("convoai_user_uid_v1")

    private fun stableAndroidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    /**
     * 将设备唯一标识 + salt 映射到稳定的 Int UID 空间（尽量减少跨设备同频道碰撞）。
     * - 不直接使用原始 ANDROID_ID，避免泄露；只取哈希后的 32bit。
     * - 输出范围控制在 [UID_MIN, UID_MAX]。
     */
    private fun generateDeviceSaltedUid(salt: String): Int {
        val raw = "${stableAndroidId(AgentApp.instance())}::$salt"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val first4 = ByteBuffer.wrap(digest, 0, 4).int
        val positive = abs(first4.toLong())
        val range = (UID_MAX - UID_MIN + 1).toLong()
        return (UID_MIN + (positive % range)).toInt()
    }

    fun generateAgentUid(occupiedUids: Set<Int>): Int {
        // 用设备基底生成一个“默认候选”，并在本地 occupied 集合内做线性探测避开冲突。
        var candidate = generateDeviceSaltedUid(AGENT_UID_SALT)
        // 极端情况下（同设备多 uid），通过扰动避免落在 userId 附近。
        var tries = 0
        while (occupiedUids.contains(candidate)) {
            candidate = nextUid(candidate, step = 97 + (tries % 17))
            tries++
            if (tries > 64) {
                // 兜底：退回到大范围随机
                candidate = Random.nextInt(UID_MIN, UID_MAX)
            }
        }
        return candidate
    }

    private fun nextUid(current: Int, step: Int): Int {
        val next = current.toLong() + step.toLong()
        return if (next <= UID_MAX) next.toInt() else (UID_MIN + ((next - UID_MIN) % (UID_MAX - UID_MIN + 1))).toInt()
    }
}
