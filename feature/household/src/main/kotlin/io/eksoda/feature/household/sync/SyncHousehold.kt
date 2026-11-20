package io.eksoda.feature.household.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.eksoda.core.designsystem.component.SectionCard
import io.eksoda.core.designsystem.theme.EksodaTheme
import io.eksoda.core.model.SyncPeer
import io.eksoda.core.ui.text.dayTimeLabel
import io.eksoda.feature.household.R

/** The household's phones, and adding one, joining a household and leaving. */
@Composable
internal fun HouseholdCard(
    peers: List<SyncPeer>,
    hasKey: Boolean,
    onEvent: (SyncScreenEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EksodaTheme.colors
    SectionCard(modifier = modifier, title = stringResource(R.string.sync_phones)) {
        if (peers.isEmpty()) {
            Text(
                text = stringResource(R.string.sync_phones_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        peers.forEach { peer ->
            PeerRow(peer)
            HorizontalDivider(color = colors.line)
        }
        if (peers.isEmpty()) HorizontalDivider(color = colors.line)
        ActionRow(
            label = stringResource(R.string.sync_add_phone),
            supporting = stringResource(R.string.sync_add_phone_hint),
            icon = Icons.Outlined.QrCode,
            onClick = { onEvent(SyncScreenEvent.AddPhoneClicked) },
        )
        HorizontalDivider(color = colors.line)
        ActionRow(
            label = stringResource(R.string.sync_join),
            supporting = stringResource(R.string.sync_join_row_hint),
            icon = Icons.Outlined.QrCodeScanner,
            onClick = { onEvent(SyncScreenEvent.JoinClicked) },
        )
        if (hasKey) {
            HorizontalDivider(color = colors.line)
            ActionRow(
                label = stringResource(R.string.sync_leave),
                icon = Icons.AutoMirrored.Outlined.Logout,
                color = MaterialTheme.colorScheme.error,
                onClick = { onEvent(SyncScreenEvent.LeaveClicked) },
            )
        }
    }
}

@Composable
private fun PeerRow(peer: SyncPeer) {
    val lastSuccess = peer.lastSuccessAt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = EksodaTheme.colors.ink2)
        Column(modifier = Modifier.weight(1f)) {
            Text(peer.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (lastSuccess != null) {
                    stringResource(R.string.sync_peer_last, dayTimeLabel(lastSuccess))
                } else {
                    stringResource(R.string.sync_peer_never)
                },
                style = MaterialTheme.typography.bodySmall,
                color = EksodaTheme.colors.ink2,
            )
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    supporting: String? = null,
    color: Color = Color.Unspecified,
) {
    val tint = if (color == Color.Unspecified) EksodaTheme.colors.ink2 else color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = EksodaTheme.colors.ink2,
                )
            }
        }
    }
}

/** The household code, and the confirmations of joining another household and of leaving. */
@Composable
internal fun SyncDialogs(dialog: SyncDialog, onEvent: (SyncScreenEvent) -> Unit) {
    val dismiss = { onEvent(SyncScreenEvent.DialogDismissed) }
    when (dialog) {
        SyncDialog.None -> Unit

        is SyncDialog.Code -> HouseholdCodeDialog(code = dialog.text, onDismiss = dismiss)

        SyncDialog.ConfirmJoin -> ConfirmDialog(
            title = stringResource(R.string.sync_join_confirm_title),
            text = stringResource(R.string.sync_join_confirm_text),
            confirm = stringResource(R.string.sync_join_confirm),
            onConfirm = { onEvent(SyncScreenEvent.JoinConfirmed) },
            onDismiss = dismiss,
        )

        SyncDialog.ConfirmLeave -> ConfirmDialog(
            title = stringResource(R.string.sync_leave_title),
            text = stringResource(R.string.sync_leave_text),
            confirm = stringResource(R.string.sync_leave_confirm),
            onConfirm = { onEvent(SyncScreenEvent.LeaveConfirmed) },
            onDismiss = dismiss,
            destructive = true,
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirm,
                    color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sync_cancel)) }
        },
    )
}
