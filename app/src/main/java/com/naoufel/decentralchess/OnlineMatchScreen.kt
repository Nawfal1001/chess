package com.naoufel.decentralchess

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
import com.naoufel.decentralchess.p2p.AuthenticatedP2PSessionController
import com.naoufel.decentralchess.p2p.SessionPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnlineMatchScreen(
    controller: AuthenticatedP2PSessionController,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Square?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(controller) {
        while (true) {
            revision++
            delay(500)
        }
    }

    val snapshot = controller.snapshot()
    val history = controller.history()
    val position = history.current
    val localSide = snapshot.localSide
    val legalTargets = selected?.let { position.legalMoves(it).map { move -> move.to }.toSet() } ?: emptySet()
    val phase = controller.phase()
    revision

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Online Match", fontWeight = FontWeight.Bold)
                        Text("${snapshot.matchId.take(8)}… • ${phase.name}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("You: ${localSide.name}", fontWeight = FontWeight.Bold)
                Text("Move ${snapshot.moveCount}")
                Text(if (phase == SessionPhase.READY) "Connected" else phase.name)
            }

            Spacer(Modifier.height(10.dp))

            OnlineChessBoard(
                position = position,
                localSide = localSide,
                selected = selected,
                legalTargets = legalTargets
            ) { sq ->
                if (phase != SessionPhase.READY) return@OnlineChessBoard
                val current = selected
                if (current == null) {
                    if (position.pieceAt(sq)?.side == localSide && position.sideToMove == localSide) {
                        selected = sq
                    }
                } else {
                    val move = position.legalMoves(current).firstOrNull { it.to == sq }
                    if (move != null) {
                        scope.launch {
                            runCatching {
                                controller.sendMove(move)
                            }.onSuccess {
                                selected = null
                                errorText = null
                            }.onFailure {
                                errorText = it.message ?: "Move rejected"
                            }
                        }
                    } else if (position.pieceAt(sq)?.side == localSide && position.sideToMove == localSide) {
                        selected = sq
                    } else {
                        selected = null
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                if (position.sideToMove == localSide) "Your turn" else "Opponent's turn",
                fontWeight = FontWeight.Bold
            )
            Text("Game hash: ${history.gameHash()}", fontSize = 9.sp)
            errorText?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            if (phase == SessionPhase.DISCONNECTED || phase == SessionPhase.RECOVERING) {
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Connection interrupted — state recovery is available.",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineChessBoard(
    position: Position,
    localSide: Side,
    selected: Square?,
    legalTargets: Set<Square>,
    onSquare: (Square) -> Unit
) {
    val ranks = if (localSide == Side.WHITE) 7 downTo 0 else 0..7
    val files = if (localSide == Side.WHITE) 0..7 else 7 downTo 0

    Column(Modifier.fillMaxWidth().aspectRatio(1f)) {
        for (rank in ranks) {
            Row(Modifier.weight(1f)) {
                for (file in files) {
                    val sq = Square(file, rank)
                    val dark = (file + rank) % 2 == 1
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .background(if (dark) Color(0xFF769656) else Color(0xFFEEEED2))
                            .clickable { onSquare(sq) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected == sq) {
                            Box(Modifier.fillMaxSize().background(Color(0x6688AAFF)))
                        }
                        if (sq in legalTargets) {
                            Box(Modifier.size(10.dp).background(Color(0x99808080)))
                        }
                        position.pieceAt(sq)?.let {
                            Text(
                                onlineGlyph(it),
                                fontSize = 34.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun onlineGlyph(piece: Piece): String = when (piece.type) {
    PieceType.KING -> if (piece.side == Side.WHITE) "♔" else "♚"
    PieceType.QUEEN -> if (piece.side == Side.WHITE) "♕" else "♛"
    PieceType.ROOK -> if (piece.side == Side.WHITE) "♖" else "♜"
    PieceType.BISHOP -> if (piece.side == Side.WHITE) "♗" else "♝"
    PieceType.KNIGHT -> if (piece.side == Side.WHITE) "♘" else "♞"
    PieceType.PAWN -> if (piece.side == Side.WHITE) "♙" else "♟"
}
