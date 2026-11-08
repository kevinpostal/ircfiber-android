package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevinpostal.fiberirc.data.irc.ComposeStyle
import com.kevinpostal.fiberirc.data.irc.ComposeStylePipeline
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.focus.onFocusChanged

/**
 * The send pipeline's pure part (`BufferViewController.send` + `IRCClient.sendInput`'s line split): the field's plain
 * text, `ComposeStylePipeline` applied when a style is active (never for a command — `/join` must never come out
 * FIGlet'd), `:emoji:` codes converted, then one line per non-empty newline-separated segment.
 */
object ComposerSend {
    /** Swift `Character.isNewline` separators (`split(whereSeparator: \.isNewline)`), CRLF as one. */
    private val newline = Regex("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]")

    /** A slash command (`//text` is text with its first `/` dropped by the client). */
    fun isCommand(plain: String): Boolean = plain.startsWith("/") && !plain.startsWith("//")

    /** The text to send for [plain], or "" when nothing should be sent. */
    fun outgoingText(plain: String, style: ComposeStyle, convertEmoji: Boolean): String {
        if (plain.isEmpty()) return ""
        var text = if (!isCommand(plain) && style.isActive()) ComposeStylePipeline.render(plain, style) else plain
        if (text.isEmpty()) return ""
        if (convertEmoji && !text.startsWith("/")) text = EmojiCodes.replaceColons(text)
        return text
    }

    /** `IRCClient.sendInput`'s split: one entry per non-empty line. */
    fun lines(text: String): List<String> = text.split(newline).filter { it.isNotEmpty() }

    /** [outgoingText] split into [lines]. */
    fun outgoingLines(plain: String, style: ComposeStyle, convertEmoji: Boolean): List<String> =
        lines(outgoingText(plain, style, convertEmoji))
}

/** Double-tap reply (`ComposerView.insertReply`): `"nick: "` into an empty field, else `" nick"` at its end. */
fun insertReply(value: TextFieldValue, nick: String): TextFieldValue {
    val text = if (value.text.isEmpty()) "$nick: " else value.text + " " + nick
    return TextFieldValue(text, TextRange(text.length))
}

/** Mention (`ComposerView.insertMention`): `"nick: "` into an empty field, else `"nick "` in place of the selection. */
fun insertMention(value: TextFieldValue, nick: String): TextFieldValue {
    if (value.text.isEmpty()) return TextFieldValue("$nick: ", TextRange(nick.length + 2))
    val start = value.selection.min
    val insertion = "$nick "
    return TextFieldValue(value.text.replaceRange(start, value.selection.max, insertion), TextRange(start + insertion.length))
}

/** [value] with a newline in place of its selection (Shift+Return). */
fun insertNewline(value: TextFieldValue): TextFieldValue {
    val start = value.selection.min
    val text = value.text.replaceRange(start, value.selection.max, "\n")
    return TextFieldValue(text, TextRange(start + 1))
}

/**
 * Whether [new] is [old] with exactly a `"\n"` typed over its selection: a soft keyboard's Return, which sends
 * (`shouldChangeTextIn` with replacement text `"\n"`). Pasted text keeps its newlines.
 */
fun isReturnInsertion(old: TextFieldValue, new: TextFieldValue): Boolean {
    val start = old.selection.min
    if (new.text.length != old.text.length - old.selection.length + 1) return false
    return new.selection == TextRange(start + 1) &&
        new.text == old.text.replaceRange(start, old.selection.max, "\n")
}

/**
 * Hardware Ctrl/⌘+B/I/U: [style] with bold/italic/underline flipped for the whole message (Style Studio styles the
 * entire outgoing message, so there's no selection to apply it to). Null for any other key.
 */
fun composeStyleShortcut(key: Key, style: ComposeStyle): ComposeStyle? = when (key) {
    Key.B -> style.copy(bold = !style.bold)
    Key.I -> style.copy(italic = !style.italic)
    Key.U -> style.copy(underline = !style.underline)
    else -> null
}

