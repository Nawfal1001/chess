package com.naoufel.decentralchess.p2p

/**
 * Local lifecycle model for a chess match. Network authentication remains owned by
 * AuthenticatedP2PSessionController; this tracker is only the app-facing state machine.
 */
enum class MatchLifecycleState {
    INVITED,
    ACCEPTED,
    HANDSHAKING,
    READY,
    PLAYING,
    DISCONNECTED,
    RECOVERING,
    FINISHED,
    ABORTED
}

data class MatchLifecycleEvent(
    val state: MatchLifecycleState,
    val atMs: Long,
    val reason: String? = null
)

class MatchLifecycleTracker(
    initialState: MatchLifecycleState = MatchLifecycleState.INVITED,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private var current = initialState
    private val events = mutableListOf(MatchLifecycleEvent(initialState, clockMs()))

    fun state(): MatchLifecycleState = current
    fun events(): List<MatchLifecycleEvent> = events.toList()

    fun transition(next: MatchLifecycleState, reason: String? = null) {
        require(isAllowed(current, next)) {
            "Invalid match lifecycle transition: $current -> $next"
        }
        current = next
        events += MatchLifecycleEvent(next, clockMs(), reason)
    }

    private fun isAllowed(from: MatchLifecycleState, to: MatchLifecycleState): Boolean = when (from) {
        MatchLifecycleState.INVITED -> to == MatchLifecycleState.ACCEPTED || to == MatchLifecycleState.ABORTED
        MatchLifecycleState.ACCEPTED -> to == MatchLifecycleState.HANDSHAKING || to == MatchLifecycleState.ABORTED
        MatchLifecycleState.HANDSHAKING -> to == MatchLifecycleState.READY || to == MatchLifecycleState.DISCONNECTED || to == MatchLifecycleState.ABORTED
        MatchLifecycleState.READY -> to == MatchLifecycleState.PLAYING || to == MatchLifecycleState.DISCONNECTED || to == MatchLifecycleState.ABORTED
        MatchLifecycleState.PLAYING -> to == MatchLifecycleState.DISCONNECTED || to == MatchLifecycleState.FINISHED || to == MatchLifecycleState.ABORTED
        MatchLifecycleState.DISCONNECTED -> to == MatchLifecycleState.RECOVERING || to == MatchLifecycleState.ABORTED
        MatchLifecycleState.RECOVERING -> to == MatchLifecycleState.READY || to == MatchLifecycleState.PLAYING || to == MatchLifecycleState.DISCONNECTED || to == MatchLifecycleState.ABORTED
        MatchLifecycleState.FINISHED, MatchLifecycleState.ABORTED -> false
    }
}
