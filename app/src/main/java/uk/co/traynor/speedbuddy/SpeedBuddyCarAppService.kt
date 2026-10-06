package uk.co.traynor.speedbuddy

import android.content.Intent
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
import kotlinx.coroutines.flow.collectLatest
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
        updates.launch { DriveBus.state.collectLatest { invalidate() } }
    }
    override fun onGetTemplate(): Template {
        val view = AndroidAutoPresenter.present(DriveBus.state.value)
        // The host scales this large sign for a wide surface or a dashboard
        // split. Two concise rows preserve a deliberately limit-first layout.
        val pane = Pane.Builder().setImage(signIcon(view.limitSign))
        pane.addRow(Row.Builder().setTitle(view.speed).apply {
            view.confidence?.let(::addText)
            view.status?.let(::addText)
        }.build())
        (view.camera ?: view.upcoming)?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
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
}
