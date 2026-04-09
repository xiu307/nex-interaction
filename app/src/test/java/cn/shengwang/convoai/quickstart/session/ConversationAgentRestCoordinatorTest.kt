package cn.shengwang.convoai.quickstart.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationAgentRestCoordinatorTest {

    @Test
    fun stopRemoteAgentIfStarted_whenNoAgent_isSuccessWithoutNetwork() = runBlocking {
        val r = ConversationAgentRestCoordinator.stopRemoteAgentIfStarted(
            agentId = null,
            authToken = null,
        )
        assertTrue(r.isSuccess)
    }
}
