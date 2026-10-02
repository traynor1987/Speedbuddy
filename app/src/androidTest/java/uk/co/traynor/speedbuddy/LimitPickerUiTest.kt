package uk.co.traynor.speedbuddy

import android.content.Intent
import android.os.SystemClock
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
            val sign=awaitNode { it.contentDescription?.toString()=="Correct road speed limit" }
            assertTrue(sign.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode { it.text?.toString()=="Choose the real limit" }
            for(label in listOf("20 mph","30 mph","40 mph","50 mph","60 mph","70 mph","National Speed Limit","Unknown")) {
                val choice=awaitNode { it.contentDescription?.toString()==label }
                assertTrue(choice.isClickable)
                val bounds=android.graphics.Rect();choice.getBoundsInScreen(bounds)
                assertTrue(bounds.height()>=48*instrumentation.targetContext.resources.displayMetrics.density)
            }
            val root=instrumentation.uiAutomation.rootInActiveWindow
            assertNull(find(root) { it.className?.toString()?.contains("EditText")==true })
            assertTrue(awaitNode { it.contentDescription?.toString()=="40 mph" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val until=SystemClock.elapsedRealtime()+5000
            while(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()=="Choose the real limit" }!=null && SystemClock.elapsedRealtime()<until) Thread.sleep(100)
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()=="Choose the real limit" })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            DriveBus.set(DriveState())
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
