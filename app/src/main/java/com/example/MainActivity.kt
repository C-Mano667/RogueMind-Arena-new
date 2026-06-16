package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  private var fatalError: String? by mutableStateOf<String?>(null)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        android.util.Log.e("RogueMind", "Uncaught exception crash!", throwable)
        runOnUiThread {
            fatalError = "CRASH: ${throwable.message}\nCheck logcat for stacktrace."
        }
    }
    
    setContent {
      MyApplicationTheme {
        if (fatalError != null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Red.copy(0.2f)), contentAlignment = Alignment.Center) {
                Text(text = fatalError ?: "Unknown crash", color = Color.White, modifier = Modifier.padding(16.dp))
            }
        } else {
            GameView(modifier = Modifier.fillMaxSize())
        }
      }
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  MyApplicationTheme { Greeting("Android") }
}
