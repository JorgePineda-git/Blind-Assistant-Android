package com.jorgepineda.blind_assistant

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // GET USER ANSWER FOR PERMISSION
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
        // Link Kotlin with XML
        setContentView(R.layout.activity_main)

        checkCameraPermission()
    }


    // CHECK PERMISSION
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


    // STARTING POINT FOR CAMERA (coming...)
    private fun onCameraPermissionGranted(){
        Toast.makeText(
            this,
            "Permiso de uso de la cámara listo",
            Toast.LENGTH_SHORT
            ).show()
    }


}