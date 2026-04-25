// FILE: app/src/main/java/com/hereliesaz/graffitixr/CrashActivity.kt
package com.hereliesaz.graffitixr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra("STACK_TRACE") ?: "The void offers no explanation."

        val sanitizedTrace = preserveTheAutopsy(trace)

        val uri = "https://github.com/hereliesaz/GraffitiXR/issues/new".toUri()
            .buildUpon()
            .appendQueryParameter("body", "```\n$sanitizedTrace\n```")
            .build()

        startActivity(Intent(Intent.ACTION_VIEW, uri))
        finish()
    }

    private fun preserveTheAutopsy(trace: String): String {
        if (trace.length <= 2000) return trace

        val lines = trace.lines()
        val topFrames = lines.take(15).joinToString("\n")

        val causes = StringBuilder()
        var parsingCause = false
        var causeLines = 0

        for (line in lines) {
            if (line.trimStart().startsWith("Caused by:")) {
                parsingCause = true
                causeLines = 0
                causes.append("\n...[FRAMEWORK NOISE EXCISED]...\n")
            }
            if (parsingCause) {
                if (causeLines < 8) {
                    causes.append(line).append("\n")
                    causeLines++
                } else if (causeLines == 8) {
                    parsingCause = false
                }
            }
        }

        val finalTrace = if (causes.isEmpty()) {
            trace.substring(0, 1000) + "\n\n...[AMPUTATED]...\n\n" + trace.substring(trace.length - 900)
        } else {
            topFrames + causes.toString()
        }

        return if (finalTrace.length > 2000) finalTrace.substring(0, 1990) + "..." else finalTrace
    }
}