package ai.nex.interaction.biometric

import android.content.Context
import ai.nex.interaction.AgentApp
import ai.nex.interaction.BuildConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 用户点击「保存到本地」时固化的注册快照（与 Android `BiometricSalRegistry` 字段/键名一致，便于迁移）。
 */
data class BiometricRegistrationSnapshot(
    val faceId: String,
    val faceImageOssUrl: String,
    val pcmOssUrl: String,
    val savedAtEpochMs: Long,
)

data class BiometricStoredPersonRow(
    val faceId: String,
    val faceImageOssUrl: String?,
    val pcmOssUrl: String?,
)

/**
 * 声纹与面部注册页的本地持久化（SharedPreferences，等价原工程 MMKV）。
 */
object BiometricSalRegistry {

    /** 未配置 OSS 时写入 SP 的占位「URL」，仅用于解锁步骤；不参与 SAL 云端合并（须真实 http(s)） */
    const val FACE_IMAGE_URL_LOCAL_ONLY = "local://biometric-face"
    const val PCM_URL_LOCAL_ONLY = "local://biometric-pcm"

    fun isHttpUrl(url: String?): Boolean = url?.startsWith("http", ignoreCase = true) == true

    private const val PREFS_NAME = "biometric_sal_prefs"

    private const val KEY_MAP = "biometric_sal_face_id_pcm_map"
    private const val KEY_FACE_IMAGE_MAP = "biometric_sal_face_id_face_image_map"
    private const val KEY_REGISTRATION_SNAPSHOT = "biometric_registration_snapshot_v1"
    private const val KEY_REGISTRATION_SNAPSHOT_MAP = "biometric_registration_snapshot_map_v1"
    private const val KEY_FACE_ID_USER_ID_MAP = "biometric_face_id_user_id_map_v1"
    private const val KEY_LAST_REGISTERED_USER_ID = "biometric_last_face_id"

    private const val KEY_ROBOT_FACE_RTM = "robot_face_rtm_uplink_enabled"
    private const val GENERATED_USER_ID_START = 6000

    private val gson = Gson()

    private fun prefs() = AgentApp.instance().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * faceId -> userId 绑定。业务主键统一使用 userId，faceId 仅用于底层识别临时映射。
     */
    fun bindFaceIdToUserId(faceId: String, userId: String) {
        if (faceId.isEmpty() || userId.isEmpty()) return
        val map = loadFaceIdToUserIdMap().toMutableMap()
        map[faceId] = userId
        prefs().edit().putString(KEY_FACE_ID_USER_ID_MAP, gson.toJson(map)).apply()
    }

    fun resolveUserIdByFaceId(faceId: String): String? =
        loadFaceIdToUserIdMap()[faceId]?.takeIf { it.isNotEmpty() }

    fun getOrCreateUserIdForFaceId(faceId: String): String {
        resolveUserIdByFaceId(faceId)?.let { return it }
        val used = loadFaceIdToUserIdMap().values.toMutableSet().apply { addAll(loadMap().keys) }
        var uid = GENERATED_USER_ID_START
        while (used.contains(uid.toString())) {
            uid++
        }
        val generated = uid.toString()
        bindFaceIdToUserId(faceId, generated)
        return generated
    }

    fun getRegisteredUserIds(): List<String> = getAllStoredPersonRows().map { it.faceId }

    fun saveRegistrationSnapshot(snapshot: BiometricRegistrationSnapshot): String {
        val json = gson.toJson(snapshot)
        val map = loadRegistrationSnapshotMap().toMutableMap()
        map[snapshot.faceId] = snapshot
        prefs().edit()
            .putString(KEY_REGISTRATION_SNAPSHOT_MAP, gson.toJson(map))
            // 保留旧 key，兼容历史读取逻辑
            .putString(KEY_REGISTRATION_SNAPSHOT, json)
            .commit()
        return json
    }

