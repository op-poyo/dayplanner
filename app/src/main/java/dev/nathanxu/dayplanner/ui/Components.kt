package dev.nathanxu.dayplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
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
    /** Opens the full form, prefilled from whatever has been typed so far. */
    onExpand: ((String) -> Unit)? = null,
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
        trailingIcon = {
            Row {
                if (onExpand != null) {
                    IconButton(onClick = {
                        onExpand(text.trim())
                        text = ""
                    }) { Icon(Icons.Default.Edit, contentDescription = "Open full form") }
                }
                IconButton(onClick = submit) { Icon(Icons.Default.Add, contentDescription = "Add") }
            }
        },
    )
}

/** Light haptic tick for completing, adding and swiping. */
@Composable
fun rememberHaptic(): () -> Unit {
    val h = androidx.compose.ui.platform.LocalHapticFeedback.current
    return remember(h) { { h.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) } }
}

/**
 * Swipe right to tick, left to delete (with Undo via snackbar). The row itself stays put when
 * ticking; it's the list that updates.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SwipeRow(
    done: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptic = rememberHaptic()
    // Always call the latest callbacks: the row is reused after it changes (ticked, reordered),
    // and the swipe state outlives the lambdas it was first created with.
    val toggle by androidx.compose.runtime.rememberUpdatedState(onToggle)
    val delete by androidx.compose.runtime.rememberUpdatedState(onDelete)
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState()
    // Act once the swipe has settled (not inside confirmValueChange, which can run several times
    // per gesture), then spring the row back.
    androidx.compose.runtime.LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> {
                haptic(); toggle(); state.reset()
            }
            androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> {
                haptic(); delete(); state.reset()
            }
            androidx.compose.material3.SwipeToDismissBoxValue.Settled -> Unit
        }
    }
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val toStart = state.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
            val bg = if (toStart) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
            Row(
                Modifier.fillMaxSize().background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = if (toStart) androidx.compose.foundation.layout.Arrangement.End
                else androidx.compose.foundation.layout.Arrangement.Start,
            ) {
                Text(
                    if (toStart) "Delete" else if (done) "Untick" else "Done",
                    color = if (toStart) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

/** A bold "!" before high-priority titles; nothing otherwise. */
@Composable
fun PriorityMark(priority: String?) {
    if (priority == "HIGH") {
        Text(
            "!",
            color = MaterialTheme.colorScheme.error,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.padding(end = 6.dp),
        )
    }
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
