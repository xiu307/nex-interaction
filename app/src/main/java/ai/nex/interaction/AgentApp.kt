package ai.nex.interaction

import android.app.Application
import ai.nex.interaction.tts.TTSManager

class AgentApp : Application() {
    companion object {
        private const val TTS_DEVICE_ID = "geely_device_0011"
        private lateinit var app: Application

        @JvmStatic
        fun instance(): Application {
            return app
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        TTSManager.getInstance().init(this, TTS_DEVICE_ID)
    }

    override fun onTerminate() {
        TTSManager.getInstance().release()
        super.onTerminate()
    }
}
