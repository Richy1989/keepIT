package org.hyperstarit.keepitapp.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.hyperstarit.keepitapp.AppContainer
import org.hyperstarit.keepitapp.data.DocumentSaver
import org.hyperstarit.keepitapp.data.TwoFactorSetupDto
import org.hyperstarit.keepitapp.data.TwoFactorStatusDto
import org.hyperstarit.keepitapp.data.apiErrorMessage
import org.hyperstarit.keepitapp.ui.theme.KeepItColors
import org.hyperstarit.keepitapp.ui.theme.accentButtonColors
import retrofit2.HttpException
import java.io.IOException
import kotlin.math.floor

/**
 * Two-factor authentication with an authenticator app, the phone twin of the web's card in
 * Security. Off, it asks for the password, then offers the new key three ways — opened straight in
 * an authenticator app on this phone, as a QR code for an app on another one, and as text — and
 * turns on once a code from the app comes back right. The recovery codes are shown once, then and
 * whenever new ones are made; they can be copied or saved to Documents/keepIT. On, it says how
 * many are left and offers new ones or turning it off, both behind the password and a code.
 *
 * Online only, like the rest of the account. The key and the codes survive a rotation
 * ([rememberSaveable]): codes lost to a turned phone would be codes the user never saw.
 */
@Composable
fun TwoFactorScreen(container: AppContainer, onBack: () -> Unit) {
    // The step in progress, saved as text: a setup as its JSON, recovery codes one per line.
    var setupJson by rememberSaveable { mutableStateOf<String?>(null) }
    var codesText by rememberSaveable { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable { mutableStateOf<ConfirmAction?>(null) }

    var reload by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<Result<TwoFactorStatusDto>?>(null) }
    LaunchedEffect(reload) {
        status = null
        status = container.session.twoFactorStatus()
    }

    SettingsPage(title = "Two-factor authentication", onBack = onBack) {
        val setup = setupJson?.let { setupCodec.decodeFromString<TwoFactorSetupDto>(it) }
        val codes = codesText?.lines()
        when {
            codes != null -> RecoveryCodesStep(
                codes = codes,
                onDone = {
                    codesText = null
                    reload++
                },
            )

            setup != null -> ScanStep(
                container = container,
                setup = setup,
                onEnabled = {
                    setupJson = null
                    codesText = it.joinToString("\n")
                },
                onCancel = { setupJson = null },
            )

            else -> StatusStep(
                container = container,
                status = status,
                onRetry = { reload++ },
                onStarted = { setupJson = setupCodec.encodeToString(TwoFactorSetupDto.serializer(), it) },
                onConfirm = { confirming = it },
            )
        }
    }

    confirming?.let { action ->
        ConfirmBothFactorsDialog(
            container = container,
            action = action,
            onDismiss = { confirming = null },
            onDone = { newCodes ->
                confirming = null
                if (newCodes != null) codesText = newCodes.joinToString("\n") else reload++
            },
        )
    }
}

/** What the password-and-code dialog is confirming. */
private enum class ConfirmAction { DISABLE, RENEW }

private val setupCodec = Json { ignoreUnknownKeys = true }

/** The status, and from it the way on: set up (off), or new codes and turning off (on). */
@Composable
private fun StatusStep(
    container: AppContainer,
    status: Result<TwoFactorStatusDto>?,
    onRetry: () -> Unit,
    onStarted: (TwoFactorSetupDto) -> Unit,
    onConfirm: (ConfirmAction) -> Unit,
) {
    SettingsGroup {
        Column(modifier = SettingsContentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val result = status
            when {
                result == null -> Text("Loading…", color = KeepItColors.TextMuted, fontSize = 14.sp)

                result.isFailure -> {
                    Text(
                        text = twoFactorFailure(result.exceptionOrNull()!!, "Couldn't load the two-factor status."),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp,
                    )
                    OutlinedButton(onClick = onRetry) { Text("Try again", fontSize = 13.sp) }
                }

                result.getOrThrow().enabled -> {
                    val left = result.getOrThrow().recoveryCodesLeft
                    Text(
                        text = "On. Signing in asks for a code from your authenticator app as well as your password.",
                        color = KeepItColors.Text,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = when (left) {
                            0 -> "You have no recovery codes left. Make new ones, or losing your phone locks you out."
                            1 -> "1 recovery code left. Make new ones soon."
                            2 -> "2 recovery codes left. Make new ones soon."
                            else -> "$left recovery codes left."
                        },
                        color = if (left <= 2) MaterialTheme.colorScheme.error else KeepItColors.TextMuted,
                        fontSize = 13.sp,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onConfirm(ConfirmAction.RENEW) }) {
                            Text("New recovery codes", fontSize = 13.sp)
                        }
                        Button(
                            onClick = { onConfirm(ConfirmAction.DISABLE) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("Turn off", fontSize = 13.sp)
                        }
                    }
                }

                else -> StartSetup(container = container, onStarted = onStarted)
            }
        }
    }
}

