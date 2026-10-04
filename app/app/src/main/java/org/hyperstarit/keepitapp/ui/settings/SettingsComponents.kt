package org.hyperstarit.keepitapp.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.hyperstarit.keepitapp.ui.theme.CardShape
import org.hyperstarit.keepitapp.ui.theme.KeepItColors

/*
 * The building blocks every settings page is made of, so the pages read as one screen: a frame
 * ([SettingsPage]), rounded cards of rows ([SettingsGroup] + [SettingsRow]) and the account card
 * that heads the top level ([SettingsAccountCard]). Colours come from [KeepItColors] only, so each
 * page follows the phone's theme like the rest of the app.
 */

/**
 * The frame of a settings page: a back arrow and [title] over a scrolling column on the canvas.
 * [content] is a run of [SettingsGroup]s; the frame spaces them evenly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = KeepItColors.Text,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = KeepItColors.TextMuted,
                        )
                    }
                },
                title = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            content = content,
        )
    }
}

/**
 * A rounded card of related rows under an optional small-caps [label]. Rows don't draw their own
 * separators — put a [SettingsDivider] between two — so a group can also hold free content, such as
 * a form, padded with [SettingsContentPadding].
 */
@Composable
fun SettingsGroup(label: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (label != null) {
            Text(
                text = label.uppercase(),
                color = KeepItColors.TextFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Surface(
            color = KeepItColors.Surface,
            shape = CardShape,
            border = BorderStroke(1.dp, KeepItColors.BorderSubtle),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

/** The inset a [SettingsGroup] gives free content (text, a form) so it lines up with the rows. */
val SettingsContentPadding = Modifier.padding(16.dp)

/**
 * A hairline between two rows of a [SettingsGroup], starting after the rows' icons — or at [inset]
 * for rows without one.
 */
@Composable
fun SettingsDivider(inset: Dp = 68.dp) {
    HorizontalDivider(color = KeepItColors.BorderSubtle, modifier = Modifier.padding(start = inset))
}

/**
 * One setting: a tinted [icon], the [title] and an optional [summary] of its current state, and
 * whatever sits at the end ([trailing] — a value, a status). A row with [onClick] is tappable;
 * [chevron] adds the arrow that says it opens a page of its own.
 *
 * [tint] colours the icon and its circle: the accent ink normally, the error colour for a row that
 * destroys something (sign out, erase) or one that needs attention.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    summaryColor: Color = KeepItColors.TextMuted,
    titleColor: Color = KeepItColors.Text,
    tint: Color = KeepItColors.AccentInk,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SettingsIcon(icon, tint)
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, color = titleColor, fontSize = 15.sp)
            if (summary != null) {
                Text(
                    text = summary,
                    color = summaryColor,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = 12.dp)) { trailing() }
        }
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = KeepItColors.TextFaint,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** A row's value at its end ("Dim", a version number). */
@Composable
fun SettingsValue(text: String, color: Color = KeepItColors.TextMuted) {
    Text(text, color = color, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A row's icon in its tinted circle. */
@Composable
fun SettingsIcon(icon: ImageVector, tint: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * The card at the top of Settings: who this is, or that there is no account. [initial] fills the
 * avatar circle — tinted like the rows' icons, since the drawer's elevated grey vanishes on the
 * light theme's white card — or [icon] does when there's no one to name.
 */
@Composable
fun SettingsAccountCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    initial: String? = null,
    icon: ImageVector? = null,
) {
    Surface(
        color = KeepItColors.Surface,
        shape = CardShape,
        border = BorderStroke(1.dp, KeepItColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onClick).padding(16.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(KeepItColors.AccentInk.copy(alpha = 0.14f)),
            ) {
                when {
                    initial != null -> Text(
                        text = initial,
                        color = KeepItColors.AccentInk,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                    )
                    icon != null -> Icon(icon, contentDescription = null, tint = KeepItColors.AccentInk)
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    text = title,
                    color = KeepItColors.Text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = KeepItColors.TextMuted,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = KeepItColors.TextFaint,
            )
        }
    }
}

/**
 * A counter that goes up every time the screen resumes. Permission state changes in the system's
 * settings, not in this process, so a screen showing it re-reads it keyed on this — and is right
 * the moment the user comes back.
 */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return tick
}
