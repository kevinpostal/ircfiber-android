package com.kevinpostal.fiberirc.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevinpostal.fiberirc.data.irc.IrcFormatting
import com.kevinpostal.fiberirc.data.irc.IrcLine
import com.kevinpostal.fiberirc.data.irc.IrcLineKind
import com.kevinpostal.fiberirc.data.irc.IrcEventKind
import com.kevinpostal.fiberirc.data.irc.IrcMember
import com.kevinpostal.fiberirc.data.irc.mentions
import com.kevinpostal.fiberirc.data.prefs.DeviceSettings
import com.kevinpostal.fiberirc.data.prefs.DisplayPrefs
import com.kevinpostal.fiberirc.data.prefs.HighlightOptions
import com.kevinpostal.fiberirc.ui.theme.FiberIrcColors
import com.kevinpostal.fiberirc.ui.theme.Grid
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import com.kevinpostal.fiberirc.ui.theme.legible
import com.kevinpostal.fiberirc.ui.theme.nickColor
import com.kevinpostal.fiberirc.ui.theme.prefixColor
import com.kevinpostal.fiberirc.ui.theme.serverLogColor
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// Port of iOS `UI/Chat/MessageCell.swift` (+ `MessageLayout.swift`): one chat line in IRCCloud's columns.

/**
 * Where a chat row's columns sit (IRCCloud `EventsTableView` insets), as offsets from the row's start edge plus the
 * text column's end inset. Port of iOS `MessageColumns`/`MessageLayout.columns`.
 */
@Immutable
data class MessageColumns(
    /** Start of the avatar column. */
    val avatarX: Dp,
    /** Start of the text column for rows that keep the avatar column. */
    val textX: Dp,
    /** Gap between the text column's end and the row's end (time column + gutter, or the margin). */
    val textEndInset: Dp,
    /** Time column start from the row's start edge when [timeOnRight] is false; from the end edge otherwise. */
    val timeInset: Dp,
    val timeOnRight: Boolean,
) {
    /**
     * Text start for a row that shows no avatar (an `.event`/`.error` row, or a collapsed summary): flush to the
     * avatar's own column instead of the text keyline. The end edge is unchanged either way.
     */
    fun textStart(hasAvatar: Boolean): Dp = if (hasAvatar) textX else avatarX

    companion object {
        /**
         * Right timestamps: avatar at the margin, text after it, the time last. Left timestamps: the time at the
         * margin, then the avatar, then the text. [avatarSize] is 32 with the nick on its own line, 16 otherwise.
         */
        fun of(avatars: Boolean, timestamps: Boolean, timeOnRight: Boolean, timeWidth: Dp, avatarSize: Dp): MessageColumns {
            val leftTime = timestamps && !timeOnRight
            val avatarX = if (leftTime) Grid.margin + timeWidth + Grid.gutter else Grid.margin
            val textX = if (avatars) avatarX + avatarSize + Grid.gutter else avatarX
            val textEnd = if (timestamps && timeOnRight) Grid.margin + timeWidth + Grid.gutter else Grid.margin
            return MessageColumns(avatarX, textX, textEnd, Grid.margin, timeOnRight)
        }
    }
}

/**
 * Everything a chat row needs beyond its line, built once per settings, members or theme change (iOS
 * `ChatRowStyle`): the account display prefs and highlight words, the channel's members, the device's message
 * layout, the fonts and the columns.
 */
