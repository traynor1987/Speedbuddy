package uk.co.traynor.speedbuddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraDbTest {
    @Test fun userCameraPersistsAndIsDetectedAfterReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = CameraDb(context).use { db -> db.create(GeoPoint(53.005, -2.0), CameraType.RED_LIGHT).id }
        CameraDb(context).use { db ->
            val camera = db.userCameras().first { it.id == id }
            val fix = Fix(GeoPoint(53.002, -2.0), 5.0, 12.5, 1.0, 0.0, 1000)
            val (alert, _) = CameraApproachDetector().evaluate(fix, null, listOf(camera), 28.0)
            assertEquals(id, alert?.camera?.id)
            db.delete(id)
        }
    }
}
