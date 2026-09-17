package com.naoufel.decentralchess.ai

interface ChessEngine { suspend fun analyze(fen: String, depth: Int): EngineAnalysis }
data class EngineAnalysis(val evaluationCp: Int, val bestMove: String?, val principalVariation: List<String>)
interface PredictionModel { suspend fun predict(fen: String): WinPrediction }
data class WinPrediction(val white: Double, val draw: Double, val black: Double)
interface CoachModel { suspend fun explain(analysis: EngineAnalysis, language: String = "en"): String }
