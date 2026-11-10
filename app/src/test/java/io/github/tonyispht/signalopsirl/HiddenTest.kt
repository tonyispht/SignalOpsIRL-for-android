package io.github.tonyispht.signalopsirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenTest {
    private val publicMac = "C8:2B:96:12:34:56"   // public address: never changes
    private val randomMac = "D3:11:22:33:44:55"   // random (locally administered) address: changes

    private fun dev(addr: String, name: String?) = SeenDevice(addr, name, -60, 0L, false)

    @Test fun publicAddressIsHiddenByMac() {
        assertEquals("MAC:$publicMac", Hidden.keyFor(dev(publicMac, "Living Room TV")))
    }

    @Test fun namedRandomAddressIsHiddenByName() {
        assertEquals("NAME:galaxy watch6", Hidden.keyFor(dev(randomMac, "Galaxy Watch6")))
    }

    @Test fun unnamedRandomAddressFallsBackToMac() {
        assertEquals("MAC:$randomMac", Hidden.keyFor(dev(randomMac.lowercase(), null)))
    }

    @Test fun matching() {
        val keys = setOf("MAC:$publicMac", "NAME:galaxy watch6")
        assertTrue(Hidden.matches(keys, dev(publicMac.lowercase(), null)))
        assertTrue(Hidden.matches(keys, dev("E1:00:00:00:00:01", "GALAXY WATCH6")))   // new random address, same name
        assertFalse(Hidden.matches(keys, dev("E1:00:00:00:00:01", "Galaxy Watch5")))
        assertFalse(Hidden.matches(emptySet(), dev(publicMac, null)))
    }

    @Test fun parseSkipsJunkAndDuplicates() {
        val list = Hidden.parse(setOf("MAC:$publicMac\tTV", "NAME:buds\tMy buds", "garbage", "MAC:$publicMac"))
        assertEquals(2, list.size)
        assertEquals("My buds", list[0].label)
        assertTrue(list[0].byName)
        assertEquals("buds", list[0].value)
    }
}
