package com.kevinpostal.fiberirc.ui.shell

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors

/** Whether another buffer has unread highlights or unread messages (the menu button's tint). */
enum class MenuState { NONE, UNREAD, HIGHLIGHT }

/**
 * The chat's left navigation button (IRCCloud's "menu" glyph): opens the channel drawer. Its tint tells whether
 * another buffer has unread highlights (badge red) or unread messages (accent blue). Ports `MenuButton.swift`.
 */
@Composable
fun MenuButton(state: MenuState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFiberIrcColors.current
    val tint = when (state) {
        MenuState.NONE -> c.textSecondary
        MenuState.UNREAD -> c.accent
        MenuState.HIGHLIGHT -> c.badge
    }
    val value = when (state) {
        MenuState.NONE -> null
        MenuState.UNREAD -> "Unread messages"
        MenuState.HIGHLIGHT -> "Unread highlights"
    }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(Grid.tapTarget)
            .testTag("buffersButton")
            .semantics { if (value != null) stateDescription = value },
    ) {
        Icon(Icons.Filled.Menu, contentDescription = "Channels", tint = tint)
    }
}
