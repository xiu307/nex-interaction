package ai.nex.interaction.session

import org.junit.Assert.assertNull
import org.junit.Test

class AgentSessionStateTest {

    @Test
    fun clearAgentRestFields_clearsIdAndToken() {
        val s = AgentSessionState()
        s.agentId = "a1"
        s.authToken = "t1"
        s.clearAgentRestFields()
        assertNull(s.agentId)
        assertNull(s.authToken)
    }
}
