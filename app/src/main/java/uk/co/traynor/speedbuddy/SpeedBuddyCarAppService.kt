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

/**
 * Android Auto entry point for the private passive-surface experiment.
 *
 * It deliberately does not call NavigationManager, start a destination, publish
 * a Trip, start DrivingService, or own audio/location/database work. The only
 * data source is the in-process DriveBus already owned by the phone service.
 */
class SpeedBuddyCarAppService : CarAppService() {
    // Android Auto developer mode can bind from the installed host. This is
    // intentionally isolated to this private sideload experiment; no host data
    // is accepted and the screen contains no actions or editable content.
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
        updates.launch {
            DriveBus.state.collectLatest { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val view = AndroidAutoPresenter.present(DriveBus.state.value)
        val pane = Pane.Builder()
            // First row is intentionally the dominant/first glance target. The
            // host adapts row sizing and truncation for full and split layouts.
            .addRow(Row.Builder().setTitle(view.hero).addText(view.speed).build())
        view.status?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        // Secondary content is omitted rather than shrinking the limit itself.
        view.camera?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        view.upcoming?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        return PaneTemplate.Builder(pane.build())
            .setHeader(Header.Builder().setTitle("Speed Buddy").build())
            .build()
    }
}
