package uk.co.traynor.speedbuddy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import android.os.Build
import android.view.WindowManager
import kotlinx.coroutines.Job
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Background: Color @Composable get() = MaterialTheme.colorScheme.background
private val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
private val Line: Color @Composable get() = MaterialTheme.colorScheme.outline
private val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Warning = Color(0xFFFFCA75)

class MainActivity : ComponentActivity() {
    private var activityDb: CameraDb? = null
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { startDrivingService() }
    override fun onDestroy() {
        val database=activityDb
        lifecycleScope.coroutineContext[Job]?.invokeOnCompletion { database?.close() }
        super.onDestroy()
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startDriving()
        else DriveBus.set(DriveState(status="Allow precise location to start driving mode"))
    }
    private fun startDriving() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        val settings=getSharedPreferences("settings",Context.MODE_PRIVATE)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED && !settings.getBoolean("notificationsAsked",false)) {
            settings.edit().putBoolean("notificationsAsked",true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);return
        }
        startDrivingService()
    }
    private fun startDrivingService() {
        try { startForegroundService(Intent(this, DrivingService::class.java)) }
        catch (_: Exception) { DriveBus.set(DriveState(status = "Could not start location service")) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = CameraDb(this).also { activityDb=it }
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        setContent {
            var page by rememberSaveable { mutableStateOf("drive") }
            BackHandler(enabled = page != "drive") { page = when (page) {
                "edit" -> "cameras"; "cameras", "mapData", "updates" -> "settings"; else -> "drive"
            } }
            val state by DriveBus.state.collectAsState()
            var settingsRevision by remember { mutableIntStateOf(0) }
            DisposableEffect(prefs) {
                val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,_->settingsRevision++ }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            DisposableEffect(state.active,settingsRevision) {
                if(state.active && prefs.getBoolean("keepAwake",true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            var records by remember { mutableStateOf(emptyList<Camera>()) }
            var editing by rememberSaveable { mutableStateOf<Camera?>(null) }
            var roadEdit by rememberSaveable { mutableStateOf<Road?>(null) }
            var message by remember { mutableStateOf("") }
            var deleting by rememberSaveable { mutableStateOf<Camera?>(null) }
            var importedInfo by remember { mutableStateOf<CameraDb.ImportedInfo?>(null) }
            LaunchedEffect(Unit) {
                records=withContext(Dispatchers.IO) { db.userCameras() }
                importedInfo=withContext(Dispatchers.IO) { db.importedInfo() }
                if (importedInfo == null) {
                    runCatching { withContext(Dispatchers.IO) {
                        val batch = assets.open("lufop-uk-2026-09.zip").use(LufopAscImporter::inspect)
                        require(batch.cameras.size == 5_233) { "Bundled camera data incomplete" }
                        synchronized(db) {
                            db.seedImportedIfEmpty(batch.cameras,"2026-09-01 · Lufop UK")
                        }
                    } }.onSuccess { importedInfo = db.importedInfo() }
                        .onFailure { runCatching {
                            db.recordImportFailure("Bundled camera setup failed: ${it.message ?: "Unknown error"}")
                        } }
                }
            }
            val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) lifecycleScope.launch { runCatching { withContext(Dispatchers.IO) {
                    val content = OwnerBackupCodec.export(db.userCameras(), prefs, db.cameraCorrections(),
                        db.roadLimits(), db.suppressedCameraIds(), db.roadCorrections(),db.aliasLinks())
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(content) }
                        ?: error("Could not open backup file")
                } }.onSuccess { message = "Camera and settings backup saved." }
                    .onFailure { message = "Backup failed: ${it.message ?: "Unknown error"}" } }
            }
            val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) lifecycleScope.launch { runCatching { withContext(Dispatchers.IO) {
                    val content = contentResolver.openInputStream(uri)?.use { BoundedIo.text(it,2_000_000) }
                        ?: error("Could not read backup file")
                    val backup = OwnerBackupCodec.parse(content)
                    db.restoreOwnerData(backup,prefs)
                    backup.cameras.size to db.userCameras()
                } }.onSuccess { (count, restored) -> records=restored; message = "Restored $count cameras and settings. Existing cameras were kept." }
                    .onFailure { message = "Restore failed: ${it.message ?: "Invalid backup"}" } }
            }
            val importLufop = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    val live = DriveBus.state.value
                    if (live.active && (live.speedMph == null || live.speedMph >= 5)) message = "Import cameras while parked."
                    else lifecycleScope.launch {
                        runCatching { withContext(Dispatchers.IO) {
                            val batch = contentResolver.openInputStream(uri)?.use(LufopAscImporter::inspect)
                                ?: error("Could not read ZIP")
                            val date = batch.archiveDateMs?.let {
                                SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date(it))
                            } ?: "Archive date unknown"
                            synchronized(db) { db.replaceImported(batch.cameras, date) }
                        } }.onSuccess {
                            importedInfo = db.importedInfo()
                            message = "Imported $it UK cameras. Existing personal cameras were kept."
                        }.onFailure {
                            runCatching { db.recordImportFailure(it.message ?: "Invalid ZIP") }
                            message = "Camera import failed: ${it.message ?: "Invalid ZIP"}. Previous data was kept."
                        }
                    }
                }
            }
            val speed = state.speedMph
            val moving = state.active && (speed == null || speed >= 5.0)
            SpeedBuddyTheme(prefs) {
                val lightBars=MaterialTheme.colorScheme.background.luminance()>.5f
                SideEffect {
                    WindowInsetsControllerCompat(window,window.decorView).apply {
                        isAppearanceLightStatusBars=lightBars;isAppearanceLightNavigationBars=lightBars
                    }
                }
                Surface(Modifier.fillMaxSize(), color = Background, contentColor = Ink) {
                  Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    when (page) {
                        "drive" -> DriveScreen(state, ::startDriving,
                            { stopService(Intent(this@MainActivity, DrivingService::class.java)) },
                            { page = "settings" }, { page = "map" }, { page = "diagnostics" },
                            { if (state.fix == null) message = "Wait for a GPS fix"
                              else if (moving) message = "Stop before editing a camera"
                              else { editing = null; page = "edit" } },
                            { type -> state.fix?.let { selected->lifecycleScope.launch {
                                runCatching { withContext(Dispatchers.IO) { db.create(selected.point,type,selected.bearing);db.userCameras() } }
                                    .onSuccess { records=it;message="Camera position saved. Edit the details while stopped." }
                                    .onFailure { message="Could not save camera: ${it.message ?: "Try again"}" }
                            } } }, onUnknownLimit = {
                                val currentFix = state.fix
                                when {
                                    moving -> message = "Stop before correcting a road limit."
                                    currentFix == null || SystemClock.elapsedRealtime() - currentFix.elapsedMs > 5000 ->
                                        message = "Wait for a current GPS fix, then select the road on the map."
                                    else -> {
                                        val target = DriveRoadEditTarget.select(currentFix, state.road)
                                        if (target != null) roadEdit = target
                                        else { page = "map"; message = "Tap your road on the map to set its limit." }
                                    }
                                }
                            }, onReportMobile = {
                                val live=DriveBus.state.value
                                val observation=MobileReportCapture.create(live.fix,live.road,SystemClock.elapsedRealtime(),System.currentTimeMillis(),prefs.getInt("mobileLifetime",120))
                                if(observation==null) android.widget.Toast.makeText(this@MainActivity,"Wait for a fresh, accurate GPS fix",android.widget.Toast.LENGTH_SHORT).show()
                                else lifecycleScope.launch {
                                    val result=runCatching { withContext(Dispatchers.IO) { MobileReportStore(db).record(observation) } }
                                    android.widget.Toast.makeText(this@MainActivity,if(result.isSuccess) "Mobile camera reported · this phone only" else "Could not save report. Try again.",android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }, onMobileFeedback = { id,stillThere ->
                                val live=DriveBus.state.value
                                if(live.alert?.camera?.id==id) lifecycleScope.launch {
                                    val result=runCatching { withContext(Dispatchers.IO) {
                                        val store=MobileReportStore(db)
                                        if(stillThere) store.confirm(id) else { store.remove(id);true }
                                    } }
                                    android.widget.Toast.makeText(this@MainActivity,when {
                                        result.isFailure -> "Could not update report. Try again."
                                        result.getOrNull()!=true -> "Report has expired"
                                        stillThere -> "Report confirmed"
                                        else -> "Report removed"
                                    },android.widget.Toast.LENGTH_SHORT).show()
                                }
                            })
                        "settings" -> SettingsScreen(prefs, { page = "drive" },
                            { page="cameras";lifecycleScope.launch { records=withContext(Dispatchers.IO) { db.userCameras() } } }, { page = "diagnostics" },
                            { exportBackup.launch("SpeedBuddy-backup.json") },
                            { importBackup.launch(arrayOf("application/json", "text/plain")) }, importedInfo,
                            { if (moving) message = "Import cameras while parked."
                              else importLufop.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                            { page = "map" }, { page = "mapData" }, { page = "updates" })
                        "updates" -> UpdateCenterScreen(this@MainActivity, moving) { page = "settings" }
                        "map" -> CameraMapScreen(db, state.fix?.point, moving,
                            importedInfo?.importedAtMs ?: 0L, importedInfo?.count ?: 0) { page = "drive" }
                        "mapData" -> MapDataScreen(db, importedInfo, { page = "settings" },
                            { importLufop.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                            { exportBackup.launch("SpeedBuddy-backup.json") },
                            { SpeedBuddyMapProvider.clearTileCache(this@MainActivity) { error->
                                message=if(error==null) "Map tile cache cleared. Driving data remains available." else "Could not clear map cache: $error"
                            } })
                        "diagnostics" -> DiagnosticsScreen(state) { page = "drive" }
                        "cameras" -> CameraList(records, moving, { page = "settings" },
                            { editing = it; page = "edit" },
                            { deleting=it })
                        "edit" -> CameraEditor(editing, state.fix?.point, moving, { page = "cameras" }) { point, type, direction, mph, note, both ->
                            if (!moving) {
                                val old = editing
                                lifecycleScope.launch {
                                    runCatching { withContext(Dispatchers.IO) {
                                        if(old==null) db.create(point,type,direction,mph,note,both)
                                        else db.upsert(old.copy(point=point,type=type,direction=direction,enforcedMph=mph,note=note,bidirectional=both))
                                        db.userCameras()
                                    } }.onSuccess { records=it;page="cameras" }
                                        .onFailure { message="Could not save camera: ${it.message ?: "Try again"}" }
                                }
                            }
                        }
                    }
                    deleting?.let { selected->AlertDialog(onDismissRequest={deleting=null},title={Text("Delete added camera?")},text={Text("This removes your saved camera from Map and driving alerts.")},confirmButton={TextButton(onClick={
                        lifecycleScope.launch {
                            runCatching { withContext(Dispatchers.IO) { db.delete(selected.id);db.userCameras() } }
                                .onSuccess { records=it;deleting=null }.onFailure { message="Could not delete camera: ${it.message ?: "Try again"}" }
                        }
                    },enabled=!moving) { Text("Delete") }},dismissButton={TextButton(onClick={deleting=null}) { Text("Cancel") }}) }
                    roadEdit?.let { selected -> DriveRoadLimitEditor(selected, db.roadCorrection(selected.id),
                        close = { roadEdit = null }, save = { kind, mph ->
                            val live = DriveBus.state.value
                            val current = DriveRoadEditTarget.select(live.fix, live.road)
                            if (current?.id != selected.id || live.fix == null ||
                                SystemClock.elapsedRealtime() - live.fix.elapsedMs > 5000 ||
                                live.active && (live.speedMph == null || live.speedMph >= 5.0)) {
                                message = "Road position changed. Select the road again while stopped."
                            } else {
                                val source = db.roadCorrection(selected.id)?.sourceValue
                                    ?: selected.tags["maxspeed"] ?: selected.tags["maxspeed:type"]
                                db.saveRoadCorrection(RoadLimitCorrection(selected.id, kind, mph,
                                    source, System.currentTimeMillis()))
                            }
                            roadEdit = null
                        }) }
                    if (message.isNotEmpty()) AlertDialog(onDismissRequest = { message = "" },
                        confirmButton = { TextButton(onClick = { message = "" }) { Text("OK") } },
                        text = { Text(message, color = Ink) })
                  }
                }
            }
        }
    }
}

