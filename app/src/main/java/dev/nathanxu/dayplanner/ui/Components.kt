package dev.nathanxu.dayplanner.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun QuickAddBar(
    placeholder: String,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onSubmit(text.trim())
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
        placeholder = { Text(placeholder, maxLines = 1) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = { IconButton(onClick = submit) { Icon(Icons.Default.Add, contentDescription = "Add") } },
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

fun formatMinutes(m: Long): String = when {
    m < 60 -> "${m}m"
    m % 60 == 0L -> "${m / 60}h"
    else -> "${m / 60}h${m % 60}"
}
