package com.example.riegoparcela

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.*

data class P(val x: Float, val y: Float)

enum class SprinklerType(
    val label: String,
    val flowLMin: Float,
    val radiusM: Float,
    val angle: Float
) {
    ROTOR("Rotor 90°", 8f, 7f, 90f),
    SPRAY("Difusor 180°", 6f, 4f, 180f),
    FULL("Aspersor 360°", 12f, 5f, 360f),
    STRIP("Banda 180°", 4f, 5f, 180f)
}

data class Sprinkler(
    val p: P,
    val type: SprinklerType,
    val zone: Int,
    val manualRadius: Float? = null
)

data class Pipe(
    val a: P,
    val b: P,
    val diameterMm: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IrrigationApp() {
    var parcel by remember { mutableStateOf(listOf(P(2f,2f), P(18f,2f), P(20f,8f), P(16f,12f), P(4f,11f))) }
    var sprinklers by remember { mutableStateOf(listOf<Sprinkler>()) }
    var pipes by remember { mutableStateOf(listOf<Pipe>()) }
    var mode by remember { mutableStateOf("parcela") }
    var sprinklerType by remember { mutableStateOf(SprinklerType.ROTOR) }
    var zone by remember { mutableStateOf(1) }
    var pressure by remember { mutableStateOf(3.0f) }
    var sourceFlow by remember { mutableStateOf(30f) }
    var scale by remember { mutableStateOf(28f) }
    var showCoverage by remember { mutableStateOf(true) }
    var showGrid by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Dibuja la parcela tocando puntos del plano.") }

    val area = polygonArea(parcel)
    val perimeter = polygonPerimeter(parcel)
    val zones = sprinklers.map { it.zone }.distinct().sorted()
    val coverage = calculateCoverage(parcel, sprinklers)
    val zoneFlows = zones.associateWith { z -> sprinklers.filter { it.zone == z }.sumOf { it.type.flowLMin.toDouble() }.toFloat() }
    val maxZoneFlow = zoneFlows.values.maxOrNull() ?: 0f
    val recommendedDiameter = when {
        maxZoneFlow <= 12 -> 20
        maxZoneFlow <= 25 -> 25
        maxZoneFlow <= 45 -> 32
        maxZoneFlow <= 70 -> 40
        else -> 50
    }
    val materials = buildMaterials(parcel, sprinklers, pipes, zones, recommendedDiameter)

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Diseñador de Riego V2") }) }
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    listOf("Plano", "Cálculo", "Materiales").forEachIndexed { i, title ->
                        Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(title) })
                    }
                }

                when (selectedTab) {
                    0 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(mode == "parcela", { mode = "parcela" }, label = { Text("Dibujar parcela") })
                            FilterChip(mode == "aspersor", { mode = "aspersor" }, label = { Text("Aspersores") })
                            FilterChip(mode == "tuberia", { mode = "tuberia" }, label = { Text("Tubería") })
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = {
                                zone++
                                status = "Zona $zone creada. Selecciona Aspersores y colócalos."
                            }) { Text("Nueva zona") }
                            OutlinedButton(onClick = {
                                parcel = emptyList(); sprinklers = emptyList(); pipes = emptyList(); zone = 1
                                status = "Proyecto vacío."
                            }) { Text("Limpiar") }
                            FilterChip(showGrid, { showGrid = !showGrid }, label = { Text("Cuadrícula") })
                            FilterChip(showCoverage, { showCoverage = !showCoverage }, label = { Text("Cobertura") })
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tipo: ${sprinklerType.label}", modifier = Modifier.width(150.dp))
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { expanded = true }) { Text("Cambiar") }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    SprinklerType.entries.forEach {
                                        DropdownMenuItem(
                                            text = { Text("${it.label} · ${it.flowLMin} L/min · ${it.radiusM} m") },
                                            onClick = { sprinklerType = it; expanded = false }
                                        )
                                    }
                                }
                            }
                        }

                        Text(status, style = MaterialTheme.typography.bodySmall)

                        Box(
                            Modifier.fillMaxWidth().weight(1f)
                                .background(Color(0xFFF2F5F0), RoundedCornerShape(12.dp))
                        ) {
                            Canvas(
                                Modifier.fillMaxSize().pointerInput(mode, sprinklerType, zone, parcel, sprinklers, pipes) {
                                    detectTapGestures { pos ->
                                        val fitted = fitTransform(parcel, size.width, size.height, scale)
                                        val world = fitted.toWorld(pos)
                                        when (mode) {
                                            "parcela" -> {
                                                parcel = parcel + world
                                                status = "Punto añadido: %.1f, %.1f m".format(world.x, world.y)
                                            }
                                            "aspersor" -> {
                                                if (parcel.size >= 3 && pointInPolygon(world, parcel)) {
                                                    sprinklers = sprinklers + Sprinkler(world, sprinklerType, zone)
                                                    status = "${sprinklerType.label} añadido a la zona $zone."
                                                } else status = "Coloca el aspersor dentro de la parcela."
                                            }
                                            "tuberia" -> {
                                                if (parcel.size >= 3 && pointInPolygon(world, parcel)) {
                                                    if (pipes.isEmpty() || pipes.last().b != world) {
                                                        val previous = pipes.lastOrNull()?.b ?: P(0f,0f)
                                                        pipes = if (pipes.isEmpty())
                                                            listOf(Pipe(P(0f,0f), world, recommendedDiameter))
                                                        else pipes + Pipe(previous, world, recommendedDiameter)
                                                        status = "Tramo de tubería añadido: Ø${recommendedDiameter} mm."
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            ) {
                                val tr = fitTransform(parcel, size.width, size.height, scale)
                                if (showGrid) drawGrid(this, tr)
                                if (parcel.isNotEmpty()) {
                                    val pts = parcel.map(tr::toScreen)
                                    val path = Path().apply {
                                        moveTo(pts[0].x, pts[0].y)
                                        pts.drop(1).forEach { lineTo(it.x, it.y) }
                                    }
                                    if (pts.size >= 3) {
                                        close()
                                        drawPath(path, Color(0xFFB7D7B1))
                                        drawPath(path, Color(0xFF2F5D38), style = Stroke(5f))
                                    } else drawPath(path, Color(0xFF2F5D38), style = Stroke(5f))
                                    pts.forEach { drawCircle(Color(0xFF2F5D38), it, 6f) }
                                }

                                if (showCoverage) {
                                    sprinklers.forEach { s ->
                                        val sp = tr.toScreen(s.p)
                                        drawCircle(Color(0x221976D2), s.type.radiusM * scale)
                                        drawCircle(Color(0xFF1976D2), sp, 7f)
                                    }
                                } else {
                                    sprinklers.forEach { s -> drawCircle(Color(0xFF1976D2), tr.toScreen(s.p), 7f) }
                                }

                                pipes.forEach { pipe ->
                                    drawLine(
                                        Color(0xFF555555),
                                        tr.toScreen(pipe.a),
                                        tr.toScreen(pipe.b),
                                        max(3f, pipe.diameterMm / 5f)
                                    )
                                }
                            }
                        }

                        Text(
                            "Parcela: %.1f m² · perímetro %.1f m · %d aspersores · %d zonas".format(
                                area, perimeter, sprinklers.size, zones.size
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    1 -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item {
                                Text("Parámetros hidráulicos", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = pressure.toString(),
                                        onValueChange = { it.toFloatOrNull()?.let { v -> pressure = v.coerceAtLeast(0f) } },
                                        label = { Text("Presión (bar)") },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = sourceFlow.toString(),
                                        onValueChange = { it.toFloatOrNull()?.let { v -> sourceFlow = v.coerceAtLeast(0f) } },
                                        label = { Text("Caudal disponible L/min") },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            item {
                                Card {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("Cobertura estimada", style = MaterialTheme.typography.titleMedium)
                                        Text("%.1f m² de %.1f m² (%.0f%%)".format(coverage.coveredArea, area, coverage.percent))
                                        Text("Solape recomendado: los círculos deben solaparse para evitar zonas secas.")
                                    }
                                }
                            }
                            item {
                                Text("Sectores", style = MaterialTheme.typography.titleMedium)
                            }
                            items(zones) { z ->
                                val f = zoneFlows[z] ?: 0f
                                val ok = f <= sourceFlow
                                Card {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("Zona $z · ${sprinklers.count { it.zone == z }} emisores")
                                        Text("Caudal estimado: %.1f L/min".format(f))
                                        Text(if (ok) "Dentro del caudal disponible" else "EXCEDE el caudal disponible")
                                        Text("Presión de entrada indicada: %.1f bar".format(pressure))
                                    }
                                }
                            }
                            item {
                                Card {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("Tubería recomendada", style = MaterialTheme.typography.titleMedium)
                                        Text("Para la zona de mayor consumo: Ø$recommendedDiameter mm como punto de partida.")
                                        Text("Caudal máximo de una zona: %.1f L/min".format(maxZoneFlow))
                                        Text("El diámetro final debe verificarse con pérdidas de carga, longitud, desnivel y presión mínima de los emisores.")
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { Text("Lista de materiales", style = MaterialTheme.typography.titleMedium) }
                            items(materials) { m ->
                                Card {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(m.name, Modifier.weight(1f))
                                        Text(m.qty)
                                    }
                                }
                            }
                            item {
                                Text(
                                    "Las cantidades son una estimación basada en el plano. Para obra real deben revisarse trazado, accesorios, presión, caudal y normativa.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class Transform(val ox: Float, val oy: Float, val scale: Float) {
    fun toScreen(p: P) = Offset(ox + p.x * scale, oy + p.y * scale)
    fun toWorld(o: Offset) = P((o.x - ox) / scale, (o.y - oy) / scale)
}

fun fitTransform(poly: List<P>, w: Float, h: Float, scaleWanted: Float): Transform {
    if (poly.isEmpty()) return Transform(w/2f, h/2f, scaleWanted)
    val minX = poly.minOf { it.x }; val maxX = poly.maxOf { it.x }
    val minY = poly.minOf { it.y }; val maxY = poly.maxOf { it.y }
    val sx = (w - 50f) / max(1f, maxX - minX)
    val sy = (h - 50f) / max(1f, maxY - minY)
    val s = min(scaleWanted, min(sx, sy))
    val ox = (w - (maxX-minX)*s)/2f - minX*s
    val oy = (h - (maxY-minY)*s)/2f - minY*s
    return Transform(ox, oy, s)
}

fun drawGrid(scope: DrawScope, tr: Transform) {
    val maxX = scope.size.width / tr.scale + 10
    val maxY = scope.size.height / tr.scale + 10
    var x = -10f
    while (x < maxX) {
        val sx = tr.toScreen(P(x,0f)).x
        scope.drawLine(Color(0xFFE0E6E0), Offset(sx,0f), Offset(sx,scope.size.height), 1f)
        x += 1f
    }
    var y = -10f
    while (y < maxY) {
        val sy = tr.toScreen(P(0f,y)).y
        scope.drawLine(Color(0xFFE0E6E0), Offset(0f,sy), Offset(scope.size.width,sy), 1f)
        y += 1f
    }
}

fun polygonArea(p: List<P>): Float {
    if (p.size < 3) return 0f
    var sum = 0f
    for (i in p.indices) {
        val a = p[i]; val b = p[(i+1)%p.size]
        sum += a.x*b.y - b.x*a.y
    }
    return abs(sum)/2f
}

fun polygonPerimeter(p: List<P>): Float {
    if (p.size < 2) return 0f
    return p.indices.sumOf {
        val a = p[it]; val b = p[(it+1)%p.size]
        hypot((a.x-b.x).toDouble(), (a.y-b.y).toDouble())
    }.toFloat()
}

fun pointInPolygon(pt: P, poly: List<P>): Boolean {
    var inside = false
    var j = poly.lastIndex
    for (i in poly.indices) {
        val xi=poly[i].x; val yi=poly[i].y; val xj=poly[j].x; val yj=poly[j].y
        val intersect = ((yi > pt.y) != (yj > pt.y)) &&
            (pt.x < (xj-xi)*(pt.y-yi)/(yj-yi + 0.00001f) + xi)
        if (intersect) inside = !inside
        j=i
    }
    return inside
}

data class Coverage(val coveredArea: Float, val percent: Float)

fun calculateCoverage(poly: List<P>, sprinklers: List<Sprinkler>): Coverage {
    val area = polygonArea(poly)
    if (area <= 0f || sprinklers.isEmpty()) return Coverage(0f,0f)
    // Conservative estimate: union area is approximated by sampling a grid.
    val minX=poly.minOf{it.x}; val maxX=poly.maxOf{it.x}
    val minY=poly.minOf{it.y}; val maxY=poly.maxOf{it.y}
    val step=0.25f
    var inside=0; var covered=0
    var y=minY
    while (y<=maxY) {
        var x=minX
        while (x<=maxX) {
            val p=P(x,y)
            if(pointInPolygon(p,poly)){
                inside++
                if(sprinklers.any { hypot((it.p.x-x).toDouble(), (it.p.y-y).toDouble()) <= it.type.radiusM }) covered++
            }
            x+=step
        }
        y+=step
    }
    val pct=if(inside==0)0f else covered*100f/inside
    return Coverage(area*pct/100f,pct)
}

data class Material(val name:String,val qty:String)

fun buildMaterials(
    parcel:List<P>, sprinklers:List<Sprinkler>, pipes:List<Pipe>, zones:List<Int>, diameter:Int
):List<Material>{
    val list= mutableListOf<Material>()
    list += Material("Aspersores / difusores", sprinklers.size.toString())
    list += Material("Electroválvulas", zones.size.toString())
    list += Material("Programador", if(zones.isNotEmpty()) "1 (${zones.size} zonas)" else "1")
    val length = pipes.sumOf { hypot((it.a.x-it.b.x).toDouble(), (it.a.y-it.b.y).toDouble()) }.toFloat()
    list += Material("Tubería Ø${diameter} mm", "%.1f m".format(length))
    val laterals = sprinklers.size
    list += Material("Derivaciones / conexiones", laterals.toString())
    list += Material("Cajas de válvulas", if(zones.isEmpty()) "0" else max(1,(zones.size+1)/2).toString())
    list += Material("Tapones finales", if(parcel.size>=3) "1" else "0")
    return list
}
