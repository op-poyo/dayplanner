package dev.nathanxu.dayplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

// ---------- tags ----------

@androidx.compose.runtime.Composable
fun TagDot(color: Int, size: androidx.compose.ui.unit.Dp = 10.dp) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(size).background(androidx.compose.ui.graphics.Color(color), androidx.compose.foundation.shape.CircleShape),
    )
}

/** Coloured dot + tag name, for list rows. */
@androidx.compose.runtime.Composable
fun TagLabel(tag: dev.nathanxu.dayplanner.domain.Tag) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TagDot(tag.color, 8.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
        Text(tag.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row of palette swatches; the selected one is ringed. */
@androidx.compose.runtime.Composable
fun ColorPalette(selected: Int?, onPick: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        dev.nathanxu.dayplanner.domain.Tags.PALETTE.forEach { c ->
            androidx.compose.foundation.layout.Box(
                Modifier.size(30.dp)
                    .then(
                        if (c == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape)
                        else Modifier
                    )
                    .padding(3.dp)
                    .background(androidx.compose.ui.graphics.Color(c), androidx.compose.foundation.shape.CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}