/**
 * IRCCloud's bottom bar, ported from `ios/UI/Chat/ComposerView.swift`: an optional upload button, a growing message
 * field (one control tall, up to five lines, then it scrolls), an "Aa" style button, and a gear that cross-fades to
 * "Send" while there is text. Completion chips sit above it and the "is typing" line under the field. The field is
 * plain text; whole-message styling is [style], applied at send time.
 *
 * Sending (Return, the keyboard's Send action, hardware Enter, or the Send button) with text in the field:
 * [interceptSend] gets the plain text first (`/paste`, the snippet offer, `/list` — return true to take over; the
 * screen then clears the field itself); otherwise the field is cleared, [onSend] is called once per outgoing line
 * (see [ComposerSend]), then [afterSend] with the plain text and whether [style] was applied (so the screen can apply
 * "Clear Formatting After Sending", scroll to bottom, donate the shortcut). Shift+Enter inserts a newline.
 *
 * Hardware Tab calls [onTabCycle] when there's a word to complete; a tapped chip calls [onPickSuggestion] (default:
 * the chip's text over its range). Ctrl/⌘+B/I/U call [onStyleChange] with the toggled style. Wire completion
 * through [ComposerCompletion]. The bar draws no system-bar/IME insets; the screen pads for them.
 */
@Composable
fun ComposerBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: (String) -> Unit,
    style: ComposeStyle,
    suggestions: List<CompletionSuggestion>,
    onTabCycle: () -> Unit,
    onGearClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Message",
    selectedSuggestion: Int? = null,
    onPickSuggestion: (CompletionSuggestion) -> Unit = { onValueChange(applyCompletion(value, it.replaceRange, it.insertText)) },
    showSuggestions: Boolean = true,
    onInputFocusChange: (Boolean) -> Unit = {},
    onStyleChange: ((ComposeStyle) -> Unit)? = null,
    onStyleClick: (() -> Unit)? = null,
    onUploadClick: (() -> Unit)? = null,
    showGear: Boolean = true,
    gearMenu: @Composable () -> Unit = {},
    typingText: String = "",
    convertEmoji: Boolean = true,
    autoCapitalization: Boolean = true,
    chatFontScale: Double = 1.0,
    interceptSend: (plain: String) -> Boolean = { false },
    afterSend: (plain: String, wasStyled: Boolean) -> Unit = { _, _ -> },
    /** The buffer's encryption lock control, first in the row (encrypted networks only). */
    lockControl: (@Composable () -> Unit)? = null,
    /** The encryption state line above the field (encrypting buffers only). */
    encryptionBanner: (@Composable () -> Unit)? = null,
) {
    val c = LocalFiberIrcColors.current
    val hasText = value.text.isNotEmpty()

    fun send() {
        val plain = value.text
        if (plain.isEmpty()) return
        val text = ComposerSend.outgoingText(plain, style, convertEmoji)
        if (text.isEmpty()) return
        if (interceptSend(plain)) return
        onValueChange(TextFieldValue())
        ComposerSend.lines(text).forEach(onSend)
        afterSend(plain, !ComposerSend.isCommand(plain) && style.isActive())
    }

    // Theme.chatFont: 15pt body, scaled by the device's chat text size and rounded.
    val fontSize = (15 * chatFontScale).roundToInt().sp
    val lineHeight = (15 * chatFontScale * 1.2).roundToInt().sp
    val textStyle = TextStyle(color = c.heading, fontSize = fontSize, lineHeight = lineHeight)
    val inset = with(LocalDensity.current) {
        max(Grid.unit.value, floor((Grid.control.toPx() - lineHeight.toPx()) / 2 / density)).dp
    }
    val uiFont = TextStyle(fontSize = 15.sp)
    val uiBold = uiFont.copy(fontWeight = FontWeight.SemiBold)

    Column(modifier.fillMaxWidth()) {
        if (showSuggestions && suggestions.isNotEmpty()) {
            CompletionStrip(suggestions, selectedSuggestion, onPickSuggestion, uiFont)
        }
        encryptionBanner?.invoke()
        Row(
            Modifier.fillMaxWidth().padding(start = Grid.margin, end = Grid.margin, top = Grid.gutter),
            horizontalArrangement = Arrangement.spacedBy(Grid.gutter),
            verticalAlignment = Alignment.Bottom,
        ) {
            lockControl?.invoke()
            if (onUploadClick != null) {
                ControlButton("Upload", "uploadButton", onUploadClick) {
                    Icon(Icons.Outlined.IosShare, contentDescription = null, tint = c.textSecondary)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(c.inputPill.copy(alpha = 0.72f), RoundedCornerShape(22.dp))
                    .border(1.dp, c.inputPillBorder.copy(alpha = 0.6f), RoundedCornerShape(22.dp)),
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.35f to Color.Transparent)),
                )
                BasicTextField(
                    value = value,
                    onValueChange = { new -> if (isReturnInsertion(value, new)) send() else onValueChange(new) },
                    textStyle = textStyle,
                    cursorBrush = SolidColor(c.accent),
                    minLines = 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (autoCapitalization) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier
                        .onFocusChanged { onInputFocusChange(it.isFocused) }
                        .fillMaxWidth()
                        .heightIn(min = Grid.control)
                        .padding(horizontal = Grid.margin, vertical = inset)
                        .testTag("messageInput")
                        .semantics { contentDescription = placeholder }
                        .onPreviewKeyEvent { e ->
                            when (e.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    if (e.type == KeyEventType.KeyDown) {
                                        if (e.isShiftPressed) onValueChange(insertNewline(value)) else send()
                                    }
                                    true
                                }
                                Key.Tab -> {
                                    val plainTab = !e.isShiftPressed && !e.isCtrlPressed && !e.isAltPressed && !e.isMetaPressed
                                    val completable = suggestions.isNotEmpty() ||
                                        Completion.word(value.text, value.selection.start) != null
                                    if (!plainTab || !completable) return@onPreviewKeyEvent false
                                    if (e.type == KeyEventType.KeyDown) onTabCycle()
                                    true
                                }
                                else -> {
                                    if (onStyleChange == null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    if (!(e.isCtrlPressed || e.isMetaPressed) || e.isAltPressed || e.isShiftPressed) {
                                        return@onPreviewKeyEvent false
                                    }
                                    val toggled = composeStyleShortcut(e.key, style) ?: return@onPreviewKeyEvent false
                                    onStyleChange(toggled)
                                    true
                                }
                            }
                        },
                    decorationBox = { inner ->
                        Box {
                            if (!hasText) {
                                Text(placeholder, style = textStyle.copy(color = c.textTertiary), maxLines = 1)
                            }
                            inner()
                        }
                    },
                )
            }
            if (onStyleClick != null) {
                ControlButton("Text style", "composerStyleButton", onStyleClick) {
                    Text("Aa", style = uiBold, color = if (style.isActive()) c.accent else c.textSecondary)
                }
            }
            AnimatedContent(
                targetState = hasText,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "gearSend",
            ) { text ->
                when {
                    text -> Box(
                        Modifier
                            .size(Grid.control)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(c.accent, c.accentActive)))
                            .clickable(role = Role.Button, onClick = ::send)
                            .testTag("sendButton"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                    }
                    showGear -> Box {
                        ControlButton("Menu", "bufferGearButton", onGearClick) {
                            Icon(Icons.Outlined.Settings, contentDescription = null, tint = c.textSecondary)
                        }
                        gearMenu()
                    }
                    else -> Box(Modifier.height(Grid.control))
                }
            }
        }
        val typingStart = Grid.margin + (if (onUploadClick != null) Grid.control + Grid.gutter else 0.dp) +
            (if (lockControl != null) Grid.control + Grid.gutter else 0.dp)
        if (typingText.isNotEmpty()) {
            Text(
                typingText,
                style = TextStyle(fontSize = 11.sp),
                color = c.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.padding(start = typingStart, end = Grid.margin).testTag("typingLabel"),
            )
        }
        Box(Modifier.height(Grid.gutter))
    }
}

