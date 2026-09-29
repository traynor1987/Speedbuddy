package uk.co.traynor.speedbuddy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale
import kotlin.math.roundToInt

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
        val db = CameraDb(this); val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        setContent {
            var page by remember { mutableStateOf("drive") }
            val state by DriveBus.state.collectAsState()
            var records by remember { mutableStateOf(db.userCameras()) }
            var editing by remember { mutableStateOf<Camera?>(null) }
                    var message by remember { mutableStateOf("") }
            val moving = state.active && (state.speedMph == null || state.speedMph >= 5.0)
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color(0xFF080D13), surface = Color(0xFF111A25), primary = Color(0xFF6ED5F6),
                onBackground = Color.White, onSurface = Color.White)) {
                when (page) {
                    "drive" -> DriveScreen(state,
                        onStart = ::startDriving,
                        onStop = { stopService(Intent(this, DrivingService::class.java)) },
                        onSettings = { page = "settings" }, onDiagnostic = { page = "diagnostics" },
                        onAdd = { if (state.fix == null) message = "Wait for a GPS fix" else if (moving) message = "Stop before editing a camera" else { editing = null; page = "edit" } },
                        onQuick = { type -> state.fix?.let { db.create(it.point, type, it.bearing); records = db.userCameras(); message = "Camera position saved. Edit it when stopped." } })
                    "settings" -> SettingsScreen(prefs, { page = "drive" }, { records = db.userCameras(); page = "cameras" }, { page = "diagnostics" })
                    "diagnostics" -> DiagnosticsScreen(state) { page = "drive" }
                    "cameras" -> CameraList(records, moving, { page = "settings" }, { editing = it; page = "edit" },
                        { db.delete(it.id); records = db.userCameras() })
                    "edit" -> CameraEditor(editing, state.fix?.point, moving, { page = "cameras" }, { point, type, direction, mph, note ->
                        if (!moving) {
                            val old = editing
                            if (old == null) db.create(point, type, direction, mph, note)
                            else db.upsert(old.copy(point = point, type = type, direction = direction, enforcedMph = mph, note = note))
                            records = db.userCameras(); page = "cameras"
                        }
                    })
                }
                if (message.isNotEmpty()) AlertDialog(onDismissRequest = { message = "" }, confirmButton = {
                    TextButton(onClick = { message = "" }) { Text("OK") }
                }, text = { Text(message) })
            }
        }
    }
}