@Immutable
class ChatRowStyle(
    val colors: FiberIrcColors,
    val display: DisplayPrefs,
    val device: DeviceSettings,
    val highlightWords: List<String>,
    val highlightOptions: HighlightOptions,
    val ownNick: String,
    members: List<IrcMember>,
    /** Width of the time column ([MessageTime.columnWidth] of [timeStyle]). */
    val timeWidth: Dp,
) {
    /** Lower-cased nick → member (mode prefixes, real name). */
    val members: Map<String, IrcMember> = LinkedHashMap<String, IrcMember>().also { out ->
        // First wins, like iOS `uniquingKeysWith: { first, _ in first }`.
        for (member in members) out.putIfAbsent(member.nick.lowercase(), member)
    }

    /** Mention colourer; null when Colourise Mentions is off or there is nothing to match. */
    val colorizer: MentionColorizer? =
        if (display.coloriseMentions) MentionColorizer.create(members.map { it.nick } + ownNick + highlightWords) else null

    val separateNickLine get() = device.nicknamesOnSeparateLine

    /** The avatar column's width: 32 with Nicknames on Separate Line, else 16. */
    val avatarSize: Dp get() = if (separateNickLine) Grid.leadingColumn else 16.dp

    val columns: MessageColumns = MessageColumns.of(
        avatars = display.showUserIcons,
        timestamps = display.showTimestamps,
        timeOnRight = device.timestampsOnRight,
        timeWidth = timeWidth,
        avatarSize = if (device.nicknamesOnSeparateLine) Grid.leadingColumn else 16.dp,
    )

    /** Chat text (Settings → Display → Monospace font) at this device's synced Font Size. */
    val regular: TextStyle = chatTextStyle(display.monospaceFont, bold = false, display.chatFontScale)
    val bold: TextStyle = chatTextStyle(display.monospaceFont, bold = true, display.chatFontScale)

    fun nickColor(nick: String): Color = if (device.colouriseNicknames) nickColor(nick, colors.dark) else colors.heading

    companion object {
        /** iOS `Theme.monoSmall`: the time column. */
        val timeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)

        /** iOS `Theme.caption`. */
        val caption = TextStyle(fontSize = 12.sp)

        /** iOS `Theme.chatFont`: mono 14 / system 15 (semibold when bold), scaled by Font Size and rounded. */
        fun chatTextStyle(mono: Boolean, bold: Boolean, scale: Double): TextStyle {
            val base = if (mono) 14.0 else 15.0
            val size = if (scale == 1.0) base else (base * scale).roundToInt().toDouble()
            return TextStyle(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = size.sp,
            )
        }
    }
}

/** Builds a [ChatRowStyle], measuring the time column for the current density and font scale. */
@Composable
fun rememberChatRowStyle(
    display: DisplayPrefs,
    device: DeviceSettings,
    highlightWords: List<String>,
    highlightOptions: HighlightOptions,
    ownNick: String,
    members: List<IrcMember>,
    colors: FiberIrcColors = LocalFiberIrcColors.current,
): ChatRowStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(display, device, highlightWords, highlightOptions, ownNick, members, colors, density) {
        val timeWidth = MessageTime.columnWidth(
            display.timestampFormat, device.showSeconds, ChatRowStyle.timeStyle, measurer, density,
        )
        ChatRowStyle(colors, display, device, highlightWords, highlightOptions, ownNick, members, timeWidth)
    }
}

/** What the chat screen wires up; every hook is optional. Navigation and the composer belong to the caller. */
@Immutable
class ChatRowCallbacks(
    /** A tapped URL or channel in chat text. */
    val onLink: (ChatLink) -> Unit = {},
    /** The reply glyph (or a collapsed "N replies" row): open the thread of [parentMsgid]. */
    val onOpenThread: (parentMsgid: String) -> Unit = {},
    /** Double tap on someone's message/action/notice (or art): insert a reply to [nick] in the composer. */
    val onReplyToSender: (nick: String) -> Unit = {},
    /** Single tap anywhere in the messages: dismiss the keyboard. */
    val onTap: () -> Unit = {},
    /**
     * Long press on a message (with the [url] under the finger, if any) or on an inline preview ([preview] true,
     * [url] the preview's): open the message menu (M9 `MessageMenuTarget(line, url, preview)`).
     */
    val onLongPress: (line: IrcLine, url: String?, preview: Boolean) -> Unit = { _, _, _ -> },
    /** Tap on a collapsed membership/MOTD row: toggle [groupId] in [ChatRowsInput.expanded]. */
    val onToggleGroup: (groupId: String) -> Unit = {},
)

/** A line's band, shared by its inline media rows (iOS `MessageCell.band(for:style:)`). */
data class RowBand(val background: Color, val mentioned: Boolean)

