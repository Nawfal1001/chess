package com.naoufel.decentralchess.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MatchLifecycleTest {
    @Test
    fun lifecycle_tracksReconnectPath() {
        var now = 100L
        val tracker = MatchLifecycleTracker(clockMs = { now })
        tracker.transition(MatchLifecycleState.ACCEPTED)
        now += 10
        tracker.transition(MatchLifecycleState.HANDSHAKING)
        now += 10
        tracker.transition(MatchLifecycleState.READY)
        tracker.transition(MatchLifecycleState.PLAYING)
        tracker.transition(MatchLifecycleState.DISCONNECTED)
        tracker.transition(MatchLifecycleState.RECOVERING)
        tracker.transition(MatchLifecycleState.READY)

        assertEquals(MatchLifecycleState.READY, tracker.state())
        assertEquals(7, tracker.events().size)
        assertEquals(120L, tracker.events().last().atMs)
    }

    @Test
    fun lifecycle_rejectsSkippingHandshake() {
        val tracker = MatchLifecycleTracker()
        assertFailsWith<IllegalArgumentException> {
            tracker.transition(MatchLifecycleState.READY)
        }
    }
}
