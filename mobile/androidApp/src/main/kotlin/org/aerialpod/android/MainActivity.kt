package org.aerialpod.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aerialpod.android.ui.AerialPodApp
import org.aerialpod.android.ui.theme.AerialPodTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = appGraph
        setContent {
            // Asked once, on first composition. Denial costs the media
            // notification and lock-screen controls, not playback itself — the
            // foreground service runs either way — so there is nothing to
            // explain up front and nothing to do if it is refused.
            val notifications = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(Unit) {
                // The permission does not exist below API 33, where notifications
                // are granted at install. Asking anyway would not fail loudly —
                // `checkSelfPermission` returns denied for a permission the
                // platform has never heard of, so an API 30–32 device would show
                // a dialog that dismisses itself on every single launch.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val prefs by graph.theme.prefs.collectAsStateWithLifecycle()
            AerialPodTheme(prefs) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AerialPodApp(graph)
                }
            }
        }
    }
}
