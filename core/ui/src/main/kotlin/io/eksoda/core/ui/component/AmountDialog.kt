package io.eksoda.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.eksoda.core.designsystem.theme.EksodaTheme
import io.eksoda.core.ui.R
import io.eksoda.core.ui.text.appLocale
import io.eksoda.core.ui.text.currencySymbol
import io.eksoda.core.ui.text.formatDecimalInput
import io.eksoda.core.ui.text.parseMoneyInput
import java.math.BigDecimal

/**
 * Asks for a money amount in [currency] (a budget, the annual income). [onConfirm] gets an amount
 * above 0, typed with either thousands convention (`parseMoneyInput`). With [onRemove], a Remove
 * button clears the value instead. [validate] explains why an amount cannot be saved (shown under
 * the field, with Save disabled), or returns null when it can.
 */
@Composable
fun AmountDialog(
    title: String,
    initial: BigDecimal?,
    currency: String,
    onConfirm: (BigDecimal) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
    onRemove: (() -> Unit)? = null,
    validate: (BigDecimal) -> String? = { null },
) {
    val locale = appLocale()
    var text by rememberSaveable {
        mutableStateOf(initial?.let { formatDecimalInput(it, locale) }.orEmpty())
    }
    val typed = parseMoneyInput(text)?.takeIf { it.signum() > 0 }
    val problem = typed?.let(validate)
    val amount = typed?.takeIf { problem == null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = EksodaTheme.colors.ink2,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = text.isNotBlank() && amount == null,
                    suffix = { Text(currencySymbol(currency, locale)) },
                    supportingText = problem?.let { message -> { Text(message) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { amount?.let(onConfirm) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { amount?.let(onConfirm) }, enabled = amount != null) {
                Text(stringResource(R.string.amount_save))
            }
        },
        dismissButton = {
            Row {
                if (onRemove != null) {
                    TextButton(onClick = onRemove) {
                        Text(
                            stringResource(R.string.amount_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.amount_cancel)) }
            }
        },
    )
}

/**
 * Asks for the household's annual income (for the e-payment target), explaining what counts and
 * why the target is an estimate. [onChange] gets the new income, or null when it is removed.
 * Used by Insights and Settings, so both explain it the same way.
 */
@Composable
fun IncomeDialog(
    income: BigDecimal?,
    currency: String,
    onChange: (BigDecimal?) -> Unit,
    onDismiss: () -> Unit,
) {
    AmountDialog(
        title = stringResource(R.string.income_title),
        description = stringResource(R.string.income_hint),
        initial = income,
        currency = currency,
        onConfirm = onChange,
        onDismiss = onDismiss,
        onRemove = income?.let { { onChange(null) } },
    )
}

@Preview
@Composable
private fun AmountDialogPreview() {
    EksodaTheme {
        AmountDialog(
            title = "Monthly budget",
            initial = BigDecimal("300"),
            currency = "EUR",
            onConfirm = {},
            onDismiss = {},
            description = "Groceries",
            onRemove = {},
        )
    }
}
