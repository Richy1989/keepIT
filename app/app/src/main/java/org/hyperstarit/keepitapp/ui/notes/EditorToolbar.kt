package org.hyperstarit.keepitapp.ui.notes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.hyperstarit.keepitapp.ui.markdown.MarkdownAction
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import org.hyperstarit.keepitapp.ui.theme.NotePalette

/**
 * The editor's tools, as a floating pill: rounded, lifted off the note, and kept clear of the
 * system navigation bar and — while it is up — the keyboard.
 *
 * It replaces two stacked, full-width rows of 20–24 dp icons that sat directly on the gesture bar.
 * The app draws edge to edge, and those rows only ever made room for the keyboard, so with the
 * keyboard down they shared the bottom strip with the gesture handle (or sat under the buttons of
 * 3-button navigation). Sizes follow Material 3's floating toolbar: a 64 dp pill of 48 dp targets
 * with 24 dp icons, held off the screen's edge.
 *
 * Only tools that put something *into* the note live here. What acts *on* the note — reminder,
 * share, pin, archive, trash — is in the top bar, which also takes trash out of reach of a slip
 * aimed at the camera. And it is one row, never two: "Aa" swaps the tools for the Markdown
 * formatting buttons and ✕ swaps them back, text notes only, since a checklist has no body to
 * format.
 */
@Composable
internal fun EditorToolbar(
    /** A text note: "Aa" and the formatting buttons are offered. */
    canFormat: Boolean,
    isChecklist: Boolean,
    /** The note has a colour of its own, which lights up the palette button. */
    hasColor: Boolean,
    recording: Boolean,
    onAdd: () -> Unit,
    onColor: () -> Unit,
    onToggleChecklist: () -> Unit,
    onToggleRecording: () -> Unit,
    onFormat: (MarkdownAction) -> Unit,
) {
    var formatting by remember { mutableStateOf(false) }
    // A switch to checklist drops the formatting buttons with it, and a switch back starts on the
    // tools rather than reopening them unasked.
    LaunchedEffect(canFormat) { if (!canFormat) formatting = false }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The navigation bar first, then whatever of the keyboard reaches above it: each
            // modifier consumes what it pads, so the two never add up to a double gap.
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = KeepItColors.Elevated,
            border = BorderStroke(1.dp, KeepItColors.BorderStrong),
            shadowElevation = 6.dp,
        ) {
            AnimatedContent(
                targetState = formatting && canFormat,
                transitionSpec = {
                    val offset = if (targetState) 1 else -1
                    (fadeIn(tween(160)) + slideInVertically(tween(160)) { offset * it / 3 }) togetherWith
                        (fadeOut(tween(110)) + slideOutVertically(tween(110)) { -offset * it / 3 }) using
                        SizeTransform(clip = false)
                },
                label = "editor toolbar",
            ) { showFormatting ->
                val formatScroll = rememberScrollState()
                Row(
                    // Eight formatting buttons and ✕ are wider than a phone: that set scrolls
                    // inside the pill rather than spilling off the screen, and fades at whichever
                    // end has more beyond it — cut off at a clean edge, the last two looked absent.
                    modifier = Modifier
                        .height(64.dp)
                        .then(
                            if (showFormatting) {
                                Modifier.fadingEdges(formatScroll).horizontalScroll(formatScroll)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (showFormatting) 0.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showFormatting) {
                        ToolButton(Icons.Filled.Close, "Close formatting") { formatting = false }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(24.dp)
                                .background(KeepItColors.BorderStrong),
                        )
                        ToolButton(Icons.Filled.FormatBold, "Bold") { onFormat(MarkdownAction.BOLD) }
                        ToolButton(Icons.Filled.FormatItalic, "Italic") { onFormat(MarkdownAction.ITALIC) }
                        ToolButton(Icons.Filled.StrikethroughS, "Strikethrough") { onFormat(MarkdownAction.STRIKE) }
                        ToolButton(Icons.Filled.Title, "Heading") { onFormat(MarkdownAction.HEADING) }
                        ToolButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullet list") {
                            onFormat(MarkdownAction.BULLET)
                        }
                        ToolButton(Icons.Filled.FormatListNumbered, "Numbered list") {
                            onFormat(MarkdownAction.ORDERED)
                        }
                        ToolButton(Icons.Filled.Link, "Link") { onFormat(MarkdownAction.LINK) }
                        ToolButton(Icons.Filled.Code, "Code") { onFormat(MarkdownAction.CODE) }
                    } else {
                        ToolButton(Icons.Filled.Add, "Add to note", onClick = onAdd)
                        if (canFormat) {
                            ToolButton(Icons.Filled.TextFormat, "Formatting") { formatting = true }
                        }
                        ToolButton(Icons.Filled.Palette, "Background color", active = hasColor, onClick = onColor)
                        ToolButton(
                            icon = Icons.Filled.Checklist,
                            label = if (isChecklist) "Switch to text" else "Switch to checklist",
                            active = isChecklist,
                            onClick = onToggleChecklist,
                        )
                        // Voice notes are Android's alone (browsers cannot record over the plain
                        // http a LAN server often is), so the microphone stays one tap away.
                        ToolButton(
                            icon = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                            label = if (recording) "Stop recording" else "Record a voice note",
                            active = recording,
                            activeColor = MaterialTheme.colorScheme.error,
                            onClick = onToggleRecording,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fades the ends of a horizontally scrolling row wherever there is more to scroll to. Applied
 * outside [horizontalScroll], so it masks the visible viewport rather than the scrolled content.
 */
private fun Modifier.fadingEdges(scroll: ScrollState, width: Dp = 28.dp): Modifier = this
    // Offscreen, so DstIn masks this row alone instead of punching through to the note behind.
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val edge = width.toPx()
        if (scroll.canScrollBackward) {
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, endX = edge),
                size = Size(edge, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.canScrollForward) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startX = size.width - edge,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - edge, 0f),
                size = Size(edge, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * One tool in the pill. A tool that is *on* — checklist mode, a colour, a live microphone — gets a
 * tinted fill behind its icon, not only a tint on it: at a glance the fill reads where a yellow
 * icon among grey ones does not.
 */
@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    activeColor: Color = KeepItColors.Accent,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) activeColor.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = if (active) activeColor else KeepItColors.TextMuted,
        ),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
    }
}

