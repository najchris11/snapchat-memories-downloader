package com.najdev.snapvault.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** No item is selected. Distinct from index 0, which is the first item. */
internal const val LIBRARY_NO_SELECTION = -1

/**
 * Where the library grid's selection moves for [key], or `null` if [key] is not a movement.
 *
 * Kept pure and out of the composable because the edges are where this goes wrong: a partial
 * last row, the very first press with nothing selected, and an empty grid. Returning `null`
 * rather than [current] for an unrelated key is what lets the handler report the key as
 * unconsumed, so ordinary typing still reaches whatever else wants it.
 */
internal fun libraryGridTarget(key: Key, current: Int, count: Int, columns: Int): Int? {
    if (count <= 0 || columns <= 0) return null
    val last = count - 1

    // Nothing selected yet: any movement key lands on an end of the grid rather than doing
    // nothing, so a user reaching for the keyboard never has to press twice to start.
    if (current < 0) {
        return when (key) {
            Key.MoveEnd -> last
            Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.MoveHome -> 0
            else -> null
        }
    }

    return when (key) {
        Key.DirectionLeft -> (current - 1).coerceAtLeast(0)
        Key.DirectionRight -> (current + 1).coerceAtMost(last)
        // A row step that leaves the grid holds still rather than clamping to the nearest
        // item. Clamping would make Down from a full row slide sideways into a short last
        // row, which reads as the selection teleporting rather than refusing to move.
        Key.DirectionUp -> (current - columns).takeIf { it >= 0 } ?: current
        Key.DirectionDown -> (current + columns).takeIf { it <= last } ?: current
        Key.MoveHome -> 0
        Key.MoveEnd -> last
        else -> null
    }
}

/**
 * Whether `/` should jump to the search field.
 *
 * The handler sits on an ancestor of the field and runs as a *preview*, so it sees the key
 * before the focused child does. Without the [searchFocused] guard, `/` could never be typed
 * into the box it had just jumped to.
 */
internal fun shouldFocusSearch(key: Key, type: KeyEventType, searchFocused: Boolean): Boolean =
    type == KeyEventType.KeyDown && key == Key.Slash && !searchFocused

/** Wires [shouldFocusSearch] to [searchField]. See it for why the guard is a parameter. */
internal fun Modifier.focusSearchOnSlash(
    searchField: FocusRequester,
    searchFocused: () -> Boolean,
): Modifier = onPreviewKeyEvent { event ->
    if (shouldFocusSearch(event.key, event.type, searchFocused())) {
        searchField.requestFocus()
        true
    } else {
        false
    }
}
