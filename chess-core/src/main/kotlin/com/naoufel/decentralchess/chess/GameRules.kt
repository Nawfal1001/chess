package com.naoufel.decentralchess.chess

import java.security.MessageDigest

enum class GameStatus { ONGOING, CHECK, CHECKMATE, STALEMATE, DRAW_REPETITION, DRAW_FIFTY_MOVE, DRAW_INSUFFICIENT_MATERIAL }

data class HashedMove(val move: Move, val positionHash: String)

class GameHistory(initial: Position = Position.initial()) {
    private val initialPosition = initial
    private val positions = mutableListOf(initial)
    private val moves = mutableListOf<HashedMove>()

    val current: Position get() = positions.last()
    val moveHistory: List<HashedMove> get() = moves.toList()

    fun initialPosition(): Position = initialPosition

    fun play(move: Move): Position {
        val next = current.apply(move)
        moves += HashedMove(move, next.stableHash())
        positions += next
        return next
    }

    fun undo(): Position {
        require(positions.size > 1) { "Cannot undo the initial position" }
        positions.removeAt(positions.lastIndex)
        moves.removeAt(moves.lastIndex)
        return current
    }

    fun repetitionCount(): Int {
        val key = current.repetitionKey()
        return positions.count { it.repetitionKey() == key }
    }

    fun status(): GameStatus = current.gameStatus(repetitionCount())

    fun gameHash(): String {
        var hash = sha256(initialPosition.stableHash())
        for (entry in moves) hash = sha256("$hash:${moveKey(entry.move)}:${entry.positionHash}")
        return hash
    }

    private fun moveKey(m: Move): String = "${m.from.file},${m.from.rank}-${m.to.file},${m.to.rank}-${m.promotion?.name ?: "-"}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun Position.repetitionKey(): String {
    val fields = toFen().split(' ')
    val epField = if (enPassant != null && legalMoves().any { it.to == enPassant && pieceAt(it.from)?.type == PieceType.PAWN }) fields[3] else "-"
    return "${fields[0]} ${fields[1]} ${fields[2]} $epField"
}

fun Position.isInsufficientMaterial(): Boolean {
    val pieces = board.filterNotNull()
    if (pieces.any { it.type == PieceType.PAWN || it.type == PieceType.ROOK || it.type == PieceType.QUEEN }) return false
    val nonKings = pieces.filter { it.type != PieceType.KING }
    if (nonKings.isEmpty()) return true
    if (nonKings.size == 1 && nonKings[0].type in setOf(PieceType.BISHOP, PieceType.KNIGHT)) return true
    if (nonKings.all { it.type == PieceType.BISHOP }) {
        val bishops = board.indices.filter { board[it]?.type == PieceType.BISHOP }
            .map { Square(it % 8, it / 8) }
        return bishops.map { (it.file + it.rank) % 2 }.distinct().size <= 1
    }
    return false
}

fun Position.gameStatus(repetitionCount: Int = 1): GameStatus {
    val legal = legalMoves()
    if (legal.isEmpty()) return if (inCheck(sideToMove)) GameStatus.CHECKMATE else GameStatus.STALEMATE
    if (isInsufficientMaterial()) return GameStatus.DRAW_INSUFFICIENT_MATERIAL
    if (halfmoveClock >= 100) return GameStatus.DRAW_FIFTY_MOVE
    if (repetitionCount >= 3) return GameStatus.DRAW_REPETITION
    return if (inCheck(sideToMove)) GameStatus.CHECK else GameStatus.ONGOING
}
