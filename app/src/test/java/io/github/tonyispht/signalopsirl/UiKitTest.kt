package io.github.tonyispht.signalopsirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiKitTest {
    @Test fun readableKeepsAcronyms() {
        assertEquals(
            "Warns when an AirTag, Tile or similar tracker keeps showing up. Uses GPS and BLE.",
            readable("WARNS WHEN AN AIRTAG, TILE OR SIMILAR TRACKER KEEPS SHOWING UP. USES GPS AND BLE.")
        )
        assertEquals("Already mixed Case stays.", readable("Already mixed Case stays."))
    }

    @Test fun rarity() {
        assertEquals(Rarity.COMMON, rarityOf("smartphones"))
        assertEquals(Rarity.LEGENDARY, rarityOf("law_enforcement_tactical"))
        assertTrue(rarityOf("hobby_rc_drones").ordinal > Rarity.RARE.ordinal)
    }

    @Test fun modelReasons() {
        assertTrue(isModelReason("Fast Pair model 0A1B2C"))
        assertTrue(!isModelReason("company 0x004C"))
    }
}
