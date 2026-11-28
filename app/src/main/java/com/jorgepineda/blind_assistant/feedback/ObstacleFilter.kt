package com.jorgepineda.blind_assistant.feedback

import com.jorgepineda.blind_assistant.ml.DetectionResult

/**
 * Represents a validated obstacle, translated to Spanish and ready for feedback
 */
data class FilteredObstacle(
    val detection: DetectionResult,
    val spanishName: String
)

/**
 * Filtered layer between IA and Multisensorial feedback
 * -Whitelist useful obstacles, COCO labels translation, temporal persistency
 */
class ObstacleFilter(
    private val minConfidence: Float = 0.55f,
    private val requiredConsecutiveFrames: Int = 3
) {
    private val obstacleWhiteList: Map<String, String> = mapOf(
        "person" to "persona",
        "bicycle" to "bicicleta",
        "car" to "auto",
        "motorcycle" to "motocicleta",
        "bus" to "autobús",
        "truck" to "camión",
        "traffic light" to "semáforo",
        "fire hydrant" to "hidrante",
        "stop sign" to "señal de alto",
        "bench" to "banca",
        "dog" to "perro",
        "umbrella" to "paraguas",
        "chair" to "silla",
        "couch" to "sillón",
        "potted plant" to "maceta",
        "bed" to "cama",
        "dining table" to "mesa",
        "laptop" to "computadora",
        "bottle" to "botella",
        "door" to "puerta"
    )

    //Count consecutive frames for each label
    private val consecutiveCounts = mutableMapOf<String, Int>()

    /**
     * Filters YOLO detections and returns only stable obstacles
     * ordered by size (bigger/closer first)
     */
    fun filter(rawDetections: List<DetectionResult>): List<FilteredObstacle> {
        //1. Whitelist and minimum confidence
        val candidates = rawDetections.filter { det ->
            det.confidence >= minConfidence && obstacleWhiteList.containsKey(det.label)
        }

        val labelsSeenThisFrame = candidates.map { it.label }.toSet()

        //2. Temporal counters, sum seen ones and restart missing ones
        val iterator = consecutiveCounts.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in labelsSeenThisFrame) {
                iterator.remove()
            }
        }
        for (label in labelsSeenThisFrame) {
            consecutiveCounts[label] = (consecutiveCounts[label] ?: 0) + 1
        }

        //3. Keep only if N continuous frames are reached
        return candidates
            .filter { (consecutiveCounts[it.label] ?: 0) >= requiredConsecutiveFrames }
            .map { det ->
                FilteredObstacle(
                    detection = det,
                    spanishName = obstacleWhiteList[det.label] ?: det.label

                )
            }
            // Priority to bigger/closer objects
            .sortedByDescending { obstacle ->
                val width = obstacle.detection.right - obstacle.detection.left
                val height = obstacle.detection.bottom - obstacle.detection.top
                width * height
            }
    }

    fun reset() {
        consecutiveCounts.clear()
    }
}