package com.syrak.scooterlab.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ForceFlashWarningDialog(
    reasonText: String,
    onCancel: () -> Unit,
    onConfirmForceFlash: () -> Unit
) {
    var isRiskAcknowledged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = "⚠️ Warnung: Unsicherer Flash-Vorgang",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = reasonText,
                    style = MaterialTheme.typography.bodyMedium
                )

                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚠️ GEFAHR: Das Flashen auf inkompatiblen Versionen kann den Scooter dauerhaft beschädigen (Bricking)!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Checkbox(
                        checked = isRiskAcknowledged,
                        onCheckedChange = { isRiskAcknowledged = it }
                    )
                    Text(
                        text = "Ich verstehe das Risiko und flashe auf eigene Verantwortung.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmForceFlash,
                enabled = isRiskAcknowledged,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Trotzdem flashen")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text("Abbrechen (Empfohlen)")
            }
        }
    )
}
