package com.happ.proxyfinder

import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCheckSummaryTest {
    @Test
    fun reportsAccessibleBlockedAndUnavailableServicesSeparately() {
        val summary = formatServiceCheckSummary(
            mapOf("YouTube" to 204, "Instagram" to 403, "ChatGPT" to null)
        )
        assertTrue(summary.contains("YouTube ✓"))
        assertTrue(summary.contains("Instagram ✗ HTTP 403"))
        assertTrue(summary.contains("ChatGPT ❗ нет ответа"))
    }
}