@Composable private fun Page(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹  Back", color = Accent) }
            Spacer(Modifier.width(10.dp))
            Text(title, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable private fun LimitSign(limit: Int?, national: Boolean, modifier: Modifier = Modifier) {
    val circle = modifier.semantics { contentDescription=when { limit==null->"Unknown speed limit";national->"National speed limit";else->"Speed limit $limit miles per hour" } }.clip(CircleShape)
    when {
        limit == null -> BoxWithConstraints(circle.border(2.dp, Line, CircleShape).background(Panel),
            contentAlignment = Alignment.Center) {
            val diameter = minOf(maxWidth, maxHeight).value
            Text("--", fontSize = (diameter * .44f).sp, fontWeight = FontWeight.Bold, color = Muted)
        }
        national -> Box(circle.background(Color.White), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(Color(0xFF111111), Offset(size.width * .18f, size.height * .82f),
                    Offset(size.width * .82f, size.height * .18f), strokeWidth = size.width * .14f)
            }
        }
        else -> BoxWithConstraints(circle.background(Color.White), contentAlignment = Alignment.Center) {
            val diameter = minOf(maxWidth, maxHeight).value
            Box(Modifier.fillMaxSize().border((diameter * .09f).dp, Color(0xFFDC282B), CircleShape),
                contentAlignment = Alignment.Center) {
                Text(limit.toString(), color = Color(0xFF111111),
                    fontSize = (diameter * (if (limit >= 100) .37f else .45f)).sp,
                    fontWeight = FontWeight.Black, letterSpacing = (-diameter * .02f).sp, maxLines = 1)
            }
        }
    }
}

@Composable private fun TurnLimitPreview(turn: TurnLimit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (turn.direction == TurnDirection.LEFT) "↰ IF LEFT" else "IF RIGHT ↱",
            color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        LimitSign(turn.mph, turn.national, Modifier.size(58.dp))
        Text("${(turn.distanceM * 1.093613).roundToInt()} yd", color = Muted, fontSize = 11.sp)
    }
}

