package uk.co.traynor.speedbuddy

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LufopImportTest {
    private fun zip(vararg files: Pair<String, String>): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive -> files.forEach { (name, body) ->
            archive.putNextEntry(ZipEntry(name)); archive.write(body.toByteArray()); archive.closeEntry()
        } }
        return ByteArrayInputStream(output.toByteArray())
    }

    @Test fun importsOnlyUKFixedAndRedLightAndDoesNotAssumeSpeedOrDirection() {
        val result = LufopAscImporter.parse(zip(
            "GBFixeGB30.asc" to "-2.00000, 53.00500, \"GB Radar Fixe GB 30\"\n",
            "GBFeuRougeGB.asc" to "-2.00000, 53.01000, \"GB Radar Feu Rouge GB\"\n",
            "GBMobileGB.asc" to "-2.0, 53.02, \"Mobile\"\n",
            "FRFixeFR50.asc" to "2.0, 48.0, \"FR Radar Fixe\"\n"))
        assertEquals(2, result.size)
        assertEquals(setOf(CameraType.SPEED, CameraType.RED_LIGHT), result.map { it.type }.toSet())
        assertTrue(result.all { it.source == CameraSource.LUFOP && it.direction == null && it.enforcedMph == null })
        assertEquals(53.005, result.first().point.lat, 0.00001)
    }

    @Test fun refusesEmptyOrMalformedUKDataRatherThanReplacingPriorImport() {
        assertThrows(IllegalArgumentException::class.java) { LufopAscImporter.parse(zip("FRFixeFR50.asc" to "2, 48, test")) }
        assertThrows(IllegalArgumentException::class.java) { LufopAscImporter.parse(zip("GBFixeGB30.asc" to "not coordinates")) }
        assertThrows(IllegalArgumentException::class.java) { LufopAscImporter.parse(zip("GBFixeGB30.asc" to "-2, 95, bad")) }
    }

    @Test fun importedCameraParticipatesInNormalApproachDetection() {
        val camera = LufopAscImporter.parse(zip("GBFeuRougeGB.asc" to "-2, 53.005, \"Red light\"\n")).single()
        val fix = Fix(GeoPoint(53.002, -2.0), 5.0, 12.5, 1.0, 0.0, 1000)
        val (alert, decision) = CameraApproachDetector().evaluate(fix, null, listOf(camera), 28.0)
        assertTrue(decision.accepted)
        assertEquals(CameraType.RED_LIGHT, alert?.camera?.type)
    }
}
