package uk.co.traynor.speedbuddy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

private val updateAccent = Color(0xFF77D5F0)
private val updateMuted = Color(0xFFAFC4D2)

@Composable fun UpdateCenterScreen(context: Context, moving: Boolean, back: () -> Unit) {
    val client = remember(context) { UpdateClient(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AvailableRelease?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Check GitHub for the latest signed release.") }
    val installed = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown" }

    fun install(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            status = "Allow updates from Speed Buddy in Android settings, then tap Install again."
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")))
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { status = "Could not open Android's installer: ${it.message ?: "Try again"}" }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("‹  Back") }
            Spacer(Modifier.width(12.dp))
            Text("Updates", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 22.dp)) {
            Text("SPEED BUDDY", color = updateAccent, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            Text("Version $installed", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("App update", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = updateMuted, lineHeight = 21.sp)
                    if (busy && progress > 0) {
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("$progress%", color = updateMuted)
                    }
                    release?.let { available ->
                        Spacer(Modifier.height(16.dp))
                        Text("Version ${available.version}  ·  ${available.bytes / 1_000_000} MB", color = updateAccent)
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(enabled = !busy && !moving, onClick = {
                        if (downloaded != null) install(downloaded!!)
                        else scope.launch {
                            busy = true
                            try {
                                val selected = release
                                if (selected == null) {
                                    status = "Checking GitHub…"
                                    release = client.check()
                                    status = if (release == null) "You're up to date." else "A signed update is available."
                                } else {
                                    status = "Downloading and verifying the update…"
                                    progress = 0
                                    downloaded = client.download(selected) { progress = it }
                                    status = "Verified. Android will ask you to confirm installation."
                                    install(downloaded!!)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                status = failure.message ?: "Could not check for updates. Try again later."
                            } finally { busy = false }
                        }
                    }) {
                        Text(when {
                            downloaded != null -> "Install update"
                            release != null -> "Download update"
                            else -> "Check for updates"
                        })
                    }
                    if (moving) Text("Updates are available when parked.", color = updateMuted,
                        modifier = Modifier.padding(top = 12.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Updates come from the Speed Buddy GitHub releases page. A downloaded APK must match the release checksum and this app's signing key before installation. Your saved cameras and road corrections stay in place for a normal update.",
                color = updateMuted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            Text("Switching from a debug build to the signed app requires a backup and reinstall. Back up cameras and settings before removing the debug build.",
                color = updateMuted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
