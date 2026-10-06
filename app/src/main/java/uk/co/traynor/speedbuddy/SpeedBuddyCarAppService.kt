package uk.co.traynor.speedbuddy

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
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
        val pane = Pane.Builder().addRow(Row.Builder().setTitle(view.hero).addText(view.speed).build())
        view.status?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        view.camera?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        view.upcoming?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        return PaneTemplate.Builder(pane.build()).setHeader(Header.Builder().setTitle("Speed Buddy").build()).build()
    }
}
