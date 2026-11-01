package com.kevinpostal.fiberirc.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.delay

/** One-time hints (IRCCloud's first-run tips), each shown once per install. Ports `FirstRunTips.swift`. */
enum class FirstRunTip(val key: String, val text: String) {
    SIDEBARS("tip.sidebars", "Swipe left and right to quickly open and close the sidebars"),
    TWO_FINGER("tip.twoFinger", "Swipe with 2 fingers to return to the previous channel or conversation"),
    DOUBLE_TAP("tip.doubleTap", "Double-tap to quickly reply to the sender"),
}

/**
 * [tip] as a capsule at the top of the chat for 2.5 s (fading in and out over 0.25 s), then [onDone]. Not
 * interactive; announced to TalkBack.
 */
@Composable
fun TipCapsule(tip: FirstRunTip?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalFiberIrcColors.current
    var shown by remember { mutableStateOf<FirstRunTip?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tip) {
        if (tip == null) return@LaunchedEffect
        shown = tip
        visible = true
        delay(2_500)
        visible = false
        delay(250)
        onDone()
    }
    Box(modifier.fillMaxWidth().padding(top = Grid.gutter), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            val shape = RoundedCornerShape(Grid.margin)
            Text(
                shown?.text.orEmpty(),
                color = c.textPrimary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = Grid.margin)
                    .background(c.inputArea, shape)
                    .border(1.dp, c.border, shape)
                    .padding(horizontal = Grid.margin, vertical = Grid.gutter)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
