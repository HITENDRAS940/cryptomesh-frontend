package com.cryptomesh.frontend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cryptomesh.frontend.navigation.CryptoMeshApp
import com.cryptomesh.frontend.ui.theme.CryptoMeshTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CryptoMeshTheme {
                CryptoMeshApp()
            }
        }
    }
}
