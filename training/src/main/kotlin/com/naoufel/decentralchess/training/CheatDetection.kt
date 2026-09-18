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

        val engineMoves = moves.count { it.engineBestMove != null }
        val exactMatches = moves.count {
            it.engineBestMove != null && it.move.equals(it.engineBestMove, ignoreCase = true)
        }
        val engineCorrelation = if (engineMoves == 0) 0.0 else exactMatches.toDouble() / engineMoves

        val losses = moves.mapNotNull { it.centipawnLoss }
        val lowLossRatio = if (losses.isEmpty()) 0.0 else
            losses.count { it <= 20 }.toDouble() / losses.size

        val timed = moves.map { it.thinkTimeMs.toDouble() }
        val meanTime = timed.average()
        val nearInstantRatio = if (meanTime <= 1.0) 0.0 else
            timed.count { it <= max(500.0, meanTime * 0.15) }.toDouble() / timed.size

        val perfectStreak = longestPerfectStreak(moves)
        val concentratedPerfectStreak = perfectStreak / moves.size.toDouble()

        val score = min(1.0,
            0.45 * engineCorrelation +
                0.30 * lowLossRatio +
                0.15 * nearInstantRatio +
                0.10 * concentratedPerfectStreak
        )

        val signals = buildList {
            if (engineMoves > 0) add(CheatSignal(
                CheatSignal.Type.ENGINE_CORRELATION, engineCorrelation,
                "$exactMatches/$engineMoves moves exactly matched the supplied engine principal move."
            ))
            if (losses.isNotEmpty()) add(CheatSignal(
                CheatSignal.Type.LOW_CENTIPAWN_LOSS, lowLossRatio,
                "${losses.count { it <= 20 }}/${losses.size} analyzed moves were within 20 cp of the reference."
            ))
            add(CheatSignal(
                CheatSignal.Type.TIMING_ANOMALY, nearInstantRatio,
                "${(nearInstantRatio * 100).toInt()}% of moves were near-instant relative to the player mean."
            ))
            add(CheatSignal(
                CheatSignal.Type.PATTERN_CONCENTRATION, concentratedPerfectStreak,
                "Longest exact-engine streak: $perfectStreak moves."
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