package com.jorgepineda.blind_assistant.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min


class YoloDetector(
    private val context: Context,
    private val modelPath: String = "yolov8n.tflite",
    private val labelsPath: String = "labels.txt",
    private val confidenceThreshold: Float = 0.45f,
    private val iouThreshold: Float = 0.45f
) : ObjectDetector {

    companion object {
        private const val TAG = "YoloDetector"
        private const val INPUT_SIZE = 640
        private const val NUM_CHANNELS = 3
        private const val BYTES_PER_FLOAT = 4
    }

    //Lock to prevent crashes when changing CPU/GPU/NPU
    private val lock = Any()

    private var interpreter: Interpreter? = null
    private var activeDelegate: Delegate? = null

    //Mapped model in memory and classes dictionary (upload once)
    private val modelBuffer: MappedByteBuffer = loadModelFile(modelPath)
    private val labels: List<String> = loadLabels(labelsPath)

    //Reused buffers to "clean" Garbage Collector (no lag)
    private val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val floatValues = FloatArray(INPUT_SIZE * INPUT_SIZE * NUM_CHANNELS)
    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * NUM_CHANNELS * BYTES_PER_FLOAT)
        .apply { order(ByteOrder.nativeOrder()) }


    // Automatic detection when uploading model: NCHW [1,3,640,640] vs NHWC [1,640,640,3]
    private var isInputNchw = false
    private var outputElements = 84
    private var outputBoxes = 8400
    private var outputBuffer: Array<Array<FloatArray>> =
        Array(1) { Array(outputElements) { FloatArray(outputBoxes) } }
    override var activeAccelerator: AcceleratorType = AcceleratorType.CPU
        private set

    override var statusNote: String = "Sin inicializar"
        private set

    override fun initialize(accelerator: AcceleratorType) {
        synchronized(lock) {
            releaseResourcesLocked()

            try {
                val options = Interpreter.Options().apply {
                    numThreads = 4
                }

                when (accelerator) {
                    AcceleratorType.CPU -> {
                        activeAccelerator = AcceleratorType.CPU
                        statusNote = "CPU (4 hilos)"
                    }

                    AcceleratorType.GPU -> {
                        val gpuDelegate = GpuDelegate()
                        options.addDelegate(gpuDelegate)
                        activeDelegate = gpuDelegate
                        activeAccelerator = AcceleratorType.GPU
                        statusNote = "GPU Activa"
                    }

                    AcceleratorType.NPU -> {
                        val nnApiDelegate = NnApiDelegate()
                        options.addDelegate(nnApiDelegate)
                        activeDelegate = nnApiDelegate
                        activeAccelerator = AcceleratorType.NPU
                        statusNote = "NPU (NNAPI)"
                    }
                }

                val newInterpreter = Interpreter(modelBuffer, options)
                setupTensors(newInterpreter)
                interpreter = newInterpreter
                Log.d(TAG, "Motor inicializado en $activeAccelerator ($statusNote)")

            } catch (e: Exception) {
                val shortReason = e.message?.take(25) ?: e.javaClass.simpleName
                Log.w(TAG, "Fallo al iniciar $accelerator: $shortReason. Aplicando fallback a CPU.")
                releaseResourcesLocked()

                // Security net (Automatic fallback to CPU)
                val cpuOptions = Interpreter.Options().apply { numThreads = 4 }
                val fallbackInterpreter = Interpreter(modelBuffer, cpuOptions)
                setupTensors(fallbackInterpreter)
                interpreter = fallbackInterpreter
                activeAccelerator = AcceleratorType.CPU
                statusNote = "Fallback $accelerator ($shortReason)"
            }
        }
    }

    override fun detect(bitmap: Bitmap): InferenceResult {
        synchronized(lock) {
            val currentInterpreter = interpreter
                ?: return InferenceResult(
                    detections = emptyList(),
                    inferenceTimeMs = 0L,
                    activeAccelerator = activeAccelerator,
                    statusNote = "Motor no inicializado"
                )

            // 1. Preprocessing: 640x640 scalar and normalize RGB to [0.0, 1.0]
            preprocessBitmap(bitmap)

            // 2. Inference (clock)
            val startTime = SystemClock.elapsedRealtime()
            currentInterpreter.run(inputBuffer, outputBuffer)
            val inferenceTimeMs = SystemClock.elapsedRealtime() - startTime

            // 3. Postprocessing: decode [1, 84, 8400] + NMS
            val detections = postprocess(outputBuffer[0])

            return InferenceResult(
                detections = detections,
                inferenceTimeMs = inferenceTimeMs,
                activeAccelerator = activeAccelerator,
                statusNote = statusNote
            )
        }
    }

    override fun close() {
        synchronized(lock) {
            releaseResourcesLocked()
        }
    }

    private fun releaseResourcesLocked() {
        try {
            interpreter?.close()
        } catch (_: Exception) {
        } finally {
            interpreter = null
        }

        try {
            activeDelegate?.close()
        } catch (_: Exception) {
        } finally {
            activeDelegate = null
        }
    }

    private fun setupTensors(interp: Interpreter) {
        // Detect expected inputs, first (NCHW) or at the end (NHWC)
        val inShape = interp.getInputTensor(0).shape()
        isInputNchw = (inShape.size == 4 && inShape[1] == NUM_CHANNELS)

        // Detect otuput shape (ej. [1, 84, 8400])
        val outShape = interp.getOutputTensor(0).shape()
        if (outShape.size == 3) {
            outputElements = outShape[1]
            outputBoxes = outShape[2]
            outputBuffer = Array(1) { Array(outputElements) { FloatArray(outputBoxes) } }
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap) {
        val scaledBitmap = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }

        scaledBitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val numPixels = INPUT_SIZE * INPUT_SIZE
        if (isInputNchw) {
            //NCHW Planar format [1, 3, 640, 640]: all R, then G, then B
            val gOffset = numPixels
            val bOffset = numPixels * 2
            for (i in 0 until numPixels) {
                val pixel = intValues[i]
                floatValues[i] = ((pixel shr 16) and 0xFF) / 255.0f
                floatValues[gOffset + i] = ((pixel shr 8) and 0xFF) / 255.0f
                floatValues[bOffset + i] = (pixel and 0xFF) / 255.0f
            }
        } else {
            //Interleaved NHWC format [1, 640, 640, 3]: R, G, B, R, G, B...
            var idx = 0
            for (i in 0 until numPixels) {
                val pixel = intValues[i]
                floatValues[idx++] = ((pixel shr 16) and 0xFF) / 255.0f
                floatValues[idx++] = ((pixel shr 8) and 0xFF) / 255.0f
                floatValues[idx++] = (pixel and 0xFF) / 255.0f
            }
        }

        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(floatValues)

        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }
    }


    private fun postprocess(rawOutput: Array<FloatArray>): List<DetectionResult> {
        val candidates = ArrayList<DetectionResult>()
        val numClasses = outputElements - 4

        for (col in 0 until outputBoxes) {
            var bestClassId = -1
            var bestConfidence = confidenceThreshold

            for (c in 0 until numClasses) {
                val score = rawOutput[4 + c][col]
                if (score > bestConfidence) {
                    bestConfidence = score
                    bestClassId = c
                }
            }

            if (bestClassId == -1) continue

            var cx = rawOutput[0][col]
            var cy = rawOutput[1][col]
            var w = rawOutput[2][col]
            var h = rawOutput[3][col]

            if (cx > 2.0f || cy > 2.0f || w > 2.0f || h > 2.0f) {
                cx /= INPUT_SIZE
                cy /= INPUT_SIZE
                w /= INPUT_SIZE
                h /= INPUT_SIZE
            }

            val left = (cx - w / 2f).coerceIn(0f, 1f)
            val top = (cy - h / 2f).coerceIn(0f, 1f)
            val right = (cx + w / 2f).coerceIn(0f, 1f)
            val bottom = (cy + h / 2f).coerceIn(0f, 1f)

            if (right <= left || bottom <= top) continue

            val label = labels.getOrElse(bestClassId) { "obj_$bestClassId" }

            candidates.add(
                DetectionResult(
                    classId = bestClassId,
                    label = label,
                    confidence = bestConfidence,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom
                )
            )
        }

        return applyNms(candidates)
    }

    private fun applyNms(detections: List<DetectionResult>): List<DetectionResult> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }
        val selected = ArrayList<DetectionResult>()

        for (candidate in sorted) {
            var shouldKeep = true
            for (kept in selected) {
                if (candidate.classId == kept.classId && calculateIoU(candidate, kept) > iouThreshold) {
                    shouldKeep = false
                    break
                }
            }
            if (shouldKeep) {
                selected.add(candidate)
            }
        }
        return selected
    }

    private fun calculateIoU(a: DetectionResult, b: DetectionResult): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interWidth = max(0f, interRight - interLeft)
        val interHeight = max(0f, interBottom - interTop)
        val interArea = interWidth * interHeight

        if (interArea <= 0f) return 0f

        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = areaA + areaB - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    private fun loadModelFile(assetPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(assetPath)
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )
        }
    }

    private fun loadLabels(assetPath: String): List<String> {
        return context.assets.open(assetPath).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
    }
}