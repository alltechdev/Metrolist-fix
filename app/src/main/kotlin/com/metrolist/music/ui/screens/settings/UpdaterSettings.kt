/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.CheckForUpdatesKey
import com.metrolist.music.constants.InstallerTypeKey
import com.metrolist.music.constants.UpdateNotificationsEnabledKey
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.ApkDownloader
import com.metrolist.music.utils.DownloadState
import com.metrolist.music.utils.ReleaseInfo
import com.metrolist.music.utils.Updater
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.updater.AppInstaller
import com.metrolist.music.utils.updater.InstallResult
import com.metrolist.music.utils.updater.InstallerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import rikka.shizuku.Shizuku
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdaterScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (checkForUpdates, onCheckForUpdatesChange) = rememberPreference(CheckForUpdatesKey, true)
    val (updateNotifications, onUpdateNotificationsChange) = rememberPreference(UpdateNotificationsEnabledKey, true)
    val (installerTypeInt, onInstallerTypeChange) = rememberPreference(InstallerTypeKey, 0)
    val installerType = InstallerType.entries.getOrElse(installerTypeInt) { InstallerType.NATIVE }

    val context = LocalContext.current
    val availableInstallers = remember { AppInstaller.getAvailableInstallers(context) }
    var isChecking by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<String?>(null) }
    var releaseInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    var showChangelog by remember { mutableStateOf(false) }
    var changelogContent by remember { mutableStateOf<String?>(null) }
    var checkError by remember { mutableStateOf<String?>(null) }

    // Download state
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }
    var isInstalling by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var showInstallerDialog by remember { mutableStateOf(false) }

    // Permission launcher for install packages
    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (ApkDownloader.canInstallPackages(context)) {
            downloadedApkFile?.let { file ->
                ApkDownloader.installApk(context, file)
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()

    // Check for existing downloaded APK and auto-check if cached update exists
    LaunchedEffect(Unit) {
        ApkDownloader.getDownloadedApk(context)?.let { file ->
            downloadedApkFile = file
            downloadState = DownloadState.Completed(file)
        }

        // Auto-populate from cached release if update is available
        Updater.getCachedLatestRelease()?.let { cached ->
            if (Updater.isUpdateAvailable(BuildConfig.VERSION_NAME, cached.versionName)) {
                latestVersion = cached.versionName
                updateAvailable = true
                changelogContent = cached.description
                releaseInfo = cached
            }
        }
    }

    // Shizuku permission listener
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                onInstallerTypeChange(InstallerType.SHIZUKU.ordinal)
            } else {
                installError = context.getString(R.string.shizuku_permission_required)
            }
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (e: Exception) {
            // Shizuku not available
        }
        onDispose {
            try {
                Shizuku.removeRequestPermissionResultListener(listener)
            } catch (e: Exception) {
                // Shizuku not available
            }
        }
    }

    fun performManualCheck() {
        coroutineScope.launch {
            isChecking = true
            checkError = null
            withContext(Dispatchers.IO) {
                Updater.checkForUpdate(forceRefresh = true).onSuccess { (info, hasUpdate) ->
                    if (info != null) {
                        latestVersion = info.versionName
                        updateAvailable = hasUpdate
                        changelogContent = info.description
                        releaseInfo = info
                    }
                }.onFailure {
                    checkError = context.getString(R.string.failed_to_check_updates, it.message ?: "Unknown error")
                }
            }
            isChecking = false
        }
    }

    fun startDownload() {
        val downloadUrl = releaseInfo?.let { Updater.getDownloadUrlForCurrentVariant(it) }
        if (downloadUrl == null) {
            downloadState = DownloadState.Error(context.getString(R.string.download_url_not_found))
            return
        }

        coroutineScope.launch {
            ApkDownloader.downloadApk(context, downloadUrl).collect { state ->
                downloadState = state
                when (state) {
                    is DownloadState.Downloading -> {
                        downloadProgress = state.progress
                        downloadedBytes = state.downloadedBytes
                        totalBytes = state.totalBytes
                    }
                    is DownloadState.Completed -> {
                        downloadedApkFile = state.file
                    }
                    else -> {}
                }
            }
        }
    }

    fun installUpdate() {
        downloadedApkFile?.let { file ->
            // For NATIVE installer, check permission first
            if (installerType == InstallerType.NATIVE && !ApkDownloader.canInstallPackages(context)) {
                installPermissionLauncher.launch(ApkDownloader.getInstallPermissionIntent(context))
                return
            }

            coroutineScope.launch {
                isInstalling = true
                installError = null
                val result = AppInstaller.install(context, file, installerType)
                isInstalling = false
                when (result) {
                    is InstallResult.Success -> {
                        // Installation completed successfully (for ROOT/SHIZUKU)
                        downloadState = DownloadState.Idle
                        downloadedApkFile = null
                        ApkDownloader.clearDownloadedApk(context)
                    }
                    is InstallResult.RequiresUserAction -> {
                        // User needs to confirm installation (for NATIVE/SESSION)
                        // The system installer UI will be shown
                    }
                    is InstallResult.Error -> {
                        installError = result.message
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Spacer(Modifier.height(4.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.current_version),
            items = listOf(
                Material3SettingsItem(
                    title = {
                        Text(stringResource(R.string.version_format, BuildConfig.VERSION_NAME))
                    },
                    description = {
                        val arch = BuildConfig.ARCHITECTURE
                        val variant = if (BuildConfig.CAST_AVAILABLE) "GMS" else "FOSS"
                        Text("$arch - $variant")
                    }
                )
            )
        )

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.update_settings),
            items = buildList {
                add(
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.check_for_updates)) },
                        icon = painterResource(R.drawable.update),
                        trailingContent = {
                            Switch(
                                checked = checkForUpdates,
                                onCheckedChange = onCheckForUpdatesChange
                            )
                        },
                        onClick = { onCheckForUpdatesChange(!checkForUpdates) }
                    )
                )

                if (checkForUpdates) {
                    add(
                        Material3SettingsItem(
                            title = { Text(stringResource(R.string.update_notifications)) },
                            icon = painterResource(R.drawable.notification),
                            trailingContent = {
                                Switch(
                                    checked = updateNotifications,
                                    onCheckedChange = onUpdateNotificationsChange
                                )
                            },
                            onClick = { onUpdateNotificationsChange(!updateNotifications) }
                        )
                    )
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        // Installer selection - single item that opens dialog
        val currentInstallerInfo = availableInstallers.find { it.type == installerType }
        Material3SettingsGroup(
            title = stringResource(R.string.installer_method),
            items = listOf(
                Material3SettingsItem(
                    title = { Text(stringResource(currentInstallerInfo?.title ?: R.string.installer_native_title)) },
                    description = { Text(stringResource(currentInstallerInfo?.description ?: R.string.installer_native_desc)) },
                    onClick = { showInstallerDialog = true }
                )
            )
        )

        // Installer selection dialog
        if (showInstallerDialog) {
            DefaultDialog(
                onDismiss = { showInstallerDialog = false },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.download),
                        contentDescription = null
                    )
                },
                title = { Text(stringResource(R.string.installer_method)) },
                buttons = {
                    TextButton(onClick = { showInstallerDialog = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                InstallerType.entries.forEach { type ->
                    val info = availableInstallers.find { it.type == type }!!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                when (type) {
                                    InstallerType.ROOT -> {
                                        coroutineScope.launch {
                                            val hasRoot = withContext(Dispatchers.IO) {
                                                AppInstaller.hasRootAccess()
                                            }
                                            if (hasRoot) {
                                                onInstallerTypeChange(type.ordinal)
                                                showInstallerDialog = false
                                            } else {
                                                installError = context.getString(R.string.installer_root_unavailable)
                                            }
                                        }
                                    }
                                    InstallerType.SHIZUKU -> {
                                        if (!AppInstaller.hasShizukuOrSui(context)) {
                                            installError = context.getString(R.string.installer_not_available)
                                        } else if (!AppInstaller.isShizukuAlive()) {
                                            installError = context.getString(R.string.shizuku_not_running)
                                        } else if (AppInstaller.hasShizukuPermission()) {
                                            onInstallerTypeChange(type.ordinal)
                                            showInstallerDialog = false
                                        } else {
                                            try {
                                                Shizuku.requestPermission(0)
                                            } catch (e: Exception) {
                                                installError = context.getString(R.string.shizuku_permission_required)
                                            }
                                        }
                                    }
                                    InstallerType.DHIZUKU -> {
                                        if (!AppInstaller.hasDhizuku(context)) {
                                            installError = context.getString(R.string.installer_not_available)
                                        } else if (AppInstaller.hasDhizukuPermission(context)) {
                                            onInstallerTypeChange(type.ordinal)
                                            showInstallerDialog = false
                                        } else {
                                            // Request Dhizuku permission
                                            try {
                                                Dhizuku.init(context)
                                                Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                                                    override fun onRequestPermission(grantResult: Int) {
                                                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                                            onInstallerTypeChange(type.ordinal)
                                                            showInstallerDialog = false
                                                        } else {
                                                            installError = context.getString(R.string.installer_dhizuku_unavailable)
                                                        }
                                                    }
                                                })
                                            } catch (e: Exception) {
                                                installError = context.getString(R.string.installer_dhizuku_unavailable)
                                            }
                                        }
                                    }
                                    else -> {
                                        onInstallerTypeChange(type.ordinal)
                                        showInstallerDialog = false
                                    }
                                }
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = installerType == type,
                            onClick = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(info.title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(info.description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.check_for_updates_title),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.refresh),
                    title = {
                        if (isChecking) {
                            Text(stringResource(R.string.checking_for_updates))
                        } else if (latestVersion != null) {
                            Text(stringResource(R.string.latest_version_format, latestVersion!!))
                        } else {
                            Text(stringResource(R.string.check_for_updates_button))
                        }
                    },
                    trailingContent = {
                        if (isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else if (updateAvailable) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.arrow_upward),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    onClick = { if (!isChecking) performManualCheck() }
                )
            )
        )

        checkError?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        installError?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.install_failed, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Update available card with download functionality
        AnimatedVisibility(
            visible = updateAvailable && latestVersion != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(Modifier.height(16.dp))

                UpdateDownloadCard(
                    latestVersion = latestVersion ?: "",
                    downloadState = downloadState,
                    downloadProgress = downloadProgress,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    isInstalling = isInstalling,
                    onDownloadClick = { startDownload() },
                    onInstallClick = { installUpdate() },
                    onRetryClick = {
                        downloadState = DownloadState.Idle
                        installError = null
                        startDownload()
                    },
                    onCancelClick = {
                        ApkDownloader.clearDownloadedApk(context)
                        downloadState = DownloadState.Idle
                        downloadedApkFile = null
                        installError = null
                    }
                )

                Spacer(Modifier.height(16.dp))

                // Changelog section
                FilledTonalButton(
                    onClick = { showChangelog = !showChangelog },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(
                            if (showChangelog) R.drawable.expand_less else R.drawable.expand_more
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (showChangelog) stringResource(R.string.hide_changelog) else stringResource(R.string.view_changelog))
                }

                AnimatedVisibility(
                    visible = showChangelog && changelogContent != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            MarkdownText(changelogContent ?: "")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.updater)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}

