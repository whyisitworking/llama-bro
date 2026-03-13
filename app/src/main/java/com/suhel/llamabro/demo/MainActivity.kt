package com.suhel.llamabro.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.suhel.llamabro.demo.ui.screens.root.RootContainer
import com.suhel.llamabro.demo.ui.theme.LlamaBroTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LlamaBroTheme {
                RootContainer()
            }
        }
    }
}
