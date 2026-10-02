package uk.co.traynor.speedbuddy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

@Composable internal fun JunctionEditor(junction: CameraJunction, close: () -> Unit,
    save: (CameraJunction) -> Unit) {
    var name by rememberSaveable(junction.id) { mutableStateOf(junction.name) }
    var ways by rememberSaveable(junction.id) { mutableIntStateOf(junction.ways) }
    val candidate = junction.copy(name=name.trim(),ways=ways)
    val valid = runCatching { JunctionRules.validate(candidate) }.isSuccess
    Surface(Modifier.fillMaxWidth().padding(12.dp),shape=RoundedCornerShape(20.dp)) {
        Column(Modifier.heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.7f)
            .verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Camera junction",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("Drag the map or tap to position the junction centre. Add each real camera after saving.",
                color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(name,{name=it.take(60)},Modifier.fillMaxWidth(),label={Text("Junction name")},
                placeholder={Text("e.g. Fiveways")},singleLine=true)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(4,5).forEach { count -> FilterChip(ways==count,{ways=count},label={Text("$count-way junction")}) }
            }
            Text("This is the number of roads, not the number of cameras. Each camera keeps its own position, type and direction.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick=close) { Text("Cancel") }
                Button(onClick={save(candidate)},enabled=valid) { Text("Save junction") }
            }
        }
    }
}

@Composable internal fun JunctionDetails(junction: CameraJunction, cameras: List<Camera>, nearby: List<Camera>,
    close: () -> Unit, edit: () -> Unit, add: () -> Unit, select: (Camera) -> Unit,
    link: (Camera) -> Unit, ungroup: () -> Unit) {
    var linking by rememberSaveable(junction.id) { mutableStateOf(false) }
    var confirmUngroup by rememberSaveable(junction.id) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(12.dp),shape=RoundedCornerShape(20.dp)) {
        Column(Modifier.heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.7f)
            .verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(junction.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text("${junction.ways}-way junction · ${cameras.size} camera${if(cameras.size==1) "" else "s"}",
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(cameras.isEmpty()) Text("Add the cameras you know are here. Roads without cameras do not need a pin.",
                style=MaterialTheme.typography.bodyMedium)
            cameras.forEach { camera -> JunctionCameraRow(camera) { select(camera) } }
            Button(onClick=add,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("Add camera") }
            if(nearby.isNotEmpty()) {
                TextButton(onClick={linking=!linking}) { Text(if(linking) "Hide nearby cameras" else "Link an existing nearby camera") }
                if(linking) nearby.take(30).forEach { camera -> JunctionCameraRow(camera) { link(camera) } }
            }
            Text("Warnings follow each camera’s direction and share one junction encounter.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick=edit,modifier=Modifier.fillMaxWidth()) { Text("Edit junction") }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick=close) { Text("Close") }
                TextButton(onClick={confirmUngroup=true}) { Text("Ungroup") }
            }
        }
    }
    if(confirmUngroup) AlertDialog(onDismissRequest={confirmUngroup=false},
        title={Text("Ungroup ${junction.name}?")},
        text={Text("The junction marker will be removed. Its cameras stay saved and warn individually.")},
        confirmButton={TextButton(onClick={confirmUngroup=false;ungroup()}) { Text("Keep cameras and ungroup") }},
        dismissButton={TextButton(onClick={confirmUngroup=false}) { Text("Cancel") }})
}

@Composable private fun JunctionCameraRow(camera: Camera, select: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick=select),color=MaterialTheme.colorScheme.surfaceContainer,
        shape=RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Surface(Modifier.size(36.dp),shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment=Alignment.Center) { Text(when(camera.type) {
                    CameraType.RED_LIGHT -> "R";CameraType.COMBINED -> "R+";else -> "S"
                },fontWeight=FontWeight.Bold) }
            }
            Column(Modifier.weight(1f)) {
                Text(camera.note?.takeIf { it.isNotBlank() } ?: when(camera.type) {
                    CameraType.RED_LIGHT -> "Red-light camera";CameraType.COMBINED -> "Speed + red-light camera";else -> "Speed camera"
                },maxLines=2,overflow=TextOverflow.Ellipsis)
                Text((camera.enforcedMph?.let { "$it mph · " } ?: "")+
                    (camera.direction?.let { "Travel ${it.toInt()}°" } ?: "Set direction before linking"),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›",style=MaterialTheme.typography.titleLarge)
        }
    }
}
