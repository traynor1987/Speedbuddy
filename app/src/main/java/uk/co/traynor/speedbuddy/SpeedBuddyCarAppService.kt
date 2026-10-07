package uk.co.traynor.speedbuddy

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SpeedBuddyCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = SpeedBuddyCarSession()
}
private class SpeedBuddyCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = SpeedBuddyCarScreen(carContext)
}
private class SpeedBuddyCarScreen(carContext: androidx.car.app.CarContext) : Screen(carContext) {
    private val updates = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { updates.cancel() }
        })
        // The host controls final rendering cadence. Every distinct authoritative DriveBus update
        // invalidates promptly; there is no car-owned polling, debounce, or location collection.
        updates.launch { DriveBus.state.collect { invalidate() } }
    }
    override fun onGetTemplate(): Template {
        val view = AndroidAutoPresenter.present(DriveBus.state.value)
        // PaneTemplate's supported large images place the speed and sign in the host's own
        // two-column layout. On a dashboard split the host reflows them without a phone UI clone.
        val pane = Pane.Builder().setImage(signIcon(view.limitSign))
        pane.addRow(Row.Builder().setTitle("CURRENT SPEED").setImage(speedIcon(view.speed), Row.IMAGE_TYPE_LARGE).apply {
            view.confidence?.let(::addText)
            view.status?.let(::addText)
        }.build())
        view.camera?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        view.upcoming?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        return PaneTemplate.Builder(pane.build()).setHeader(Header.Builder().setTitle("Speed Buddy").build()).build()
    }
    private fun signIcon(sign: AndroidAutoLimitSign): CarIcon = CarIcon.Builder(
        IconCompat.createWithResource(carContext, when (sign) {
            AndroidAutoLimitSign.MPH_20 -> R.drawable.aa_limit_20
            AndroidAutoLimitSign.MPH_30 -> R.drawable.aa_limit_30
            AndroidAutoLimitSign.MPH_40 -> R.drawable.aa_limit_40
            AndroidAutoLimitSign.MPH_50 -> R.drawable.aa_limit_50
            AndroidAutoLimitSign.MPH_60 -> R.drawable.aa_limit_60
            AndroidAutoLimitSign.MPH_70 -> R.drawable.aa_limit_70
            AndroidAutoLimitSign.UNKNOWN -> R.drawable.aa_limit_unknown
        })
    ).build()

    private fun speedIcon(speed: String): CarIcon {
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val value = speed.substringBefore(' ')
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER; textSize = 112f
        }
        val unitPaint = Paint(valuePaint).apply { textSize = 37f; letterSpacing = .08f }
        canvas.drawText(value, 112f, 118f, valuePaint)
        canvas.drawText("MPH", 112f, 170f, unitPaint)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }
}
