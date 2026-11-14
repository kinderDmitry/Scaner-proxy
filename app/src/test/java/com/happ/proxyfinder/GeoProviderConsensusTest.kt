package com.happ.proxyfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoProviderConsensusTest {
    @Test
    fun `countryAndCityUseMajorityAcrossIndependentProviders`() {
        val result = mergeGeoProviderResults(
            listOf(
                Triple("United States", "US", "New York"),
                Triple("United States", "US", "New York"),
                Triple("Germany", "DE", "Berlin"),
                Triple("United States", "US", "Boston")
            )
        )

        assertEquals(Triple("United States", "US", "New York"), result)
    }

    @Test
    fun `cityIsOmittedWhenProvidersDisagree`() {
        val result = mergeGeoProviderResults(
            listOf(
                Triple("United States", "US", "New York"),
                Triple("United States", "US", "Boston"),
                Triple("United States", "US", "")
            )
        )

        assertEquals(Triple("United States", "US", ""), result)
    }

    @Test
    fun `singleAvailableProviderRemainsUsableAsFallback`() {
        val result = mergeGeoProviderResults(
            listOf(null, Triple("Germany", "DE", "Berlin"), null, null)
        )

        assertEquals(Triple("Germany", "DE", "Berlin"), result)
    }

    @Test
    fun `returnsNullWhenEveryProviderFails`() {
        assertNull(mergeGeoProviderResults(listOf(null, null, null, null)))
    }
}
