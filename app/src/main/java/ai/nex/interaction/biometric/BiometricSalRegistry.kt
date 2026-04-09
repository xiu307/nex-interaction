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

    private const val KEY_ROBOT_FACE_RTM = "robot_face_rtm_uplink_enabled"

    private val gson = Gson()

    private fun prefs() = AgentApp.instance().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveRegistrationSnapshot(snapshot: BiometricRegistrationSnapshot): String {
        val json = gson.toJson(snapshot)
        prefs().edit().putString(KEY_REGISTRATION_SNAPSHOT, json).commit()
        return json
    }

    fun getRegistrationSnapshot(): BiometricRegistrationSnapshot? {
        val json = prefs().getString(KEY_REGISTRATION_SNAPSHOT, "") ?: ""
        if (json.isEmpty()) return null
        return try {
            gson.fromJson(json, BiometricRegistrationSnapshot::class.java)
        } catch (_: Exception) {
            null
        }
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
        val snap = getRegistrationSnapshot()
        val keys = pcmMap.keys.union(faceMap.keys).toMutableSet()
        snap?.faceId?.takeIf { it.isNotEmpty() }?.let { keys.add(it) }
        return keys.sorted().map { faceId ->
            val fromSnap = snap?.takeIf { it.faceId == faceId }
            BiometricStoredPersonRow(
                faceId = faceId,
                faceImageOssUrl = faceMap[faceId] ?: fromSnap?.faceImageOssUrl,
                pcmOssUrl = pcmMap[faceId] ?: fromSnap?.pcmOssUrl,
            )
        }
    }

    /**
     * SAL 云端只认 **http(s)** 的人脸图 URL + PCM URL；仅本地注册（`local://` 或缺 OSS）不会进入此 Map。
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
                if (pcm.isNotEmpty() && faceImg.isNotEmpty() && isHttpUrl(pcm) && isHttpUrl(faceImg)) {
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
        val faceUrl = fm[faceId]
        val pcmUrl = pm[faceId]
        val newFace = if (faceUrl != null) mapOf(faceId to faceUrl) else emptyMap()
        val newPcm = if (pcmUrl != null) mapOf(faceId to pcmUrl) else emptyMap()
        val e = prefs().edit()
        e.putString(KEY_FACE_IMAGE_MAP, gson.toJson(newFace))
        e.putString(KEY_MAP, gson.toJson(newPcm))
        for (k in prefs().all.keys.toList()) {
            if (k.startsWith("biometric_local_face_image_") || k.startsWith("biometric_local_pcm_")) {
                e.remove(k)
            }
        }
        e.commit()
    }

    /** 本地注册页有 URL 映射，但尚无同时满足 http 的人脸图+PCM（例如仅 OSS 未配置）。用于诊断 sample_urls 为何不含你的 faceId。 */
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
            val doubleHttp = isHttpUrl(pcm) && isHttpUrl(faceImg)
            hasAny && !doubleHttp
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
        faceMap.remove(faceId)
        pcmMap.remove(faceId)
        val e = prefs().edit()
        e.putString(KEY_FACE_IMAGE_MAP, gson.toJson(faceMap))
        e.putString(KEY_MAP, gson.toJson(pcmMap))
        val snap = getRegistrationSnapshot()
        if (snap?.faceId == faceId) {
            e.remove(KEY_REGISTRATION_SNAPSHOT)
        }
        if (getLastRegisteredFaceId() == faceId) {
            val remaining = faceMap.keys.union(pcmMap.keys).filter { it.isNotEmpty() }.sorted().firstOrNull()
            e.putString("biometric_last_face_id", remaining ?: "")
        }
        e.commit()
        ConvoFacedetDock.clearFacePipelineState(AgentApp.instance(), faceId)
    }

    /**
     * 清除本页所有登记数据（URL 映射、快照、last faceId、本地路径键）。不修改 [KEY_ROBOT_FACE_RTM]。
     */
    fun clearAllRegistration() {
        val e = prefs().edit()
        e.remove(KEY_MAP)
        e.remove(KEY_FACE_IMAGE_MAP)
        e.remove(KEY_REGISTRATION_SNAPSHOT)
        e.remove("biometric_last_face_id")
        for (k in prefs().all.keys.toList()) {
            if (k.startsWith("biometric_local_face_image_") || k.startsWith("biometric_local_pcm_")) {
                e.remove(k)
            }
        }
        e.commit()
        ConvoFacedetDock.clearAllFacesPipelineState(AgentApp.instance())
    }

    fun setLastRegisteredFaceId(id: String?) {
        prefs().edit().putString("biometric_last_face_id", id ?: "").apply()
    }

    fun getLastRegisteredFaceId(): String? =
        prefs().getString("biometric_last_face_id", "")?.takeIf { it.isNotEmpty() }

    fun setRobotFaceRtmEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_ROBOT_FACE_RTM, enabled).apply()
    }

    fun isRobotFaceRtmEnabled(): Boolean =
        prefs().getBoolean(KEY_ROBOT_FACE_RTM, BuildConfig.DEBUG)
}
