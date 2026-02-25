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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stackTrace = intent.getStringExtra("STACK_TRACE") ?: "No stack trace available."
        val errorReport = generateErrorReport(stackTrace)

        setContent {
            GraffitiXRTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Crash",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )

                        Text(
                            text = "Reality Glitched",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        Text(
                            text = "The app encountered a fatal error. A report has been generated.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        // Error Log Box
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                text = stackTrace,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            )
                        }

                        // Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { restartApp() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.RestartAlt, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Restart")
                            }

                            Button(
                                onClick = { copyToClipboard(errorReport) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Icon(Icons.Default.ContentCopy, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Copy Log")
                            }
                        }

                        Button(
                            onClick = { reportIssue(errorReport) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Icon(Icons.Default.BugReport, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Report on GitHub")
                        }
                    }
                }
            }
        }
    }

    private fun generateErrorReport(stackTrace: String): String {
        return """
            **Device Info:**
            Manufacturer: ${android.os.Build.MANUFACTURER}
            Model: ${android.os.Build.MODEL}
            Android SDK: ${android.os.Build.VERSION.SDK_INT}
            
            **Stack Trace:**
            ```
            $stackTrace
            ```
        """.trimIndent()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Crash Log", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun restartApp() {
        val intent = baseContext.packageManager.getLaunchIntentForPackage(baseContext.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun reportIssue(body: String) {
        // Safe access to BuildConfig dynamically linked properties.
        val repoOwner = BuildConfig.REPO_OWNER
        val repoName = BuildConfig.REPO_NAME

        val encodedBody = URLEncoder.encode(body, StandardCharsets.UTF_8.toString())
        val url = "https://github.com/$repoOwner/$repoName/issues/new?labels=crash&body=$encodedBody"

        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(browserIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
    }
}