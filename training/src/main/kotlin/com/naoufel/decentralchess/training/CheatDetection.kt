package com.naoufel.decentralchess.training

import kotlin.math.max
import kotlin.math.min

/**
 * Signals are evidence for review, not proof of cheating.
 *
 * The analyzer combines independent signals instead of declaring a player
 * guilty from engine agreement alone.
 */
data class AnalyzedMove(
    val move: String,
    val engineBestMove: String?,
    val centipawnLoss: Int?,
    val thinkTimeMs: Long
) {
    init {
        require(move.isNotBlank())
        require(centipawnLoss == null || centipawnLoss >= 0)
        require(thinkTimeMs >= 0)
    }
}

data class CheatSignal(
    val type: Type,
    val strength: Double,
    val evidence: String
) {
    enum class Type {
        ENGINE_CORRELATION,
        LOW_CENTIPAWN_LOSS,
        TIMING_ANOMALY,
        PATTERN_CONCENTRATION,
        INSUFFICIENT_DATA
    }

    init { require(strength in 0.0..1.0) }
}

data class CheatAssessment(
    val sampleSize: Int,
    val suspicionScore: Double,
    val signals: List<CheatSignal>,
    val reviewRecommended: Boolean
) {
    init {
        require(sampleSize >= 0)
        require(suspicionScore in 0.0..1.0)
    }
}

class CheatDetectionAnalyzer(private val minimumSample: Int = 12) {
    init { require(minimumSample >= 4) }

    fun assess(moves: List<AnalyzedMove>): CheatAssessment {
        if (moves.size < minimumSample) {
            return CheatAssessment(
                moves.size, 0.0,
                listOf(CheatSignal(CheatSignal.Type.INSUFFICIENT_DATA, 0.0,
                    "At least $minimumSample analyzed moves are needed for a meaningful assessment.")),
                false
            )
        }

        val engineMoves = moves.count { it.engineBestMove != null || it.engineRank != null }
        val exactMatches = moves.count {
            it.engineBestMove != null && it.move.equals(it.engineBestMove, ignoreCase = true)
        }
        val topThreeMatches = moves.count { it.engineRank != null && it.engineRank <= 3 }
        val engineCorrelation = if (engineMoves == 0) 0.0 else {
            val exactRatio = exactMatches.toDouble() / engineMoves
            val topThreeRatio = topThreeMatches.toDouble() / engineMoves
            0.70 * exactRatio + 0.30 * topThreeRatio
        }

        val losses = moves.mapNotNull { it.centipawnLoss }
        val lowLossRatio = if (losses.isEmpty()) 0.0 else
            losses.count { it <= 20 }.toDouble() / losses.size

        val timed = moves.map { it.thinkTimeMs.toDouble() }
        val meanTime = timed.average()
        val nearInstantRatio = if (meanTime <= 1.0) 0.0 else
            timed.count { it <= max(500.0, meanTime * 0.15) }.toDouble() / timed.size

        val baselineComparisons = moves.mapNotNull { move ->
            move.baselineThinkTimeMs?.takeIf { it > 0 }?.let { baseline ->
                (move.thinkTimeMs.toDouble() / baseline).coerceAtMost(10.0)
            }
        }
        val baselineAnomaly = if (baselineComparisons.isEmpty()) 0.0 else
            baselineComparisons.count { it < 0.20 }.toDouble() / baselineComparisons.size

        val perfectStreak = longestPerfectStreak(moves)
        val concentratedPerfectStreak = perfectStreak / moves.size.toDouble()
        val difficultMoves = moves.filter { it.positionDifficulty >= 0.70 }
        val difficultEngineMatches = difficultMoves.count {
            it.engineBestMove != null && it.move.equals(it.engineBestMove, ignoreCase = true)
        }
        val difficultCorrelation = if (difficultMoves.isEmpty()) 0.0
            else difficultEngineMatches.toDouble() / difficultMoves.size

        val score = min(1.0,
            0.35 * engineCorrelation +
                0.25 * lowLossRatio +
                0.12 * nearInstantRatio +
                0.08 * baselineAnomaly +
                0.10 * difficultCorrelation +
                0.10 * concentratedPerfectStreak
        )

        val signals = buildList {
            if (engineMoves > 0) add(CheatSignal(
                CheatSignal.Type.ENGINE_CORRELATION, engineCorrelation,
                "$exactMatches/$engineMoves moves matched the supplied engine principal move; top-3 evidence is included when available."
            ))
            if (losses.isNotEmpty()) add(CheatSignal(
                CheatSignal.Type.LOW_CENTIPAWN_LOSS, lowLossRatio,
                "${losses.count { it <= 20 }}/${losses.size} analyzed moves were within 20 cp of the reference."
            ))
            add(CheatSignal(
                CheatSignal.Type.TIMING_ANOMALY, nearInstantRatio,
                "${(nearInstantRatio * 100).toInt()}% of moves were near-instant relative to the player mean."
            ))
            if (baselineComparisons.isNotEmpty()) add(CheatSignal(
                CheatSignal.Type.TIMING_ANOMALY, baselineAnomaly,
                "${(baselineAnomaly * 100).toInt()}% of moves were under 20% of the player's baseline think time."
            ))
            add(CheatSignal(
                CheatSignal.Type.PATTERN_CONCENTRATION, difficultCorrelation,
                "High-difficulty engine agreement: ${(difficultCorrelation * 100).toInt()}%. Longest exact-engine streak: $perfectStreak moves."
            ))
        }

        return CheatAssessment(
            moves.size, score, signals,
            score >= 0.72 && engineMoves >= minimumSample / 2
        )
    }

    private fun longestPerfectStreak(moves: List<AnalyzedMove>): Int {
        var current = 0
        var longest = 0
        for (move in moves) {
            if (move.engineBestMove != null && move.move.equals(move.engineBestMove, ignoreCase = true)) {
                current++
                longest = max(longest, current)
            } else current = 0
        }
        return longest
    }
}