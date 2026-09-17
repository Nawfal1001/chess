package com.naoufel.decentralchess.chess

/** Deterministic chess core foundation. Board index: rank*8+file, rank 0 = rank 1. */
enum class Side { WHITE, BLACK }
enum class PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }
data class Piece(val side: Side, val type: PieceType)
data class Square(val file: Int, val rank: Int) { val index get() = rank * 8 + file }
data class Move(val from: Square, val to: Square, val promotion: PieceType? = null)

data class Position(val board: Array<Piece?>, val sideToMove: Side, val moveNumber: Int = 1) {
    fun pieceAt(s: Square): Piece? = board[s.index]
    fun apply(move: Move): Position {
        require(move.from.file in 0..7 && move.from.rank in 0..7)
        require(move.to.file in 0..7 && move.to.rank in 0..7)
        val next = board.copyOf()
        val piece = next[move.from.index] ?: error("No piece on source square")
        require(piece.side == sideToMove) { "Wrong side to move" }
        next[move.to.index] = if (move.promotion != null) Piece(piece.side, move.promotion) else piece
        next[move.from.index] = null
        return Position(next, if (sideToMove == Side.WHITE) Side.BLACK else Side.WHITE, if (sideToMove == Side.BLACK) moveNumber + 1 else moveNumber)
    }

    fun stableHash(): String {
        var h = 1125899906842597L
        for (p in board) h = 31 * h + when (p?.side) { Side.WHITE -> 1; Side.BLACK -> 2; null -> 0 }
        for (p in board) h = 31 * h + when (p?.type) { PieceType.KING -> 1; PieceType.QUEEN -> 2; PieceType.ROOK -> 3; PieceType.BISHOP -> 4; PieceType.KNIGHT -> 5; PieceType.PAWN -> 6; null -> 0 }
        h = 31 * h + sideToMove.ordinal
        h = 31 * h + moveNumber
        return h.toULong().toString(16)
    }

    companion object {
        fun initial(): Position {
            val b = arrayOfNulls<Piece>(64)
            fun put(rank: Int, file: Int, side: Side, type: PieceType) { b[rank * 8 + file] = Piece(side, type) }
            val back = listOf(PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN, PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK)
            for (f in 0..7) { put(0, f, Side.WHITE, back[f]); put(1, f, Side.WHITE, PieceType.PAWN); put(6, f, Side.BLACK, PieceType.PAWN); put(7, f, Side.BLACK, back[f]) }
            return Position(b, Side.WHITE)
        }
    }
}
