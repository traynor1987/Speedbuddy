package uk.co.traynor.speedbuddy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

private val Ink = Color(0xFFF4F8FB)
private val Muted = Color(0xFFAFC4D2)
private val Background = Color(0xFF071019)
private val Panel = Color(0xFF142331)
private val Line = Color(0xFF304454)
private val Accent = Color(0xFF77D5F0)
private val Warning = Color(0xFFFFCA75)

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startDriving()
    }
    private fun startDriving() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        try { startForegroundService(Intent(this, DrivingService::class.java)) }
        catch (_: Exception) { DriveBus.set(DriveState(status = "Could not start location service")) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = CameraDb(this)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        setContent {
            var page by remember { mutableStateOf("drive") }
            val state by DriveBus.state.collectAsState()
            var records by remember { mutableStateOf(db.userCameras()) }
            var editing by remember { mutableStateOf<Camera?>(null) }
            var message by remember { mutableStateOf("") }
            val speed = state.speedMph
            val moving = state.active && (speed == null || speed >= 5.0)
            MaterialTheme(colorScheme = darkColorScheme(
                background = Background, surface = Panel, primary = Accent,
                onBackground = Ink, onSurface = Ink, onPrimary = Background,
                surfaceVariant = Color(0xFF203443), onSurfaceVariant = Muted,
                outline = Line)) {
                Surface(Modifier.fillMaxSize(), color = Background, contentColor = Ink) {
                    when (page) {
                        "drive" -> DriveScreen(state, ::startDriving,
                            { stopService(Intent(this, DrivingService::class.java)) },
                            { page = "settings" }, { page = "diagnostics" },
                            { if (state.fix == null) message = "Wait for a GPS fix"
                              else if (moving) message = "Stop before editing a camera"
                              else { editing = null; page = "edit" } },
                            { type -> state.fix?.let {
                                db.create(it.point, type, it.bearing)
                                records = db.userCameras()
                                message = "Camera position saved. Edit the details while stopped."
                            } })
                        "settings" -> SettingsScreen(prefs, { page = "drive" },
                            { records = db.userCameras(); page = "cameras" }, { page = "diagnostics" })
                        "diagnostics" -> DiagnosticsScreen(state) { page = "drive" }
                        "cameras" -> CameraList(records, moving, { page = "settings" },
                            { editing = it; page = "edit" },
                            { db.delete(it.id); records = db.userCameras() })
                        "edit" -> CameraEditor(editing, state.fix?.point, moving, { page = "cameras" }) { point, type, direction, mph, note ->
                            if (!moving) {
                                val old = editing
                                if (old == null) db.create(point, type, direction, mph, note)
                                else db.upsert(old.copy(point = point, type = type, direction = direction, enforcedMph = mph, note = note))
                                records = db.userCameras(); page = "cameras"
                            }
                        }
                    }
                    if (message.isNotEmpty()) AlertDialog(onDismissRequest = { message = "" },
                        confirmButton = { TextButton(onClick = { message = "" }) { Text("OK") } },
                        text = { Text(message, color = Ink) })
                }
            }
        }
    }
}

@Composable private fun Page(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹  Back", color = Accent) }
            Spacer(Modifier.width(10.dp))
            Text(title, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        content()
    }
}

@Composable private fun LimitSign(limit: Int?, national: Boolean, modifier: Modifier = Modifier) {
    val circle = modifier.clip(CircleShape)
    when {
        limit == null -> Box(circle.border(2.dp, Line, CircleShape).background(Panel), contentAlignment = Alignment.Center) {
            Text("--", fontSize = 58.sp, fontWeight = FontWeight.Bold, color = Muted)
        }
        national -> Box(circle.background(Color.White), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(Color(0xFF111111), Offset(size.width * .18f, size.height * .82f),
                    Offset(size.width * .82f, size.height * .18f), strokeWidth = size.width * .14f)
            }
        }
        else -> Box(circle.background(Color.White).border(14.dp, Color(0xFFDC282B), CircleShape), contentAlignment = Alignment.Center) {
            Text(limit.toString(), color = Color(0xFF111111), fontSize = if (limit >= 100) 53.sp else 69.sp,
                fontWeight = FontWeight.Black, letterSpacing = (-3).sp, maxLines = 1)
        }
    }
}