/**
 * What "+" offers: a photo, images from the gallery, or a voice note — labelled rows rather than
 * three more bare icons. The sheet slides shut before acting, because the camera and the photo
 * picker are activities of their own and would otherwise open over a sheet still on its way out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToNoteSheet(
    /** The note holds [MAX_IMAGES_PER_NOTE] images, queued ones included: no more can be added. */
    imagesAtLimit: Boolean,
    recording: Boolean,
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickImages: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = KeepItColors.Surface,
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            SheetTitle("Add to note")
            SheetAction(Icons.Filled.PhotoCamera, "Take photo", enabled = !imagesAtLimit) {
                closeThen(onTakePhoto)
            }
            SheetAction(Icons.Filled.Image, "Add images", enabled = !imagesAtLimit) {
                closeThen(onPickImages)
            }
            SheetAction(
                icon = if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                label = if (recording) "Stop recording" else "Record a voice note",
            ) { closeThen(onToggleRecording) }
            if (imagesAtLimit) {
                Text(
                    text = "This note holds $MAX_IMAGES_PER_NOTE images, the most it can have.",
                    color = KeepItColors.TextFaint,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The note's background, as large swatches with their names. Picking one applies it at once and
 * leaves the sheet open: the note recolours behind it, so trying a few is a tap each rather than a
 * reopen each. Same swatches, same order as the web ColorPicker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteColorSheet(
    /** The note's colour key, or null for the default surface. */
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = KeepItColors.Surface) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            SheetTitle("Background")
            NotePalette.chunked(5).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    row.forEach { option ->
                        val isDefault = option.key == "default"
                        val isSelected = (selected ?: "default") == option.key
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(64.dp)
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { onPick(if (isDefault) null else option.key) },
                                )
                                .padding(vertical = 4.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(option.bg, CircleShape)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        // The default swatch is the sheet's own colour: its usual
                                        // border would leave it an invisible circle.
                                        color = when {
                                            isSelected -> KeepItColors.Accent
                                            isDefault -> KeepItColors.BorderStrong
                                            else -> option.border
                                        },
                                        shape = CircleShape,
                                    ),
                            ) {
                                when {
                                    isSelected -> Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = KeepItColors.Accent,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    isDefault -> Icon(
                                        Icons.Filled.FormatColorReset,
                                        contentDescription = null,
                                        tint = KeepItColors.TextFaint,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Text(
                                text = option.label,
                                color = if (isSelected) KeepItColors.Text else KeepItColors.TextFaint,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        color = KeepItColors.Text,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    )
}

/** A labelled row in a sheet: a 56 dp target, icon then text, like a Material list item. */
@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp)
            .alpha(if (enabled) 1f else 0.38f),
    ) {
        Icon(icon, contentDescription = null, tint = KeepItColors.TextMuted, modifier = Modifier.size(24.dp))
        Text(
            text = label,
            color = KeepItColors.Text,
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}
