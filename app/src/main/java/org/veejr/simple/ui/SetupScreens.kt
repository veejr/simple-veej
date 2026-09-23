package org.veejr.simple.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.veejr.core.network.Recipient

/** Setup is done once, usually by whoever hands over the phone. */
@Composable
fun SetupFrame(title: String, subtitle: String, error: String?, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF6F7F9))
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, fontSize = 18.sp, color = Color(0xFF4A4F57))
        if (error != null) {
            Text(
                error,
                color = HangUpRed,
                fontSize = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HangUpRed.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
            )
        }
        content()
    }
}

@Composable
fun SignInScreen(defaultServer: String, busy: Boolean, error: String?, onSignIn: (String, String, CharArray) -> Unit) {
    var server by remember { mutableStateOf(defaultServer) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    SetupFrame("Set up simple-veej", "Sign in with the veejr account this phone belongs to.", error) {
        OutlinedTextField(server, { server = it }, label = { Text("veejr server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            email,
            { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Sign in", busy, enabled = email.isNotBlank() && password.isNotEmpty()) {
            val chars = password.toCharArray()
            password = ""
            onSignIn(server, email, chars)
        }
    }
}

@Composable
fun UnlockScreen(busy: Boolean, error: String?, onUnlock: (CharArray) -> Unit, onStartOver: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }

    SetupFrame(
        "Unlock once",
        "Enter the encryption passphrase one time. This phone keeps the key locked in its secure hardware, so calls never ask for it again.",
        error,
    ) {
        OutlinedTextField(
            passphrase,
            { passphrase = it },
            label = { Text("Encryption passphrase") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Unlock", busy, enabled = passphrase.isNotEmpty()) {
            val chars = passphrase.toCharArray()
            passphrase = ""
            onUnlock(chars)
        }
        TextButton(onClick = onStartOver) { Text("Use a different account") }
    }
}

@Composable
fun ChoosePersonScreen(friends: List<Recipient>, onChoose: (Recipient, String) -> Unit) {
    var chosen by remember { mutableStateOf<Recipient?>(null) }
    var name by remember { mutableStateOf("") }

    val selected = chosen
    if (selected == null) {
        Column(Modifier.fillMaxSize().background(Color(0xFFF6F7F9)).systemBarsPadding().padding(24.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("Who does this phone call?", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Pick the one person the big button will ring.", fontSize = 18.sp, color = Color(0xFF4A4F57))
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(friends, key = { it.id }) { friend ->
                    Text(
                        friend.handle,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(14.dp))
                            .clickable {
                                chosen = friend
                                name = friend.username.replaceFirstChar { it.uppercase() }
                            }
                            .padding(20.dp),
                    )
                }
            }
        }
    } else {
        SetupFrame(
            "What should the button say?",
            "The button will read “Call …” with this name. Something like Mom, Grandpa, or Sam.",
            null,
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name on the button") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PrimaryButton("Done", busy = false, enabled = name.isNotBlank()) { onChoose(selected, name) }
            TextButton(onClick = { chosen = null }) { Text("Pick someone else") }
        }
    }
}

@Composable
fun SettingsScreen(
    personName: String,
    fullScreenAllowed: Boolean,
    pushStatus: String,
    onAllowFullScreen: () -> Unit,
    onBack: () -> Unit,
    onStartOver: () -> Unit,
) {
    SetupFrame("Settings", "This phone calls $personName.", null) {
        StatusRow("Rings when the app is closed", pushStatus)
        StatusRow(
            "Answer screen on the lock screen",
            if (fullScreenAllowed) "Allowed" else "Not allowed",
        )
        if (!fullScreenAllowed) {
            PrimaryButton("Allow lock-screen calls", busy = false, enabled = true, onClick = onAllowFullScreen)
        }
        PrimaryButton("Back", busy = false, enabled = true, onClick = onBack)
        TextButton(onClick = onStartOver) {
            Text("Sign out and set up again", color = HangUpRed, fontSize = 18.sp)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(label, fontSize = 16.sp, color = Color(0xFF4A4F57))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PrimaryButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        colors = ButtonDefaults.buttonColors(containerColor = CallGreen),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(60.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.height(24.dp))
        } else {
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