/** Off: what it does, and the password to start setting it up. */
@Composable
private fun StartSetup(container: AppContainer, onStarted: (TwoFactorSetupDto) -> Unit) {
    val scope = rememberCoroutineScope()
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start() {
        if (busy || password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            container.session.startTwoFactorSetup(password)
                .onSuccess {
                    password = ""
                    onStarted(it)
                }
                .onFailure { error = twoFactorFailure(it, "Could not start the setup.") }
            busy = false
        }
    }

    Text(
        text = "Off. Turn it on to ask for a code from an authenticator app, such as Aegis, 2FAS or " +
            "Google Authenticator, each time you sign in. Someone who learns your password still " +
            "can't get in without your phone.",
        color = KeepItColors.TextMuted,
        fontSize = 13.sp,
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { start() }),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
    Button(onClick = ::start, enabled = !busy && password.isNotEmpty(), colors = accentButtonColors()) {
        BusyLabel(busy, "Set up")
    }
}

/**
 * The new key, offered three ways, then the code that proves the app has it. Opening the
 * `otpauth://` link is first: on a phone, the authenticator is most often on the same phone, where
 * a QR code can't be scanned.
 */
@Composable
private fun ScanStep(
    container: AppContainer,
    setup: TwoFactorSetupDto,
    onEnabled: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var noApp by rememberSaveable { mutableStateOf(false) }

    fun enable() {
        if (busy || code.isBlank()) return
        busy = true
        error = null
        scope.launch {
            container.session.enableTwoFactor(code)
                .onSuccess(onEnabled)
                .onFailure { error = twoFactorFailure(it, "Could not turn on two-factor authentication.") }
            busy = false
        }
    }

    SettingsGroup(label = "1. Add keepIT to your authenticator app") {
        Column(modifier = SettingsContentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    noApp = try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, setup.authenticatorUri.toUri()))
                        false
                    } catch (_: ActivityNotFoundException) {
                        true
                    }
                },
                colors = accentButtonColors(),
            ) {
                Text("Open in authenticator app")
            }
            if (noApp) {
                Text(
                    text = "No app on this phone opens authenticator links. Install one, or scan the code " +
                        "below with the phone that has yours.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
            }
            Text(
                text = "Authenticator on another phone? Scan this code with it:",
                color = KeepItColors.TextMuted,
                fontSize = 13.sp,
            )
            QrCodeImage(
                rows = setup.qrCode,
                modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth(),
            )
            Text(text = "Or type this key into the app:", color = KeepItColors.TextMuted, fontSize = 13.sp)
            SelectionContainer {
                Text(
                    text = setup.sharedKey,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    color = KeepItColors.Text,
                )
            }
            OutlinedButton(onClick = { copySensitive(context, "keepIT authenticator key", setup.sharedKey.replace(" ", "")) }) {
                Text("Copy key", fontSize = 13.sp)
            }
        }
    }

    SettingsGroup(label = "2. Enter the code it shows") {
        Column(modifier = SettingsContentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Six-digit code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { enable() }),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::enable, enabled = !busy && code.isNotBlank(), colors = accentButtonColors()) {
                    BusyLabel(busy, "Turn on")
                }
                TextButton(onClick = onCancel, enabled = !busy) {
                    Text("Cancel", color = KeepItColors.TextMuted)
                }
            }
        }
    }
}

