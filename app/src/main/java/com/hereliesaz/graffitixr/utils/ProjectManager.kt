package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.hereliesaz.graffitixr.data.ProjectData
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.hereliesaz.graffitixr.utils.AnimatedGifEncoder

/**
 * Manages the persistence of project data, including saving, loading, and exporting.
 *
 * Handles both the JSON metadata ([ProjectData]) and the associated binary assets (images).
 *
 * @param context Application context used for file I/O and accessing ContentResolver.
 */
class ProjectManager(private val context: Context) {
    private val projectsDir = File(context.filesDir, "projects")
    private val targetsDir = File(context.filesDir, "targets")

    init {
        if (!projectsDir.exists()) projectsDir.mkdirs()
        if (!targetsDir.exists()) targetsDir.mkdirs()
    }

    /**
     * Saves the project metadata to a JSON file.
     * @param projectName The name of the project (filename without extension).
     * @param projectData The data object to serialize.
     */
    fun saveProject(projectName: String, projectData: ProjectData) {
        val file = File(projectsDir, "$projectName.json")
        val jsonString = Json.encodeToString(ProjectData.serializer(), projectData)
        file.writeText(jsonString)
    }

    /**
     * Loads project metadata from a JSON file.
     * @param projectName The name of the project to load.
     * @return The [ProjectData] object, or null if the file doesn't exist.
     */
    fun loadProject(projectName: String): ProjectData? {
        val file = File(projectsDir, "$projectName.json")
        return if (file.exists()) {
            val jsonString = file.readText()
            Json.decodeFromString(ProjectData.serializer(), jsonString)
        } else {
            null
        }
    }

    /**
     * Saves a list of bitmaps to internal storage and returns their URIs.
     * This ensures the robust target data persists across app restarts.
     *
     * @param projectName The name of the project these targets belong to.
     * @param bitmaps The list of bitmaps to save.
     * @return A list of URIs pointing to the saved files.
     */
    fun saveTargetImages(projectName: String, bitmaps: List<Bitmap>): List<Uri> {
        val projectTargetDir = File(targetsDir, projectName)
        if (!projectTargetDir.exists()) projectTargetDir.mkdirs()

        // Clean old targets for this project
        projectTargetDir.listFiles()?.forEach { it.delete() }

        return bitmaps.mapIndexed { index, bitmap ->
            val file = File(projectTargetDir, "target_$index.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            // Return internal file URI
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }
    }

    /**
     * Retrieves a list of all saved project names.
     */
    fun getProjectList(): List<String> {
        return projectsDir.listFiles()?.map { it.nameWithoutExtension } ?: emptyList()
    }

    /**
     * Deletes a project and its associated target images.
     */
    fun deleteProject(projectName: String) {
        val file = File(projectsDir, "$projectName.json")
        if (file.exists()) {
            file.delete()
        }
        val projectTargetDir = File(targetsDir, projectName)
        if (projectTargetDir.exists()) {
            projectTargetDir.deleteRecursively()
        }
    }

    /**
     * Exports the entire project (JSON + Images) to a ZIP file.
     * Also generates a GIF animation of the evolution history.
     *
     * @param uri The URI to write the ZIP file to (usually from a system picker).
     * @param projectData The project data to export.
     */
    fun exportProjectToZip(uri: Uri, projectData: ProjectData) {
        try {
            val outputStream = context.contentResolver.openOutputStream(uri) ?: return
            val zipOut = ZipOutputStream(outputStream)

            fun addFileToZip(originalUri: Uri, entryName: String): Uri {
                try {
                    val inputStream = context.contentResolver.openInputStream(originalUri)
                    if (inputStream != null) {
                        val entry = ZipEntry(entryName)
                        zipOut.putNextEntry(entry)
                        inputStream.copyTo(zipOut)
                        zipOut.closeEntry()
                        inputStream.close()
                        return Uri.parse("gxr://$entryName")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return Uri.EMPTY
            }

            val newBg = projectData.backgroundImageUri?.let { addFileToZip(it, "background.png") }
            val newOverlay = projectData.overlayImageUri?.let { addFileToZip(it, "overlay.png") }

            val newTargets = projectData.targetImageUris.mapIndexed { index, uri ->
                addFileToZip(uri, "targets/target_$index.png")
            }

            val newEvolution = projectData.evolutionImageUris.mapIndexed { index, uri ->
                addFileToZip(uri, "evolution/frame_$index.png")
            }

            // Generate GIF
            if (projectData.evolutionImageUris.isNotEmpty()) {
                val gifEntry = ZipEntry("evolution.gif")
                zipOut.putNextEntry(gifEntry)

                val encoder = AnimatedGifEncoder()
                encoder.start(zipOut)
                encoder.setDelay(500)
                encoder.setRepeat(0)

                val bitmaps = loadTargetBitmaps(projectData.evolutionImageUris)
                for (bmp in bitmaps) {
                    encoder.addFrame(bmp)
                }
                encoder.finish()
                zipOut.closeEntry()
            }

            val exportedData = projectData.copy(
                backgroundImageUri = newBg,
                overlayImageUri = newOverlay,
                targetImageUris = newTargets,
                evolutionImageUris = newEvolution
            )

            val jsonString = Json.encodeToString(ProjectData.serializer(), exportedData)
            val jsonEntry = ZipEntry("project.json")
            zipOut.putNextEntry(jsonEntry)
            zipOut.write(jsonString.toByteArray())
            zipOut.closeEntry()

            zipOut.close()
            outputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Helper to load bitmaps for GIF generation.
     */
    private fun loadTargetBitmaps(uris: List<Uri>): List<Bitmap> {
        return uris.mapNotNull { uri ->
            try {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
