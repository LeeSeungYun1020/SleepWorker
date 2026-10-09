package aiflow.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.MutableStateFlow

/** Tokens keep disposal of one field from clearing another field's focus. */
class TextInputFocus {
    private val focused = mutableSetOf<Any>()
    val active = MutableStateFlow(false)
    fun update(token: Any, hasFocus: Boolean) {
        if (hasFocus) focused.add(token) else focused.remove(token)
        active.value = focused.isNotEmpty()
    }
}
val LocalTextInputFocus = staticCompositionLocalOf<TextInputFocus?> { null }
fun Modifier.trackTextInputFocus(): Modifier = composed {
    val owner = LocalTextInputFocus.current
    val token = remember { Any() }
    DisposableEffect(owner) { onDispose { owner?.update(token, false) } }
    onFocusChanged { owner?.update(token, it.isFocused) }
}

/** A selection must not dispose invalid identifier input without feedback. */
class PendingIdentifierEdits {
    private val commits = mutableMapOf<Any, () -> Boolean>()
    fun register(token: Any, commit: (() -> Boolean)?) { if (commit == null) commits.remove(token) else commits[token] = commit }
    fun commit(): Boolean = commits.values.toList().all { it() }
}
val LocalPendingIdentifierEdits = staticCompositionLocalOf<PendingIdentifierEdits?> { null }
