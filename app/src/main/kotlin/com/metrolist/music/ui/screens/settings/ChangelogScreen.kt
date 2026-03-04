/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.BuildConfig
import com.metrolist.music.utils.ReleaseInfo
import com.metrolist.music.utils.Updater

private val markdownLinkRegex = Regex("(@[a-zA-Z0-9_-]+)|(https?://[\\w-]+(\\.[\\w-]+)+[\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])")
private val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
private val admonitionRegex = Regex("^>\\s*\\[!(WARNING|NOTE|TIP|IMPORTANT|CAUTION)]\\s*$", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChangelogScreen(
    onDismiss: () -> Unit
) {
    var releases by remember { mutableStateOf<List<ReleaseInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        Updater.getAllReleases().onSuccess { allReleases ->
            releases = allReleases.filter { release ->
                Updater.compareVersions(BuildConfig.VERSION_NAME, release.tagName) >= 0
            }
            isLoading = false
        }.onFailure {
            isLoading = false
        }
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false
    )

    val showFab by remember {
        derivedStateOf { sheetState.targetValue != SheetValue.Hidden }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.changelog),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                item {
                    val density = LocalDensity.current
                    val stroke = remember(density) {
                        Stroke(width = with(density) { 3.dp.toPx() }, cap = StrokeCap.Round)
                    }
                    LinearWavyProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                        stroke = stroke,
                        trackStroke = stroke,
                        amplitude = { 1f }
                    )
                }

                if (isLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (releases.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text(text = stringResource(R.string.changelog_empty))
                        }
                    }
                } else {
                    items(releases) { release ->
                        ReleaseItem(release)
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showFab,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                val githubReleasesUrl = stringResource(R.string.github_releases_url)
                ExtendedFloatingActionButton(
                    onClick = { uriHandler.openUri(githubReleasesUrl) },
                    icon = { Icon(painterResource(R.drawable.github), contentDescription = null, modifier = Modifier.size(24.dp)) },
                    text = { Text(stringResource(R.string.view_on_github)) },
                    containerColor = MaterialTheme.colorScheme.onPrimary,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun ReleaseItem(release: ReleaseInfo) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape
            ) {
                Text(
                    text = release.tagName,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Text(
                text = release.releaseDate.split("T").firstOrNull() ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                MarkdownText(release.description)
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
fun MarkdownText(text: String) {
    val lines = text.split("\n")
    val uriHandler = LocalUriHandler.current

    var currentAdmonition: String? = null
    val admonitionContent = mutableListOf<String>()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmedLine = line.trim()

            // Check for GitHub admonition start
            val admonitionMatch = admonitionRegex.find(trimmedLine)
            if (admonitionMatch != null) {
                currentAdmonition = admonitionMatch.groupValues[1].uppercase()
                admonitionContent.clear()
                i++
                // Collect admonition content (lines starting with >)
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    val contentLine = lines[i].trim().removePrefix(">").trim()
                    if (contentLine.isNotBlank()) {
                        admonitionContent.add(contentLine)
                    }
                    i++
                }
                // Render admonition
                AdmonitionBlock(type = currentAdmonition, content = admonitionContent.joinToString(" "))
                currentAdmonition = null
                continue
            }

            // Skip empty lines
            if (trimmedLine.isBlank()) {
                i++
                continue
            }

            // Headers
            if (trimmedLine.startsWith("#")) {
                val level = trimmedLine.takeWhile { it == '#' }.length
                val headerText = trimmedLine.substring(level).trim()
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = headerText,
                        style = when (level) {
                            1 -> MaterialTheme.typography.headlineMedium
                            2 -> MaterialTheme.typography.headlineSmall
                            else -> MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val isListItem = trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ")
                val contentText = if (isListItem) {
                    trimmedLine.substring(2).trim()
                } else {
                    trimmedLine
                }

                val annotatedString = buildStyledText(contentText)

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (isListItem) {
                            Text(
                                text = stringResource(R.string.list_bullet),
                                modifier = Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        ClickableText(
                            text = annotatedString,
                            style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            onClick = { offset ->
                                annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                    .firstOrNull()?.let { annotation ->
                                        uriHandler.openUri(annotation.item)
                                    }
                            }
                        )
                    }

                    if (isListItem) {
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
            i++
        }
    }
}

@Composable
private fun buildStyledText(text: String): AnnotatedString {
    return buildAnnotatedString {
        var remaining = text
        var currentIndex = 0

        while (remaining.isNotEmpty()) {
            // Find the next match (bold or link)
            val boldMatch = boldRegex.find(remaining)
            val linkMatch = markdownLinkRegex.find(remaining)

            val nextMatch = listOfNotNull(boldMatch, linkMatch)
                .minByOrNull { it.range.first }

            if (nextMatch == null) {
                append(remaining)
                break
            }

            // Append text before match
            append(remaining.substring(0, nextMatch.range.first))

            when (nextMatch) {
                boldMatch -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(boldMatch.groupValues[1])
                    }
                }
                linkMatch -> {
                    val match = linkMatch.value
                    val link = if (match.startsWith("@")) "https://github.com/${match.substring(1)}" else match

                    pushStringAnnotation(tag = "URL", annotation = link)
                    withStyle(SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = if (match.startsWith("@")) FontWeight.Bold else FontWeight.Normal,
                        textDecoration = if (match.startsWith("@")) TextDecoration.None else TextDecoration.Underline
                    )) {
                        append(match)
                    }
                    pop()
                }
            }

            remaining = remaining.substring(nextMatch.range.last + 1)
        }
    }
}

@Composable
private fun AdmonitionBlock(type: String, content: String) {
    val (containerColor, contentColor, icon) = when (type) {
        "WARNING", "CAUTION" -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            R.drawable.error
        )
        "NOTE" -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            R.drawable.info
        )
        "TIP" -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            R.drawable.info
        )
        "IMPORTANT" -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            R.drawable.info
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            R.drawable.info
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = type,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = type,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }
        }
    }
}
