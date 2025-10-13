/*===============================================================================
Copyright (c) 2024 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

package com.vuforia.engine.native_sample

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

import com.vuforia.engine.native_sample.databinding.ActivityMainBinding


/**
 * The MainActivity presents a simple choice for the user to select Image Targets or Model Targets.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
    }


    fun goToActivity(view: View) {
        if (view.id == binding.btnImageTarget.id || view.id == binding.btnModelTarget.id) {

            val intent = Intent(
                this@MainActivity,
                VuforiaActivity::class.java
            )
            if (view.id == binding.btnImageTarget.id) {
                intent.putExtra("Target", VuforiaActivity.getImageTargetId())
            } else {
                intent.putExtra("Target", VuforiaActivity.getModelTargetId())
            }

            startActivity(intent)
        }
    }


    companion object {

        // Used to load the 'VuforiaSample' library on application startup.
        init {
            System.loadLibrary("VuforiaSample")
        }
    }

    private lateinit var binding: ActivityMainBinding
}