/** Recovery codes, shown this once: copy them, save them to Documents/keepIT, or write them down. */
@Composable
private fun RecoveryCodesStep(codes: List<String>, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<String?>(null) }
    val text = "keepIT recovery codes\n" +
        "Each one signs you in once in place of a code from your authenticator app.\n\n" +
        codes.joinToString("\n") + "\n"

    SettingsGroup(label = "Recovery codes") {
        Column(modifier = SettingsContentPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Two-factor authentication is on. Save these codes somewhere safe, away from this " +
                    "phone. If you lose it, each one signs you in once. They won't be shown again.",
                color = KeepItColors.Text,
                fontSize = 14.sp,
            )
            SelectionContainer {
                Column {
                    codes.forEach {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = KeepItColors.Text)
                    }
                }
            }
            saved?.let { Text(it, color = KeepItColors.AccentInk, fontSize = 13.sp) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copySensitive(context, "keepIT recovery codes", codes.joinToString("\n")) }) {
                    Text("Copy", fontSize = 13.sp)
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        saved = if (DocumentSaver.save(context, "keepIT recovery codes.txt", text, mime = "text/plain")) {
                            "Saved to Documents/keepIT."
                        } else {
                            "Couldn't save the file. Copy the codes or write them down instead."
                        }
                    }
                }) {
                    Text("Save to Documents", fontSize = 13.sp)
                }
                Button(onClick = onDone, colors = accentButtonColors()) {
                    Text("I've saved them", fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * The password and a code, for turning two-factor off or making new recovery codes: a phone left
 * unlocked can do neither. [onDone] gets the new codes, or null when it was turned off.
 */
@Composable
private fun ConfirmBothFactorsDialog(
    container: AppContainer,
    action: ConfirmAction,
    onDismiss: () -> Unit,
    onDone: (List<String>?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun confirm() {
        if (busy || password.isEmpty() || code.isBlank()) return
        busy = true
        error = null
        scope.launch {
            val result = when (action) {
                ConfirmAction.DISABLE -> container.session.disableTwoFactor(password, code).map { null }
                ConfirmAction.RENEW -> container.session.newRecoveryCodes(password, code)
            }
            result
                .onSuccess(onDone)
                .onFailure { error = twoFactorFailure(it, "That didn't work. Check the password and the code.") }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = KeepItColors.Surface,
        title = { Text(if (action == ConfirmAction.DISABLE) "Turn off two-factor?" else "New recovery codes?") },
        text = {
            Column {
                Text(
                    text = if (action == ConfirmAction.DISABLE) {
                        "Signing in will ask for your password only. Your authenticator entry and recovery " +
                            "codes stop working."
                    } else {
                        "Your current recovery codes stop working, and you get ten new ones."
                    },
                    color = KeepItColors.TextMuted,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code from your app, or a recovery code") },
                    singleLine = true,
                    // A password keyboard, shown in clear: it neither suggests nor learns a recovery code.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::confirm,
                enabled = !busy && password.isNotEmpty() && code.isNotBlank(),
                colors = if (action == ConfirmAction.DISABLE) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    accentButtonColors()
                },
            ) {
                BusyLabel(busy, if (action == ConfirmAction.DISABLE) "Turn off" else "Make new codes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Cancel", color = KeepItColors.TextMuted)
            }
        },
    )
}

@Composable
private fun BusyLabel(busy: Boolean, label: String) {
    if (busy) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
    } else {
        Text(label)
    }
}

/** A failure as the user should read it: an older server, no connection, or the server's own words. */
private fun twoFactorFailure(e: Throwable, fallback: String): String = when {
    e is HttpException && e.code() == 404 ->
        "This server doesn't offer two-factor authentication yet. It needs keepIT 0.9.2 or newer."
    e is IOException -> "Can't reach your server. Two-factor settings need a connection."
    else -> apiErrorMessage(e, fallback)
}

/**
 * Copies a secret. Marked sensitive, so Android 13 and later keep it out of the clipboard preview
 * and the keyboard's suggestions; the system shows its own confirmation.
 */
private fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

/**
 * Black on white, whatever the theme: scanners look for dark modules on a light ground, and many
 * can't read the inverse. The one place these screens use colours of their own.
 */
private val QrDark = Color.Black
private val QrLight = Color.White

/**
 * A QR code from the rows of modules the server sends ([TwoFactorSetupDto.qrCode]). Modules are
 * whole pixels at whole-pixel positions: centred on half a pixel, every edge was anti-aliased into
 * a grey seam between neighbouring rows, which a scanner reading a sharp image could stumble on.
 * The light border is part of the rows, and the white fills the rest of the square.
 */
@Composable
fun QrCodeImage(rows: List<String>, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = "QR code to add keepIT to an authenticator app" },
    ) {
        drawRect(QrLight)
        val modules = rows.size
        if (modules == 0) return@Canvas
        val cell = floor(size.minDimension / modules)
        val origin = floor((size.minDimension - cell * modules) / 2)
        for (run in qrRuns(rows)) {
            drawRect(
                color = QrDark,
                topLeft = Offset(origin + run.start * cell, origin + run.row * cell),
                size = Size(run.length * cell, cell),
            )
        }
    }
}

/** A run of dark modules in one row of a QR code. */
internal data class QrRun(val row: Int, val start: Int, val length: Int)

/** The dark runs of [rows] ('1' dark, '0' light), row by row: one rectangle each to draw. */
internal fun qrRuns(rows: List<String>): List<QrRun> = buildList {
    rows.forEachIndexed { y, row ->
        var x = 0
        while (x < row.length) {
            if (row[x] != '1') {
                x++
                continue
            }
            val start = x
            while (x < row.length && row[x] == '1') x++
            add(QrRun(y, start, x - start))
        }
    }
}
