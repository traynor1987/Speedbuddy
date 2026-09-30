package uk.co.traynor.speedbuddy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

@Composable fun CameraDetailSheet(camera: Camera, sourcePoint: GeoPoint?, close: ()->Unit,
    edit: ()->Unit, remove: ((Camera)->Unit)?) {
    var advanced by rememberSaveable(camera.id) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(12.dp),shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.65f)
            .verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(when(camera.type) { CameraType.SPEED->"Speed camera";CameraType.RED_LIGHT->"Red-light camera";CameraType.COMBINED->"Speed + red-light camera";CameraType.AVERAGE->"Average-speed enforcement point";CameraType.MOBILE->"Mobile speed camera report" },
                style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text(if(camera.source==CameraSource.USER) "USER ADDED · unverified" else "${camera.source.name} · ${if(camera.locallyCorrected) "local correction · unverified" else "source record · not locally verified"}",
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(camera.enforcedMph?.let { "Speed limit $it mph" } ?: "Camera speed limit unknown")
            Text(camera.direction?.let { "Enforced travel direction ${it.toInt()}°${if(camera.bidirectional) " and opposite direction" else ""}" } ?: "Enforced direction unknown")
            camera.note?.let { Text(it) }
            if(camera.updatedAtMs>0) Text("Saved/refreshed ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(camera.updatedAtMs))}",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={advanced=!advanced}) { Text(if(advanced) "Hide details" else "More details") }
            if(advanced) Text("Effective position: ${camera.point.lat}, ${camera.point.lon}"+
                (sourcePoint?.let { "\nSource position: ${it.lat}, ${it.lon}" } ?: "")+"\nRecord: ${camera.id}",style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick=close) { Text("Close") }
                remove?.let { TextButton(onClick={it(camera)}) { Text(if(camera.source==CameraSource.USER) "Delete" else "Hide locally") } }
                Button(onClick=edit) { Text("Edit camera") }
            }
        }
    }
}
