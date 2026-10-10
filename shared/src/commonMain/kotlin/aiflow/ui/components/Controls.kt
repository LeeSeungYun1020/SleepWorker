package aiflow.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer

@Composable
fun SelectField(label: String, value: String, options: List<String>, enabled: Boolean = true, onSelect: (String) -> Unit) =
    SelectField(label, value, options.distinct(), { it.ifBlank { "없음" } }, enabled, onSelect)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SelectField(label: String, value: T?, options: List<T>, optionLabel: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded && enabled, { if (enabled) expanded = it }) {
        OutlinedTextField(value?.let(optionLabel) ?: "선택 필요", {}, readOnly = true, enabled = enabled, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded && enabled, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { expanded = false; onSelect(option) }) } }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComboField(label: String, value: String, suggestions: List<String>, enabled: Boolean = true, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded && enabled, { expanded = it }) {
        OutlinedTextField(value, { onValueChange(it); expanded = true }, enabled = enabled, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded, Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable)) }, modifier = Modifier.trackTextInputFocus().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded && enabled, { expanded = false }) { suggestions.filter { value.isBlank() || it.contains(value, true) }.distinct().forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onValueChange(option) }) } }
    }
}
@Composable
fun <T> SegmentedChoice(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge
    val labels = options.map(label)
    // Reserve the selected icon, its gap and horizontal padding in every segment.
    val segmentWidth = with(density) { (labels.maxOfOrNull { measurer.measure(it, style, softWrap = false).size.width } ?: 0).toDp() } + 56.dp
    SingleChoiceSegmentedButtonRow(Modifier.width(segmentWidth * options.size - 1.dp * (options.size - 1).coerceAtLeast(0))) { options.forEachIndexed { i, option -> SegmentedButton(selected = option == selected, onClick = { onSelect(option) }, enabled = enabled, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label(option), maxLines = 1, softWrap = false) } } }
}
/** Failed commits retain input and stay editable; Escape restores the accepted value. */
@Composable
fun CommitTextField(label: String, value: String, enabled: Boolean = true, validate: (String) -> String? = { null }, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var accepted by remember(value) { mutableStateOf(value) }
    val registry = LocalPendingIdentifierEdits.current
    val token = remember { Any() }
    var error by remember(value) { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val latestCommit by rememberUpdatedState(onCommit)
    fun commit(): Boolean {
        if (!enabled || text == accepted) return true
        error = validate(text)
        if (error != null) return false
        return try { latestCommit(text); accepted = text; true } catch (e: Exception) { error = e.message ?: "이름을 확인하세요"; false }
    }
    SideEffect { registry?.register(token, if (text != accepted) ::commit else null) }
    DisposableEffect(registry) { onDispose { registry?.register(token, null) } }
    val invalid = error ?: if (text != value) validate(text) else null
    OutlinedTextField(text, { text = it; error = null }, label = { Text(label) }, enabled = enabled, singleLine = true, isError = invalid != null,
        supportingText = { Text(invalid ?: if (text != value) "Enter 또는 포커스 이탈 시 반영 · Esc 취소" else "참조도 함께 갱신됩니다") },
        trailingIcon = { if (text != value) Icon(Icons.Outlined.Edit, "반영 대기") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.trackTextInputFocus().fillMaxWidth().onPreviewKeyEvent { e -> when { e.type != KeyEventType.KeyDown -> false; e.key == Key.Escape && (text != latestValue || error != null) -> { text = latestValue; error = null; true }; e.key == Key.Enter -> { commit(); true }; else -> false } }.onFocusChanged { state -> if (focused && !state.isFocused) commit(); focused = state.isFocused })
}
@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); content() }
    }
}
@Composable
fun ExpandableSection(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text(title) }, trailingContent = { Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "접기" else "펼치기") }, modifier = Modifier.clickable { open = !open })
    if (open) content()
}
@Composable
fun SelectionItem(title: String, supporting: String? = null, selected: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } }, leadingContent = icon?.let { { Icon(it, null) } }, colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick))
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolIcon(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) { IconButton(onClick, enabled = enabled) { Icon(icon, label) } }
}
@Composable
fun EmptyState(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(40.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Text(description, style = MaterialTheme.typography.bodyMedium)
        if (action != null) FilledTonalButton(onAction) { Text(action) }
    }
}
@Composable
fun DetailRow(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) } }
@Composable
fun StatusBadge(label: String, icon: ImageVector = Icons.Outlined.Edit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(icon, null, Modifier.size(16.dp)); Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}
