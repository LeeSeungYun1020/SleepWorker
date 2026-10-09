package aiflow

import aiflow.ui.components.*
import aiflow.ui.theme.*
import androidx.compose.ui.graphics.luminance
import kotlin.test.*

class InputAndThemeTest {
    @Test fun disposingAnOldFieldDoesNotClearTheNewFieldsFocus() {
        val tracker = TextInputFocus(); val old = Any(); val next = Any()
        tracker.update(old, true); tracker.update(next, true); tracker.update(old, false)
        assertTrue(tracker.active.value)
        tracker.update(next, false); assertFalse(tracker.active.value)
    }
    @Test fun invalidIdentifierBlocksSelectionAndValidInputCommitsBeforeSelection() {
        val edits = PendingIdentifierEdits(); val field = Any()
        var valid = false; var committed = false; var selected = false
        edits.register(field) { if (valid) { committed = true; true } else false }
        if (edits.commit()) selected = true
        assertFalse(selected); assertFalse(committed)
        valid = true
        if (edits.commit()) { assertTrue(committed); selected = true }
        assertTrue(selected)
        edits.register(field, null); assertTrue(edits.commit())
    }
    @Test fun stateColorPairsKeepReadableTextInBothThemes() {
        listOf(Light, Dark).forEach { colors ->
            listOf(colors.primary to colors.onPrimary, colors.secondary to colors.onSecondary,
                colors.tertiaryContainer to colors.onTertiaryContainer, colors.errorContainer to colors.onErrorContainer,
                colors.surfaceContainerLow to colors.onSurface).forEach { (background, foreground) ->
                val high = maxOf(background.luminance(), foreground.luminance())
                val low = minOf(background.luminance(), foreground.luminance())
                assertTrue((high + .05f) / (low + .05f) >= 4.5f)
            }
        }
    }
}
