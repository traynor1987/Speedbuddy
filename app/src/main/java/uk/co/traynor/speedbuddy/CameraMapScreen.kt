package uk.co.traynor.speedbuddy

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.abs

private val MapInk = Color(0xFFF4F8FB)
private val MapMuted = Color(0xFFB5C9D7)
private val MapPanel = Color(0xFF142331)
private data class MapDrawData(val groups: List<CameraMapGroup>, val roads: List<Pair<Road, Int?>>,
    val cameraCount: Int)

/** Basemap tiles provide context; only the OSM extract supplies limit values. */
@Composable
fun CameraMapScreen(db: CameraDb, current: GeoPoint?, moving: Boolean, back: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val source = remember { OsmDataSource(context, "editor") }
    var snapshot by remember { mutableStateOf(source.cached()) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var viewport by remember { mutableIntStateOf(0) }
    var drawnCenter by remember { mutableStateOf<GeoPoint?>(null) }
    var drawnZoom by remember { mutableDoubleStateOf(-1.0) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var mapAvailable by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Tap Load road limits to inspect this area") }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var pendingRemoval by remember { mutableStateOf<Camera?>(null) }
    var road by remember { mutableStateOf<Road?>(null) }
    var pin by remember { mutableStateOf<GeoPoint?>(null) }
    var following by remember { mutableStateOf(true) }
    var headingUp by remember { mutableStateOf(false) }
    val drive by DriveBus.state.collectAsState()
    val markers = remember { mutableMapOf<Long, Camera>() }
    var locationMarker by remember { mutableStateOf<org.maplibre.android.annotations.Marker?>(null) }
    BackHandler(enabled = pendingRemoval != null || pin != null || camera != null || road != null) {
        pendingRemoval = null; pin = null; camera = null; road = null
    }
    val mapView = remember(context) {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            addOnDidFailLoadingMapListener { mapAvailable = false }
            addOnDidFinishLoadingMapListener { mapAvailable = true }
        }
    }
    LaunchedEffect(moving) { if (moving) { pin = null; camera = null; road = null } }
    DisposableEffect(mapView, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> mapView.onStart()
            Lifecycle.Event.ON_RESUME -> mapView.onResume()
            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
            Lifecycle.Event.ON_STOP -> mapView.onStop()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose { lifecycle.removeObserver(observer); mapView.onDestroy() }
    }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { ready ->
            ready.cameraPosition = CameraPosition.Builder()
                .target(LatLng(current?.lat ?: 53.550, current?.lon ?: -2.777)).zoom(14.0).build()
            ready.setStyle("https://tiles.openfreemap.org/styles/liberty") {
                ready.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false
                }
                ready.addOnCameraIdleListener {
                    val center = ready.cameraPosition.target
                    if (center != null) {
                        val point = GeoPoint(center.latitude, center.longitude)
                        if (drawnCenter?.let { Geo.distance(it, point) > 300 } != false ||
                            abs(drawnZoom - ready.cameraPosition.zoom) > .4) viewport++
                    }
                    if (pin != null) ready.cameraPosition.target?.let { pin = GeoPoint(it.latitude, it.longitude) }
                }
                ready.setOnMarkerClickListener { selected ->
                    if (!moving) { camera = markers[selected.id]; road = null; pin = null }
                    true
                }
                ready.addOnMapClickListener { clicked ->
                    if (moving) return@addOnMapClickListener false
                    val point = GeoPoint(clicked.latitude, clicked.longitude)
                    if (pin != null) { pin = point; return@addOnMapClickListener true }
                    road = snapshot?.roads?.let { RoadSelection.select(point, it) }
                    camera = null; pin = null
                    road != null
                }
                ready.addOnMapLongClickListener { selected ->
                    if (moving) false else {
                        pin = GeoPoint(selected.latitude, selected.longitude)
                        camera = null; road = null; following = false
                        true
                    }
                }
                map = ready
            }
        }
    }
    LaunchedEffect(drive.fix?.elapsedMs, following, headingUp, map) {
        val fix = drive.fix ?: return@LaunchedEffect
        val ready = map ?: return@LaunchedEffect
        if (!following || !drive.active || pin != null) return@LaunchedEffect
        ready.cameraPosition = CameraPosition.Builder().target(LatLng(fix.point.lat, fix.point.lon))
            .zoom(ready.cameraPosition.zoom.coerceAtLeast(14.0))
            .bearing(if (headingUp && fix.speedMps != null && fix.speedMps > 3) fix.bearing ?: 0.0 else 0.0).build()
    }
    LaunchedEffect(drive.fix?.elapsedMs, map) {
        val ready = map ?: return@LaunchedEffect
        val fix = drive.fix
        if (fix == null || SystemClock.elapsedRealtime() - fix.elapsedMs > 5_000) {
            locationMarker?.remove(); locationMarker = null
        } else {
            val position = LatLng(fix.point.lat, fix.point.lon)
            if (locationMarker == null) locationMarker = ready.addMarker(MarkerOptions().position(position)
                .title("Current location")
                .icon(IconFactory.getInstance(context).fromBitmap(mapPin(android.graphics.Color.rgb(45, 157, 230), "•"))))
            else locationMarker?.position = position
        }
    }
    LaunchedEffect(map) {
        val center = map?.cameraPosition?.target ?: return@LaunchedEffect
        val point = GeoPoint(center.latitude, center.longitude)
        if (snapshot?.usable(point, System.currentTimeMillis()) == true) return@LaunchedEffect
        loading = true; status = "Loading nearby tagged road limits…"
        runCatching { withContext(Dispatchers.IO) { source.fetch(point) } }
            .onSuccess { snapshot = it; status = "Tap a road to inspect its tagged limit" }
            .onFailure { status = "Road data unavailable: ${it.message?.take(70) ?: "Unknown error"}" }
        loading = false
    }
    LaunchedEffect(pin != null) {
        val target = pin ?: return@LaunchedEffect
        val ready = map ?: return@LaunchedEffect
        ready.cameraPosition = CameraPosition.Builder().target(LatLng(target.lat, target.lon))
            .zoom(ready.cameraPosition.zoom).bearing(ready.cameraPosition.bearing).build()
    }
    LaunchedEffect(map, snapshot, viewport, revision, road?.id) {
        val ready = map ?: return@LaunchedEffect
        val center = ready.cameraPosition.target ?: return@LaunchedEffect
        val point = GeoPoint(center.latitude, center.longitude)
        val zoom = ready.cameraPosition.zoom
        if (zoom < 9) { ready.clear(); markers.clear(); status = "Zoom in to see camera coverage"; return@LaunchedEffect }
        val span = (0.045 * Math.pow(2.0, 14.0 - zoom)).coerceIn(0.002, 1.0)
        val draw = withContext(Dispatchers.IO) {
            val raw = db.importedInBounds(point.lat - span, point.lon - span * 1.7,
                point.lat + span, point.lon + span * 1.7)
            val personal = db.userCameras().filter { abs(it.point.lat - point.lat) <= span && abs(it.point.lon - point.lon) <= span * 1.7 }
            val public = snapshot?.cameras.orEmpty().filter { abs(it.point.lat - point.lat) <= span && abs(it.point.lon - point.lon) <= span * 1.7 }
            val overrides = db.cameraCorrections().filter { abs(it.point.lat - point.lat) <= span && abs(it.point.lon - point.lon) <= span * 1.7 }
            val candidates = (raw + public).associateBy { it.id }.toMutableMap()
            overrides.forEach { correction -> if (correction.id !in candidates) {
                val sourcePoint = when (correction.source) {
                    CameraSource.LUFOP -> db.importedPoint(correction.id)
                    CameraSource.OSM -> snapshot?.cameras?.firstOrNull { it.id == correction.id }?.point
                    CameraSource.USER -> null
                }
                if (sourcePoint != null) candidates[correction.id] = Camera(correction.id, sourcePoint,
                    correction.type, correction.source)
            } }
            val effective = db.effectiveCameras(candidates.values.toList(), personal).filter {
                abs(it.point.lat - point.lat) <= span && abs(it.point.lon - point.lon) <= span * 1.7
            }
            val limits = db.roadCorrections().associateBy { it.id }
            val nearbyRoads = snapshot?.roads.orEmpty().filter { item -> item.points.any {
                abs(it.lat - point.lat) <= span && abs(it.lon - point.lon) <= span * 1.7
            } }.map { it to SpeedLimits.mph((limits[it.id]?.apply(it) ?: it).tags) }
            MapDrawData(CameraClustering.group(effective, zoom), nearbyRoads, effective.size)
        }
        ready.clear(); markers.clear(); locationMarker = null
        drive.fix?.takeIf { SystemClock.elapsedRealtime() - it.elapsedMs <= 5_000 }?.let { fix ->
            locationMarker = ready.addMarker(MarkerOptions().position(LatLng(fix.point.lat, fix.point.lon))
                .title("Current location")
                .icon(IconFactory.getInstance(context).fromBitmap(mapPin(android.graphics.Color.rgb(45, 157, 230), "•"))))
        }
        drawnCenter = point; drawnZoom = zoom
        val icons = IconFactory.getInstance(context)
        val iconCache = mutableMapOf<String, org.maplibre.android.annotations.Icon>()
        draw.groups.take(600).forEach { group ->
            val item = group.camera
            val label = if (group.count > 1) group.count.coerceAtMost(99).toString() +
                (if (group.count > 99) "+" else "") else when (item?.type) {
                CameraType.RED_LIGHT -> "R"; CameraType.COMBINED -> "R+"
                CameraType.AVERAGE -> "A"; else -> "S"
            }
            val colour = if (group.count > 1) android.graphics.Color.rgb(55, 119, 147)
                else if (item?.source == CameraSource.USER) android.graphics.Color.rgb(151, 211, 238)
                else if (item?.type == CameraType.RED_LIGHT || item?.type == CameraType.COMBINED)
                    android.graphics.Color.rgb(236, 93, 95)
                else android.graphics.Color.rgb(239, 176, 74)
            val angle = item?.direction?.let { ((it - ready.cameraPosition.bearing + 360) % 360).toInt() / 15 * 15 }
            val key = "$colour:$label:$angle"
            val icon = iconCache.getOrPut(key) { icons.fromBitmap(mapPin(colour, label, angle?.toDouble())) }
            val marker = ready.addMarker(MarkerOptions().position(LatLng(group.point.lat, group.point.lon))
                .title(if (group.count > 1) "${group.count} cameras · zoom in" else when (item?.type) {
                    CameraType.SPEED -> "Speed camera"; CameraType.RED_LIGHT -> "Red-light camera"
                    CameraType.COMBINED -> "Speed + red-light camera"; CameraType.AVERAGE -> "Average-speed camera"
                    else -> "Camera"
                })
                .icon(icon))
            if (item != null) markers[marker.id] = item
        }
        draw.roads.forEach { (item, mph) ->
            ready.addPolyline(PolylineOptions().addAll(item.points.map { LatLng(it.lat, it.lon) })
                .color(when { item.id == road?.id -> android.graphics.Color.WHITE
                    mph == null -> android.graphics.Color.GRAY
                    mph <= 30 -> android.graphics.Color.YELLOW
                    else -> android.graphics.Color.GREEN }).width(if (item.id == road?.id) 9f else 4f))
        }
        if (draw.groups.size > 600) status = "Zoom in for more cameras · ${draw.cameraCount} nearby"
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF071019))) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = MapPanel, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = back) { Text("‹ Back") }
                    Text("Camera map", Modifier.weight(1f), color = MapInk, fontWeight = FontWeight.Bold)
                    TextButton(enabled = !moving, onClick = {
                        val center = map?.cameraPosition?.target ?: return@TextButton
                        pin = GeoPoint(center.latitude, center.longitude); camera = null; road = null
                    }) { Text("+ Pin") }
                    TextButton(onClick = {
                        following = true
                        val fix = drive.fix
                        if (fix != null) map?.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(fix.point.lat, fix.point.lon)).zoom(15.0).build()
                    }, enabled = drive.fix != null) { Text("◎", modifier = Modifier.sizeIn(minWidth = 48.dp),
                        color = MapInk) }
                    TextButton(onClick = { headingUp = !headingUp }, enabled = drive.active) {
                        Text(if (headingUp) "Heading" else "N ↑")
                    }
                }
            }
            Surface(color = MapPanel, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("S speed · R red light · coloured roads = tagged limits", color = MapInk, style = MaterialTheme.typography.labelMedium)
                    Text(status, color = MapMuted, style = MaterialTheme.typography.labelSmall)
                    if (!mapAvailable) Text("Base map unavailable. Saved camera alerts and road data still work.",
                        color = Color(0xFFFFCA75), style = MaterialTheme.typography.labelSmall)
                    TextButton(enabled = !loading && !moving, onClick = {
                        val center = map?.cameraPosition?.target ?: return@TextButton
                        loading = true; status = "Loading road tags for this area…"
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) {
                                source.fetch(GeoPoint(center.latitude, center.longitude))
                            } }.onSuccess { snapshot = it; status = "Tap a coloured road to view its tagged limit" }
                                .onFailure { status = "Road data unavailable: ${it.message?.take(70) ?: "Unknown error"}" }
                            loading = false
                        }
                    }) { Text(if (loading) "Loading…" else "Load road limits here") }
                }
            }
        }
        if (pin != null) Text("✚", Modifier.align(Alignment.Center), color = Color(0xFF004A62),
            style = MaterialTheme.typography.headlineLarge)
        when {
            pin != null && !moving -> Box(Modifier.align(Alignment.BottomCenter)) { CameraMapEditor(null, pin!!, { pin = null }, { point, type, direction, mph, note ->
                db.create(point, type, direction, mph, note); revision++; pin = null
            }) }
            camera != null && !moving -> Box(Modifier.align(Alignment.BottomCenter)) { CameraMapEditor(camera, camera!!.point, { camera = null }, { point, type, direction, mph, note ->
                val item = camera!!
                if (item.source == CameraSource.USER) db.upsert(item.copy(point = point, type = type,
                    direction = direction, enforcedMph = mph, note = note))
                else {
                    val previous = db.cameraCorrections().firstOrNull { it.id == item.id }
                    val sourcePoint = previous?.sourcePoint ?: when (item.source) {
                        CameraSource.LUFOP -> db.importedPoint(item.id)
                        CameraSource.OSM -> snapshot?.cameras?.firstOrNull { it.id == item.id }?.point
                        else -> null
                    }
                    db.saveCameraCorrection(CameraCorrection(item.id, item.source, point, type,
                        direction, note, mph, sourcePoint))
                }
                revision++; camera = null
            }, onDelete = { item ->
                pendingRemoval = item
            }, sourcePoint = camera?.let { item -> when (item.source) {
                CameraSource.LUFOP -> db.importedPoint(item.id)
                CameraSource.OSM -> snapshot?.cameras?.firstOrNull { it.id == item.id }?.point
                CameraSource.USER -> null
            } }) }
            road != null && !moving -> Box(Modifier.align(Alignment.BottomCenter)) { RoadMapEditor(road!!, db.roadCorrection(road!!.id), {
                road = null
            }, { correction ->
                if (correction == null) db.deleteRoadLimit(road!!.id) else db.saveRoadCorrection(correction)
                revision++; road = null
            }) }
            else -> Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                color = MapPanel, shape = RoundedCornerShape(12.dp)) {
                Text("Tap a camera or road to inspect. Use + Pin to place a missing camera.",
                    Modifier.padding(12.dp), color = MapInk, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("© OpenStreetMap contributors · OpenMapTiles · OpenFreeMap",
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = if (camera != null || road != null || pin != null) 220.dp else 72.dp)
                .background(MapPanel.copy(alpha = .88f))
                .clickable { uriHandler.openUri("https://www.openstreetmap.org/copyright") }
                .padding(4.dp), color = MapInk,
            style = MaterialTheme.typography.labelSmall)
        pendingRemoval?.let { item -> AlertDialog(onDismissRequest = { pendingRemoval = null },
            title = { Text(if (item.source == CameraSource.USER) "Delete your camera?" else "Hide this source camera?") },
            text = { Text(if (item.source == CameraSource.USER) "This pin will be removed from this phone."
                else "The imported record remains available; Speed Buddy will hide it from the map and alerts.") },
            confirmButton = { TextButton(onClick = {
                if (item.source == CameraSource.USER) db.delete(item.id) else db.suppressCamera(item.id, item.source)
                revision++; camera = null; pendingRemoval = null
            }) { Text(if (item.source == CameraSource.USER) "Delete" else "Hide") } },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } }) }
    }
}

