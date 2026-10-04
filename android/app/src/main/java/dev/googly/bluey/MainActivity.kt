package dev.googly.bluey

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var googly: Googly
    private var micAnswer: ((Boolean) -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, "Bluey needs the microphone to hear you. You can allow it in Settings › Apps.",
                Toast.LENGTH_LONG).show()
        }
        micAnswer?.invoke(granted)
        micAnswer = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No status bar, no navigation bar, and the screen stays on: he lives here.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        googly = Googly(this)
        googly.live.askMicPermission = { answer ->
            micAnswer = answer
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent { GooglyRoot(googly) }
    }

    override fun onStart() {
        super.onStart()
        googly.link.start()
    }

    override fun onStop() {
        super.onStop()
        // Android doesn't let apps keep the mic in the background, so he naps until you come back.
        googly.live.sleep()
        googly.link.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
