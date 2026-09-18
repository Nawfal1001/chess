package com.naoufel.decentralchess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naoufel.decentralchess.chess.*
import com.naoufel.decentralchess.identity.AndroidKeystoreIdentityProvider
import com.naoufel.decentralchess.storage.GameRecordRepository
import com.naoufel.decentralchess.storage.CommunityRepository
import com.naoufel.decentralchess.storage.SqliteCommunityRepository
import com.naoufel.decentralchess.storage.SqliteGameRecordRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DecentralChessApp(SqliteGameRecordRepository(applicationContext), SqliteCommunityRepository(applicationContext)) }
    }
}

@Composable
fun DecentralChessApp(repository: GameRecordRepository, communityRepository: CommunityRepository) {
    var showCommunity by remember { mutableStateOf(false) }
    val identity = remember { AndroidKeystoreIdentityProvider().identity() }
    val communityRuntime = remember(identity.id) { CommunityRuntime(communityRepository, identity) }
    if (showCommunity) {
        CommunityScreen(
            repository = communityRepository,
            identity = identity,
            runtime = communityRuntime,
            onBack = { showCommunity = false }
        )
        return
    }
    val restored = remember {
        repository.list(1).firstOrNull()?.let { stored ->
            runCatching {
                val history = stored.replay()
                require(history.initialPosition().toFen() == stored.initialFen)
                require(history.current.toFen() == stored.finalFen)
                require(history.gameHash() == stored.gameHash)
                stored.id to history
            }.getOrNull()
        }
    }
    var gameId by remember { mutableStateOf(restored?.first) }
    val history = remember { restored?.second ?: GameHistory() }
    var selected by remember { mutableStateOf<Square?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val position = history.current
    val legalTargets = selected?.let { from -> position.legalMoves(from).map { it.to }.toSet() } ?: emptySet()
    val status = position.gameStatus()
    revision // Keep Compose subscribed to history mutations.

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text("DECENTRAL CHESS", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Foundation v0.4 • persistent local game records", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (status == GameStatus.CHECK || status == GameStatus.ONGOING) "${position.sideToMove} TO MOVE"
                    else status.name.replace('_', ' '),
                    fontWeight = FontWeight.Bold
                )
                Text(if (gameId == null) "Unsaved game" else "Local game: ${gameId!!.take(8)}…", fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                ChessBoard(position, selected, legalTargets) { sq ->
                    val current = selected
                    if (current == null) {
                        if (position.pieceAt(sq)?.side == position.sideToMove) selected = sq
                    } else {
                        val candidate = position.legalMoves(current).firstOrNull { it.to == sq }
                        if (candidate != null) {
                            history.play(candidate)
                            val saved = repository.save(history, gameId)
                            gameId = saved.id
                            selected = null
                            revision++
                        } else if (position.pieceAt(sq)?.side == position.sideToMove) {
                            selected = sq
                        } else {
                            selected = null
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("FEN: ${position.toFen()}", fontSize = 10.sp)
                Text("SHA-256: ${position.stableHash()}", fontSize = 10.sp)
                Text("Game hash: ${history.gameHash()}", fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { showCommunity = true }) { Text("Community") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    gameId = null
                    history.undoAll()
                    selected = null
                    revision++
                }) { Text("New Game") }
            }
        }
    }
}

private fun GameHistory.undoAll() {
    while (moveHistory.isNotEmpty()) undo()
}

private fun glyph(piece: Piece): String = when(piece.type) {
    PieceType.KING -> if (piece.side == Side.WHITE) "♔" else "♚"
    PieceType.QUEEN -> if (piece.side == Side.WHITE) "♕" else "♛"
    PieceType.ROOK -> if (piece.side == Side.WHITE) "♖" else "♜"
    PieceType.BISHOP -> if (piece.side == Side.WHITE) "♗" else "♝"
    PieceType.KNIGHT -> if (piece.side == Side.WHITE) "♘" else "♞"
    PieceType.PAWN -> if (piece.side == Side.WHITE) "♙" else "♟"
}

@Composable
private fun ChessBoard(position: Position, selected: Square?, legalTargets: Set<Square>, onSquare: (Square) -> Unit) {
    Column(Modifier.fillMaxWidth().aspectRatio(1f)) {
        for (rank in 7 downTo 0) Row(Modifier.weight(1f)) {
            for (file in 0..7) {
                val sq = Square(file, rank)
                val dark = (file + rank) % 2 == 1
                val selectedHere = selected == sq
                val targetHere = sq in legalTargets
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(if (dark) Color(0xFF769656) else Color(0xFFEEEED2))
                        .clickable { onSquare(sq) },
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedHere) Box(Modifier.fillMaxSize().background(Color(0x6688AAFF)))
                    if (targetHere) Box(Modifier.size(10.dp).background(Color(0x99808080)))
                    position.pieceAt(sq)?.let { Text(glyph(it), fontSize = 34.sp) }
                }
            }
        }
    }
}
