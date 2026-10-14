package dev.servd.core.discovery

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNameTest {

    @Test
    fun names_must_be_a_single_dns_label() {
        for (ok in listOf("servd", "myhub", "lab-2", "a", "x".repeat(63))) assertTrue(LocalName.isValid(ok), ok)
        for (bad in listOf("", "-hub", "hub-", "my hub", "my.hub", "Servd", "hub_1", "x".repeat(64), "ü")) {
            assertFalse(LocalName.isValid(bad), bad)
        }
    }
}