fun rowBand(line: IrcLine, style: ChatRowStyle): RowBand {
    val c = style.colors
    val sender = line.sender ?: ""
    val chat = line.kind == IrcLineKind.MESSAGE || line.kind == IrcLineKind.ACTION || line.kind == IrcLineKind.NOTICE
    val own = chat && sender.isNotEmpty() && sender.equals(style.ownNick, ignoreCase = true)
    val mentioned = line.mentions(style.ownNick) || HighlightMatcher.matches(line, style.highlightWords, style.ownNick, style.highlightOptions)
    val background = when {
        mentioned -> c.rowHighlight
        line.kind == IrcLineKind.NOTICE -> c.noticeRow
        own -> c.selfRow
        else -> c.background
    }
    return RowBand(background, mentioned)
}

/** An event row's badge: glyph and fill colour (iOS `MessageCell.eventBadge(for:)`). */
data class EventBadge(val glyph: String, val color: Color)

/**
 * Compact event-badge glyph+colour for an `EVENT` row (caller checks the kind). Structured events get a fixed per-kind
 * glyph; a MODE takes its first prefix symbol. Topic lines have no structured event and are matched by text; every
 * other untyped event row gets none.
 */
fun eventBadge(line: IrcLine, c: FiberIrcColors): EventBadge? {
    val event = line.event ?: run {
        val text = IrcFormatting.strip(line.text)
        return if (text.startsWith("Topic:") || text.contains(" set the topic")) EventBadge("T", c.eventTopic) else null
    }
    return when (event.kind) {
        IrcEventKind.JOIN -> EventBadge("J", c.online)
        IrcEventKind.PART -> EventBadge("P", c.error)
        IrcEventKind.QUIT -> EventBadge("Q", c.error)
        IrcEventKind.KICK -> EventBadge("K", c.error)
        IrcEventKind.NICK -> EventBadge("N", c.eventNick)
        IrcEventKind.MODE -> {
            val symbol = event.modes.firstNotNullOfOrNull { it.symbol }
            if (symbol != null) EventBadge(symbol.toString(), prefixColor(symbol.toString(), c)) else EventBadge("M", c.logText)
        }
    }
}

/** Fixed (non-scaling) letter size: iOS `avatarLetterLarge` 21 / `avatarLetterSmall` 10, bold system. */
@Composable
private fun fixedSp(value: Float): TextUnit = with(LocalDensity.current) { value.dp.toSp() }

/**
 * IRCCloud's generated avatar: a square in the nick's colour with its first letter, bold, in the background colour.
 * With [glyph]/[fill] it is an event badge instead.
 */