@Composable private fun DriveRoadLimitEditor(road: Road, correction: RoadLimitCorrection?,
    close: () -> Unit, save: (RoadLimitKind, Int?) -> Unit) {
    var selected by rememberSaveable(road.id) { mutableStateOf<Pair<RoadLimitKind, Int?>?>(null) }
    AlertDialog(onDismissRequest = close,
        title = { Text("Set road speed limit") },
        text = {
            Column(Modifier.heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.65f).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(road.name ?: "This road", fontWeight = FontWeight.Bold)
                Text("Choose the posted limit for this road. This correction is saved on your phone.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                if(road.tags.keys.any { it in setOf("maxspeed:conditional","maxspeed:variable","maxspeed:lanes","maxspeed:forward","maxspeed:backward") })
                    Text("This road has variable or directional limits. A simple correction is saved, but its driving limit stays unknown until that context can be resolved.",color=Muted,style=MaterialTheme.typography.bodySmall)
                listOf(listOf(20, 30, 40), listOf(50, 60, 70)).forEach { values ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        values.forEach { mph ->
                            FilterChip(selected == (RoadLimitKind.NUMERIC to mph),
                                onClick = { selected = RoadLimitKind.NUMERIC to mph },
                                label = { Text("$mph") })
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected == (RoadLimitKind.NATIONAL_SINGLE to 60),
                        onClick = { selected = RoadLimitKind.NATIONAL_SINGLE to 60 },
                        label = { Text("National · single") })
                    FilterChip(selected == (RoadLimitKind.NATIONAL_DUAL to 70),
                        onClick = { selected = RoadLimitKind.NATIONAL_DUAL to 70 },
                        label = { Text("National · dual") })
                }
                FilterChip(selected==(RoadLimitKind.UNKNOWN to null),onClick={selected=RoadLimitKind.UNKNOWN to null},label={Text("Unknown")})
                Text("Source: ${correction?.sourceValue ?: road.tags["maxspeed"] ?: road.tags["maxspeed:type"] ?: "not tagged"}",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { selected?.let { save(it.first, it.second) } },
            enabled = selected != null) { Text("Save limit") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable internal fun DriveScreen(state: DriveState, onStart: () -> Unit, onStop: () -> Unit,
    onSettings: () -> Unit, onMap: () -> Unit, onDiagnostic: () -> Unit,
    onAdd: () -> Unit, onQuick: (CameraType) -> Unit, onUnknownLimit: () -> Unit,
    onReportMobile: () -> Unit = {}, onMobileFeedback: (String,Boolean) -> Unit = { _,_-> }) {
    val speed = state.speedMph
    val moving = state.active && (speed == null || speed >= 5.0)
    val fix = state.fix
    val fixAge = fix?.let { SystemClock.elapsedRealtime() - it.elapsedMs }
    val gpsLabel = when {
        !state.active -> "READY WHEN YOU ARE"
        fix == null -> "SEARCHING FOR GPS"
        fixAge == null || fixAge > 5000 -> "GPS SIGNAL LOST"
        fix.accuracyM > 50 -> "GPS SIGNAL WEAK"
        else -> "GPS FIX · ${fix.accuracyM.roundToInt()} M"
    }
    val tags = state.road?.road?.tags
    val national = state.limitMph != null && (
        tags?.get("maxspeed:type")?.startsWith("GB:nsl") == true ||
        tags?.get("maxspeed")?.startsWith("GB:nsl") == true || tags?.get("maxspeed") == "GB:motorway")
    val compact=LocalConfiguration.current.screenHeightDp<800
    Column(Modifier.fillMaxSize().then(if(compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        .padding(horizontal = 22.dp, vertical = if(compact) 6.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("SPEED BUDDY", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(gpsLabel, color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
            }
            Row {
                TextButton(onClick = onMap) { Text("Map") }
                TextButton(onClick = onSettings, enabled = !moving) { Text("Settings") }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("CURRENT SPEED", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Text(speed?.roundToInt()?.toString() ?: "--", color = if (state.overspeed) Warning else Ink,
            fontSize = if(compact) 80.sp else 124.sp, lineHeight = if(compact) 88.sp else 132.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text("MPH", color = Muted, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
        Spacer(Modifier.height(28.dp))
        Text("CURRENT ROAD LIMIT", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val mainSize = minOf(if(compact) 110.dp else 158.dp,(maxWidth-152.dp).coerceAtLeast(96.dp))
            Box(Modifier.fillMaxWidth().height(mainSize + if (state.limitMph == null) 32.dp else 0.dp),
                contentAlignment = Alignment.TopCenter) {
                Box(Modifier.fillMaxWidth().height(mainSize), contentAlignment = Alignment.Center) {
                    LimitSign(state.limitMph, national, Modifier.size(mainSize).testTag("current-road-limit"))
                    state.turns.firstOrNull { it.direction == TurnDirection.LEFT }?.let {
                        TurnLimitPreview(it, Modifier.align(Alignment.CenterStart).width(72.dp))
                    }
                    state.turns.firstOrNull { it.direction == TurnDirection.RIGHT }?.let {
                        TurnLimitPreview(it, Modifier.align(Alignment.CenterEnd).width(72.dp))
                    }
                }
                if (state.limitMph == null) Surface(
                    onClick = onUnknownLimit,
                    modifier = Modifier.align(Alignment.BottomCenter).size(48.dp)
                        .semantics { contentDescription = "Set this road's speed limit" },
                    shape = CircleShape, color = Accent, contentColor = Background,
                    border = BorderStroke(3.dp, Background)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("+", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        state.upcoming?.let { next ->
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AHEAD", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                LimitSign(next.mph, next.national, Modifier.size(42.dp))
                Text("${(next.distanceM * 1.093613).roundToInt()} yd", color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(when {
            state.limitMph == null -> "Limit unknown"
            national -> "National speed limit · ${state.limitMph} mph"
            else -> "${state.limitMph} mph"
        }, color = Muted, fontSize = 15.sp)
        if(compact) Spacer(Modifier.height(12.dp)) else Spacer(Modifier.weight(1f))
        state.averageSection?.let { section->
            Text("Average-speed section" + (section.section.mph?.let { " · $it mph" } ?: "") +
                if(section.remainingM.isFinite()) " · ${(section.remainingM*1.093613).roundToInt()} yd remaining" else " · last known position",
                color=Accent,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(bottom=8.dp))
        }
        val alert = state.alert
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            color = if (alert != null) MaterialTheme.colorScheme.secondaryContainer else Panel, contentColor = Ink) {
            Row(Modifier.fillMaxWidth().heightIn(min = 82.dp).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (alert != null) {
                    val symbol = when (alert.camera.type) {
                        CameraType.SPEED -> "S"; CameraType.RED_LIGHT -> "R"
                        CameraType.COMBINED -> "R+"; CameraType.AVERAGE -> "A";CameraType.MOBILE -> "M"
                    }
                    Surface(Modifier.size(42.dp), shape = CircleShape, color = Warning, contentColor = Background) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(symbol, fontSize = 17.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (alert == null) "CAMERA STATUS" else when (alert.camera.type) {
                        CameraType.SPEED -> "SPEED CAMERA AHEAD"
                        CameraType.RED_LIGHT -> "RED-LIGHT CAMERA AHEAD"
                        CameraType.COMBINED -> "SPEED + RED-LIGHT CAMERA"
                        CameraType.AVERAGE -> "AVERAGE-SPEED CAMERA"
                        CameraType.MOBILE -> "MOBILE CAMERA REPORTED"
                    },
                        color = if (alert == null) Muted else Warning, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(if (alert == null) state.status.ifBlank { "No active camera alert" } else if(state.alertPositionFresh) "Approaching" else "Last known approach · GPS unavailable",
                        color = Ink, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (alert != null) Text("${(alert.distanceM * 1.093613).roundToInt()} yd", color = Ink,
                    fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        alert?.camera?.mobileReport?.let { report ->
            MobileObservationActions(report,{onMobileFeedback(report.id,true)},{onMobileFeedback(report.id,false)},
                confirmationEnabled=fixAge!=null && fixAge in 0..5_000 && fix!=null && fix.accuracyM<=35 && state.alertPositionFresh)
        }
        if(state.active) OutlinedButton(onClick=onReportMobile,
            enabled=fix!=null && fixAge!=null && fixAge in 0..5_000 && fix.accuracyM<=35,
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).padding(top=4.dp)) { Text("Report mobile camera") }
        Spacer(Modifier.height(8.dp))
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
    cameras: () -> Unit, diagnostics: () -> Unit, exportBackup: () -> Unit, importBackup: () -> Unit,
    imported: CameraDb.ImportedInfo?, importLufop: () -> Unit, openMap: () -> Unit,
    openMapData: () -> Unit, openUpdates: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    Page("Settings", back) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionLabel("DRIVING")
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column {
                    val enabled = prefs.getBoolean("overspeed", false).also { version }
                    SettingsToggle("Keep screen awake while driving",prefs.getBoolean("keepAwake",true).also { version }) { prefs.edit().putBoolean("keepAwake",it).apply();version++ }
                    SettingsToggle("Speak speed-limit changes",prefs.getBoolean("limitVoice",true).also { version }) { prefs.edit().putBoolean("limitVoice",it).apply();version++ }
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
                    listOf(Triple("Fixed cameras", "fixedCamera", true),Triple("Mobile camera reports", "mobileCamera", true),Triple("Speed cameras", "speedCamera", true), Triple("Red-light cameras", "redCamera", true),
                        Triple("Voice warnings", "cameraSound", true), Triple("Vibration", "vibrate", true)).forEachIndexed { index, (label, key, default) ->
                        if (index > 0) HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsToggle(label, prefs.getBoolean(key, default).also { version }) {
                            prefs.edit().putBoolean(key, it).apply(); version++
                        }
                    }
                    HorizontalDivider(color=Line,modifier=Modifier.padding(horizontal=16.dp))
                    Column(Modifier.padding(16.dp)) {
                        Text("Mobile reports expire after",color=Ink,style=MaterialTheme.typography.bodyMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            MobileReportCapture.lifetimes.forEach { minutes ->
                                FilterChip(selected=prefs.getInt("mobileLifetime",120).also { version }==minutes,
                                    onClick={prefs.edit().putInt("mobileLifetime",minutes).apply();version++},
                                    label={Text(if(minutes<60) "$minutes min" else "${minutes/60} hr")})
                            }
                        }
                        Text("New reports use this lifetime. Confirmation renews an active report. Reports stay on this phone; no shared live feed is connected.",color=Muted,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(22.dp)); SectionLabel("YOUR DATA")
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column {
                    MenuRow("Camera and speed-limit map", "Follow a drive; edit roads and camera pins while parked", openMap)
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                    MenuRow("Map & road data", "Camera database, cache and local corrections", openMapData)
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                    MenuRow("Manage my cameras", "View, edit or delete saved cameras", cameras)
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                    MenuRow("Back up cameras and settings", "Save a JSON file to your chosen location", exportBackup)
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                    MenuRow("Restore backup", "Merge saved cameras and restore settings", importBackup)
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                    MenuRow("Diagnostics", "GPS, road match and camera decisions", diagnostics)
                }
            }
            Spacer(Modifier.height(22.dp)); SectionLabel("APPEARANCE")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System","light" to "Day","dark" to "Night").forEach { (value,label)->
                    FilterChip(prefs.getString("theme","system")==value,{prefs.edit().putString("theme",value).apply();version++},label={Text(label)})
                }
            }
            Spacer(Modifier.height(22.dp)); SectionLabel("APP")
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                MenuRow("Updates", "Check signed Speed Buddy releases on GitHub", openUpdates)
            }
            Spacer(Modifier.height(22.dp)); SectionLabel("PUBLIC CAMERA FILE")
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column {
                    MenuRow("Import Lufop UK cameras", "Choose the free Europe ASC ZIP while parked", importLufop)
                    HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                    Text(if (imported == null) "No Lufop file imported yet"
                        else "${imported.count} UK cameras · archive ${imported.sourceDate} · imported ${SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(imported.importedAtMs))}",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                }
            }
            Spacer(Modifier.height(22.dp)); SectionLabel("SOURCES & PRIVACY")
            Text("Roads and public cameras © OpenStreetMap contributors (ODbL). Nearby coordinates are sent to the public Overpass API. Data may be incomplete; follow road signs.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(8.dp))
            Text("Speed-limit sign designs based on The Highway Code. © Crown copyright, Open Government Licence v3.0. No account or journey history is stored.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(8.dp))
            Text("Imported camera data: Lufop.net and OpenStreetMap contributors (ODbL 1.0). Import the free file again each month; Speed Buddy does not access your Lufop account. Camera positions and types may be incomplete or wrong, and the file does not provide reliable UK mph or direction metadata.",
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

@Composable private fun MapDataScreen(db: CameraDb, imported: CameraDb.ImportedInfo?, back: () -> Unit,
    update: () -> Unit, export: () -> Unit, clearCache: () -> Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val uriHandler=LocalUriHandler.current
    val scope=rememberCoroutineScope()
    val live by DriveBus.state.collectAsState()
    val parked=!live.active || live.speedMph?.let { it<5 }==true
    val prefs=remember { context.getSharedPreferences("settings",0) }
    var revision by remember { mutableIntStateOf(0) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var feedEditor by rememberSaveable { mutableStateOf(false) }
    var feed by rememberSaveable { mutableStateOf(prefs.getString("cameraFeed","").orEmpty()) }
    var notice by remember { mutableStateOf("") }
    var information by remember { mutableStateOf(imported) }
    var attempt by remember { mutableStateOf<CameraDb.ImportStatus?>(null) }
    var owners by remember { mutableIntStateOf(0) }
    var corrections by remember { mutableStateOf(emptyList<CameraCorrection>()) }
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var roads by remember { mutableStateOf(emptyList<RoadLimitCorrection>()) }
    var cache by remember { mutableStateOf(0 to 0L) }
    var shown by rememberSaveable { mutableIntStateOf(30) }
    LaunchedEffect(revision) {
        while(true) {
            withContext(Dispatchers.IO) {
                val info=db.importedInfo();val status=db.importStatus();val ownerCount=db.ownerCameraCount()
                val edits=db.cameraCorrections();val suppressed=db.suppressedCameraIds();val roadEdits=db.roadCorrections()
                val usage=RoadCache(context).use { it.diagnostics() }
                withContext(Dispatchers.Main) {
                    information=info;attempt=status;owners=ownerCount;corrections=edits;hidden=suppressed;roads=roadEdits;cache=usage
                }
            }
            kotlinx.coroutines.delay(5000)
        }
    }
    fun change(action: ()->Unit) { scope.launch {
        runCatching { withContext(Dispatchers.IO) { action() } }.onSuccess { revision++ }
            .onFailure { notice="Could not change saved data: ${it.message ?: "Try again"}" }
    } }
    Page("Map & road data", back) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SectionLabel("CAMERA DATABASE")
            Surface(shape=RoundedCornerShape(18.dp),color=Panel) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Lufop UK camera file",color=Ink,fontWeight=FontWeight.Bold)
                    Text(information?.let { "${it.count} cameras · archive ${it.sourceDate}\nLast successful update ${SimpleDateFormat("d MMM yyyy HH:mm",Locale.UK).format(Date(it.importedAtMs))}" } ?: "Preparing included camera data…",color=Muted)
                    Text("The included snapshot works offline. Lufop's free monthly ZIP is downloaded from your account and imported while parked.",color=Muted,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={uriHandler.openUri("https://lufop.net/en/asc-and-csv-speed-camera-files/")}) { Text("Lufop source · ODbL 1.0") }
                    attempt?.let { a->Text("Last attempted ${SimpleDateFormat("d MMM yyyy HH:mm",Locale.UK).format(Date(a.attemptedAtMs))}"+(a.failure?.let { " · $it" } ?: " · successful"),color=Muted,style=MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick=update,enabled=parked) { Text("Update from ZIP") }
                    Text(if(prefs.getString("cameraFeed","").isNullOrBlank()) "Automatic updates: no feed configured" else "Automatic updates: daily on Wi-Fi, while driving is stopped",color=Muted,style=MaterialTheme.typography.bodySmall)
                    if(!prefs.getString("cameraFeed","").isNullOrBlank()) OutlinedButton(onClick={CameraDatabaseUpdates.now(context);notice="Camera update queued. The previous database stays active until validation succeeds."},enabled=parked) { Text("Update now") }
                    TextButton(onClick={feedEditor=true},enabled=parked) { Text("Configure automatic updates") }
                }
            }
            SectionLabel("LOCAL DATA")
            Surface(shape=RoundedCornerShape(18.dp),color=Panel) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("$owners added cameras · ${corrections.size} camera corrections · ${hidden.size} hidden source records · ${roads.size} road corrections",color=Ink)
                    Text("${SpeedBuddyMapProvider.name} · OpenStreetMap road tags",color=Muted,style=MaterialTheme.typography.bodySmall)
                    Text("Saved road coverage: ${cache.first} visited areas · ${"%.1f".format(Locale.UK,cache.second/1_000_000.0)} MB. Refresh after 24 hours when online; available offline for up to 30 days. Map tiles cache separately.",color=Muted,style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick=export) { Text("Export owner data") }
                    TextButton(onClick={confirm="camera"},enabled=parked) { Text("Reset camera corrections") }
                    TextButton(onClick={confirm="road"},enabled=parked) { Text("Reset road-limit corrections") }
                    TextButton(onClick={confirm="cache"},enabled=parked) { Text("Clear map tile cache") }
                }
            }
            if(hidden.isNotEmpty() || corrections.isNotEmpty() || roads.isNotEmpty()) {
                SectionLabel("MANAGE CORRECTIONS")
                hidden.sorted().take(shown).forEach { id->
                    Surface(shape=RoundedCornerShape(12.dp),color=Panel) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Hidden camera",color=Ink);Text(id,color=Muted,style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={change { db.restoreHiddenCamera(id) }},enabled=parked) { Text("Restore linked camera") }
                    } }
                }
                corrections.take(shown).forEach { correction->
                    Surface(shape=RoundedCornerShape(12.dp),color=Panel) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(correction.note ?: "Local camera correction",color=Ink)
                        Text("${correction.source.name} · ${correction.id}",color=Muted,style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={confirm="edit:${correction.id}"},enabled=parked) { Text("Remove correction") }
                    } }
                }
                roads.take(shown).forEach { correction->
                    Surface(shape=RoundedCornerShape(12.dp),color=Panel) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("${correction.mph?.let { "$it mph" } ?: "Unknown"} · ${correction.id}",color=Ink)
                        TextButton(onClick={confirm="roadEdit:${correction.id}"},enabled=parked) { Text("Remove correction") }
                    } }
                }
                if(maxOf(hidden.size,corrections.size,roads.size)>shown) TextButton(onClick={shown+=30}) { Text("Show more") }
            }
            if(notice.isNotBlank()) Text(notice,color=Ink)
            Text("Camera alerts and saved corrections work without map tiles. Road limits require saved road coverage. Follow posted signs.",color=Muted,style=MaterialTheme.typography.bodySmall)
        }
    }
    if(feedEditor) AlertDialog(onDismissRequest={feedEditor=false},title={Text("Automatic camera updates")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Use a permitted HTTPS address for a Lufop-format ZIP. No account passwords. Leave blank to turn off automatic updates.")
            OutlinedTextField(feed,{feed=it.take(1000)},label={Text("HTTPS camera ZIP address")},singleLine=true)
        }
    },confirmButton={TextButton(onClick={CameraDatabaseUpdates.configure(context,feed);feedEditor=false;revision++},enabled=parked && (feed.isBlank() || CameraDatabaseUpdates.validFeed(feed))) { Text("Save") }},dismissButton={TextButton(onClick={feedEditor=false}) { Text("Cancel") }})
    confirm?.let { action->AlertDialog(onDismissRequest={confirm=null},title={Text(if(action=="cache") "Clear map tiles?" else "Remove local corrections?")},text={Text(if(action=="cache") "The map may need a connection to display tiles again. Camera alerts remain available." else "The selected local changes will be removed. Added cameras and imported source data stay.")},confirmButton={TextButton(onClick={
        if(action=="cache") clearCache() else change {
            when { action=="camera"->db.resetCameraOverrides();action=="road"->db.resetRoadLimits()
                action.startsWith("edit:")->db.deleteCameraCorrection(action.removePrefix("edit:"))
                action.startsWith("roadEdit:")->db.deleteRoadLimit(action.removePrefix("roadEdit:")) }
        }
        confirm=null
    },enabled=parked) { Text("Remove") }},dismissButton={TextButton(onClick={confirm=null}) { Text("Cancel") }}) }
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
    val fixAge = fix?.let { SystemClock.elapsedRealtime() - it.elapsedMs }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        DiagnosticCard("GPS", listOf(
            "Status" to when {
                !state.active -> "Driving mode stopped"
                fix == null -> "Waiting for GPS"
                fixAge == null || fixAge > 5000 -> "Fix stale"
                fix.accuracyM > 50 -> "Weak fix"
                else -> "Live GPS fix"
            },
            "Provider" to if (state.active) "Device GPS" else null,
            "Location" to fix?.point?.let { "${it.lat.format()}, ${it.lon.format()}" },
            "Accuracy" to fix?.let { "${it.accuracyM.roundToInt()} m" },
            "Speed" to fix?.speedMps?.let { "${(it * MPS_TO_MPH).roundToInt()} mph" },
            "Heading" to fix?.bearing?.let { "${it.roundToInt()}°" },
            "Fix age" to fixAge?.let { "${it / 1000} s" }))
        DiagnosticCard("ROAD", listOf(
            "Matched road" to state.road?.road?.let { "${it.name ?: "Unnamed"} · ${it.id}" },
            "Confidence" to state.road?.let { String.format(Locale.UK, "%.2f", it.confidence) },
            "Known limit" to state.limitMph?.let { "$it mph · OSM" },
            "Map data age" to state.dataAgeMs?.let { "${it / 60_000} min" },
            "Map request" to state.mapStatus))
        DiagnosticCard("CAMERA", listOf(
            "Public records nearby" to state.publicCameraCount.toString(),
            "Lufop UK records" to state.importedCameraCount.toString(),
            "Lufop candidates nearby" to state.importedNearbyCount.toString(),
            "My saved cameras" to state.userCameraCount.toString(),
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
                    Text(when (camera.type) { CameraType.SPEED -> "Speed camera"
                        CameraType.RED_LIGHT -> "Red-light camera"; CameraType.COMBINED -> "Speed + red-light camera"
                        CameraType.AVERAGE -> "Average-speed camera";CameraType.MOBILE -> "Mobile camera report" },
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
@Composable internal fun CameraEditor(existing: Camera?, current: GeoPoint?, moving: Boolean, back: () -> Unit,
    save: (GeoPoint, CameraType, Double?, Int?, String?,Boolean) -> Unit) {
    val initial = existing?.point ?: current
    var latitude by rememberSaveable(existing?.id) { mutableStateOf(initial?.lat?.format() ?: "") }
    var longitude by rememberSaveable(existing?.id) { mutableStateOf(initial?.lon?.format() ?: "") }
    var type by rememberSaveable(existing?.id) { mutableStateOf(existing?.type ?: CameraType.SPEED) }
    var mph by rememberSaveable(existing?.id) { mutableStateOf(existing?.enforcedMph?.toString() ?: "") }
    var direction by rememberSaveable(existing?.id) { mutableStateOf(existing?.direction?.toInt()?.toString() ?: "") }
    var note by rememberSaveable(existing?.id) { mutableStateOf(existing?.note ?: "") }
    var both by rememberSaveable(existing?.id) { mutableStateOf(existing?.bidirectional==true) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    Page(if (existing == null) "Add camera" else "Edit camera", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text("Edit only while parked. Save your current GPS position or correct the coordinates below.",
                color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column(Modifier.padding(16.dp)) {
                    Text("Camera type", color = Ink, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        CameraType.entries.filter { it != CameraType.MOBILE }.forEach { value->FilterChip(type==value,{type=value},label={Text(when(value) {
                            CameraType.SPEED->"Speed";CameraType.RED_LIGHT->"Red light";CameraType.COMBINED->"Combined";CameraType.AVERAGE->"Average speed";CameraType.MOBILE->"Mobile report" })}) }
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
                    DirectionDial(direction.toDoubleOrNull()) { direction=it.toInt().toString() }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("Both travel directions",Modifier.weight(1f));Switch(both,{both=it},enabled=direction.toDoubleOrNull()!=null)
                    }
                    TextButton(onClick={direction="";both=false}) { Text("Direction unknown") }
                    TextButton(onClick={advanced=!advanced}) { Text(if(advanced) "Hide bearing entry" else "Advanced bearing entry") }
                    if(advanced) OutlinedTextField(direction, { direction = it.filter(Char::isDigit).take(3) }, label = { Text("Travel direction · 0–359°") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(note, { note = it.take(100) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        val corrected = latitude.toDoubleOrNull()?.let { lat -> longitude.toDoubleOrNull()?.let { lon ->
            if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
        } }
        Button(onClick = { corrected?.let { save(it, type, direction.toDoubleOrNull(), mph.toIntOrNull(), note.ifBlank { null },both) } },
            enabled = !moving && corrected != null && (direction.isEmpty() || direction.toIntOrNull()?.let { it in 0..359 } == true) &&
                (mph.isEmpty() || mph.toIntOrNull()?.let { it in 5..130 } == true),
            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Save camera") }
        Spacer(Modifier.height(12.dp))
    }
}
