package ai.nex.interaction.rtm

import android.util.Log
import io.agora.rtm.ErrorInfo
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient

/**
 * RTM 先 logout 再 login 的登录流程，及 [logout]；与原先 [ai.nex.interaction.ui.AgentChatViewModel] 内联逻辑一致。
 */
object ConversationRtmLogin {

    /**
     * @param logTag 一般为 ViewModel 的 TAG，便于 logcat 过滤。
     * @param statusLog 成功/失败时的用户可见调试文案（原 `addStatusLog`）。
     */
    fun loginAfterLogout(
        client: RtmClient?,
        rtmToken: String,
        state: RtmLoginState,
        logTag: String,
        completion: (Exception?) -> Unit,
        statusLog: (String) -> Unit,
    ) {
        Log.d(logTag, "Starting RTM login")

        if (state.isLoggingIn) {
            completion(Exception("Login already in progress"))
            Log.d(logTag, "Login already in progress")
            return
        }

        if (state.isRtmLogin) {
            completion(null)
            Log.d(logTag, "Already logged in")
            return
        }

        if (client == null) {
            completion(Exception("RTM client not initialized"))
            Log.d(logTag, "RTM client not initialized")
            return
        }

        state.isLoggingIn = true
        Log.d(logTag, "Performing logout to ensure clean environment before login")

        state.isRtmLogin = false
        client.logout(object : ResultCallback<Void> {
            override fun onSuccess(responseInfo: Void?) {
                Log.d(logTag, "Logout completed, starting login")
                performLogin(client, rtmToken, state, logTag, completion, statusLog)
            }

            override fun onFailure(errorInfo: ErrorInfo?) {
                Log.d(logTag, "Logout failed but continuing with login: ${errorInfo?.errorReason}")
                performLogin(client, rtmToken, state, logTag, completion, statusLog)
            }
        })
    }

    private fun performLogin(
        client: RtmClient,
        rtmToken: String,
        state: RtmLoginState,
        logTag: String,
        completion: (Exception?) -> Unit,
        statusLog: (String) -> Unit,
    ) {
        client.login(rtmToken, object : ResultCallback<Void> {
            override fun onSuccess(p0: Void?) {
                state.isRtmLogin = true
                state.isLoggingIn = false
                Log.d(logTag, "RTM login successful")
                completion(null)
                statusLog("Rtm login successful")
            }

            override fun onFailure(errorInfo: ErrorInfo?) {
                state.isRtmLogin = false
                state.isLoggingIn = false
                Log.e(logTag, "RTM token login failed: ${errorInfo?.errorReason}")
                completion(Exception("${errorInfo?.errorCode}"))
                statusLog("Rtm login failed, code: ${errorInfo?.errorCode}")
            }
        })
    }

    fun logout(client: RtmClient?, state: RtmLoginState, logTag: String) {
        Log.d(logTag, "RTM start logout")
        client?.logout(object : ResultCallback<Void> {
            override fun onSuccess(responseInfo: Void?) {
                state.isRtmLogin = false
                Log.d(logTag, "RTM logout successful")
            }

            override fun onFailure(errorInfo: ErrorInfo?) {
                Log.e(logTag, "RTM logout failed: ${errorInfo?.errorCode}")
                state.isRtmLogin = false
            }
        })
    }
}
