/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import android.net.Uri

/**
 * Helper function for constructing video routes.
 */
fun videoRoute(videoId: String, title: String? = null, artist: String? = null): String {
    val params = listOfNotNull(
        title?.takeIf { it.isNotBlank() }?.let { "title=${Uri.encode(it)}" },
        artist?.takeIf { it.isNotBlank() }?.let { "artist=${Uri.encode(it)}" },
    )
    val query = if (params.isNotEmpty()) params.joinToString("&", prefix = "?") else ""
    return "video/$videoId$query"
}
