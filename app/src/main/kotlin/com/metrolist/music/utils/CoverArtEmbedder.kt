/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level utility for embedding metadata into M4A files.
 * Uses Bento4 native library via CoverArtNative JNI wrapper.
 */
@Singleton
class CoverArtEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "CoverArtEmbedder"
    }

    /**
     * Embed metadata into an M4A file at a SAF URI location.
     * Creates a temporary copy, embeds metadata, then replaces the original.
     *
     * @param fileUri SAF URI of the M4A file
     * @param artworkData Cover art image data (JPEG or PNG), can be null
     * @param title Song title, can be null
     * @param artist Artist name, can be null
     * @param album Album name, can be null
     * @param year Year string, can be null
     * @return true if successful, false otherwise
     */
    suspend fun embedMetadataIntoFile(
        fileUri: Uri,
        artworkData: ByteArray?,
        title: String?,
        artist: String?,
        album: String?,
        year: String?
    ): Boolean = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("=== Starting metadata embedding ===")
        Timber.tag(TAG).d("File URI: $fileUri")
        Timber.tag(TAG).d("Title: $title, Artist: $artist, Album: $album, Year: $year")
        Timber.tag(TAG).d("Artwork size: ${artworkData?.size ?: 0} bytes")

        val tempDir = File(context.cacheDir, "coverart_temp")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }

        val inputFile = File(tempDir, "input_${System.currentTimeMillis()}.m4a")
        val outputFile = File(tempDir, "output_${System.currentTimeMillis()}.m4a")

        try {
            // Step 1: Copy SAF file to local temp file
            Timber.tag(TAG).d("Step 1: Copying SAF file to temp...")
            val docFile = DocumentFile.fromSingleUri(context, fileUri)
            if (docFile == null || !docFile.exists()) {
                Timber.tag(TAG).e("File does not exist: $fileUri")
                return@withContext false
            }

            context.contentResolver.openInputStream(fileUri)?.use { input ->
                FileOutputStream(inputFile).use { output ->
                    val bytes = input.copyTo(output)
                    Timber.tag(TAG).d("Copied $bytes bytes to temp file")
                }
            } ?: run {
                Timber.tag(TAG).e("Failed to open input stream for: $fileUri")
                return@withContext false
            }

            // Step 2: Embed metadata using native library
            Timber.tag(TAG).d("Step 2: Embedding metadata via native library...")
            val success = CoverArtNative.embedMetadata(
                inputPath = inputFile.absolutePath,
                outputPath = outputFile.absolutePath,
                artworkData = artworkData,
                title = title,
                artist = artist,
                album = album,
                year = year
            )

            if (!success) {
                Timber.tag(TAG).e("Native embedMetadata failed")
                return@withContext false
            }

            Timber.tag(TAG).d("Native embedding successful, output size: ${outputFile.length()} bytes")

            // Step 3: Copy result back to SAF location
            Timber.tag(TAG).d("Step 3: Copying result back to SAF location...")
            context.contentResolver.openOutputStream(fileUri, "wt")?.use { output ->
                outputFile.inputStream().use { input ->
                    val bytes = input.copyTo(output)
                    Timber.tag(TAG).d("Wrote $bytes bytes back to SAF file")
                }
            } ?: run {
                Timber.tag(TAG).e("Failed to open output stream for: $fileUri")
                return@withContext false
            }

            Timber.tag(TAG).d("=== Metadata embedding completed successfully ===")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error embedding metadata")
            false
        } finally {
            // Cleanup temp files
            if (inputFile.exists()) {
                inputFile.delete()
                Timber.tag(TAG).d("Deleted temp input file")
            }
            if (outputFile.exists()) {
                outputFile.delete()
                Timber.tag(TAG).d("Deleted temp output file")
            }
        }
    }

    /**
     * Embed metadata into a local file (non-SAF).
     * Creates output file, then replaces original.
     *
     * @param filePath Path to the M4A file
     * @param artworkData Cover art image data (JPEG or PNG), can be null
     * @param title Song title, can be null
     * @param artist Artist name, can be null
     * @param album Album name, can be null
     * @param year Year string, can be null
     * @return true if successful, false otherwise
     */
    suspend fun embedMetadataIntoLocalFile(
        filePath: String,
        artworkData: ByteArray?,
        title: String?,
        artist: String?,
        album: String?,
        year: String?
    ): Boolean = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("=== Starting local file metadata embedding ===")
        Timber.tag(TAG).d("File path: $filePath")

        val inputFile = File(filePath)
        if (!inputFile.exists()) {
            Timber.tag(TAG).e("Input file does not exist: $filePath")
            return@withContext false
        }

        val outputFile = File(inputFile.parent, "temp_${inputFile.name}")

        try {
            Timber.tag(TAG).d("Embedding metadata via native library...")
            val success = CoverArtNative.embedMetadata(
                inputPath = inputFile.absolutePath,
                outputPath = outputFile.absolutePath,
                artworkData = artworkData,
                title = title,
                artist = artist,
                album = album,
                year = year
            )

            if (!success) {
                Timber.tag(TAG).e("Native embedMetadata failed")
                return@withContext false
            }

            Timber.tag(TAG).d("Native embedding successful, replacing original...")

            // Replace original with output
            if (inputFile.delete() && outputFile.renameTo(inputFile)) {
                Timber.tag(TAG).d("=== Local file metadata embedding completed ===")
                true
            } else {
                Timber.tag(TAG).e("Failed to replace original file")
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error embedding metadata into local file")
            outputFile.delete()
            false
        }
    }
}
