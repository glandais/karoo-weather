package io.github.glandais.karoo.weather.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One selectable value and the label the rider reads. */
data class DropdownOption<T>(val value: T, val label: String)

/**
 * A tap-only dropdown.
 *
 * `ExposedDropdownMenuBox` is avoided deliberately: it is an experimental API whose anchor
 * semantics have changed between Material3 releases, and it buys nothing here — every option list
 * in this app is short, fixed and non-searchable. `OutlinedButton` + `DropdownMenu` is stable API
 * and gives a 56 dp anchor and 56 dp items with no gesture beyond a tap (DESIGN §7).
 *
 * Selection is applied immediately; there is no confirm step (DESIGN §5: dropdowns save on change).
 */
@Composable
fun <T> Dropdown(
    options: List<DropdownOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected } ?: options.firstOrNull()

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 56.dp).defaultMinSize(minWidth = 120.dp),
        ) {
            Text(
                text = current?.label.orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        expanded = false
                        if (option.value != selected) onSelect(option.value)
                    },
                    modifier = Modifier.heightIn(min = 56.dp),
                )
            }
        }
    }
}
