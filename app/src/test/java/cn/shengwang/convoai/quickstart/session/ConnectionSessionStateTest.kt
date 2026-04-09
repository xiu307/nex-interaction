package cn.shengwang.convoai.quickstart.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSessionStateTest {

    @Test
    fun rtcAndRtmReady_requiresBothFlags() {
        val c = ConnectionSessionState()
        assertFalse(c.rtcAndRtmReady)
        c.rtcJoined = true
        assertFalse(c.rtcAndRtmReady)
        c.rtmLoggedIn = true
        assertTrue(c.rtcAndRtmReady)
    }

    @Test
    fun beginJoinAttempt_resetsFlagsAndSetsChannel() {
        val c = ConnectionSessionState()
        c.rtcJoined = true
        c.rtmLoggedIn = true
        c.beginJoinAttempt("ch1")
        assertEquals("ch1", c.channelName)
        assertFalse(c.rtcJoined)
        assertFalse(c.rtmLoggedIn)
    }

    @Test
    fun markRtcLeft_clearsRtcJoined() {
        val c = ConnectionSessionState()
        c.rtcJoined = true
        c.markRtcLeft()
        assertFalse(c.rtcJoined)
    }
}
