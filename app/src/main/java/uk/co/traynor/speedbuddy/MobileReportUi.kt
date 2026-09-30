package uk.co.traynor.speedbuddy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable internal fun MobileObservationActions(report: MobileReport, confirm: ()->Unit, remove: ()->Unit,
    confirmationEnabled: Boolean=true) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(report.id) { while(true) { delay(15_000);now=System.currentTimeMillis() } }
    Column(Modifier.fillMaxWidth()) {
        Text(listOfNotNull(report.roadName,mobileAgeLabel(report,now)+" · this phone").joinToString(" · "),
            color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall,
            modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=confirm,enabled=confirmationEnabled && report.activeAt(now),modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text("Still there") }
            TextButton(onClick=remove,modifier=Modifier.weight(1f).heightIn(min=48.dp)) { Text("Not there") }
        }
    }
}

@Composable internal fun MobileReportDetailSheet(report: MobileReport,close: ()->Unit,confirm: ()->Unit,remove: ()->Unit) {
    Surface(Modifier.fillMaxWidth().padding(12.dp),shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.65f)
            .verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Mobile speed camera",style=MaterialTheme.typography.titleLarge)
            Text("TEMPORARY REPORT",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
            Text("Source: ${report.source}",style=MaterialTheme.typography.bodySmall)
            Text("Reported ${DateFormat.getDateTimeInstance().format(Date(report.reportedAtMs))}",style=MaterialTheme.typography.bodySmall)
            Text("Expires ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(report.expiresAtMs))}",style=MaterialTheme.typography.bodySmall)
            Text(report.direction?.let { "Reported travel direction ${it.toInt()}°" } ?: "Travel direction unknown")
            Text("A local observation; presence and enforcement direction are unverified.",style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            MobileObservationActions(report,confirm,remove)
            TextButton(onClick=close) { Text("Close") }
        }
    }
}
