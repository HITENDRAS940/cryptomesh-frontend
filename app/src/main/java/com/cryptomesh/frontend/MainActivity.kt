package com.cryptomesh.frontend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cryptomesh.frontend.navigation.CryptoMeshApp
import com.cryptomesh.frontend.ui.theme.CryptoMeshTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CryptoMeshTheme {
                CryptoMeshApp()
            }
        }
    }
}
