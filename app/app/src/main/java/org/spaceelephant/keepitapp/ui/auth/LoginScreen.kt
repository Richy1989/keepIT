package org.spaceelephant.keepitapp.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.spaceelephant.keepitapp.data.SessionRepository
import org.spaceelephant.keepitapp.data.apiErrorMessage
import org.spaceelephant.keepitapp.ui.theme.KeepItColors

/**
 * Sign-in / sign-up, the phone twin of the web's AuthPage: a centered card on the dark canvas with
 * the keepIT wordmark. Adds a server-URL field (self-hosted deployments differ per user; the
 * emulator talks to the host machine via http://10.0.2.2:5025) persisted for next launch.
 */
@Composable
fun LoginScreen(session: SessionRepository) {
    val scope = rememberCoroutineScope()

    var serverUrl by rememberSaveable { mutableStateOf(session.serverUrl ?: "") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var registerMode by rememberSaveable { mutableStateOf(false) }
    // Forgot-password: request the reset link here; the reset completes in the browser via the
    // emailed link (there is no native reset screen on purpose — the link targets the web app).
    var forgotMode by rememberSaveable { mutableStateOf(false) }
    var resetRequested by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || serverUrl.isBlank() || email.isBlank() || (!forgotMode && password.isBlank())) return
        busy = true
        error = null
        scope.launch {
            if (forgotMode) {
                session.requestPasswordReset(serverUrl, email.trim())
                    .onSuccess { resetRequested = true }
                    .onFailure { error = apiErrorMessage(it, "Could not request a reset link.") }
            } else {
                val result = if (registerMode) {
                    session.register(serverUrl, email.trim(), password, displayName)
                } else {
                    session.login(serverUrl, email.trim(), password)
                }
                result.onFailure {
                    error = apiErrorMessage(
                        it,
                        if (registerMode) "Could not create the account." else "Invalid email or password.",
                    )
                }
            }
            busy = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            color = KeepItColors.Surface,
            shape = MaterialTheme.shapes.large,
            border = androidx.compose.foundation.BorderStroke(1.dp, KeepItColors.BorderSubtle),
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "keepIT",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = KeepItColors.Accent,
                )
                Text(
                    text = when {
                        forgotMode -> "Reset your password"
                        registerMode -> "Create your account"
                        else -> "Welcome back"
                    },
                    color = KeepItColors.TextMuted,
                )

                if (forgotMode && resetRequested) {
                    Text(
                        text = "If an account exists for ${email.trim()}, a reset link is on its " +
                            "way (valid for 2 hours). Open it, choose a new password, then sign " +
                            "in here.",
                        color = KeepItColors.Accent,
                        fontSize = 13.sp,
                    )
                    Button(
                        onClick = { forgotMode = false; resetRequested = false; error = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Back to sign in")
                    }
                    return@Column
                }

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://10.0.2.2:5025") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (registerMode) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Display name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!forgotMode) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }

                Button(
                    onClick = ::submit,
                    enabled = !busy && serverUrl.isNotBlank() && email.isNotBlank() &&
                        (forgotMode || password.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(2.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(
                            when {
                                forgotMode -> "Send reset link"
                                registerMode -> "Create account"
                                else -> "Sign in"
                            },
                        )
                    }
                }

                if (forgotMode) {
                    TextButton(onClick = { forgotMode = false; error = null }) {
                        Text(text = "Back to sign in", color = KeepItColors.TextMuted)
                    }
                } else {
                    TextButton(onClick = { registerMode = !registerMode; error = null }) {
                        Text(
                            text = if (registerMode) "Have an account? Sign in" else "New here? Create an account",
                            color = KeepItColors.TextMuted,
                        )
                    }
                    if (!registerMode) {
                        TextButton(onClick = { forgotMode = true; error = null }) {
                            Text(text = "Forgot password?", color = KeepItColors.TextMuted)
                        }
                    }
                }
            }
        }
    }
}
