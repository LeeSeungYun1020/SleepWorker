package aiflow.ui.editor

import aiflow.model.*
import aiflow.ui.components.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import aiflow.model.Target
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import kotlin.math.*

private data class Curve(val from: Offset, val c1: Offset, val c2: Offset, val to: Offset) {
    fun at(t: Float): Offset { val u = 1 - t; return from * (u*u*u) + c1 * (3*u*u*t) + c2 * (3*u*t*t) + to * (t*t*t) }
    fun path() = Path().apply { moveTo(from.x, from.y); cubicTo(c1.x, c1.y, c2.x, c2.y, to.x, to.y) }
}

@Composable
fun GraphCanvas(vm: EditorViewModel, w: Workflow, focus: Pair<String, Int>?, onConnect: (String, String) -> Unit, modifier: Modifier = Modifier, editingEnabled: Boolean = !vm.readOnly, beforeSelection: () -> Boolean = { true }) {
    val node by vm.selectedNode.collectAsState(); val selectedEdge by vm.selectedEdge.collectAsState()
    val issues by vm.issues.collectAsState()
    val density = LocalDensity.current.density
    var zoom by remember { mutableStateOf(.72f) }; var pan by remember { mutableStateOf(Offset(24f, 32f)) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf<Pair<String, NodePosition>?>(null) }
    var wire by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    val positions = w.editor?.nodes.orEmpty() + listOfNotNull(dragging).toMap()
    val reachable = remember(w.start, w.steps) { GraphLayout.reachable(w) }
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    fun screen(p: Offset) = (p * zoom + pan) * density
    fun logical(p: Offset) = (p / density - pan) / zoom
    fun fit() {
        if (positions.isEmpty() || size.width == 0) return
        val minX = positions.values.minOf { it.x }; val minY = positions.values.minOf { it.y }
        val maxX = positions.values.maxOf { it.x } + 220; val maxY = positions.values.maxOf { it.y } + 115
        zoom = minOf((size.width / density - 64) / (maxX - minX), (size.height / density - 64) / (maxY - minY)).coerceIn(.18f, 1.5f)
        pan = Offset(32 - minX * zoom, 32 - minY * zoom)
    }
    LaunchedEffect(focus) { focus?.first?.let { id -> positions[id]?.let { pan = Offset(size.width / density / 2 - (it.x + 110) * zoom, size.height / density / 2 - (it.y + 58) * zoom) } } }
    val edges = buildList {
        if (w.start.isNotBlank() && positions[w.start] != null) add(Triple("start", -1, Transition(Condition.Otherwise, Target.StepId(w.start))))
        w.steps.forEach { step -> step.transitions.forEachIndexed { i, t -> if (positions[targetId(t.next)] != null) add(Triple(step.id, i, t)) } }
    }
    fun curve(source: String, i: Int, target: String): Curve? {
        val a = positions[source] ?: return null; val b = positions[target] ?: return null
        val from = Offset(a.x + 220, a.y + 58); val to = Offset(b.x, b.y + 58)
        val ordinal = if (i < 0) 0 else w.steps.firstOrNull { it.id == source }?.transitions?.take(i)?.count { targetId(it.next) == target } ?: 0
        val spread = 35f * ordinal
        return if (source == target) Curve(from, from + Offset(100f + spread, -160f - spread), to + Offset(-100f - spread, -160f - spread), to)
        else if (to.x < from.x) Curve(from, from + Offset(90f + spread, -100f - spread - i.coerceAtLeast(0) * 25), to + Offset(-90f - spread, -100f - spread - i.coerceAtLeast(0) * 25), to)
        else { val bend = maxOf(65f, (to.x - from.x) / 2); Curve(from, from + Offset(bend, spread), to + Offset(-bend, spread), to) }
    }
    Box(modifier.background(colors.surfaceContainerLowest).onSizeChanged { size = it }.clipToBounds()) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(zoom) { detectTransformGestures { centroid, movement, factor, _ ->
                val before = logical(centroid); zoom = (zoom * factor).coerceIn(.18f, 2f)
                pan = centroid / density - before * zoom + movement / density
            } }
            .pointerInput(edges, positions, zoom, pan) { detectTapGestures { pointer ->
                val previousNode = vm.selectedNode.value
                if (!beforeSelection()) return@detectTapGestures
                val p = logical(pointer)
                val nearest = edges.mapNotNull { (source, i, t) -> curve(source, i, targetId(t.next))?.let { c -> Triple(source, i, (0..40).minOf { (c.at(it / 40f) - p).getDistance() }) } }.minByOrNull { it.third }
                if (nearest != null && nearest.third < 20 / zoom && nearest.second >= 0) vm.selectEdge(if (nearest.first == previousNode && vm.workflow?.steps?.none { it.id == nearest.first } == true) vm.selectedNode.value ?: nearest.first else nearest.first, nearest.second) else vm.selectNode(null)
            } }) {
            edges.forEach { (source, index, transition) ->
                val c = curve(source, index, targetId(transition.next)) ?: return@forEach
                val selected = selectedEdge == EdgeSelection(source, index)
                val tint = if (selected) colors.primary else colors.outline
                val path = Curve(screen(c.from), screen(c.c1), screen(c.c2), screen(c.to)).path()
                drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(if (selected) 3.dp.toPx() else 1.5.dp.toPx()))
                val point = screen(c.to); val previous = screen(c.at(.96f)); val vector = (point - previous).let { it / it.getDistance().coerceAtLeast(1f) }; val side = Offset(-vector.y, vector.x)
                drawPath(Path().apply { moveTo(point.x, point.y); val a = point - vector * 10.dp.toPx() + side * 5.dp.toPx(); lineTo(a.x, a.y); val b = point - vector * 10.dp.toPx() - side * 5.dp.toPx(); lineTo(b.x, b.y); close() }, tint)
                val label = if (index < 0) "start" else "${index + 1} · ${conditionName(transition.`when`)}${transition.maxVisits?.let { " ≤$it" }.orEmpty()}${if (transition.resetSession) " ↺ session" else ""}"
                val measured = measurer.measure(AnnotatedString(label), TextStyle(color = if (selected) colors.primary else colors.onSurface, fontSize = (11 * zoom.coerceAtLeast(.7f)).sp))
                val at = screen(c.at(.5f)) - Offset(measured.size.width / 2f, measured.size.height / 2f)
                drawRect(colors.surface, at - Offset(3f, 2f), androidx.compose.ui.geometry.Size(measured.size.width + 6f, measured.size.height + 4f))
                drawText(measured, topLeft = at)
            }
            wire?.let { (source, end) -> positions[source]?.let { p -> drawLine(colors.primary, screen(Offset(p.x + 220, p.y + 58)), screen(end), 2.dp.toPx()) } }
        }
        (GraphLayout.special + w.steps.map { it.id }).distinct().forEach { id ->
            val p = positions[id] ?: return@forEach
            val savedPosition by rememberUpdatedState(w.editor?.nodes?.get(id) ?: p)
            val step = w.steps.firstOrNull { it.id == id }
            val badges = issues.filter { it.stepId == id }; val orphan = step != null && id !in reachable
            Surface(color = when { node == id -> colors.primaryContainer; orphan -> colors.surfaceContainerHigh; else -> colors.surfaceContainerHigh }, border = BorderStroke(if (node == id) 2.dp else 1.dp, if (badges.any { it.severity == Severity.ERROR }) colors.error else colors.outlineVariant), shape = MaterialTheme.shapes.medium,
                modifier = Modifier.offset { IntOffset(((p.x * zoom + pan.x) * density).roundToInt(), ((p.y * zoom + pan.y) * density).roundToInt()) }.size((220 * zoom).dp, (115 * zoom).dp)
                    .pointerInput(id, zoom, editingEnabled) {
                        if (editingEnabled) detectDragGestures(onDragStart = { if (beforeSelection() && (id in GraphLayout.special || vm.workflow?.steps?.any { it.id == id } == true)) { vm.selectNode(id); dragging = id to savedPosition } }, onDragCancel = { dragging = null }, onDragEnd = { dragging?.let { vm.moveNode(it.first, it.second) }; dragging = null }) { change, amount -> change.consume(); val origin = dragging?.second ?: return@detectDragGestures; dragging = id to NodePosition(origin.x + amount.x / density / zoom, origin.y + amount.y / density / zoom) }
                    }.semantics { contentDescription = "단계 $id ${step?.title.orEmpty()}" }.clickable { if (beforeSelection() && (id in GraphLayout.special || vm.workflow?.steps?.any { it.id == id } == true)) vm.selectNode(id) }) {
                Box {
                    Column(Modifier.padding((10 * zoom).dp)) {
                        Text(if (step == null) id.uppercase() else "${step.id} ${step.title.orEmpty()}", fontSize = (14 * zoom).sp, lineHeight = (18 * zoom).sp, maxLines = 1)
                        if (step != null) {
                            Text(if (step.effectiveKind == StepKind.SHELL) "! Shell" else "${w.sessions[step.session?.ref]?.provider ?: "Agent"} · ${step.session?.ref.orEmpty()} / ${step.session?.mode?.label() ?: "?"}", fontSize = (11 * zoom).sp, lineHeight = (15 * zoom).sp, maxLines = 1)
                            Text(if (step.effectiveKind == StepKind.AGENT) "${step.model ?: "모델 기본값"} · ${step.effort?.name?.lowercase() ?: "effort 기본값"}" else "${step.workspace?.label() ?: "workspace 필요"}", fontSize = (10 * zoom).sp, lineHeight = (14 * zoom).sp, maxLines = 1)
                            Text(if (badges.isNotEmpty()) "오류 ${badges.count { it.severity == Severity.ERROR }} · 경고 ${badges.count { it.severity == Severity.WARNING }}" else if (orphan) "도달 불가" else "출력 ●에서 연결", fontSize = (10 * zoom).sp, lineHeight = (14 * zoom).sp, color = if (badges.any { it.severity == Severity.ERROR }) colors.error else colors.onSurfaceVariant)
                        }
                    }
                    if (editingEnabled && id !in listOf("end", "ask")) Box(Modifier.align(Alignment.CenterEnd).size((26 * zoom).dp).background(colors.primary, androidx.compose.foundation.shape.CircleShape)
                        .pointerInput(id, positions, zoom, pan) { detectDragGestures(onDragStart = { wire = id to Offset(p.x + 220, p.y + 58) }, onDragCancel = { wire = null }, onDragEnd = {
                            wire?.let { (_, end) -> positions.entries.firstOrNull { (target, pos) -> target != "start" && end.x in pos.x..pos.x + 220 && end.y in pos.y..pos.y + 115 }?.let { (target, _) -> onConnect(id, target) } }; wire = null
                        }) { change, amount -> change.consume(); wire = id to ((wire?.second ?: Offset(p.x + 220, p.y + 58)) + amount / density / zoom) } })
                }
            }
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(12.dp), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 2.dp) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolIcon("노드 추가", Icons.Outlined.Add, editingEnabled) { vm.addNode() }
                ToolIcon("자동 배치", Icons.Outlined.AutoAwesomeMosaic, editingEnabled) { vm.autoLayout() }
                ToolIcon("전체 맞춤", Icons.Outlined.FitScreen) { fit() }
                ToolIcon("축소", Icons.Outlined.ZoomOut) { zoom = (zoom / 1.2f).coerceAtLeast(.18f) }
                ToolIcon("확대", Icons.Outlined.ZoomIn) { zoom = (zoom * 1.2f).coerceAtMost(2f) }
                Text("${(zoom * 100).toInt()}%", Modifier.padding(12.dp))
            }
        }
    }
}
