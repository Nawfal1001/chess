package com.naoufel.decentralchess.training

/** Federated-learning boundary. Raw player data should remain local by default. */
data class TrainingTask(val modelVersion: String, val shardId: String, val steps: Int)
data class ModelUpdate(val modelVersion: String, val taskId: String, val payload: ByteArray, val signature: ByteArray)
interface TrainingClient { suspend fun train(task: TrainingTask): ModelUpdate }
