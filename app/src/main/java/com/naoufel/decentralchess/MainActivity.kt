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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { DecentralChessApp() } }
}

@Composable
fun DecentralChessApp() {
    var position by remember { mutableStateOf(Position.initial()) }
    var selected by remember { mutableStateOf<Square?>(null) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text("DECENTRAL CHESS", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Foundation v0.1 • local-first architecture", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Text("${position.sideToMove} TO MOVE", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                ChessBoard(position, selected) { sq ->
                    val current = selected
                    if (current == null && position.pieceAt(sq)?.side == position.sideToMove) selected = sq
                    else if (current != null) {
                        runCatching { position = position.apply(Move(current, sq)) }
                        selected = null
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("State hash: ${position.stableHash()}", fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { position = Position.initial(); selected = null }) { Text("New Game") }
            }
        }
    }
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
private fun ChessBoard(position: Position, selected: Square?, onSquare: (Square) -> Unit) {
    Column(Modifier.fillMaxWidth().aspectRatio(1f)) {
        for (rank in 7 downTo 0) Row(Modifier.weight(1f)) {
            for (file in 0..7) {
                val sq = Square(file, rank); val dark = (file + rank) % 2 == 1
                Box(Modifier.weight(1f).fillMaxHeight().background(if (dark) Color(0xFF769656) else Color(0xFFEEEED2)).clickable { onSquare(sq) }, contentAlignment = Alignment.Center) {
                    if (selected == sq) Box(Modifier.fillMaxSize().background(Color(0x6688AAFF)))
                    position.pieceAt(sq)?.let { Text(glyph(it), fontSize = 34.sp) }
                }
            }
        }
    }
}
