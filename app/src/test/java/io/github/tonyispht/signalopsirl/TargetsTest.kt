package io.github.tonyispht.signalopsirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's own target list: every entry style in the User Guide must keep matching (and not over-matching). */
class TargetsTest {
    private val mac = "C8:2B:96:12:34:56"

    private fun dev(
        name: String? = null, address: String = mac, rssi: Int = -55,
        cids: Set<Int> = emptySet(), services: Set<String> = emptySet()
    ) = MatchInput(name, address, rssi, cids, services)

    private fun hits(entry: String, m: MatchInput): Boolean {
        val c = Targets.compile(entry)
        assertNull("'$entry' should compile, got ${c.error}", c.error)
        return c.rule!!.eval(m) > 0
    }

    @Test fun namePartIsCaseInsensitive() {
        assertTrue(hits("tile", dev("Tile Mate")))
        assertTrue(hits("FLIPPER", dev("Flipper Zero")))
        assertEquals(false, hits("tile", dev("Galaxy Buds2")))
        assertEquals(false, hits("tile", dev(null)))
    }

    @Test fun exactMacAndPrefix() {
        assertTrue(hits(mac, dev()))
        assertTrue(hits(mac.lowercase(), dev()))
        assertEquals(false, hits("C8:2B:96:12:34:57", dev()))
        assertTrue(hits("C8:2B:96", dev()))
        assertEquals(false, hits("C8:2B:97", dev()))
    }

    @Test fun companyId() {
        assertTrue(hits("CID:004C", dev(cids = setOf(0x004C))))
        assertEquals(false, hits("CID:004C", dev(cids = setOf(0x0075))))
    }

    @Test fun serviceUuid() {
        val feaa = Targets.uuid16(0xFEAA)
        assertTrue(hits("UUID:FEAA", dev(services = setOf(feaa))))
        assertEquals(false, hits("UUID:FEAA", dev(services = setOf(Targets.uuid16(0x180F)))))
    }

    @Test fun exactStartsEndsAndRegex() {
        assertTrue(hits("EXACT:Tile", dev("tile")))
        assertEquals(false, hits("EXACT:Tile", dev("Tile Mate")))
        assertTrue(hits("STARTS:Flip", dev("Flipper Zero")))
        assertTrue(hits("ENDS:Zero", dev("Flipper Zero")))
        assertTrue(hits("REGEX:^esp32-[0-9a-f]{4}$", dev("ESP32-1A2B")))
    }

    @Test fun signalGateAndCombinations() {
        assertTrue(hits("tile @-60", dev("Tile", rssi = -50)))
        assertEquals(false, hits("tile @-60", dev("Tile", rssi = -75)))
        assertTrue(hits("CID:004C & !NAME:AirPods", dev("iPhone", cids = setOf(0x004C))))
        assertEquals(false, hits("CID:004C & !NAME:AirPods", dev("AirPods Pro", cids = setOf(0x004C))))
    }

    @Test fun badEntriesReportAnError() {
        assertNotNull(Targets.compile("UUID:XYZ").error)
        assertNotNull(Targets.compile("CID:").error)
    }

    @Test fun bundledTargetListsAllCompile() {
        val dir = listOf(java.io.File("../target-lists"), java.io.File("target-lists")).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".txt") }!!.toList()
        assertTrue(files.isNotEmpty())
        for (f in files) for (line in f.readLines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            assertNull("${f.name}: '$t'", Targets.compile(t).error)
        }
    }
}
