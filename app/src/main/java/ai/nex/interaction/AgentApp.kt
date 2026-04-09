package ai.nex.interaction

import android.app.Application

class AgentApp : Application() {
    companion object {
        private const val TAG = "AgentApp"
        private lateinit var app: Application

        @JvmStatic
        fun instance(): Application {
            return app
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = this
    }
}