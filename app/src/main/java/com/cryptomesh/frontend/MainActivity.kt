package com.cryptomesh.frontend

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cryptomesh.frontend.navigation.CryptoMeshApp
import com.cryptomesh.frontend.ui.theme.CryptoMeshTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            )
        }
        setContent {
            CryptoMeshTheme {
                CryptoMeshApp()
            }
        }
    }
}
