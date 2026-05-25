package com.hereliesaz.graffitixr.feature.dashboard

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (downloadId == -1L) return

        val prefs = context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        val expectedId = prefs.getLong("update_download_id", -1L)

        if (downloadId != expectedId) {
            return
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = downloadManager.getUriForDownloadedFile(downloadId)

        if (uri != null) {
            // Confirming reality before torching the bridge.
            prefs.edit().remove("update_download_id").apply()

            // Installing an APK needs REQUEST_INSTALL_PACKAGES *and* the user having granted
            // "install unknown apps" for us. Without this gate the ACTION_VIEW below silently
            // fails and the self-update dead-ends at a Toast.
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "Allow installing unknown apps to finish updating.", Toast.LENGTH_LONG).show()
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Toast.makeText(context, "Open Settings → Install unknown apps to update.", Toast.LENGTH_LONG).show()
                }
                return
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(installIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to open installer: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Update download failed.", Toast.LENGTH_LONG).show()
        }
    }
}