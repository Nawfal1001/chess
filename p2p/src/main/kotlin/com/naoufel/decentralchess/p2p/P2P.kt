package com.naoufel.decentralchess.p2p

/** Transport-independent P2P boundary. Direct WebRTC/QUIC and encrypted relay can implement this. */
data class PeerId(val value: String)
interface PeerTransport { suspend fun connect(peer: PeerId); suspend fun send(peer: PeerId, payload: ByteArray); suspend fun close(peer: PeerId) }
