package app.listwerk.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.listwerk.android.R

@Composable
fun LoginScreen(vm: MainViewModel, onBack: (() -> Unit)?) {
    val state by vm.login.collectAsState()
    var server by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.login_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        when (val s = state) {
            is LoginState.Waiting -> {
                Row2 { CircularProgressIndicator(Modifier.padding(end = 12.dp)); Text(stringResource(R.string.login_waiting)) }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.login_waiting_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { vm.cancelLogin() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
            }
            else -> {
                OutlinedTextField(
                    value = server, onValueChange = { server = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.login_server)) }, placeholder = { Text("cloud.example.org") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.startLogin(server) }),
                )
                if (s is LoginState.Failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.startLogin(server) }, enabled = server.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.login_button))
                }
                Spacer(Modifier.height(8.dp))
                if (onBack == null) {
                    // Erststart: auch ganz ohne Konto nutzbar; eine spätere Anmeldung lädt alles hoch.
                    TextButton(onClick = { vm.skipLogin() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.login_skip)) }
                    Text(stringResource(R.string.login_skip_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
                    Text(stringResource(R.string.login_later_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Row2(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) { content() }
}
