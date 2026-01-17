package com.hereliesaz.graffitixr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide UI flags...

        setContent {
            val navController = rememberNavController()
            GraffitiXRTheme {
                // FIXED: Signature matches the user's MainScreen
                MainScreen(
                    viewModel = viewModel,
                    navController = navController
                )
            }
        }
    }
}