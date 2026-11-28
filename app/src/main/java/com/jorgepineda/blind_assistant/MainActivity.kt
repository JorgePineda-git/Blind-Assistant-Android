package com.jorgepineda.blind_assistant

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.jorgepineda.blind_assistant.ml.AcceleratorType
import com.jorgepineda.blind_assistant.ml.ObjectDetector
import com.jorgepineda.blind_assistant.ml.YoloDetector
import com.jorgepineda.blind_assistant.feedback.FeedbackManager
import com.jorgepineda.blind_assistant.feedback.FilteredObstacle
import com.jorgepineda.blind_assistant.feedback.ObstacleFilter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewFinder: PreviewView
    private lateinit var debugHudText: TextView
    private lateinit var btnCpu: Button
    private lateinit var btnGpu: Button
    private lateinit var btnNpu: Button

    //Secondary string for frames analysis and inference
    private lateinit var cameraExecutor: ExecutorService

    //IA Engine (using interface "Plug & Play")
    private lateinit var objectDetector: ObjectDetector

    //String 3: Obstacle filter + TTS and vibration
    private lateinit var obstacleFilter: ObstacleFilter
    private lateinit var feedbackManager: FeedbackManager

    //Debug HUD variables
    private var framesInWindow = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var currentFps = 0.0

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {isGranted: Boolean ->
        if (isGranted) {
            onCameraPermissionGranted()
        } else {
            Toast.makeText(
                this,
                "Se requiere el permiso de la cámara",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    // WINDOW SCREEN CREATION
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //Link kotlin var to XML ID
        viewFinder = findViewById(R.id.viewFinder)
        debugHudText = findViewById(R.id.debugHudText)
        btnCpu = findViewById(R.id.btnCpu)
        btnGpu = findViewById(R.id.btnGpu)
        btnNpu = findViewById(R.id.btnNpu)

        cameraExecutor = Executors.newSingleThreadExecutor()
        objectDetector = YoloDetector(this, modelPath = "yolov8n_int8.tflite")

        obstacleFilter = ObstacleFilter()
        feedbackManager = FeedbackManager(this)

        setupHardwareButtons()

        //Default, starts in CPU in the background
        switchAccelerator(AcceleratorType.CPU)

        checkCameraPermission()
    }


    private fun setupHardwareButtons() {
        btnCpu.setOnClickListener { switchAccelerator(AcceleratorType.CPU) }
        btnGpu.setOnClickListener { switchAccelerator(AcceleratorType.GPU) }
        btnNpu.setOnClickListener { switchAccelerator(AcceleratorType.NPU) }
    }


    private fun switchAccelerator(target: AcceleratorType) {
        debugHudText.text = "Cambiando a $target..."
        setButtonsEnabled(false)

        // Initialize accelerator on secondary string
        cameraExecutor.execute {
            objectDetector.initialize(target)
            val active = objectDetector.activeAccelerator
            val note = objectDetector.statusNote

            runOnUiThread {
                updateButtonHighlight(active)
                setButtonsEnabled(true)
                if (active != target) {
                    Toast.makeText(this, note, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnCpu.isEnabled = enabled
        btnGpu.isEnabled = enabled
        btnNpu.isEnabled = enabled
    }

    private fun updateButtonHighlight(active: AcceleratorType) {
        val activeColor = ColorStateList.valueOf(Color.parseColor("#2E7D32"))   // Active green
        val inactiveColor = ColorStateList.valueOf(Color.parseColor("#424242")) // Inactive gray

        btnCpu.backgroundTintList = if (active == AcceleratorType.CPU) activeColor else inactiveColor
        btnGpu.backgroundTintList = if (active == AcceleratorType.GPU) activeColor else inactiveColor
        btnNpu.backgroundTintList = if (active == AcceleratorType.NPU) activeColor else inactiveColor
    }

    private fun checkCameraPermission(){
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                //Already gave permission before
                onCameraPermissionGranted()
            }
            else -> {
                // No permission yet
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun onCameraPermissionGranted(){
        //Permission confirmed, run CameraX
        startCamera()
    }


    private fun startCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            //Linking camera to screen life cycle
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            //Case 1: Screen preview (UI)
            val preview = Preview.Builder().build()
                .also{
                    it.surfaceProvider = viewFinder.surfaceProvider
                }

            //Case 2: Image analyzer pipeline in the background (String 1)
            val imageAnalyzer = ImageAnalysis.Builder()
                //Discard late frames to prevent lag
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                //Conversion to RGBA_8888
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            val rawBitmap = imageProxy.toBitmap()
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val uprightBitmap = rotateBitmap(rawBitmap, rotation)

                            // Execute YOLOv8 inference (String 2)
                            val result = objectDetector.detect(uprightBitmap)

                            //String 3: Whitelist + persistency and announce
                            val filteredObstacles = obstacleFilter.filter(result.detections)
                            feedbackManager.announce(filteredObstacles)

                            // Free temporary bitmaps to avoid memory leaks
                            if (uprightBitmap !== rawBitmap) {
                                uprightBitmap.recycle()
                            }
                            rawBitmap.recycle()

                            // Calculate real inference FPS every 500 ms
                            framesInWindow++
                            val now = System.currentTimeMillis()
                            val elapsed = now - lastFpsTimestamp
                            if (elapsed >= 500) {
                                currentFps = (framesInWindow * 1000.0) / elapsed
                                framesInWindow = 0
                                lastFpsTimestamp = now
                            }

                            // Format top 3 detected objects for the HUD debug
                            val objectsText = if (result.detections.isEmpty()) {
                                "Ninguno"
                            } else {
                                result.detections
                                    .take(3)
                                    .joinToString(", ") {
                                        "${it.label} (${(it.confidence * 100).toInt()}%)"
                                    }
                            }

                            val hudMessage = "HW: ${result.activeAccelerator} (${result.statusNote})\n" +
                                    "Latencia: ${result.inferenceTimeMs} ms | FPS: ${"%.1f".format(currentFps)}\n" +
                                    "Objs (${result.detections.size}): $objectsText" +
                                    "\nVoz ES: ${filteredObstacles.firstOrNull()?.spanishName ?: "Ninguno"}"

                            runOnUiThread {
                                debugHudText.text = hudMessage
                            }
                        } catch (e: Exception) {
                            Log.e("YoloPipeline", "Error procesando frame", e)
                        } finally {
                            // ALWAYS free CameraX frame
                            imageProxy.close()
                        }
                    }
                }


            //Select back camera as the main
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try{
                cameraProvider.unbindAll()

                //Connect cam,preview and analyzer to lifecycle
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("CameraX_Hilo1", "Error al inicar la Cámara", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        //Queue engine closing before shutdown the string
        cameraExecutor.execute { objectDetector.close() }
        cameraExecutor.shutdown()
        feedbackManager.shutdown()
    }
}