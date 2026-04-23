package com.sheltron.captioner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sheltron.captioner.ui.nav.CaptionerNav
import com.sheltron.captioner.ui.theme.CaptionerTheme

class MainActivity : ComponentActivity() {

    private var pendingSessionId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingSessionId = intent?.consumeOpenSessionExtra()
        setContent {
            CaptionerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CaptionerNav(openSessionOnLaunch = pendingSessionId, onSessionOpened = {
                        pendingSessionId = null
                    })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Tapping a results notification re-enters the running Activity; propagate the extra.
        intent.consumeOpenSessionExtra()?.let { pendingSessionId = it }
    }

    private fun Intent.consumeOpenSessionExtra(): Long? {
        val id = getLongExtra(EXTRA_OPEN_SESSION_ID, -1L)
        if (id <= 0) return null
        removeExtra(EXTRA_OPEN_SESSION_ID)
        return id
    }

    companion object {
        const val EXTRA_OPEN_SESSION_ID = "com.sheltron.captioner.EXTRA_OPEN_SESSION_ID"
    }
}
