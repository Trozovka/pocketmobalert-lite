package com.trozovka.pocketmobalert.core

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    Box(
                        modifier = Modifier.padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        HomePlaceholder()
                    }
                }
            }
        }
    }
}

@Composable
private fun HomePlaceholder() {
    Text("PocketMOBAlert -- Crew/Watch mode setup coming in M2/M3")
}