@Composable private fun DriveScreen(state: DriveState, onStart: () -> Unit, onStop: () -> Unit,
    onSettings: () -> Unit, onDiagnostic: () -> Unit, onAdd: () -> Unit, onQuick: (CameraType) -> Unit) {
    val speed = state.speedMph
    val moving = state.active && (speed == null || speed >= 5.0)
    val tags = state.road?.road?.tags
    val national = state.limitMph != null && (
        tags?.get("maxspeed:type")?.startsWith("GB:nsl") == true ||
        tags?.get("maxspeed")?.startsWith("GB:nsl") == true || tags?.get("maxspeed") == "GB:motorway")
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("SPEED BUDDY", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(if (state.active) "DRIVING MODE ACTIVE" else "READY WHEN YOU ARE", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
            }
            TextButton(onClick = onSettings, enabled = !moving) { Text("Settings") }
        }
        Spacer(Modifier.height(24.dp))
        Text("CURRENT SPEED", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Text(speed?.roundToInt()?.toString() ?: "--", color = if (state.overspeed) Warning else Ink,
            fontSize = 124.sp, lineHeight = 132.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text("MPH", color = Muted, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
        Spacer(Modifier.height(28.dp))
        Text("CURRENT ROAD LIMIT", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        LimitSign(state.limitMph, national, Modifier.size(158.dp))
        Spacer(Modifier.height(9.dp))
        Text(when {
            state.limitMph == null -> "Limit unknown"
            national -> "National speed limit · ${state.limitMph} mph"
            else -> "${state.limitMph} mph"
        }, color = Muted, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        val alert = state.alert
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            color = if (alert != null) Color(0xFF3B2B1A) else Panel, contentColor = Ink) {
            Row(Modifier.fillMaxWidth().heightIn(min = 82.dp).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (alert == null) "CAMERA STATUS" else if (alert.camera.type == CameraType.SPEED) "SPEED CAMERA AHEAD" else "RED-LIGHT CAMERA AHEAD",
                        color = if (alert == null) Muted else Warning, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(if (alert == null) state.status.ifBlank { "No active camera alert" } else "Approaching",
                        color = Ink, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (alert != null) Text("${(alert.distanceM * 1.093613).roundToInt()} yd", color = Ink,
                    fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onAdd, enabled = !moving) { Text("+ Add camera") }
            TextButton(onClick = onDiagnostic, enabled = !moving) { Text("Diagnostics") }
        }
        if (moving && state.fix != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onQuick(CameraType.SPEED) }, modifier = Modifier.weight(1f)) { Text("Save speed", maxLines = 1) }
            OutlinedButton(onClick = { onQuick(CameraType.RED_LIGHT) }, modifier = Modifier.weight(1f)) { Text("Save red light", maxLines = 1) }
        }
        Button(onClick = if (state.active) onStop else onStart, modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp)) { Text(if (state.active) "Stop driving mode" else "Start driving mode", fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun SectionLabel(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
}
@Composable private fun SettingsToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), color = Ink, fontSize = 16.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
@Composable private fun SettingsScreen(prefs: android.content.SharedPreferences, back: () -> Unit,
    cameras: () -> Unit, diagnostics: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    Page("Settings", back) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionLabel("DRIVING")
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column {
                    val enabled = prefs.getBoolean("overspeed", false).also { version }
                    SettingsToggle("Overspeed warning", enabled) { prefs.edit().putBoolean("overspeed", it).apply(); version++ }
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                    Column(Modifier.padding(16.dp)) {
                        Text("Warning threshold", color = Ink, fontSize = 16.sp)
                        Text("Select a margin above the known limit", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(0, 1, 2, 3, 5).forEach { value ->
                                FilterChip(selected = prefs.getInt("tolerance", 2).also { version } == value,
                                    onClick = { prefs.edit().putInt("tolerance", value).apply(); version++ },
                                    label = { Text("+$value") }, enabled = enabled)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp)); SectionLabel("CAMERA ALERTS")
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column {
                    listOf(Triple("Speed cameras", "speedCamera", true), Triple("Red-light cameras", "redCamera", true),
                        Triple("Sound", "cameraSound", true), Triple("Vibration", "vibrate", true)).forEachIndexed { index, (label, key, default) ->
                        if (index > 0) HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsToggle(label, prefs.getBoolean(key, default).also { version }) {
                            prefs.edit().putBoolean(key, it).apply(); version++
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp)); SectionLabel("YOUR DATA")
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column {
                    MenuRow("Manage my cameras", "View, edit or delete saved cameras", cameras)
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                    MenuRow("Diagnostics", "GPS, road match and camera decisions", diagnostics)
                }
            }
            Spacer(Modifier.height(22.dp)); SectionLabel("SOURCES & PRIVACY")
            Text("Roads and public cameras © OpenStreetMap contributors (ODbL). Nearby coordinates are sent to the public Overpass API. Data may be incomplete; follow road signs.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(8.dp))
            Text("Speed-limit sign designs based on The Highway Code. © Crown copyright, Open Government Licence v3.0. No account or journey history is stored.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
@Composable private fun MenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        Text("›", color = Accent, fontSize = 26.sp)
    }
}
@Composable private fun DiagnosticRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(.45f), color = Muted, fontSize = 14.sp)
        Text(value ?: "Unknown", Modifier.weight(.55f), color = Ink, fontSize = 14.sp, textAlign = TextAlign.End)
    }
}
@Composable private fun DiagnosticCard(title: String, entries: List<Pair<String, String?>>) {
    SectionLabel(title)
    Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            entries.forEachIndexed { index, (label, value) ->
                if (index > 0) HorizontalDivider(color = Line)
                DiagnosticRow(label, value)
            }
        }
    }
    Spacer(Modifier.height(20.dp))
}
@Composable private fun DiagnosticsScreen(state: DriveState, back: () -> Unit) = Page("Diagnostics", back) {
    val fix = state.fix
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        DiagnosticCard("GPS", listOf(
            "Accuracy" to fix?.let { "${it.accuracyM.roundToInt()} m" },
            "Speed" to fix?.speedMps?.let { "${(it * MPS_TO_MPH).roundToInt()} mph" },
            "Heading" to fix?.bearing?.let { "${it.roundToInt()}°" },
            "Fix age" to fix?.let { "${(android.os.SystemClock.elapsedRealtime() - it.elapsedMs) / 1000} s" }))
        DiagnosticCard("ROAD", listOf(
            "Matched road" to state.road?.road?.let { "${it.name ?: "Unnamed"} · ${it.id}" },
            "Confidence" to state.road?.let { String.format(Locale.UK, "%.2f", it.confidence) },
            "Known limit" to state.limitMph?.let { "$it mph · OSM" },
            "Map data age" to state.dataAgeMs?.let { "${it / 60_000} min" }))
        DiagnosticCard("CAMERA", listOf(
            "Nearest candidate" to state.decision.camera?.id,
            "Distance" to state.decision.distanceM?.let { "${it.roundToInt()} m" },
            "Bearing difference" to state.decision.bearingDifference?.let { "${it.roundToInt()}°" },
            "Approaching" to if (state.decision.accepted) "Yes" else "No",
            "Decision" to state.decision.reason))
    }
}
@Composable private fun CameraList(records: List<Camera>, moving: Boolean, back: () -> Unit,
    edit: (Camera) -> Unit, delete: (Camera) -> Unit) = Page("My cameras", back) {
    if (records.isEmpty()) Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
        Text("No cameras added yet. Add one from the driving screen while parked.",
            color = Muted, modifier = Modifier.padding(20.dp))
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(records, key = { it.id }) { camera ->
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(if (camera.type == CameraType.SPEED) "Speed camera" else "Red-light camera",
                        color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("${camera.point.lat.format()}, ${camera.point.lon.format()}", color = Muted, fontSize = 13.sp)
                    Row {
                        TextButton(onClick = { edit(camera) }, enabled = !moving) { Text("Edit") }
                        TextButton(onClick = { delete(camera) }, enabled = !moving) { Text("Delete") }
                    }
                }
            }
        }
    }
}
private fun Double.format() = String.format(Locale.UK, "%.6f", this)
@Composable private fun CameraEditor(existing: Camera?, current: GeoPoint?, moving: Boolean, back: () -> Unit,
    save: (GeoPoint, CameraType, Double?, Int?, String?) -> Unit) {
    val initial = existing?.point ?: current
    var latitude by remember(existing) { mutableStateOf(initial?.lat?.format() ?: "") }
    var longitude by remember(existing) { mutableStateOf(initial?.lon?.format() ?: "") }
    var type by remember(existing) { mutableStateOf(existing?.type ?: CameraType.SPEED) }
    var mph by remember(existing) { mutableStateOf(existing?.enforcedMph?.toString() ?: "") }
    var direction by remember(existing) { mutableStateOf(existing?.direction?.roundToInt()?.toString() ?: "") }
    var note by remember(existing) { mutableStateOf(existing?.note ?: "") }
    Page(if (existing == null) "Add camera" else "Edit camera", back) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Edit only while parked. Save your current GPS position or correct the coordinates below.",
                color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column(Modifier.padding(16.dp)) {
                    Text("Camera type", color = Ink, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(type == CameraType.SPEED, { type = CameraType.SPEED }, label = { Text("Speed") })
                        FilterChip(type == CameraType.RED_LIGHT, { type = CameraType.RED_LIGHT }, label = { Text("Red light") })
                    }
                    OutlinedTextField(latitude, { latitude = it.take(14) }, label = { Text("Latitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(longitude, { longitude = it.take(14) }, label = { Text("Longitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (current != null) TextButton(onClick = { latitude = current.lat.format(); longitude = current.lon.format() }) { Text("Use current position") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column(Modifier.padding(16.dp)) {
                    Text("Optional details", color = Ink, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(mph, { mph = it.filter(Char::isDigit).take(3) }, label = { Text("Enforced speed · mph") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(direction, { direction = it.filter(Char::isDigit).take(3) }, label = { Text("Travel direction · 0–359°") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(note, { note = it.take(100) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(20.dp))
            val corrected = latitude.toDoubleOrNull()?.let { lat -> longitude.toDoubleOrNull()?.let { lon ->
                if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
            } }
            Button(onClick = { corrected?.let { save(it, type, direction.toDoubleOrNull(), mph.toIntOrNull(), note.ifBlank { null }) } },
                enabled = !moving && corrected != null && (direction.isEmpty() || direction.toIntOrNull()?.let { it in 0..359 } == true) &&
                    (mph.isEmpty() || mph.toIntOrNull()?.let { it in 5..130 } == true),
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Save camera") }
        }
    }
}
