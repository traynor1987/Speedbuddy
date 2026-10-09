package uk.co.traynor.speedbuddy

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Called only by the explicit Export button. One redacted cache file, no network or owner-data access. */
internal suspend fun shareRoadDiagnosticReport(context: Context,history: FlightHistory) {
    val file=withContext(Dispatchers.IO) { writeRoadDiagnosticReport(context,history) }
    val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
    val send=Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send,"Export diagnostic report"))
}
internal fun writeRoadDiagnosticReport(context: Context,history: FlightHistory): File=
    File(context.cacheDir,"diagnostic-reports").apply { mkdirs() }
        .resolve("SpeedBuddy-road-decisions.json").apply { writeText(history.report()) }
