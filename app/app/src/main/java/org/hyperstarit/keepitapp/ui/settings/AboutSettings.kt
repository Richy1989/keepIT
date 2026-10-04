package org.hyperstarit.keepitapp.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.R
import org.hyperstarit.keepitapp.data.orNullUnlessCancelled
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/** This build's version name ("0.8.5"), or "unknown" if the package manager won't say. */
@Composable
fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }
}

/**
 * About keepIT, the twin of the web Settings page's About section and in the same words
 * ([AboutContent]): what the app is, the app and server versions — so a self-hoster can spot an
 * outdated APK or container at a glance — the project's links, and the open-source projects it is
 * built on, with thanks. The server version row goes when there is no server (standalone mode).
 */
@Composable
fun AboutSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val standalone by container.appMode.standalone.collectAsState()
    val appVersion = rememberAppVersion()
    var serverVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(standalone) {
        if (!standalone) serverVersion = orNullUnlessCancelled { container.apiClient.api.meta() }?.version
    }

    // A phone with no browser has nothing to open a link in; the tap then simply does nothing.
    val uriHandler = LocalUriHandler.current
    fun open(url: String) {
        runCatching { uriHandler.openUri(url) }
    }

    SettingsPage(title = "About", onBack = onBack) {
        AboutHeader(appVersion)

        SettingsGroup {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = SettingsContentPadding) {
                AboutContent.description.forEach { paragraph ->
                    Text(paragraph, color = KeepItColors.TextMuted, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }

        SettingsGroup(label = "Versions") {
            SettingsRow(
                icon = Icons.Outlined.PhoneAndroid,
                title = "App",
                trailing = { SettingsValue(appVersion) },
            )
            if (!standalone) {
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.Dns,
                    title = "Server",
                    trailing = { SettingsValue(serverVersion ?: "unavailable (offline?)") },
                )
            }
        }

        SettingsGroup(label = "Links") {
            AboutContent.links.forEachIndexed { index, link ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    icon = linkIcon(link.id),
                    title = link.label,
                    summary = link.detail,
                    onClick = { open(link.url) },
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = "Opens in your browser",
                            tint = KeepItColors.TextFaint,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }

        SettingsGroup(label = "Open-source software") {
            Text(
                text = AboutContent.THANKS,
                color = KeepItColors.TextMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = SettingsContentPadding,
            )
        }
        CreditGroup(label = "Used in this app", credits = AboutContent.androidCredits, onOpen = ::open)
        CreditGroup(label = "Used in the server", credits = AboutContent.serverCredits, onOpen = ::open)

        Text(
            text = "${AboutContent.COPYRIGHT} · Released under the MIT License",
            color = KeepItColors.TextFaint,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** The page's head: the launcher icon, the name, the tagline and this build's version. */
@Composable
private fun AboutHeader(appVersion: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
    ) {
        AppIcon(size = 80.dp)
        Text(
            text = AboutContent.NAME,
            color = KeepItColors.Text,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = AboutContent.TAGLINE,
            color = KeepItColors.TextMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "Version $appVersion",
            color = KeepItColors.TextFaint,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * The launcher icon, drawn from its two layers: Compose can't draw the adaptive icon itself, and
 * the layers are what `docs/brand/render_icons.py` writes, so this stays the real icon. A launcher
 * shows the middle two thirds of a 108dp layer, hence the 1.5× layers cropped to the tile.
 */
@Composable
private fun AppIcon(size: Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.5f),
        )
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}

/** One group of credits under [label]: each project, what it does here and its licence. */
@Composable
private fun CreditGroup(label: String, credits: List<Credit>, onOpen: (String) -> Unit) {
    SettingsGroup(label = label) {
        credits.forEachIndexed { index, credit ->
            if (index > 0) SettingsDivider(inset = 16.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(credit.url) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(credit.name, color = KeepItColors.Text, fontSize = 15.sp)
                    Text(
                        text = credit.role,
                        color = KeepItColors.TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = credit.license,
                    color = KeepItColors.TextFaint,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .background(KeepItColors.Canvas, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** The icon beside each link, by its id — the same choices as the web's About section. */
private fun linkIcon(id: String): ImageVector = when (id) {
    "source" -> Icons.Outlined.Code
    "issues" -> Icons.Outlined.ChatBubbleOutline
    "releases" -> Icons.Outlined.LocalOffer
    "license" -> Icons.Outlined.Description
    "privacy" -> Icons.Outlined.PrivacyTip
    "support" -> Icons.Outlined.FavoriteBorder
    else -> Icons.AutoMirrored.Outlined.OpenInNew
}
