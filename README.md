# Decentral Chess

Initial foundation for a local-first, decentralized chess ecosystem.

## v0.5 community foundation
- Global/local channel, DM, game and tournament domain models
- Peer profiles, challenges and moderation state
- Persistent chat-history interface for the Android storage layer
- Existing authenticated P2P envelope remains the transport/security boundary

## v0.3 implemented
- Deterministic legal move generation
- Check and king-safety validation
- Checkmate and stalemate detection
- Castling with attack-path validation
- En passant
- Promotion (Q/R/B/N)
- FEN import/export
- Halfmove clock and castling/en-passant state
- SHA-256 position hashing
- Game history with undo
- Cryptographic game hash chain
- Repetition-key and draw-rule foundations
- Insufficient-material detection
- SAN generation and parsing
- PGN export/import with replay
- SAN/PGN round-trip tests
- Android UI consumes legal moves instead of applying arbitrary board moves
- Android Keystore EC identity with SHA-256/ECDSA signatures
- Signed game records bind identity, FENs, PGN and game hash
- Signed-record verification replays PGN and checks the deterministic hash
- GitHub Actions build/test workflow

## Architecture

```text
CHESS PLATFORM
├── chess-core     deterministic rules, notation, state, history
├── identity       Android Keystore cryptographic player identity
├── security       signed game records and anti-tamper boundary
├── p2p            transport-independent peer networking
├── ai             engine / prediction / coach interfaces
├── training       federated-training boundary
└── app             Android UI
```

The chess core has no dependency on a central server. P2P, identity, wallets, ledger, tournaments, anti-cheat and distributed AI training can be layered around the deterministic core later.

## Current milestone
The game can now move from deterministic chess state → SAN/PGN → replay → hash-chain verification → device-backed signature. This is the foundation required before building a decentralized match protocol.

## Next milestone
1. Add persistent local game storage and a game-record repository.
2. Add two-player signed records and peer/session message envelopes.
3. Integrate Stockfish behind the existing `ChessEngine` interface.
4. Build richer board UX: clocks, move list, animations, themes, sounds, promotion picker and analysis mode.
5. Start encrypted P2P session transport and reconnect/state synchronization.
6. Add anti-cheat telemetry around verified game records before introducing competitive rankings.

## Verification note
The repository contains CI configuration, but the GitHub connector currently reports no workflow run/status for the latest commits. Therefore no green CI result or APK build is being claimed yet.
