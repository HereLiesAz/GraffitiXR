package com.hereliesaz.graffitixr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.BuildConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stackTrace = intent.getStringExtra("STACK_TRACE") ?: "No stack trace available."
        val exceptionName = intent.getStringExtra("EXCEPTION_NAME") ?: "Unknown Error"

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    CrashScreen(
                        stackTrace = stackTrace,
                        exceptionName = exceptionName,
                        onReportClick = { reportIssue(stackTrace, exceptionName) },
                        onCloseClick = { finishAffinity() }
                    )
                }
            }
        }
    }

    private fun reportIssue(stackTrace: String, exceptionName: String) {
        val title = "[Crash] Runtime Error: $exceptionName"
        val body = """
@gemini-cli /jules please fix this crash

**Device Info:**
- Manufacturer: ${android.os.Build.MANUFACTURER}
- Model: ${android.os.Build.MODEL}
- Android Version: ${android.os.Build.VERSION.SDK_INT}
- App Version: ${BuildConfig.VERSION_NAME}

**Stack Trace:**
```
$stackTrace
```
        """.trimIndent()

        val encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString())
        val encodedBody = URLEncoder.encode(body, StandardCharsets.UTF_8.toString())
        val labels = "bug,jules"

        val url = "https://github.com/HereLiesAZ/GraffitiXR/issues/new?title=$encodedTitle&body=$encodedBody&labels=$labels"

        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(browserIntent)
        } catch (e: Exception) {
            // Fallback if no browser is available, though unlikely.
            e.printStackTrace()
        }
    }
}

@Composable
fun CrashScreen(
    stackTrace: String,
    exceptionName: String,
    onReportClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Error",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Oops! Something went wrong.",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "The application encountered an unexpected error and needs to close.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onErrorContainer
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = exceptionName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stackTrace,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onReportClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.BugReport, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Report to GitHub (Auto-Fix)")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onCloseClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Close App")
        }
    }
}