private fun mapPin(color: Int, letter: String, direction: Double? = null): Bitmap {
    val bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    canvas.drawCircle(36f, 36f, 30f, paint)
    paint.color = android.graphics.Color.BLACK; paint.textSize = if (letter.length > 2) 21f else 34f
    paint.isFakeBoldText = true
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText(letter, 36f, 48f, paint)
    if (direction != null) {
        canvas.save()
        canvas.rotate(direction.toFloat(), 36f, 36f)
        paint.color = android.graphics.Color.WHITE
        val arrow = android.graphics.Path().apply {
            moveTo(36f, 0f); lineTo(29f, 14f); lineTo(43f, 14f); close()
        }
        canvas.drawPath(arrow, paint)
        canvas.restore()
    }
    return bitmap
}

@Composable private fun CameraMapEditor(existing: Camera?, initial: GeoPoint, close: () -> Unit,
    save: (GeoPoint, CameraType, Double?, Int?, String?) -> Unit,
    onDelete: ((Camera) -> Unit)? = null, sourcePoint: GeoPoint? = null) {
    var advanced by remember(existing?.id) { mutableStateOf(false) }
    var type by remember(existing?.id) { mutableStateOf(existing?.type ?: CameraType.SPEED) }
    var direction by remember(existing?.id) { mutableStateOf(existing?.direction?.toInt()?.toString() ?: "") }
    var note by remember(existing?.id) { mutableStateOf(existing?.note ?: "") }
    var mph by remember(existing?.id) { mutableStateOf(existing?.enforcedMph?.toString() ?: "") }
    var lat by remember(existing?.id, initial) { mutableStateOf(initial.lat.toString()) }
    var lon by remember(existing?.id, initial) { mutableStateOf(initial.lon.toString()) }
    val point = lat.toDoubleOrNull()?.let { y -> lon.toDoubleOrNull()?.let { x ->
        if (y in -90.0..90.0 && x in -180.0..180.0) GeoPoint(y, x) else null
    } }
    Surface(Modifier.fillMaxWidth().padding(12.dp), color = MapPanel, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(if (existing == null) "Drop camera pin" else "Edit ${existing.source.name.lowercase()} camera",
                color = MapInk, fontWeight = FontWeight.Bold)
            if (existing != null) Text(when (existing.source) {
                CameraSource.USER -> "USER ADDED · saved on this phone"
                CameraSource.LUFOP -> "LUFOP · imported · unverified direction"
                CameraSource.OSM -> "OPENSTREETMAP · community data"
            }, color = MapMuted, style = MaterialTheme.typography.labelSmall)
            Text("${if (existing == null) "Drag the map or tap to move the pin · " else ""}Direction is enforced travel bearing",
                color = MapMuted, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(type == CameraType.SPEED, { type = CameraType.SPEED }, label = { Text("Speed") })
                FilterChip(type == CameraType.RED_LIGHT, { type = CameraType.RED_LIGHT }, label = { Text("Red light") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(type == CameraType.COMBINED, { type = CameraType.COMBINED }, label = { Text("Both") })
                FilterChip(type == CameraType.AVERAGE, { type = CameraType.AVERAGE }, label = { Text("Average") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat, { lat = it.take(14) }, Modifier.weight(1f), label = { Text("Latitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(lon, { lon = it.take(14) }, Modifier.weight(1f), label = { Text("Longitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(mph, { mph = it.filter(Char::isDigit).take(3) }, Modifier.weight(.38f),
                    label = { Text("Limit mph") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(direction, { direction = it.filter(Char::isDigit).take(3) }, Modifier.weight(.38f),
                    label = { Text("Direction °") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(note, { note = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("Note") }, singleLine = true)
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide coordinates" else "More details") }
            if (advanced) Text("Effective: ${initial.lat}, ${initial.lon}" +
                (sourcePoint?.let { "\nSource: ${it.lat}, ${it.lon}" } ?: "") +
                (existing?.updatedAtMs?.takeIf { it > 0 }?.let { " · updated ${java.text.DateFormat.getDateInstance().format(java.util.Date(it))}" } ?: ""),
                color = MapMuted, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("N" to 0, "E" to 90, "S" to 180, "W" to 270).forEach { (label, angle) ->
                    FilterChip(direction == angle.toString(), { direction = angle.toString() },
                        label = { Text(label) }, modifier = Modifier.weight(1f))
                }
                FilterChip(direction.isBlank(), { direction = "" }, label = { Text("?") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = close) { Text("Cancel") }
                if (existing != null && onDelete != null) TextButton(onClick = { onDelete(existing) }) {
                    Text(if (existing.source == CameraSource.USER) "Delete pin" else "Hide locally")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { point?.let { save(it, type, direction.toDoubleOrNull(), mph.toIntOrNull(), note.ifBlank { null }) } },
                    enabled = point != null && (direction.isBlank() || direction.toIntOrNull()?.let { it in 0..359 } == true) &&
                        (mph.isBlank() || mph.toIntOrNull()?.let { it in 5..130 } == true)) { Text("Save") }
            }
        }
    }
}

@Composable private fun RoadMapEditor(road: Road, correction: RoadLimitCorrection?, close: () -> Unit,
    save: (RoadLimitCorrection?) -> Unit) {
    var kind by remember(road.id) { mutableStateOf(correction?.kind ?: RoadLimitKind.NUMERIC) }
    var mph by remember(road.id) { mutableStateOf((correction?.mph ?: SpeedLimits.mph(road.tags))?.toString() ?: "") }
    Surface(Modifier.fillMaxWidth().padding(12.dp), color = MapPanel, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(road.name ?: "Unnamed road", color = MapInk, fontWeight = FontWeight.Bold)
            Text("Source: ${road.tags["maxspeed"] ?: road.tags["maxspeed:type"] ?: "unknown"} · ${road.id}", color = MapMuted)
            Text("Local correction affects Speed Buddy only. Check the posted sign first; variable limits stay unknown.",
                color = MapMuted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(20, 30, 40, 50, 60, 70).forEach { value ->
                    FilterChip(kind == RoadLimitKind.NUMERIC && mph == value.toString(),
                        { kind = RoadLimitKind.NUMERIC; mph = value.toString() },
                        label = { Text(value.toString()) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                FilterChip(kind == RoadLimitKind.NATIONAL_SINGLE,
                    { kind = RoadLimitKind.NATIONAL_SINGLE; mph = "60" }, label = { Text("National · single") })
                FilterChip(kind == RoadLimitKind.NATIONAL_DUAL,
                    { kind = RoadLimitKind.NATIONAL_DUAL; mph = "70" }, label = { Text("National · dual") })
                FilterChip(kind == RoadLimitKind.UNKNOWN,
                    { kind = RoadLimitKind.UNKNOWN; mph = "" }, label = { Text("Unknown") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = close) { Text("Cancel") }
                if (correction != null) TextButton(onClick = { save(null) }) { Text("Reset edit") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { save(RoadLimitCorrection(road.id, kind,
                    if (kind == RoadLimitKind.UNKNOWN) null else mph.toIntOrNull(),
                    road.tags["maxspeed"] ?: road.tags["maxspeed:type"], System.currentTimeMillis())) },
                    enabled = kind != RoadLimitKind.NUMERIC || mph.toIntOrNull()?.let { it in listOf(20, 30, 40, 50, 60, 70) } == true) {
                    Text("Save")
                }
            }
        }
    }
}
