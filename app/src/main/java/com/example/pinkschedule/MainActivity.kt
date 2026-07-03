package com.example.pinkschedule

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.pinkschedule.ui.PinkScheduleAppScreen
import com.example.pinkschedule.ui.theme.PinkScheduleTheme
import com.example.pinkschedule.viewmodel.ScheduleViewModel

class MainActivity : ComponentActivity() {
    private val incomingImportIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingImportIntent.value = intent.takeIf { it.isImportIntent() }
        enableEdgeToEdge()
        setContent {
            PinkScheduleTheme {
                val viewModel = remember { ScheduleViewModel(application) }
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.safeDrawingPadding()) {
                        PinkScheduleAppScreen(
                            viewModel = viewModel,
                            incomingImportIntent = incomingImportIntent.value,
                            onIncomingImportIntentConsumed = {
                                incomingImportIntent.value = null
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingImportIntent.value = intent.takeIf { it.isImportIntent() }
    }

    private fun Intent?.isImportIntent(): Boolean {
        return this != null && action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)
    }
}