@Composable
private fun UpdateDownloadCard(
    latestVersion: String,
    downloadState: DownloadState,
    downloadProgress: Float,
    downloadedBytes: Long,
    totalBytes: Long,
    isInstalling: Boolean,
    onDownloadClick: () -> Unit,
    onInstallClick: () -> Unit,
    onRetryClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = downloadProgress,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "download_progress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header with icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = downloadState,
                    contentKey = { state ->
                        // Use class name as key so Downloading doesn't re-animate on progress updates
                        state::class.simpleName
                    },
                    label = "icon_animation"
                ) { state ->
                    when (state) {
                        is DownloadState.Idle -> {
                            Icon(
                                painter = painterResource(R.drawable.download),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        is DownloadState.Downloading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f),
                                strokeWidth = 3.dp
                            )
                        }
                        is DownloadState.Completed -> {
                            Icon(
                                painter = painterResource(R.drawable.done),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        is DownloadState.Error -> {
                            Icon(
                                painter = painterResource(R.drawable.error),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Title
            Text(
                text = when (downloadState) {
                    is DownloadState.Idle -> stringResource(R.string.update_available_title)
                    is DownloadState.Downloading -> stringResource(R.string.downloading_update)
                    is DownloadState.Completed -> stringResource(R.string.download_complete)
                    is DownloadState.Error -> stringResource(R.string.download_failed)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(Modifier.height(4.dp))

            // Version info
            Text(
                text = stringResource(R.string.version_update_info, BuildConfig.VERSION_NAME, latestVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            // Progress section for downloading state
            AnimatedVisibility(
                visible = downloadState is DownloadState.Downloading,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${ApkDownloader.formatBytes(downloadedBytes)} / ${ApkDownloader.formatBytes(totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Error message
            if (downloadState is DownloadState.Error) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = downloadState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (downloadState) {
                    is DownloadState.Idle -> {
                        Button(
                            onClick = onDownloadClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.download),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.download_update),
                                maxLines = 1
                            )
                        }
                    }
                    is DownloadState.Downloading -> {
                        OutlinedButton(
                            onClick = onCancelClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cancel))
                        }
                    }
                    is DownloadState.Completed -> {
                        Button(
                            onClick = onInstallClick,
                            modifier = Modifier.weight(1f),
                            enabled = !isInstalling,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            if (isInstalling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.update),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isInstalling) stringResource(R.string.installing) else stringResource(R.string.install),
                                maxLines = 1
                            )
                        }
                        OutlinedButton(
                            onClick = onCancelClick,
                            modifier = Modifier.weight(1f),
                            enabled = !isInstalling
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                    is DownloadState.Error -> {
                        Button(
                            onClick = onRetryClick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.refresh),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.retry_button))
                        }
                        OutlinedButton(
                            onClick = onCancelClick,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }
}