    fun getRegistrationSnapshot(): BiometricRegistrationSnapshot? {
        // 优先返回“最近一次 faceId”对应快照，避免旧调用点语义变化。
        getLastRegisteredFaceId()?.let { fid ->
            getRegistrationSnapshotForFaceId(fid)?.let { return it }
        }
        // 回退：多快照中按时间取最新一条
        loadRegistrationSnapshotMap().values.maxByOrNull { it.savedAtEpochMs }?.let { return it }
        // 兼容旧单快照格式
        val json = prefs().getString(KEY_REGISTRATION_SNAPSHOT, "") ?: ""
        if (json.isEmpty()) return null
        return try {
            gson.fromJson(json, BiometricRegistrationSnapshot::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun getRegistrationSnapshotForFaceId(faceId: String): BiometricRegistrationSnapshot? {
        if (faceId.isEmpty()) return null
        val fromMap = loadRegistrationSnapshotMap()[faceId]
        if (fromMap != null) return fromMap
        // 兼容旧单快照：若 faceId 恰好相等，仍可展示
        val legacy = getLegacyRegistrationSnapshot()
        return legacy?.takeIf { it.faceId == faceId }
    }

    /** 仅写入 PCM 映射 URL（http 或 local:// 占位），不持久化本地文件路径。 */
    fun saveFaceIdToPcmUrl(faceIdKey: String, pcmHttpUrl: String) {
        val map = loadMap().toMutableMap()
        map[faceIdKey] = pcmHttpUrl
        // commit：保证紧接着读 getCompleteSalFaceIdToPcmUrls / 自动快照时数据已落盘（apply 异步会偶发读旧值）
        prefs().edit()
            .putString(KEY_MAP, gson.toJson(map))
            .commit()
    }

    fun getSalSampleUrls(): Map<String, String> = loadMap()

    fun getAllStoredPersonRows(): List<BiometricStoredPersonRow> {
        val pcmMap = loadMap()
        val faceMap = loadFaceImageMap()
        val snapshotMap = loadRegistrationSnapshotMap()
        val legacySnap = getLegacyRegistrationSnapshot()
        val keys = pcmMap.keys.union(faceMap.keys).toMutableSet()
        keys.addAll(snapshotMap.keys.filter { it.isNotEmpty() })
        legacySnap?.faceId?.takeIf { it.isNotEmpty() }?.let { keys.add(it) }
        return keys.sorted().map { faceId ->
            val fromSnap = snapshotMap[faceId] ?: legacySnap?.takeIf { it.faceId == faceId }
            BiometricStoredPersonRow(
                faceId = faceId,
                faceImageOssUrl = faceMap[faceId] ?: fromSnap?.faceImageOssUrl,
                pcmOssUrl = pcmMap[faceId] ?: fromSnap?.pcmOssUrl,
            )
        }
    }

    /**
     * SAL `sample_urls` 仅要求 PCM 为 **http(s)**；face URL 只要有值即可（允许 `local://`）。
     */
    fun getCompleteSalFaceIdToPcmUrls(): Map<String, String> {
        val pcmMap = loadMap()
        val faceMap = loadFaceImageMap()
        val keys = pcmMap.keys.union(faceMap.keys)
        return buildMap {
            for (faceId in keys) {
                if (faceId.isEmpty()) continue
                val pcm = pcmMap[faceId]?.trim().orEmpty()
                val faceImg = faceMap[faceId]?.trim().orEmpty()
                if (pcm.isNotEmpty() && faceImg.isNotEmpty() && isHttpUrl(pcm)) {
                    put(faceId, pcm)
                }
            }
        }
    }

    /**
     * 保存到本地 / 自动快照时调用：**只保留 [faceId]**，其它 faceId 的 URL 映射全部删除；并清理历史 `biometric_local_*` 路径键（不再写入新路径）。
     */
    fun replaceAllRegistrationDataWithSingleFaceId(faceId: String) {
        if (faceId.isEmpty()) return
        val fm = loadFaceImageMap()
        val pm = loadMap()
        val sm = loadRegistrationSnapshotMap()
        val faceUrl = fm[faceId]
        val pcmUrl = pm[faceId]
        val snap = sm[faceId]
        val newFace = if (faceUrl != null) mapOf(faceId to faceUrl) else emptyMap()
        val newPcm = if (pcmUrl != null) mapOf(faceId to pcmUrl) else emptyMap()
        val newSnap = if (snap != null) mapOf(faceId to snap) else emptyMap()
        val e = prefs().edit()
        e.putString(KEY_FACE_IMAGE_MAP, gson.toJson(newFace))
        e.putString(KEY_MAP, gson.toJson(newPcm))
        e.putString(KEY_REGISTRATION_SNAPSHOT_MAP, gson.toJson(newSnap))
        if (snap == null) {
            e.remove(KEY_REGISTRATION_SNAPSHOT)
        } else {
            e.putString(KEY_REGISTRATION_SNAPSHOT, gson.toJson(snap))
        }
        for (k in prefs().all.keys.toList()) {
            if (k.startsWith("biometric_local_face_image_") || k.startsWith("biometric_local_pcm_")) {
                e.remove(k)
            }
        }
        e.commit()
    }

    /** 本地注册页有 URL 映射，但 PCM 尚未满足 http(s)。用于诊断 sample_urls 为何不含你的 faceId。 */
    fun hasLocalRegistrationButNoHttpSalPair(): Boolean {
        val complete = getCompleteSalFaceIdToPcmUrls()
        if (complete.isNotEmpty()) return false
        val pcmMap = loadMap()
        val faceMap = loadFaceImageMap()
        val keys = pcmMap.keys.union(faceMap.keys)
        return keys.any { fid ->
            if (fid.isEmpty()) return@any false
            val pcm = pcmMap[fid]?.trim().orEmpty()
            val faceImg = faceMap[fid]?.trim().orEmpty()
            val hasAny = pcm.isNotEmpty() || faceImg.isNotEmpty()
            val salReadyByNewRule = pcm.isNotEmpty() && faceImg.isNotEmpty() && isHttpUrl(pcm)
            hasAny && !salReadyByNewRule
        }
    }

    fun getPcmHttpUrl(faceIdKey: String): String? = loadMap()[faceIdKey]

    fun getLastRegisteredPcmHttpUrl(): String? =
        getLastRegisteredFaceId()?.let { getPcmHttpUrl(it) }

    /** 仅写入人脸图映射 URL（http 或 local:// 占位），不持久化本地文件路径。 */
    fun saveFaceIdToFaceImageUrl(faceIdKey: String, imageHttpUrl: String) {
        val map = loadFaceImageMap().toMutableMap()
        map[faceIdKey] = imageHttpUrl
        prefs().edit()
            .putString(KEY_FACE_IMAGE_MAP, gson.toJson(map))
            .commit()
    }

    fun getFaceImageHttpUrl(faceIdKey: String): String? = loadFaceImageMap()[faceIdKey]

    fun getLastRegisteredFaceImageUrl(): String? =
        getLastRegisteredFaceId()?.let { getFaceImageHttpUrl(it) }

    private fun loadFaceImageMap(): Map<String, String> {
        val json = prefs().getString(KEY_FACE_IMAGE_MAP, "") ?: ""
        if (json.isEmpty()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun loadMap(): Map<String, String> {
        val json = prefs().getString(KEY_MAP, "") ?: ""
        if (json.isEmpty()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 删除指定 [faceId] 的人脸图与 PCM 映射；若本地快照对应该 faceId 则一并清除；
     * 若 [getLastRegisteredFaceId] 与之一致则改为剩余条目的第一个或清空。
     * 随后调用 [ConvoFacedetDock.clearFacePipelineState]：删 ArcFace embedding + 清 pipeline 跟踪状态。
     */
    fun removeRegistrationForFaceId(faceId: String) {
        if (faceId.isEmpty()) return
        val faceMap = loadFaceImageMap().toMutableMap()
        val pcmMap = loadMap().toMutableMap()
        val snapshotMap = loadRegistrationSnapshotMap().toMutableMap()
        val faceToUser = loadFaceIdToUserIdMap().toMutableMap()
        faceMap.remove(faceId)
        pcmMap.remove(faceId)
        snapshotMap.remove(faceId)
        val boundFaceIds = faceToUser.filterValues { it == faceId }.keys
        boundFaceIds.forEach { faceToUser.remove(it) }
        val e = prefs().edit()
        e.putString(KEY_FACE_IMAGE_MAP, gson.toJson(faceMap))
        e.putString(KEY_MAP, gson.toJson(pcmMap))
        e.putString(KEY_REGISTRATION_SNAPSHOT_MAP, gson.toJson(snapshotMap))
        e.putString(KEY_FACE_ID_USER_ID_MAP, gson.toJson(faceToUser))
        val legacySnap = getLegacyRegistrationSnapshot()
        if (legacySnap?.faceId == faceId) {
            e.remove(KEY_REGISTRATION_SNAPSHOT)
        }
        if (getLastRegisteredFaceId() == faceId) {
            val remaining = faceMap.keys.union(pcmMap.keys).filter { it.isNotEmpty() }.sorted().firstOrNull()
            e.putString(KEY_LAST_REGISTERED_USER_ID, remaining ?: "")
        }
        e.commit()
        // 删除业务 userId 时，回收其对应底层 faceId 的 embedding/跟踪状态。
        if (boundFaceIds.isEmpty()) {
            ConvoFacedetDock.clearFacePipelineState(AgentApp.instance(), faceId)
        } else {
            boundFaceIds.forEach { rawFaceId ->
                ConvoFacedetDock.clearFacePipelineState(AgentApp.instance(), rawFaceId)
            }
        }
    }

    /**
     * 清除本页所有登记数据（URL 映射、快照、last faceId、本地路径键）。不修改 [KEY_ROBOT_FACE_RTM]。
     */
    fun clearAllRegistration() {
        val e = prefs().edit()
        e.remove(KEY_MAP)
        e.remove(KEY_FACE_IMAGE_MAP)
        e.remove(KEY_REGISTRATION_SNAPSHOT)
        e.remove(KEY_REGISTRATION_SNAPSHOT_MAP)
        e.remove(KEY_FACE_ID_USER_ID_MAP)
        e.remove(KEY_LAST_REGISTERED_USER_ID)
        for (k in prefs().all.keys.toList()) {
            if (k.startsWith("biometric_local_face_image_") || k.startsWith("biometric_local_pcm_")) {
                e.remove(k)
            }
        }
        e.commit()
        ConvoFacedetDock.clearAllFacesPipelineState(AgentApp.instance())
    }

    fun setLastRegisteredFaceId(id: String?) {
        prefs().edit().putString(KEY_LAST_REGISTERED_USER_ID, id ?: "").apply()
    }

    fun getLastRegisteredFaceId(): String? =
        prefs().getString(KEY_LAST_REGISTERED_USER_ID, "")?.takeIf { it.isNotEmpty() }

    fun setRobotFaceRtmEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_ROBOT_FACE_RTM, enabled).apply()
    }

    fun isRobotFaceRtmEnabled(): Boolean =
        prefs().getBoolean(KEY_ROBOT_FACE_RTM, BuildConfig.DEBUG)

    private fun loadRegistrationSnapshotMap(): Map<String, BiometricRegistrationSnapshot> {
        val json = prefs().getString(KEY_REGISTRATION_SNAPSHOT_MAP, "") ?: ""
        if (json.isEmpty()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, BiometricRegistrationSnapshot>>() {}.type
            gson.fromJson<Map<String, BiometricRegistrationSnapshot>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun getLegacyRegistrationSnapshot(): BiometricRegistrationSnapshot? {
        val json = prefs().getString(KEY_REGISTRATION_SNAPSHOT, "") ?: ""
        if (json.isEmpty()) return null
        return try {
            gson.fromJson(json, BiometricRegistrationSnapshot::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadFaceIdToUserIdMap(): Map<String, String> {
        val json = prefs().getString(KEY_FACE_ID_USER_ID_MAP, "") ?: ""
        if (json.isEmpty()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
