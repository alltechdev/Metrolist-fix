/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

/**
 * JNI wrapper for native Bento4-based metadata embedding.
 * This class provides low-level access to native functions for embedding
 * cover art and text metadata into M4A/MP4 files.
 */
object CoverArtNative {
    init {
        System.loadLibrary("coverart")
    }

    /**
     * Embed metadata (cover art, title, artist, album, year) into an M4A/MP4 file.
     * All text is stored as UTF-8 (supports Hebrew, Arabic, and all Unicode).
     *
     * @param inputPath Path to the input M4A/MP4 file
     * @param outputPath Path for the output file with embedded metadata
     * @param artworkData JPEG or PNG image data for cover art (can be null)
     * @param title Song title (can be null)
     * @param artist Artist name (can be null)
     * @param album Album name (can be null)
     * @param year Year string (can be null)
     * @return true if successful, false otherwise
     */
    external fun embedMetadata(
        inputPath: String,
        outputPath: String,
        artworkData: ByteArray?,
        title: String?,
        artist: String?,
        album: String?,
        year: String?
    ): Boolean

    /**
     * Defragment a DASH/fragmented MP4 file to standard MP4.
     * This is needed because DASH files use moof/mdat structure instead of moov/mdat.
     *
     * @param inputPath Path to the fragmented input file
     * @param outputPath Path for the defragmented output file
     * @return true if successful, false otherwise
     */
    external fun defragmentFile(
        inputPath: String,
        outputPath: String
    ): Boolean
}
