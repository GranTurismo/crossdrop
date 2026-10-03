package com.crossdrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.crossdrop.ui.Root
import com.crossdrop.ui.theme.CrossDropTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CrossDropApp).container
        setContent {
            CrossDropTheme {
                Root(container)
            }
        }
    }
}
