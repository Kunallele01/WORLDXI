package com.dreamxi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.dreamxi.app.navigation.DreamXiNavHost
import com.dreamxi.app.ui.theme.DreamXITheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DreamXITheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DreamXiNavHost(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