/** A 36×36dp control (`Grid.control`). */
@Composable
internal fun ControlButton(label: String, tag: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(Grid.control)
            .clip(CircleShape)
            .background(LocalFiberIrcColors.current.inputPill.copy(alpha = 0.35f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * IRCCloud's `NickCompletionView` (`CompletionStrip.swift`): a 36dp horizontal strip of chips above the composer. A
 * tap picks a chip; the hardware-Tab selection is the accent chip, scrolled into view.
 */
@Composable
private fun CompletionStrip(
    suggestions: List<CompletionSuggestion>,
    selected: Int?,
    onPick: (CompletionSuggestion) -> Unit,
    font: TextStyle,
) {
    val c = LocalFiberIrcColors.current
    val state = rememberLazyListState()
    LaunchedEffect(suggestions) { state.scrollToItem(0) }
    LaunchedEffect(selected) { selected?.let { if (it in suggestions.indices) state.scrollToItem(it) } }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().height(Grid.control).testTag("completionStrip"),
        contentPadding = PaddingValues(horizontal = Grid.margin),
        horizontalArrangement = Arrangement.spacedBy(Grid.unit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(suggestions) { index, s ->
            val isSelected = index == selected
            Box(
                Modifier
                    .height(Grid.control - Grid.gutter)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) c.accent else c.inputPill)
                    .clickable(role = Role.Button) { onPick(s) }
                    .padding(horizontal = Grid.margin),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.label, style = font, color = if (isSelected) Color.White else c.heading, maxLines = 1)
            }
        }
    }
}
