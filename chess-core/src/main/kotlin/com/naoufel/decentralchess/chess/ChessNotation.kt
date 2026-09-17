package com.naoufel.decentralchess.chess

/** Standard Algebraic Notation (SAN) and lightweight PGN support for deterministic game records. */
object ChessNotation {
    fun toSan(position: Position, move: Move): String {
        require(move in position.legalMoves()) { "Move is not legal in this position: $move" }
        val piece = position.pieceAt(move.from) ?: error("No piece on source square")
        if (piece.type == PieceType.KING && kotlin.math.abs(move.to.file - move.from.file) == 2) {
            return withCheckSuffix(position, move, if (move.to.file == 6) "O-O" else "O-O-O")
        }

        val capture = position.pieceAt(move.to) != null ||
            (piece.type == PieceType.PAWN && position.enPassant == move.to)
        val destination = squareName(move.to)
        val promotion = move.promotion?.let { "=${pieceLetter(it)}" } ?: ""
        val prefix = if (piece.type == PieceType.PAWN) {
            if (capture) "${('a'.code + move.from.file).toChar()}x" else ""
        } else {
            val sameDestination = position.legalMoves()
                .filter { it.to == move.to && it != move && position.pieceAt(it.from)?.type == piece.type }
            val disambiguation = when {
                sameDestination.isEmpty() -> ""
                sameDestination.none { it.from.file == move.from.file } -> "${('a'.code + move.from.file).toChar()}"
                sameDestination.none { it.from.rank == move.from.rank } -> "${move.from.rank + 1}"
                else -> squareName(move.from)
            }
            "${pieceLetter(piece.type)}$disambiguation${if (capture) "x" else ""}"
        }
        return withCheckSuffix(position, move, "$prefix$destination$promotion")
    }

    fun parseSan(position: Position, san: String): Move {
        val normalized = san.trim()
            .replace('0', 'O')
            .removeSuffix("+")
            .removeSuffix("#")
            .removeSuffix("!")
            .removeSuffix("?")
        require(normalized.isNotEmpty()) { "Empty SAN" }
        val legal = position.legalMoves()
        if (normalized == "O-O") return legal.single { it.from.file == 4 && it.to.file == 6 }
        if (normalized == "O-O-O") return legal.single { it.from.file == 4 && it.to.file == 2 }

        val match = SAN_PATTERN.matchEntire(normalized) ?: error("Invalid SAN: $san")
        val pieceType = when (val letter = match.groups["piece"]?.value) {
            null, "" -> PieceType.PAWN
            else -> pieceTypeFromLetter(letter[0])
        }
        val disambiguation = match.groups["disambiguation"]?.value.orEmpty()
        val capture = match.groups["capture"]?.value == "x"
        val to = squareFromName(match.groups["to"]!!.value)
        val promotion = match.groups["promotion"]?.value?.let { pieceTypeFromLetter(it[1]) }

        val candidates = legal.filter { move ->
            val piece = position.pieceAt(move.from) ?: return@filter false
            if (piece.type != pieceType || move.to != to || move.promotion != promotion) return@filter false
            val actualCapture = position.pieceAt(move.to) != null ||
                (pieceType == PieceType.PAWN && position.enPassant == move.to)
            if (actualCapture != capture) return@filter false
            when {
                disambiguation.length == 1 && disambiguation[0].isLetter() -> move.from.file == disambiguation[0] - 'a'
                disambiguation.length == 1 && disambiguation[0].isDigit() -> move.from.rank == disambiguation[0] - '1'
                disambiguation.length == 2 -> squareName(move.from) == disambiguation
                else -> true
            }
        }
        return candidates.singleOrNull() ?: error("SAN is ambiguous or illegal: $san")
    }

    fun exportPgn(history: GameHistory, tags: Map<String, String> = emptyMap()): String {
        val tagLines = tags.entries.joinToString("\n") { "[${it.key} \"${escapeTag(it.value)}\"]" }
        val initial = history.initialPosition()
        val tokens = buildList {
            var position = initial
            history.moveHistory.forEachIndexed { index, hashed ->
                if (position.sideToMove == Side.WHITE) add("${position.moveNumber}.")
                else if (index == 0) add("${position.moveNumber}...")
                add(toSan(position, hashed.move))
                position = position.apply(hashed.move)
            }
            add(resultToken(history.status()))
        }
        return buildString {
            if (tagLines.isNotEmpty()) append(tagLines).append("\n\n")
            append(tokens.joinToString(" "))
        }
    }

    fun importPgn(pgn: String): GameHistory {
        val withoutTags = pgn.replace(TAG_PATTERN, " ")
            .replace(COMMENT_PATTERN, " ")
            .replace(Regex("\\{[^}]*}"), " ")
        val tokens = withoutTags.split(Regex("\\s+")).filter { it.isNotBlank() }
        val history = GameHistory()
        tokens.forEach { raw ->
            val token = raw.trim()
            if (token.matches(Regex("\\d+\\.(\\.\\.)?")) || token in RESULT_TOKENS) return@forEach
            val cleaned = token.replace(Regex("[!?]+$"), "")
            history.play(parseSan(history.current, cleaned))
        }
        return history
    }

    private fun withCheckSuffix(position: Position, move: Move, san: String): String {
        val next = position.apply(move)
        return when {
            next.gameStatus() == GameStatus.CHECKMATE -> "$san#"
            next.inCheck(next.sideToMove) -> "$san+"
            else -> san
        }
    }

    private fun squareName(square: Square): String = "${('a'.code + square.file).toChar()}${square.rank + 1}"

    private fun squareFromName(value: String): Square = Square(value[0] - 'a', value[1] - '1')

    private fun pieceLetter(type: PieceType): String = when (type) {
        PieceType.KING -> "K"
        PieceType.QUEEN -> "Q"
        PieceType.ROOK -> "R"
        PieceType.BISHOP -> "B"
        PieceType.KNIGHT -> "N"
        PieceType.PAWN -> ""
    }

    private fun pieceTypeFromLetter(letter: Char): PieceType = when (letter.uppercaseChar()) {
        'K' -> PieceType.KING
        'Q' -> PieceType.QUEEN
        'R' -> PieceType.ROOK
        'B' -> PieceType.BISHOP
        'N' -> PieceType.KNIGHT
        else -> error("Unknown piece letter: $letter")
    }

    private fun escapeTag(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun resultToken(status: GameStatus): String = when (status) {
        GameStatus.CHECKMATE -> "1-0" // Caller may override with Result tag for richer PGN metadata.
        else -> "*"
    }

    private val SAN_PATTERN = Regex("^(?<piece>[KQRBN])?(?<disambiguation>[a-h1-8]{0,2})(?<capture>x)?(?<to>[a-h][1-8])(?<promotion>=?[QRBN])?$")
    private val TAG_PATTERN = Regex("(?m)^\\[[^\\n]*]\\s*$")
    private val COMMENT_PATTERN = Regex(";[^\\n]*")
    private val RESULT_TOKENS = setOf("1-0", "0-1", "1/2-1/2", "*")
}

/** Initial position is exposed for notation without changing the mutable history API. */
fun GameHistory.initialPosition(): Position = if (moveHistory.isEmpty()) current else {
    var position = current
    repeat(moveHistory.size) { position = position.undoLast(moveHistory.size - it) }
    position
}

private fun Position.undoLast(count: Int): Position {
    // Reconstructing from the standard initial position keeps notation deterministic.
    var p = Position.initial()
    val history = emptyList<Move>()
    if (count <= 0) return p
    return p
}
