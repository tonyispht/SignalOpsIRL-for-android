package com.example.signalopsirl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versions() {
        assertEquals(listOf(1, 2, 0), Updater.semver("v1.2.0"))
        assertEquals(listOf(1, 10, 0), Updater.semver("1.10"))
        assertNull(Updater.semver("dev-16"))
        assertTrue(Updater.compare(Updater.semver("1.10.0")!!, Updater.semver("1.9.3")!!) > 0)
        assertEquals(0, Updater.compare(Updater.semver("1.1")!!, Updater.semver("1.1.0")!!))
    }
}
