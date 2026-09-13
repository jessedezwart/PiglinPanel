package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.FilterChip
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.mods.ModSide

private val CLIENT_BLUE = Color(0xFF5B9BF8)
private val SERVER_AMBER = Color(0xFFE0A040)

/** Radius of the smallest node; every further dependent grows it a little. */
private const val NODE_BASE_RADIUS = 4f
private const val NODE_RADIUS_PER_DEPENDENT = 1.1f
private const val NODE_MAX_RADIUS = 16f

/** How close a click has to land, in pixels, to count as hitting a node. */
private const val HIT_SLOP = 12f

/** A mod in the graph. [dependsOn] and [neededBy] index into the same node list. */
private data class GraphNode(
    val label: String,
    val side: ModSide,
    val dependsOn: List<Int>,
    val neededBy: List<Int>,
    val missing: List<String>,
)

private data class Graph(val nodes: List<GraphNode>, val edges: List<Pair<Int, Int>>)

/**
 * The installed mods and what they require of each other, drawn as a force-directed graph.
 *
 * Built from what the jars declare, exactly like the warnings on the Installed tab, so a jar whose
 * manifest could not be read simply has no edges rather than appearing independent.
 */
@Composable
fun DependencyGraphTab(mods: List<ModFile>, groups: List<ModGroup>) {
    val labels = remember(groups) {
        buildMap {
            for (group in groups) group.active?.let { put(it.relativePath, group.displayName) }
        }
    }
    val graph = remember(mods, labels) { buildGraph(mods, labels) }

    /** Isolated mods are the majority and say nothing about dependencies; hidden by default. */
    var showIsolated by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var focused by remember(graph) { mutableStateOf<Int?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val visible = remember(graph, showIsolated) {
        if (showIsolated) graph.nodes.indices.toList()
        else graph.nodes.indices.filter { graph.nodes[it].dependsOn.isNotEmpty() || graph.nodes[it].neededBy.isNotEmpty() }
    }
    val positions = remember(graph, visible) { layout(graph, visible) }

    val measurer = rememberTextMeasurer()
    val edgeColor = MaterialTheme.colorScheme.outlineVariant
    val focusColor = MaterialTheme.colorScheme.primary
    val bothColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val labelColor = MaterialTheme.colorScheme.onSurface

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { text ->
                    query = text
                    focused = visible.firstOrNull {
                        text.isNotBlank() && graph.nodes[it].label.contains(text.trim(), ignoreCase = true)
                    }
                },
                placeholder = { Text("Find a mod", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(220.dp),
            )
            FilterChip(
                selected = showIsolated,
                onClick = { showIsolated = !showIsolated },
                label = { Text("Include mods with no dependencies") },
            )
            OutlinedButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(6f) }) { Text("+") }
            OutlinedButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.2f) }) { Text("−") }
            Button(onClick = { zoom = 1f; pan = Offset.Zero; focused = null }) { Text("Reset view") }
        }

        val selected = focused?.let { graph.nodes[it] }
        Text(
            when {
                graph.nodes.isEmpty() -> "No mods scanned yet. Run a scan on the Installed tab."
                selected == null ->
                    "${visible.size} mods, ${graph.edges.count { it.first in visible && it.second in visible }} " +
                        "dependencies. Drag to pan, click a mod to focus it."
                else -> buildString {
                    append(selected.label)
                    append("  ·  needs ")
                    append(
                        selected.dependsOn.takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { graph.nodes[it].label } ?: "nothing installed",
                    )
                    append("  ·  needed by ")
                    append(
                        selected.neededBy.takeIf { it.isNotEmpty() }
                            ?.joinToString(", ") { graph.nodes[it].label } ?: "nothing",
                    )
                    if (selected.missing.isNotEmpty()) {
                        append("  ·  missing ")
                        append(selected.missing.joinToString(", "))
                    }
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (selected?.missing?.isNotEmpty() == true) errorColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(Modifier.fillMaxSize().padding(bottom = 4.dp)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { _, delta -> pan += delta }
                    }
                    .pointerInput(graph, visible, positions, zoom, pan) {
                        detectTapGestures { tap ->
                            val transform = Transform(Size(size.width.toFloat(), size.height.toFloat()), zoom, pan)
                            focused = visible.minByOrNull { index ->
                                (transform.toScreen(positions[index]) - tap).getDistance()
                            }?.takeIf { index ->
                                (transform.toScreen(positions[index]) - tap).getDistance() <= HIT_SLOP
                            }
                        }
                    },
            ) {
                val transform = Transform(size, zoom, pan)
                val neighbours = focused?.let { index ->
                    (graph.nodes[index].dependsOn + graph.nodes[index].neededBy + index).toSet()
                }

                for ((from, to) in graph.edges) {
                    if (from !in visible || to !in visible) continue
                    val highlighted = neighbours != null && from in neighbours && to in neighbours
                    if (neighbours != null && !highlighted) continue
                    drawLine(
                        color = if (highlighted) focusColor else edgeColor,
                        start = transform.toScreen(positions[from]),
                        end = transform.toScreen(positions[to]),
                        strokeWidth = if (highlighted) 1.6f else 1f,
                    )
                }

                for (index in visible) {
                    val node = graph.nodes[index]
                    val center = transform.toScreen(positions[index])
                    val radius = min(
                        NODE_MAX_RADIUS,
                        NODE_BASE_RADIUS + node.neededBy.size * NODE_RADIUS_PER_DEPENDENT,
                    )
                    val color = when {
                        node.missing.isNotEmpty() -> errorColor
                        node.side == ModSide.CLIENT -> CLIENT_BLUE
                        node.side == ModSide.SERVER -> SERVER_AMBER
                        else -> bothColor
                    }
                    val dim = neighbours != null && index !in neighbours
                    drawCircle(
                        color = if (dim) color.copy(alpha = 0.2f) else color,
                        radius = radius,
                        center = center,
                    )
                    if (index == focused) {
                        drawCircle(
                            color = focusColor,
                            radius = radius + 3f,
                            center = center,
                            style = Stroke(width = 1.5f),
                        )
                    }
                    // Labelling every node at once is unreadable; the ones that matter are the
                    // hubs, the broken ones, and whatever is in focus.
                    val labelled = index == focused ||
                        (neighbours?.contains(index) == true) ||
                        (neighbours == null && (node.neededBy.size >= 2 || node.missing.isNotEmpty()))
                    if (labelled) {
                        drawLabel(measurer, node.label, center, radius, if (dim) color else labelColor)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawLabel(
    measurer: androidx.compose.ui.text.TextMeasurer,
    label: String,
    center: Offset,
    radius: Float,
    color: Color,
) {
    val measured = measurer.measure(label, TextStyle(fontSize = 9.sp, color = color))
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(center.x - measured.size.width / 2f, center.y + radius + 2f),
    )
}

/** Maps normalised layout coordinates onto the canvas, honouring the current zoom and pan. */
private class Transform(size: Size, private val zoom: Float, private val pan: Offset) {
    private val extent = min(size.width, size.height) * 0.9f * zoom
    private val origin = Offset(
        (size.width - extent) / 2f + pan.x,
        (size.height - extent) / 2f + pan.y,
    )

    fun toScreen(position: Offset): Offset =
        Offset(origin.x + position.x * extent, origin.y + position.y * extent)
}

/**
 * The mods and the hard dependencies between them. Only enabled jars take part: a disabled one
 * provides nothing to the loader, so an edge into it would claim a dependency that is not satisfied.
 */
private fun buildGraph(mods: List<ModFile>, labels: Map<String, String>): Graph {
    val enabled = mods.filter { !it.archived && it.metadata != null }
    if (enabled.isEmpty()) return Graph(emptyList(), emptyList())

    // Every id each jar answers to, including the ones it bundles, so a library shipped inside
    // another mod resolves to that mod rather than looking absent.
    val providerOf = HashMap<String, Int>()
    enabled.forEachIndexed { index, file ->
        file.metadata!!.ids().forEach { id -> providerOf.putIfAbsent(id, index) }
    }

    val dependsOn = List(enabled.size) { mutableListOf<Int>() }
    val neededBy = List(enabled.size) { mutableListOf<Int>() }
    val missing = List(enabled.size) { mutableListOf<String>() }
    val edges = mutableListOf<Pair<Int, Int>>()

    enabled.forEachIndexed { index, file ->
        for (id in file.metadata!!.requires) {
            val provider = providerOf[id]
            when {
                provider == null -> missing[index] += id
                provider != index -> {
                    if (provider !in dependsOn[index]) {
                        dependsOn[index] += provider
                        neededBy[provider] += index
                        edges += index to provider
                    }
                }
            }
        }
    }

    val nodes = enabled.mapIndexed { index, file ->
        GraphNode(
            label = labels[file.relativePath]
                ?: file.metadata?.name?.takeIf { it.isNotBlank() }
                ?: file.metadata?.modId
                ?: file.fileName,
            side = file.side,
            dependsOn = dependsOn[index],
            neededBy = neededBy[index],
            missing = missing[index],
        )
    }
    return Graph(nodes, edges)
}

/**
 * A force-directed layout: nodes push each other apart, dependencies pull their ends together.
 *
 * Seeded from a fixed [Random] and run to a fixed iteration count, so the same mod list always
 * lands in the same picture — a graph that reshuffled itself on every recomposition would be
 * impossible to read twice.
 */
private fun layout(graph: Graph, visible: List<Int>, iterations: Int = 300): List<Offset> {
    val positions = MutableList(graph.nodes.size) { Offset.Zero }
    if (visible.isEmpty()) return positions

    val random = Random(seed = 20260830)
    val count = visible.size
    val x = FloatArray(count)
    val y = FloatArray(count)
    val slot = HashMap<Int, Int>(count)
    visible.forEachIndexed { i, node ->
        slot[node] = i
        x[i] = random.nextFloat()
        y[i] = random.nextFloat()
    }
    val edges = graph.edges.mapNotNull { (from, to) ->
        val a = slot[from] ?: return@mapNotNull null
        val b = slot[to] ?: return@mapNotNull null
        a to b
    }

    // The ideal edge length for a unit square holding `count` nodes.
    val k = sqrt(1f / count)
    var temperature = 0.1f
    val dx = FloatArray(count)
    val dy = FloatArray(count)

    repeat(iterations) {
        dx.fill(0f)
        dy.fill(0f)

        for (i in 0 until count) {
            for (j in i + 1 until count) {
                var deltaX = x[i] - x[j]
                var deltaY = y[i] - y[j]
                var distance = sqrt(deltaX * deltaX + deltaY * deltaY)
                if (distance < 1e-4f) {
                    // Coincident nodes have no direction to separate along; nudge them apart.
                    deltaX = random.nextFloat() - 0.5f
                    deltaY = random.nextFloat() - 0.5f
                    distance = 1e-4f
                }
                val force = k * k / distance
                val fx = deltaX / distance * force
                val fy = deltaY / distance * force
                dx[i] += fx; dy[i] += fy
                dx[j] -= fx; dy[j] -= fy
            }
        }

        for ((a, b) in edges) {
            val deltaX = x[a] - x[b]
            val deltaY = y[a] - y[b]
            val distance = max(1e-4f, sqrt(deltaX * deltaX + deltaY * deltaY))
            val force = distance * distance / k
            val fx = deltaX / distance * force
            val fy = deltaY / distance * force
            dx[a] -= fx; dy[a] -= fy
            dx[b] += fx; dy[b] += fy
        }

        for (i in 0 until count) {
            val length = max(1e-4f, sqrt(dx[i] * dx[i] + dy[i] * dy[i]))
            x[i] += dx[i] / length * min(length, temperature)
            y[i] += dy[i] / length * min(length, temperature)
        }
        temperature *= 0.985f
    }

    // Fit whatever shape came out into the unit square the canvas transform expects.
    val minX = x.min()
    val maxX = x.max()
    val minY = y.min()
    val maxY = y.max()
    val span = max(1e-4f, max(maxX - minX, maxY - minY))
    visible.forEachIndexed { i, node ->
        positions[node] = Offset((x[i] - minX) / span, (y[i] - minY) / span)
    }
    return positions
}
