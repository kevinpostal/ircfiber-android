package com.kevinpostal.fiberirc.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinpostal.fiberirc.data.prefs.AccountSettingsStore
import com.kevinpostal.fiberirc.ui.theme.LocalFiberIrcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val MAX_WORDS = 100
private const val MAX_WORD_BYTES = 64

/**
 * The server's rules (trimmed, ≤64 UTF-8 bytes, unique ignoring case, ≤100 words), checked up front so the user
 * gets a reason instead of a silently normalized list. Null = [word] may be added.
 */
internal fun highlightWordProblem(word: String, words: List<String>): String? = when {
    word.toByteArray(Charsets.UTF_8).size > MAX_WORD_BYTES -> "Highlight words can be at most 64 bytes."
    words.any { it.equals(word, ignoreCase = true) } -> "“$word” is already a highlight word."
    words.size >= MAX_WORDS -> "You can have at most 100 highlight words."
    else -> null
}

/**
 * Settings → Highlight Words (ports `HighlightWordsViewController`): words that highlight a message like a
 * mention of your nick, kept on the account and shared with the web. Add (+), delete per row (swipe or ✕); every
 * change saves the whole list through [AccountSettingsStore.setHighlightWords] (POST /api/me/highlight-words).
 */
@Composable
fun HighlightWordsScreen(accountSettings: AccountSettingsStore, onBack: () -> Unit) {
    val c = LocalFiberIrcColors.current
    val scope = rememberCoroutineScope()
    val alerts = rememberAlertState()
    val words by accountSettings.highlightWords.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    fun save(list: List<String>) {
        scope.launch {
            try {
                accountSettings.setHighlightWords(list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                alerts.show("Couldn't save highlight words", e)
            }
        }
    }

    fun add(raw: String) {
        val word = raw.trim()
        if (word.isEmpty()) return
        highlightWordProblem(word, words)?.let { alerts.show("Add Highlight Word", it); return }
        save(words + word)
    }

    SettingsScaffold(
        title = "Highlight Words",
        onBack = onBack,
        actions = {
            IconButton(onClick = { draft = ""; adding = true }) { Icon(Icons.Filled.Add, contentDescription = "Add Highlight Word") }
        },
        message = if (words.isEmpty()) "No custom highlight words set. Your nickname is highlighted by default." else null,
    ) {
        if (words.isNotEmpty()) {
            settingsSection(
                key = "words",
                footer = "Messages containing these words are highlighted like mentions of your nick. Saved to your " +
                    "account and used on the web too.",
                rows = words.map { word ->
                    {
                        SwipeActionRow("Delete", c.error, onSwipe = { save(words.filter { it != word }) }) {
                            SettingsRow(word) {
                                IconButton(onClick = { save(words.filter { it != word }) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Delete $word", tint = c.textSecondary)
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add Highlight Word") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { adding = false; add(draft) }),
                )
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = { adding = false; add(draft) }) { Text("Add") } },
        )
    }
    ErrorDialog(alerts)
}
