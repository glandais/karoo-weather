package io.github.glandais.karoo.weather.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.glandais.karoo.weather.R

/**
 * First-run consent (DESIGN §5, ARCHITECTURE §1.3).
 *
 * `consentAccepted` gates every network call, so this dialog is genuinely blocking: it is not
 * dismissible by a tap outside or by the back gesture, and the only way past it is one of the two
 * 48 dp buttons. Declining closes the app rather than leaving the rider on a screen that can never
 * fill with data.
 */
@Composable
fun ConsentDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = {
            Text(
                text = stringResource(R.string.consent_title),
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Default,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.consent_body),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Default,
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.consent_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.consent_decline))
            }
        },
    )
}
