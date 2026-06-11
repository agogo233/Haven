package sh.haven.feature.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import sh.haven.core.fido.FidoTouchPrompt

/**
 * FIDO touch / PIN dialog used during "Discover from security key"
 * enumeration. Mirrors [sh.haven.feature.connections.FidoTouchPromptDialog]
 * but reads keys-local strings so feature/keys doesn't have to depend on
 * feature/connections. The two could be consolidated into core/ui in a
 * follow-up — for now duplication keeps the v1 patch contained.
 */
@Composable
internal fun KeysFidoTouchPromptDialog(prompt: FidoTouchPrompt) {
    when (prompt) {
        is FidoTouchPrompt.EnterPin -> PinEntryDialog(prompt)
        is FidoTouchPrompt.WaitingForKey,
        is FidoTouchPrompt.WrongKey,
        is FidoTouchPrompt.TouchKey -> TouchDialog(prompt)
    }
}

@Composable
private fun TouchDialog(prompt: FidoTouchPrompt) {
    val (title, body) = when (prompt) {
        // WrongKey can't arise during resident-credential discovery (no specific
        // credential is targeted); fall back to the waiting copy for exhaustiveness.
        is FidoTouchPrompt.WaitingForKey, is FidoTouchPrompt.WrongKey ->
            stringResource(R.string.keys_fido_waiting_title) to
                stringResource(R.string.keys_fido_waiting_body)
        is FidoTouchPrompt.TouchKey -> when (prompt.transport) {
            FidoTouchPrompt.TouchKey.Transport.USB ->
                stringResource(R.string.keys_fido_touch_usb_title) to
                    stringResource(R.string.keys_fido_touch_usb_body)
            FidoTouchPrompt.TouchKey.Transport.NFC ->
                stringResource(R.string.keys_fido_touch_nfc_title) to
                    stringResource(R.string.keys_fido_touch_nfc_body)
        }
        is FidoTouchPrompt.EnterPin -> error("PinEntryDialog handles this state")
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.keys_fido_cancel_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun PinEntryDialog(prompt: FidoTouchPrompt.EnterPin) {
    var pin by remember { mutableStateOf("") }
    val retriesNote = prompt.retriesRemaining?.let {
        stringResource(R.string.keys_fido_pin_wrong, it)
    }
    AlertDialog(
        onDismissRequest = { prompt.submit(null) },
        title = { Text(stringResource(R.string.keys_fido_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.keys_fido_pin_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (retriesNote != null) {
                    Text(
                        retriesNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(stringResource(R.string.keys_fido_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { prompt.submit(pin) },
                enabled = pin.isNotEmpty(),
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = { prompt.submit(null) }) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

/**
 * Picker dialog shown after [KeysViewModel.discoverFromSecurityKey] returns
 * a non-empty list. Multi-select — user ticks the credentials they want to
 * import. The fingerprint per row lets the user disambiguate multiple
 * resident keys on the same RP (e.g. host-pinned ssh: keys for different
 * services).
 *
 * Each ticked credential exposes an editable label field (#231) so the
 * user can name it as they import — the motivating case is several
 * dongles that all expose the same `ssh:` rpId, which would otherwise be
 * saved under identical default labels. [onImport] receives a map of the
 * selected credential ids to their chosen labels.
 */
@Composable
internal fun DiscoveredCredentialsPicker(
    credentials: List<DiscoveredSkCredential>,
    onImport: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateOf(setOf<String>()) }
    // id -> user-edited label. Absent until the user types; the field
    // shows the `FIDO2: <rpId>` default in the meantime, and import falls
    // back to that default for any selected row left untouched.
    val labelEdits = remember { mutableStateOf(mapOf<String, String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_discover_picker_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.keys_discover_picker_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                credentials.forEach { cred ->
                    val isOn = cred.id in selected.value
                    val defaultLabel = "FIDO2: ${cred.rpId}"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(
                            checked = isOn,
                            onCheckedChange = { on ->
                                selected.value = if (on) selected.value + cred.id
                                else selected.value - cred.id
                            },
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(cred.rpId, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                cred.algorithmName,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                cred.fingerprint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (isOn) {
                                OutlinedTextField(
                                    value = labelEdits.value[cred.id] ?: defaultLabel,
                                    onValueChange = {
                                        labelEdits.value = labelEdits.value + (cred.id to it)
                                    },
                                    label = { Text(stringResource(R.string.common_label)) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onImport(
                        selected.value.associateWith { id ->
                            val cred = credentials.first { it.id == id }
                            labelEdits.value[id] ?: "FIDO2: ${cred.rpId}"
                        },
                    )
                },
                enabled = selected.value.isNotEmpty(),
            ) {
                Text(stringResource(R.string.keys_discover_import_selected))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