@Composable private fun Frame(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF080D13)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("‹ Back") }
            Spacer(Modifier.width(12.dp)); Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp)); content()
    }
}
@Composable private fun DriveScreen(state: DriveState, onStart: () -> Unit, onStop: () -> Unit,
    onSettings: () -> Unit, onDiagnostic: () -> Unit, onAdd: () -> Unit, onQuick: (CameraType) -> Unit) {
    val moving = state.active && (state.speedMph == null || state.speedMph >= 5.0)
    Column(Modifier.fillMaxSize().background(Color(0xFF080D13)).padding(horizontal = 22.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SPEED BUDDY", color = Color(0xFF92A9B9), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            TextButton(onClick = onSettings, enabled = !moving) { Text("Settings") }
        }
        Spacer(Modifier.weight(.2f))
        Text(state.speedMph?.roundToInt()?.toString() ?: "--", fontSize = 126.sp, lineHeight = 130.sp,
            fontWeight = FontWeight.Black, color = if (state.overspeed) Color(0xFFFFC76A) else Color.White)
        Text("MPH", fontSize = 24.sp, letterSpacing = 7.sp, color = Color(0xFF91AABD))
        Spacer(Modifier.height(32.dp))
        Column(Modifier.background(Color(0xFF152331), RoundedCornerShape(24.dp)).padding(horizontal = 44.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LIMIT", fontSize = 15.sp, letterSpacing = 3.sp, color = Color(0xFFAAC5D5))
            Text(state.limitMph?.toString() ?: "--", fontSize = 70.sp, fontWeight = FontWeight.Bold, lineHeight = 75.sp)
        }
        Spacer(Modifier.weight(.25f))
        HorizontalDivider(color = Color(0xFF334454))
        Box(Modifier.fillMaxWidth().height(105.dp), contentAlignment = Alignment.Center) {
            val alert = state.alert
            if (alert != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (alert.camera.type == CameraType.SPEED) "SPEED CAMERA" else "RED-LIGHT CAMERA", color = Color(0xFFFFC76A), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("${(alert.distanceM * 1.093613).roundToInt()} yd ahead", fontSize = 25.sp)
            } else Text(state.status.ifBlank { "No camera alert" }, textAlign = TextAlign.Center, color = Color(0xFF9AB0BF))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onAdd, enabled = !moving) { Text("+ Add camera") }
            TextButton(onClick = onDiagnostic, enabled = !moving) { Text("Diagnostics") }
        }
        if (moving && state.fix != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onQuick(CameraType.SPEED) }, modifier = Modifier.weight(1f)) { Text("Save speed camera") }
            OutlinedButton(onClick = { onQuick(CameraType.RED_LIGHT) }, modifier = Modifier.weight(1f)) { Text("Save red light") }
        }
        Button(onClick = if (state.active) onStop else onStart, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (state.active) "Stop driving mode" else "Start driving mode")
        }
    }
}
@Composable private fun SettingsScreen(prefs: android.content.SharedPreferences, back: () -> Unit, cameras: () -> Unit, diagnostics: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    fun checked(key: String, default: Boolean) = prefs.getBoolean(key, default)
    Frame("Settings", back) {
        listOf(Triple("Overspeed warning", "overspeed", false), Triple("Camera sound", "cameraSound", true),
            Triple("Vibration", "vibrate", true), Triple("Speed-camera alerts", "speedCamera", true),
            Triple("Red-light-camera alerts", "redCamera", true)).forEach { (label, key, default) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label); Switch(checked = checked(key, default).also { version }, onCheckedChange = { prefs.edit().putBoolean(key, it).apply(); version++ })
            }
        }
        Spacer(Modifier.height(12.dp)); Text("Overspeed tolerance (mph)")
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(0, 1, 2, 3, 5).forEach { value -> FilterChip(selected = prefs.getInt("tolerance", 2).also { version } == value,
                onClick = { prefs.edit().putInt("tolerance", value).apply(); version++ }, label = { Text("+$value") }) }
        }
        Spacer(Modifier.height(22.dp))
        TextButton(onClick = cameras) { Text("Manage my cameras") }
        TextButton(onClick = diagnostics) { Text("Diagnostics") }
        Spacer(Modifier.height(20.dp))
        Text("Roads and public cameras: © OpenStreetMap contributors (ODbL). Nearby coordinates are sent to the public Overpass API for map data. Data can be missing or wrong; follow road signs. No account or journey history is stored.", color = Color(0xFF9AB0BF), fontSize = 13.sp)
    }
}
@Composable private fun DiagnosticsScreen(state: DriveState, back: () -> Unit) = Frame("Diagnostics", back) {
    val fix = state.fix
    listOf("GPS accuracy" to fix?.let { "${it.accuracyM.roundToInt()} m" },
        "GPS speed" to fix?.speedMps?.let { "${(it * MPS_TO_MPH).roundToInt()} mph" },
        "Heading" to fix?.bearing?.let { "${it.roundToInt()}°" },
        "Fix age" to fix?.let { "${(android.os.SystemClock.elapsedRealtime() - it.elapsedMs) / 1000} s" },
        "Road" to state.road?.road?.let { "${it.id} ${it.name.orEmpty()}" },
        "Match confidence" to state.road?.let { String.format(Locale.UK, "%.2f", it.confidence) },
        "Speed limit" to state.limitMph?.let { "$it mph (OSM maxspeed)" },
        "Map age" to state.dataAgeMs?.let { "${it / 60_000} min" },
        "Nearest camera" to state.decision.camera?.id,
        "Distance" to state.decision.distanceM?.let { "${it.roundToInt()} m" },
        "Camera bearing difference" to state.decision.bearingDifference?.let { "${it.roundToInt()}°" },
        "Approaching" to if (state.decision.accepted) "YES" else "NO",
        "Reason" to state.decision.reason).forEach { (label, value) -> Text("$label: ${value ?: "Unknown"}", modifier = Modifier.padding(vertical = 5.dp)) }
}
@Composable private fun CameraList(records: List<Camera>, moving: Boolean, back: () -> Unit, edit: (Camera) -> Unit, delete: (Camera) -> Unit) = Frame("My cameras", back) {
    if (records.isEmpty()) Text("No cameras added yet")
    LazyColumn {
        items(records, key = { it.id }) { camera ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(if (camera.type == CameraType.SPEED) "Speed camera" else "Red-light camera", fontWeight = FontWeight.Bold)
                Text("${camera.point.lat.format()}, ${camera.point.lon.format()}", fontSize = 13.sp, color = Color.LightGray)
                Row { TextButton(onClick = { edit(camera) }, enabled = !moving) { Text("Edit") }
                    TextButton(onClick = { delete(camera) }, enabled = !moving) { Text("Delete") } }
            }; HorizontalDivider()
        }
    }
}
private fun Double.format() = String.format(Locale.UK, "%.6f", this)
@Composable private fun CameraEditor(existing: Camera?, current: GeoPoint?, moving: Boolean, back: () -> Unit,
    save: (GeoPoint, CameraType, Double?, Int?, String?) -> Unit) {
    var point by remember(existing) { mutableStateOf(existing?.point ?: current) }
    var latitude by remember(existing) { mutableStateOf(point?.lat?.format() ?: "") }
    var longitude by remember(existing) { mutableStateOf(point?.lon?.format() ?: "") }
    var type by remember(existing) { mutableStateOf(existing?.type ?: CameraType.SPEED) }
    var mph by remember(existing) { mutableStateOf(existing?.enforcedMph?.toString() ?: "") }
    var direction by remember(existing) { mutableStateOf(existing?.direction?.roundToInt()?.toString() ?: "") }
    var note by remember(existing) { mutableStateOf(existing?.note ?: "") }
    Frame(if (existing == null) "Add camera" else "Edit camera", back) {
        Text("Only edit while stopped. Save your current GPS position or keep the saved location.")
        Spacer(Modifier.height(12.dp))
        Row { FilterChip(type == CameraType.SPEED, { type = CameraType.SPEED }, label = { Text("Speed camera") })
            Spacer(Modifier.width(8.dp)); FilterChip(type == CameraType.RED_LIGHT, { type = CameraType.RED_LIGHT }, label = { Text("Red light") }) }
        OutlinedTextField(latitude, { latitude = it.take(14) }, label = { Text("Latitude") }, singleLine = true)
        OutlinedTextField(longitude, { longitude = it.take(14) }, label = { Text("Longitude") }, singleLine = true)
        if (current != null) TextButton(onClick = { point = current; latitude = current.lat.format(); longitude = current.lon.format() }) { Text("Use current position") }
        OutlinedTextField(mph, { mph = it.filter(Char::isDigit).take(3) }, label = { Text("Enforced speed (mph, optional)") }, singleLine = true)
        OutlinedTextField(direction, { direction = it.filter(Char::isDigit).take(3) }, label = { Text("Travel direction 0–359° (optional)") }, singleLine = true)
        OutlinedTextField(note, { note = it.take(100) }, label = { Text("Note (optional)") })
        Spacer(Modifier.height(22.dp))
        val corrected = latitude.toDoubleOrNull()?.let { lat -> longitude.toDoubleOrNull()?.let { lon ->
            if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
        } }
        Button(onClick = { corrected?.let { save(it, type, direction.toDoubleOrNull(), mph.toIntOrNull(), note.ifBlank { null }) } },
            enabled = !moving && corrected != null && (direction.isEmpty() || direction.toIntOrNull()?.let { it in 0..359 } == true) &&
                (mph.isEmpty() || mph.toIntOrNull()?.let { it in 5..130 } == true)) { Text("Save camera") }
    }
}
