package com.example.signalopsirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Checks the bundled Bluetooth database and the identification rules that use it, on a plain JVM. */
class BtDbTest {
    companion object {
        private val assets = listOf("src/main/assets", "app/src/main/assets").map { File(it) }.first { it.exists() }

        @BeforeClass @JvmStatic
        fun load() {
            fun r(n: String) = File(assets, "btdb/$n").bufferedReader()
            BtDb.load(r("companies.tsv"), r("uuid16.tsv"), r("appearance.tsv"), File(assets, "btdb/VERSION").readText())
            Classifier.loadFrom(File(assets, "scanner_config.json").readText())
        }

        private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** One AD structure: length byte, type byte, payload. */
        private fun ad(type: Int, payload: String): ByteArray {
            val p = hex(payload)
            return byteArrayOf((p.size + 1).toByte(), type.toByte()) + p
        }
        private fun svc(u: Int) = "0000%04X".format(u) + BtDb.BASE_SUFFIX
        private const val RANDOM = "5A:11:22:33:44:55"
    }

    @Test fun databaseIsComplete() {
        assertTrue(BtDb.companyCount > 3500)
        assertTrue(BtDb.uuidCount > 700)
        assertEquals("Apple", Classifier.companyName(0x004C))          // config short name wins
        assertNotNull(BtDb.company(0x0E1D))                              // only in the full SIG list
        assertEquals("Tile, Inc.", BtDb.uuid16(0xFEED)?.name)
        assertEquals("Human Interface Device · Keyboard", BtDb.appearanceName(0x03C1))
        assertEquals("Heart Rate", BtDb.serviceName(svc(0x180D)))
    }

    @Test fun serviceDataOnlyDevicesAreFound() {
        // Samsung SmartTag: flags + service data 0xFD5A only (no UUID list)
        val pk = hex("02 01 06 0D 16 5A FD 11 22 33 44 55 66 77 88 99 AA")
        val svcs = AdDecoder.serviceDataUuids(listOf(pk))
        assertTrue(svc(0xFD5A) in svcs)
        val c = Classifier.classify(Evidence(RANDOM, null, services = svcs))!!
        assertEquals("trackers_valuables", c.category)
        assertEquals("Samsung SmartTag", c.label)
    }

    @Test fun droneRemoteId() {
        val pk = ad(0x16, "FA FF 0D 00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF 00 11 22 33 44 55")
        val c = Classifier.classify(Evidence(RANDOM, null, services = AdDecoder.serviceDataUuids(listOf(pk))))!!
        assertEquals("hobby_rc_drones", c.category)
    }

    @Test fun googleFindHubTracker() {
        val pk = ad(0x01, "06") + ad(0x03, "AA FE") + ad(0x16, "AA FE 40 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF 00 11 22 33")
        val p = listOf(pk)
        val c = Classifier.classify(Evidence(RANDOM, null, services = AdDecoder.serviceDataUuids(p), hints = AdDecoder.hints(p)))!!
        assertEquals("trackers_valuables", c.category)
        assertEquals("Google Find Hub tracker", c.label)
    }

    @Test fun windowsPcFromMicrosoftCdp() {
        // Microsoft 0x0006, scenario 0x01, device type 9 (Windows desktop)
        val pk = ad(0xFF, "06 00 01 09 20 02 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF 00 11 22 33 44")
        val c = Classifier.classify(Evidence(RANDOM, null, companyIds = setOf(0x0006), hints = AdDecoder.hints(listOf(pk))))!!
        assertEquals("laptops_tablets", c.category)
        assertEquals("Windows PC", c.label)
    }

    @Test fun appearanceGivesCategory() {
        val pk = hex("02 01 06 03 19 C1 03")   // appearance = keyboard
        val c = Classifier.classify(Evidence(RANDOM, null, hints = AdDecoder.hints(listOf(pk))))!!
        assertEquals("computer_peripherals", c.category)
        assertEquals(Tier.INFO, c.tier)
    }

    @Test fun unknownCompanyStillNamed() {
        val id = 0x0E1D
        val c = Classifier.classify(Evidence(RANDOM, null, companyIds = setOf(id)))!!
        assertEquals(BtDb.company(id), c.label)
    }

    @Test fun memberUuidOwnerNamed() {
        // a company service UUID with no rule: the owner from the SIG list is shown
        val c = Classifier.classify(Evidence(RANDOM, null, services = setOf(svc(0xFD69))))!!
        assertTrue(c.label!!.contains("Samsung"))
    }

    @Test fun publicAddressVendorNamed() {
        val c = Classifier.classify(Evidence("00:1B:66:00:00:01", null, vendor = "Sennheiser electronic GmbH & Co. KG"))!!
        assertTrue(c.label!!.contains("Sennheiser"))
    }

    @Test fun axonServiceIsLawEnforcement() {
        val c = Classifier.classify(Evidence(RANDOM, null, services = setOf(svc(0xFC81))))!!
        assertEquals(Tier.ALERT, c.tier)
        assertEquals("law_enforcement_tactical", c.category)
    }

    @Test fun nameRulesStillWin() {
        val c = Classifier.classify(Evidence(RANDOM, "Bash Bunny", services = setOf(svc(0x1812))))
        assertNotNull(c)
        assertTrue(c!!.category != "computer_peripherals")
    }
}
