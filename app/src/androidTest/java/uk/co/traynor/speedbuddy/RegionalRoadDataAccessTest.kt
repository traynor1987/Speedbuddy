package uk.co.traynor.speedbuddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionalRoadDataAccessTest {
    @Test fun savesATrimmedCredentialAndClearsItWithoutTouchingOtherSettings() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("settings",0)
        preferences.edit().clear().putString("unrelated","kept").commit()
        val access=RegionalRoadDataAccess(context)

        access.save("  owner-access-token  ")
        assertEquals("owner-access-token",access.credential())
        assertEquals("kept",preferences.getString("unrelated",null))

        access.clear()
        assertNull(access.credential())
        assertEquals("kept",preferences.getString("unrelated",null))
    }
}
