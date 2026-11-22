package com.jorgepineda.blind_assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // Referencing cam visual component in XML
    private lateinit var viewFinder: PreviewView
    private lateinit var debugHudText: TextView

    // STRING 1: Exclusively for frame analysis
    private lateinit var cameraExecutor: ExecutorService

    //Debug HUD variables
    private var totalFrames = 0L
    private var framesInWindow = 0
    private var lastFpsTimestamp = System.currentTimeMillis()

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

        //Start String 1 in the background
        cameraExecutor = Executors.newSingleThreadExecutor()

        checkCameraPermission()
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
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        totalFrames++
                        framesInWindow++

                        val currentTime = System.currentTimeMillis()
                        val elapsedMs = currentTime - lastFpsTimestamp

                        //Refresh HUD every 500ms
                        if (elapsedMs >= 500){
                            val fps = (framesInWindow * 1000.0) / elapsedMs
                            val width = imageProxy.width
                            val height = imageProxy.height
                            val rotation = imageProxy.imageInfo.rotationDegrees

                            val hudMessage = "Hilo 1: OK\n" +
                                    "Frames: $totalFrames\n" +
                                    "FPS: ${"%.1f".format(fps)}\n" +
                                    "Res: ${width}x${height} | Rot: ${rotation}°"

                            //Modify UI only in main string
                            runOnUiThread { debugHudText.text = hudMessage }

                            framesInWindow = 0
                            lastFpsTimestamp = currentTime
                        }

                        //Close frame to free CameraX buffer
                        imageProxy.close()
                    }
                }
            //Select back camera as the main
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try{
                //Unbind any previous use before linking again
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


    override fun onDestroy() {
        super.onDestroy()
        //Turn off secondary string by closing app
        cameraExecutor.shutdown()
    }
}