# Decentral Chess

Initial foundation for a local-first, decentralized chess ecosystem.

## v0.2 implemented
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
- Unit tests for core rules
- Android UI now consumes legal moves instead of applying arbitrary board moves
- GitHub Actions build/test workflow

## Architecture

```text
CHESS PLATFORM
├── chess-core     deterministic rules, state, history, verification
├── identity       cryptographic player identity boundary
├── security       signing / anti-tamper boundary
├── p2p            transport-independent peer networking
├── ai             engine / prediction / coach interfaces
├── training       federated-training boundary
└── app             Android UI
```

The chess core has no dependency on a central server. P2P, identity, wallets, ledger, tournaments, anti-cheat and distributed AI training can be layered around the deterministic core later.

## Next milestone
1. Complete PGN/SAN generation and replay serialization.
2. Add persistent local game storage.
3. Add Android Keystore-backed identity and signed game records.
4. Integrate Stockfish behind the existing `ChessEngine` interface.
5. Build the richer board UX: clocks, move list, animations, themes, sounds and analysis mode.
6. Start the P2P session protocol on top of signed deterministic game records.

## Verification note
The repository contains CI configuration, but CI execution must be observed before claiming a build is green. Local environment limitations may prevent an APK build from being run directly.
