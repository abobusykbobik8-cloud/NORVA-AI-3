package ai.norva.app

import ai.norva.app.ui.NorvaNav
import ai.norva.app.ui.NorvaTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NorvaApp).container
        setContent {
            NorvaTheme {
                NorvaNav(container)
            }
        }
    }
}
