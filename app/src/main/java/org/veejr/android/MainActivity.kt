package org.veejr.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: VeejrViewModel = viewModel(
                factory = VeejrViewModel.Factory(SessionVault(applicationContext)),
            )
            VeejrApp(viewModel)
        }
    }
}

@Composable
fun VeejrApp(viewModel: VeejrViewModel) {
    val state by viewModel.state.collectAsState()
    VeejrTheme {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(targetState = state.screen, label = "session-screen") { screen ->
                    when (screen) {
                        AppScreen.INSTANCE -> InstanceScreen(state, viewModel::connect)
                        AppScreen.LOGIN -> LoginScreen(
                            state = state,
                            onLogin = viewModel::login,
                            onChangeInstance = viewModel::changeInstance,
                        )
                        AppScreen.KEY_SETUP -> KeySetupScreen(state, viewModel::setupIdentity)
                        AppScreen.KEY_UNLOCK -> KeyUnlockScreen(state, viewModel::unlockIdentity)
                        AppScreen.HOME -> HomeScreen(
                            state = state,
                            onAccept = viewModel::acceptNotification,
                            onDecline = viewModel::declineNotification,
                            onRefresh = viewModel::refreshInbox,
                            onLogout = viewModel::logout,
                            onChangeInstance = viewModel::changeInstance,
                        )
                    }
                }
                if (state.loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun KeySetupScreen(state: AppUiState, onSetup: (String, String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AppCard {
        BrandHeader("Create encryption keys", "Only you can unlock your conversations.")
        Text(
            text = "Choose a separate passphrase with at least 8 characters. It never leaves this device, and it cannot be recovered.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
        Spacer(Modifier.height(18.dp))
        SecretField("Encryption passphrase", passphrase, { passphrase = it }, state.loading)
        Spacer(Modifier.height(12.dp))
        SecretField("Confirm passphrase", confirmation, { confirmation = it }, state.loading)
        ErrorText(state.error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                onSetup(passphrase, confirmation)
                passphrase = ""
                confirmation = ""
            },
            enabled = passphrase.length >= IdentityCoordinator.MIN_PASSPHRASE_LENGTH &&
                confirmation.isNotEmpty() && !state.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Generate keys")
        }
    }
}

@Composable
private fun KeyUnlockScreen(state: AppUiState, onUnlock: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AppCard {
        BrandHeader("Unlock your conversations", state.account?.handle.orEmpty())
        Text(
            text = "Your encrypted identity came from ${state.endpoint.removePrefix("https://")}. The passphrase is processed only on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
        Spacer(Modifier.height(18.dp))
        SecretField("Encryption passphrase", passphrase, { passphrase = it }, state.loading)
        ErrorText(state.error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                onUnlock(passphrase)
                passphrase = ""
            },
            enabled = passphrase.isNotEmpty() && !state.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Unlock")
        }
    }
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    loading: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        enabled = !loading,
    )
}

@Composable
private fun InstanceScreen(state: AppUiState, onConnect: (String) -> Unit) {
    var endpoint by remember { mutableStateOf(state.endpoint) }
    AppCard {
        BrandHeader("Connect your instance", "Your conversations stay on the server you choose.")
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Instance URL") },
            placeholder = { Text("https://veejr.example") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            enabled = !state.loading,
        )
        ErrorText(state.error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onConnect(endpoint) },
            enabled = endpoint.isNotBlank() && !state.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Continue")
        }
        Text(
            text = if (BuildConfig.DEBUG) {
                "Local emulator: use http://10.0.2.2:4000. HTTPS remains required in release builds."
            } else {
                "HTTPS is required. veejr checks compatibility before sending credentials."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun LoginScreen(
    state: AppUiState,
    onLogin: (String, String) -> Unit,
    onChangeInstance: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AppCard {
        BrandHeader("Welcome back", state.endpoint.removePrefix("https://"))
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            enabled = !state.loading,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            enabled = !state.loading,
        )
        ErrorText(state.error)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                onLogin(email, password)
                password = ""
            },
            enabled = email.isNotBlank() && password.isNotBlank() && !state.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Sign in")
        }
        TextButton(onClick = onChangeInstance, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Use a different instance")
        }
    }
}

@Composable
private fun HomeScreen(
    state: AppUiState,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onChangeInstance: () -> Unit,
) {
    val account = state.account ?: return
    AppCard {
        BrandHeader("You’re connected", state.endpoint.removePrefix("https://"))
        Spacer(Modifier.height(28.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(22.dp)) {
                Text(
                    text = account.displayName ?: account.username,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = account.handle,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = if (account.keysConfigured) "Encryption keys ready" else "Encryption setup is next",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Inbox", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${state.notifications.size} waiting for consent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh") }
        }
        if (state.notifications.isEmpty() && state.messages.isEmpty()) {
            Text(
                "No messages yet. Pending content appears here without being downloaded.",
                modifier = Modifier.padding(top = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.notifications.forEach { notification ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "${notification.sender.handle} sent an encrypted ${notification.kind}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Nothing is downloaded until you accept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { onDecline(notification.id) }) { Text("Decline") }
                        Button(onClick = { onAccept(notification.id) }) { Text("Accept") }
                    }
                }
            }
        }
        state.messages.forEach { message ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        message.senderHandle,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        ErrorText(state.error)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("Sign out")
        }
        TextButton(onClick = onChangeInstance, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Forget this instance")
        }
    }
}

@Composable
private fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(28.dp).verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

@Composable
private fun BrandHeader(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary) {
            Text(
                text = "v",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun VeejrTheme(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme.copy(
        primary = Color(0xFF345C49),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCEDE3),
        background = Color(0xFFF5F2EA),
        surface = Color(0xFFFFFCF5),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Preview(showBackground = true)
@Composable
private fun InstancePreview() {
    VeejrTheme { InstanceScreen(AppUiState(loading = false), {}) }
}
