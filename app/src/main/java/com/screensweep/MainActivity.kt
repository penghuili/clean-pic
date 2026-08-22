package com.screensweep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.screensweep.ui.screens.AppRoot
import com.screensweep.ui.theme.ScreenSweepTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScreenSweepTheme {
                AppRoot()
            }
        }
    }
}