@Composable
fun LetterAvatar(
    nick: String,
    size: Dp,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    fill: Color? = null,
    colors: FiberIrcColors = LocalFiberIrcColors.current,
) {
    val letter = glyph ?: nick.firstOrNull()?.let { first ->
        val end = if (Character.isHighSurrogate(first) && nick.length > 1) 2 else 1
        nick.substring(0, end).uppercase()
    } ?: ""
    Box(
        modifier.size(size).background(fill ?: nickColor(nick, colors.dark)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            color = colors.background,
            fontSize = fixedSp(if (size >= Grid.leadingColumn) 21f else 10f),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Link spans in a row's body: [ChatLink] at UTF-16 [start]..<[end] of the body text. */
private class BodyLink(val start: Int, val end: Int, val link: ChatLink)

/** The built body: annotated text, its plain reading, and the tappable links in it. */
private class RowBody(val text: AnnotatedString, val plain: String, val links: List<BodyLink>)

/** Appends to an [AnnotatedString.Builder] while keeping its plain text (the colouriser matches against it). */
private class BodyBuilder {
    val b = AnnotatedString.Builder()
    val sb = StringBuilder()
    val length get() = sb.length

    fun append(s: String, style: SpanStyle) {
        b.pushStyle(style)
        b.append(s)
        b.pop()
        sb.append(s)
    }

    fun append(a: AnnotatedString) {
        b.append(a)
        sb.append(a.text)
    }
}

/** The mode-glyph + bold nick run before a sender's text, or the author line (iOS `senderText`). */
private fun BodyBuilder.appendSender(sender: String, suffix: String, member: IrcMember?, style: ChatRowStyle) {
    val prefixes = member?.prefixes ?: ""
    val boldSize = style.bold.fontSize
    ModeGlyph.glyph(prefixes, style.display.modeIndicator)?.let { glyph ->
        append("$glyph ", SpanStyle(color = prefixColor(prefixes, style.colors), fontWeight = FontWeight.SemiBold, fontSize = boldSize))
    }
    append(sender + suffix, SpanStyle(color = style.nickColor(sender), fontWeight = FontWeight.SemiBold, fontSize = boldSize))
}

private fun buildBody(line: IrcLine, style: ChatRowStyle, member: IrcMember?, emojiSize: TextUnit?): RowBody {
    val c = style.colors
    val prefs = style.display
    val sender = line.sender ?: ""
    val chat = line.kind == IrcLineKind.MESSAGE || line.kind == IrcLineKind.ACTION || line.kind == IrcLineKind.NOTICE
    val s = BodyBuilder()
    val links = ArrayList<BodyLink>()
    val regularSize = style.regular.fontSize

    // Chat text: mentions in nick colours (Colourise mentions), then URLs and channels in link colour, tagged for
    // taps (links win; no underline: web parity).
    fun appendChat(color: Color) {
        if (line.redacted) {
            s.append("(message deleted)", SpanStyle(color = c.textTertiary, fontStyle = FontStyle.Italic, fontSize = regularSize))
            return
        }
        val formatted = IrcFormatting.toAnnotatedString(line.text, color, c.background, prefs.formatColors) {
            legible(it, c.dark)
        }
        val start = s.length
        if (emojiSize != null) s.b.pushStyle(SpanStyle(fontSize = emojiSize))
        s.append(formatted)
        if (emojiSize != null) s.b.pop()
        val range = start until s.length
        if (prefs.coloriseMentions && !range.isEmpty()) style.colorizer?.apply(s.b, s.sb.toString(), range, c.dark)
        for (found in ChatLinks.find(formatted.text)) {
            val a = start + found.range.first
            val b = start + found.range.last + 1
            s.b.addStyle(SpanStyle(color = c.link), a, b)
            links += BodyLink(a, b, found.link)
        }
    }

    val plain: String
    when (line.kind) {
        IrcLineKind.MESSAGE, IrcLineKind.NOTICE -> {
            if (!style.separateNickLine && sender.isNotEmpty()) s.appendSender(sender, " ", member, style)
            val color = if (line.kind == IrcLineKind.NOTICE) serverLogColor(line.serverLogTag, c) ?: c.noticeText else c.chatText
            appendChat(color)
            plain = if (line.redacted) "(message deleted)" else IrcFormatting.strip(line.text)
        }
        IrcLineKind.ACTION -> {
            s.append("* ", SpanStyle(color = c.textTertiary, fontSize = regularSize))
            s.appendSender(sender, " ", member, style)
            appendChat(c.chatText)
            plain = if (line.redacted) "(message deleted)" else IrcFormatting.strip(line.text)
        }
        IrcLineKind.EVENT -> {
            val event = line.event
            if (event != null) {
                s.append(MembershipSummary.annotated(listOf(event), c, style.device.colouriseNicknames))
            } else {
                val text = IrcFormatting.strip(line.text)
                val arrow = MembershipSummary.arrow(text)
                if (arrow.isNotEmpty()) s.append("$arrow ", SpanStyle(color = c.logArrow))
                s.append(text, SpanStyle(color = c.logText))
            }
            plain = s.sb.toString()
        }
        IrcLineKind.ERROR -> {
            val text = IrcFormatting.strip(line.text)
            s.append("✕ $text", SpanStyle(color = c.error))
            plain = text
        }
    }
    if (line.edited && chat && !line.redacted) {
        s.append(
            " (edited)",
            SpanStyle(color = c.textTertiary, fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp),
        )
    }
    return RowBody(s.b.toAnnotatedString(), plain, links)
}

/** Where the body was placed and how it laid out, for hit-testing taps against its links. */
private class BodyHit {
    var layout: TextLayoutResult? = null
    var origin = Offset.Zero
    var reply = Rect.Zero
}

/**
 * One chat line (iOS `MessageCell.configure`): the avatar column, the text column and the time column; text never runs
 * under either. A header row has the 32dp avatar and an author line (mode glyph, nick in its colour, real name in a
 * lighter colour) above the text; grouped rows have neither. With Nicknames on Separate Line off, every row leads
 * with the bold nick beside a 16dp avatar; actions always do. Mentions get the highlight band and the 4dp accent
 * bar; your own rows and notices their own backgrounds.
 *
 * [flash] going true tints the row accent 30% for 1.2s, then fades back over 0.6s (a search result opened here).
 */
@Composable
fun MessageRow(
    row: ChatRow.Line,
    style: ChatRowStyle,
    callbacks: ChatRowCallbacks,
    modifier: Modifier = Modifier,
    flash: Boolean = false,
) {
    val line = row.line
    val header = row.header
    val c = style.colors
    val prefs = style.display
    val sender = line.sender ?: ""
    val chat = line.kind == IrcLineKind.MESSAGE || line.kind == IrcLineKind.ACTION || line.kind == IrcLineKind.NOTICE
    val band = remember(line, style) { rowBand(line, style) }
    val member = style.members[sender.lowercase()]
    val density = LocalDensity.current

    // Emoji-only lines: 32sp like the web's emoji-only rows, scaled with the text size up to 56.
    val emojiOnly = remember(line.text, prefs.enlargeEmoji, chat, line.redacted) {
        prefs.enlargeEmoji && chat && !line.redacted && EmojiOnly.matches(line.text)
    }
    val emojiSize = if (emojiOnly) {
        val scaled = EmojiOnly.fontSize.value * density.fontScale
        (minOf(scaled, EmojiOnly.maxFontSize.value) / density.fontScale).sp
    } else {
        null
    }
    val body = remember(line, style, member, emojiSize) { buildBody(line, style, member, emojiSize) }

    // Author line: a header's nick, then its real name (Show Real Names) when it says something the nick doesn't.
    val showsAuthor = header && sender.isNotEmpty()
    val author = if (showsAuthor) {
        remember(line, style, member) {
            val a = BodyBuilder()
            a.appendSender(sender, "", member, style)
            val realname = member?.realname ?: ""
            if (style.device.showRealNames && realname.isNotEmpty() && !realname.equals(sender, ignoreCase = true)) {
                a.append(" $realname", SpanStyle(color = c.textTertiary, fontWeight = FontWeight.Normal))
            }
            a.b.toAnnotatedString()
        }
    } else {
        null
    }

    // The avatar: 32dp beside a header's author line; 16dp on one-line rows and actions; none on grouped rows.
    val avatarSize: Dp? = when {
        !prefs.showUserIcons || sender.isEmpty() || !chat -> null
        line.kind == IrcLineKind.ACTION || !style.separateNickLine -> 16.dp
        header -> Grid.leadingColumn
        else -> null
    }
    // Event badge: `.event` rows have no sender, so a 16dp square keyed by event kind fills the same gutter.
    val badge = if (prefs.showUserIcons && line.kind == IrcLineKind.EVENT) eventBadge(line, c) else null
    val shownSize: Dp? = avatarSize ?: if (badge != null) 16.dp else null
    // Every chat row with a real sender, and every badged event row, keeps the avatar column reserved.
    val hasAvatarColumn = (chat && sender.isNotEmpty()) || badge != null
    val timeText = remember(line.time, prefs.timestampFormat, style.device.showSeconds) {
        MessageTime.string(line.time, prefs.timestampFormat, style.device.showSeconds)
    }

    // Flash: accent at 30% over the chat background, held 1.2s, then 0.6s back to the band.
    val flashProgress = remember { Animatable(0f) }
    LaunchedEffect(flash) {
        if (flash) {
            flashProgress.snapTo(1f)
            delay(1200)
            flashProgress.animateTo(0f, tween(600))
        }
    }
    val flashColor = c.accent.copy(alpha = 0.3f).compositeOver(c.background)

    val hit = remember { BodyHit() }
    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentLine by rememberUpdatedState(line)
    val currentBody by rememberUpdatedState(body)
    val tapSlop = with(density) { 4.dp.toPx() }

    val label = buildString {
        if (band.mentioned) append("Mentions you. ")
        if (sender.isNotEmpty()) append("$sender: ")
        append(body.plain)
    }

    Layout(
        modifier = modifier
            .testTag("messageRow")
            .drawBehind {
                drawRect(lerp(band.background, flashColor, flashProgress.value))
                if (band.mentioned) drawRect(c.accent, size = Size(Grid.unit.toPx(), size.height))
            }
            .pointerInput(Unit) {
                fun linkAt(pos: Offset): ChatLink? {
                    val layout = hit.layout ?: return null
                    val local = pos - hit.origin
                    if (local.x < -tapSlop || local.y < -tapSlop) return null
                    val offset = layout.getOffsetForPosition(local)
                    val links = currentBody.links
                    val found = links.firstOrNull { offset >= it.start && offset < it.end } ?: return null
                    val box = layout.getBoundingBox(offset.coerceAtMost(layout.layoutInput.text.length - 1))
                    val near = Rect(box.left - tapSlop, box.top - tapSlop, box.right + tapSlop, box.bottom + tapSlop)
                    return if (near.contains(local) || box.contains(local)) found.link else {
                        // Between glyphs of the same link (e.g. its last char's trailing edge): accept within the line.
                        val lineIdx = layout.getLineForOffset(offset)
                        val inLine = local.y >= layout.getLineTop(lineIdx) - tapSlop &&
                            local.y <= layout.getLineBottom(lineIdx) + tapSlop &&
                            local.x <= layout.getLineRight(lineIdx) + tapSlop
                        if (inLine) found.link else null
                    }
                }
                detectTapGestures(
                    onTap = { pos ->
                        val l = currentLine
                        val replyTo = l.replyTo
                        val padded = hit.reply.inflate(((Grid.tapTarget - 12.dp) / 2).toPx())
                        when {
                            replyTo != null && padded.contains(pos) -> currentCallbacks.onOpenThread(replyTo)
                            else -> {
                                val link = linkAt(pos)
                                if (link != null) currentCallbacks.onLink(link) else currentCallbacks.onTap()
                            }
                        }
                    },
                    onDoubleTap = {
                        val l = currentLine
                        val nick = l.sender
                        val chatKind = l.kind == IrcLineKind.MESSAGE || l.kind == IrcLineKind.ACTION ||
                            l.kind == IrcLineKind.NOTICE
                        if (!nick.isNullOrEmpty() && chatKind) currentCallbacks.onReplyToSender(nick)
                    },
                    onLongPress = { pos ->
                        val url = (linkAt(pos) as? ChatLink.Url)?.url
                        currentCallbacks.onLongPress(currentLine, url, false)
                    },
                )
            }
            .clearAndSetSemantics {
                contentDescription = label
                if (prefs.showTimestamps) stateDescription = timeText
                val actions = body.links.map { bl ->
                    val name = when (val l = bl.link) {
                        is ChatLink.Url -> l.url
                        is ChatLink.Channel -> l.name
                    }
                    CustomAccessibilityAction("Open $name") { callbacks.onLink(bl.link); true }
                }.toMutableList()
                line.replyTo?.let { parent ->
                    actions += CustomAccessibilityAction("Open Thread") { callbacks.onOpenThread(parent); true }
                }
                if (actions.isNotEmpty()) customActions = actions
            },
        content = {
            if (shownSize != null) {
                if (avatarSize != null) {
                    LetterAvatar(sender, avatarSize, Modifier.layoutId("avatar"), colors = c)
                } else if (badge != null) {
                    LetterAvatar("", 16.dp, Modifier.layoutId("avatar"), glyph = badge.glyph, fill = badge.color, colors = c)
                }
            }
            if (author != null) {
                Text(author, Modifier.layoutId("author"), style = style.bold, maxLines = 1, softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text(
                body.text,
                Modifier.layoutId("body"),
                style = style.regular.copy(color = c.chatText),
                onTextLayout = { hit.layout = it },
            )
            if (prefs.showTimestamps) {
                Text(
                    timeText,
                    Modifier.layoutId("time"),
                    style = ChatRowStyle.timeStyle,
                    color = c.textTertiary,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = if (style.columns.timeOnRight) TextAlign.End else TextAlign.Start,
                )
            }
            if (line.replyTo != null) {
                Icon(
                    Icons.Filled.ChatBubble,
                    contentDescription = null,
                    tint = c.textTertiary,
                    modifier = Modifier.layoutId("reply").size(12.dp),
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val cols = style.columns
        val textX = cols.textStart(hasAvatarColumn).roundToPx()
        val textWidth = max(0, width - textX - cols.textEndInset.roundToPx())
        val textConstraints = Constraints(maxWidth = textWidth)
        fun m(id: String, cs: Constraints): Placeable? = measurables.firstOrNull { it.layoutId == id }?.measure(cs)

        val authorP = m("author", textConstraints)
        val bodyP = m("body", textConstraints)!!
        val timeW = style.timeWidth.roundToPx()
        val timeP = m("time", Constraints.fixedWidth(timeW).copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val shownPx = shownSize?.roundToPx()
        val avatarP = shownPx?.let { m("avatar", Constraints.fixed(it, it)) }
        val replyP = m("reply", Constraints.fixed(12.dp.roundToPx(), 12.dp.roundToPx()))

        val compact = style.device.compactSpacing
        val top = (if (header) (if (compact) Grid.unit else Grid.gutter) else (if (compact) 0.dp else Grid.unit)).roundToPx()
        var y = top
        val authorY = y
        if (authorP != null) y += authorP.height
        val bodyY = y
        val baseline = bodyP[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
        val capHeight = capHeightPx(style.regular, this)
        val firstLineMiddle = bodyY + baseline - capHeight / 2f
        val timeBaseline = timeP?.get(FirstBaseline)?.takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
        val timeY = max(bodyY, bodyY + baseline - timeBaseline)
        val timeX = if (cols.timeOnRight) width - cols.timeInset.roundToPx() - timeW else cols.timeInset.roundToPx()
        val replyY = (firstLineMiddle - 6.dp.toPx()).roundToInt()

        var avatarX = 0
        var avatarY = 0
        if (avatarP != null && shownPx != null) {
            avatarY = if (shownSize == Grid.leadingColumn) top else (firstLineMiddle - shownPx / 2f).roundToInt()
            avatarX = cols.avatarX.roundToPx() + (style.avatarSize.roundToPx() - shownPx) / 2
        }
        var bottom = bodyY + bodyP.height
        if (avatarP != null) bottom = max(bottom, avatarY + avatarP.height)
        if (timeP != null) bottom = max(bottom, timeY + timeP.height)
        val height = ceil((bottom + Grid.unit.toPx())).toInt()

        hit.origin = Offset(textX.toFloat(), bodyY.toFloat())
        hit.reply = if (replyP != null) Rect(0f, replyY.toFloat(), replyP.width.toFloat(), replyY + replyP.height.toFloat()) else Rect.Zero

        layout(width, height) {
            avatarP?.place(avatarX, avatarY)
            authorP?.place(textX, authorY)
            bodyP.place(textX, bodyY)
            timeP?.place(timeX, timeY)
            replyP?.place(0, replyY)
        }
    }
}

/** Cap height of [style]'s font, approximated as 0.7 em (SF/Roboto/Menlo all sit at 0.70–0.72). */
private fun capHeightPx(style: TextStyle, density: Density): Float =
    with(density) { style.fontSize.toPx() * 0.7f }
