package uk.co.traynor.speedbuddy

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real driving sign and sheet through Android accessibility, without a GPS feed. */
@RunWith(AndroidJUnit4::class)
class LimitPickerUiTest {
    @Test fun tappingMainSignOpensEightLargeChoicesWithoutTextEntry() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val p=GeoPoint(53.5,-2.8)
        val road=Road("way/ui-current","Test",listOf(p,Geo.ahead(p,0.0,400.0)),mapOf("maxspeed" to "30 mph"))
        DriveBus.set(DriveState(active=true,speedMph=20.0,limitMph=30,
            fix=Fix(p,5.0,10.0,1.0,0.0,SystemClock.elapsedRealtime()),road=RoadMatch(road,0.0,0.0,.95),
            limitDecision=LimitDecision(30,reason="UI fixture")))
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            tap(awaitNode { it.contentDescription?.toString()=="Correct road speed limit" && it.isEnabled && it.isClickable })
            awaitNode { it.text?.toString()=="Choose the real limit" }
            for(label in listOf("20 mph","30 mph","40 mph","50 mph","60 mph","70 mph","National Speed Limit","Unknown")) {
                val choice=awaitNode { it.contentDescription?.toString()==label }
                assertTrue("$label must be clickable",choice.isClickable)
                val bounds=android.graphics.Rect();choice.getBoundsInScreen(bounds)
                assertTrue("$label touch target is too small: $bounds",bounds.height()>=48*instrumentation.targetContext.resources.displayMetrics.density)
            }
            val root=instrumentation.uiAutomation.rootInActiveWindow
            assertNull(find(root) { it.className?.toString()?.contains("EditText")==true })
            tap(awaitNode { it.contentDescription?.toString()=="40 mph" && it.isEnabled && it.isClickable })
            val until=SystemClock.elapsedRealtime()+5000
            while(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()=="Choose the real limit" }!=null && SystemClock.elapsedRealtime()<until) Thread.sleep(100)
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()=="Choose the real limit" })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            DriveBus.set(DriveState())
        }
    }
    /** Exercise the owner's real tap, including touch dispatch, rather than an accessibility shortcut. */
    private fun tap(node: AccessibilityNodeInfo) {
        val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
        assertFalse("Cannot tap an empty control",bounds.isEmpty)
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val down=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Touch injection failed",automation.injectInputEvent(event,true)) }
            finally { event.recycle() }
        }
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo {
        val until=SystemClock.elapsedRealtime()+7000
        do {
            find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow,predicate)?.let { return it }
            Thread.sleep(100)
        } while(SystemClock.elapsedRealtime()<until)
        error("Expected UI control was not visible")
    }
    private fun find(node: AccessibilityNodeInfo?,predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo? {
        if(node==null) return null
        if(predicate(node)) return node
        for(i in 0 until node.childCount) find(node.getChild(i),predicate)?.let { return it }
        return null
    }
}
