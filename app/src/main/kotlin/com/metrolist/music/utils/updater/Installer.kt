/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.updater

import androidx.annotation.StringRes
import com.metrolist.music.R

enum class InstallerType {
    NATIVE,
    SESSION,
    ROOT,
    SHIZUKU,
    DHIZUKU
}

data class InstallerInfo(
    val type: InstallerType,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val available: Boolean
)

object InstallerRegistry {
    val NATIVE = InstallerInfo(
        type = InstallerType.NATIVE,
        title = R.string.installer_native_title,
        description = R.string.installer_native_desc,
        available = true
    )

    val SESSION = InstallerInfo(
        type = InstallerType.SESSION,
        title = R.string.installer_session_title,
        description = R.string.installer_session_desc,
        available = true
    )

    val ROOT = InstallerInfo(
        type = InstallerType.ROOT,
        title = R.string.installer_root_title,
        description = R.string.installer_root_desc,
        available = true // Will be checked at runtime
    )

    val SHIZUKU = InstallerInfo(
        type = InstallerType.SHIZUKU,
        title = R.string.installer_shizuku_title,
        description = R.string.installer_shizuku_desc,
        available = true // Will be checked at runtime
    )

    val DHIZUKU = InstallerInfo(
        type = InstallerType.DHIZUKU,
        title = R.string.installer_dhizuku_title,
        description = R.string.installer_dhizuku_desc,
        available = true // Will be checked at runtime
    )
}
