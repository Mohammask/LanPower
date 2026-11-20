package com.mohammask.lanpower

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mohammask.lanpower.ui.MainScreen
import com.mohammask.lanpower.ui.theme.LanPowerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LanPowerTheme {
                MainScreen()
            }
        }
    }
}
