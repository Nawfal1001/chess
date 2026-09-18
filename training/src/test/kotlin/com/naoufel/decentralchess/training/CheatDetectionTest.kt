package com.naoufel.decentralchess.training

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatDetectionTest {
    @Test
    fun insufficientGamesAreNotFlagged() {
        val result = CheatDetectionAnalyzer().assess(listOf(
            AnalyzedMove("e2e4", "e2e4", 0, 1000)
        ))
        assertFalse(result.reviewRecommended)
        assertTrue(result.signals.any { it.type == CheatSignal.Type.INSUFFICIENT_DATA })
    }

    @Test
    fun strongIndependentSignalsRecommendReview() {
        val moves = (1..16).map {
            AnalyzedMove("e2e4", "e2e4", 0, 250)
        }
        val result = CheatDetectionAnalyzer().assess(moves)
        assertTrue(result.suspicionScore > 0.72)
        assertTrue(result.reviewRecommended)
    }

    @Test
    fun mixedHumanLikeSampleDoesNotAutomaticallyFlag() {
        val moves = (1..16).map { index ->
            if (index % 3 == 0) AnalyzedMove("e2e4", "e2e4", 10, 4000)
            else AnalyzedMove("g1f3", "d2d4", 80, 2200)
        }
        val result = CheatDetectionAnalyzer().assess(moves)
        assertFalse(result.reviewRecommended)
    }


    @Test
    fun rankAndDifficultyEvidence_isIncluded() {
        val moves = (1..12).map {
            AnalyzedMove(
                move = "e4",
                engineBestMove = "e4",
                centipawnLoss = 0,
                thinkTimeMs = 1000,
                engineRank = 1,
                positionDifficulty = 0.9,
                baselineThinkTimeMs = 10000
            )
        }
        val assessment = CheatDetectionAnalyzer().assess(moves)
        kotlin.test.assertTrue(assessment.signals.any { it.type == CheatSignal.Type.PATTERN_CONCENTRATION })
        kotlin.test.assertTrue(assessment.signals.any { it.type == CheatSignal.Type.TIMING_ANOMALY })
    }
}