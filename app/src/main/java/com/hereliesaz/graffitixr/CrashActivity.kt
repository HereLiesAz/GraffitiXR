
package com.hereliesaz.graffitixr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stackTrace = intent.getStringExtra("EXTRA_STACK_TRACE") ?: "Unknown Error occurred."

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "GraffitiXR Encountered an Error",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "We apologize for the interruption. Helping us report this will make the app better for all artists.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = {
                                copyToClipboard(stackTrace)
                                reportIssue(stackTrace)
                            }
                        ) {
                            Text("Copy Log & Report Issue on GitHub")
                        }
                    }
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Crash Log", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Full crash log copied to clipboard!", Toast.LENGTH_LONG).show()
    }

    private fun reportIssue(body: String) {
        val repoOwner = "HereLiesAz"
        val repoName = "GraffitiXR"

        // Prevent ActivityNotFoundException by keeping the URL under ~2000 chars,
        // while preserving BOTH the top of the stack trace and the critical "Caused by" at the bottom.
        val safeBody = if (body.length > 1500) {
            val top = body.substring(0, 500)
            val bottom = body.substring(body.length - 900)
            "$top\n\n... [Log truncated for URL limit. Please paste the FULL log from your clipboard!] ...\n\n$bottom"
        } else {
            body
        }

        val encodedBody = URLEncoder.encode(safeBody, StandardCharsets.UTF_8.toString())
        val url = "https://github.com/$repoOwner/$repoName/issues/new?labels=crash&body=$encodedBody"

        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(browserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open browser. Please paste the log manually.", Toast.LENGTH_LONG).show()
        }
    }
}
