package com.jorgepineda.blind_assistant.ml

import android.graphics.Bitmap

/**
 * Hardware accelerators selector to execute Edge AI inference
 */
enum class AcceleratorType {
    CPU,
    GPU,
    NPU
}

/**
 * Represents a detected object with normalized coords in range [0.0, 1.0]
 * Allows to draw boxes on any screen size
 */
data class DetectionResult(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Inference full result (including debug HUD info and future work)
 */
data class InferenceResult(
    val detections: List<DetectionResult>,
    val inferenceTimeMs: Long,
    val activeAccelerator: AcceleratorType,
    val statusNote: String = ""
)

/**
 * "Plug and play" contract for any object detection engine
 * Allows to change models without touching UI
 */
interface ObjectDetector {
    val activeAccelerator: AcceleratorType
    val statusNote: String

    fun initialize(accelerator: AcceleratorType)
    fun detect(bitmap: Bitmap): InferenceResult
    fun close()
}