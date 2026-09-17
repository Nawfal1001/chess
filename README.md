# Decentral Chess

Initial foundation for a local-first, decentralized chess ecosystem.

## Implemented in v0.1 scaffold
- Modular Android architecture
- Deterministic chess position model
- Initial board and legal-side move boundary
- Position hashing foundation
- AI abstraction (engine / prediction / coach)
- P2P transport abstraction
- Identity/security abstractions
- Federated-training abstraction

## Next build milestone
Implement the full legal move generator, FEN/PGN, game history/hash-chain, Android Keystore identity, then integrate Stockfish.

## Architecture principle
The chess core must not depend on a central server. P2P, ledger, wallet, AI training, tournaments and optional infrastructure are layered around it.
