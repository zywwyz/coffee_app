package com.niumi.coffeejournal.core.model

import org.junit.Assert.*
import org.junit.Test

class PersonalCoffeeCostTest {
    @Test fun `bean cost uses whole fraction and exact rounding`() {
        assertEquals(600L, personalCoffeeCost(PersonalProductType.BEANS, 10000, 250000, null, 15000, null))
        assertEquals(667L, personalCoffeeCost(PersonalProductType.DRIP_BAG, 1000, null, 3, null, 2))
        assertEquals(590L, personalCoffeeCost(PersonalProductType.DRIP_BAG, 5900, null, 10, null, 1))
    }
    @Test fun `missing base and invalid quantities never become zero`() {
        assertNull(personalCoffeeCost(PersonalProductType.BEANS, null, 250000, null, 15000, null))
        assertNull(personalCoffeeCost(PersonalProductType.BEANS, 10000, 0, null, 15000, null))
        assertNull(personalCoffeeCost(PersonalProductType.BEANS, 10000, 250000, null, null, null))
        assertNull(personalCoffeeCost(PersonalProductType.DRIP_BAG, Long.MAX_VALUE, null, 1, null, 2))
        assertEquals(0L, personalCoffeeCost(PersonalProductType.DRIP_BAG, 0, null, 1, null, 1))
    }
    @Test fun `weight input rejects precision exponent negative and overflow`() {
        assertEquals(15001L, parseGramsToMilligrams("15.001"))
        listOf("0", "-1", "NaN", "1e3", "1.0001", "9223372036854776").forEach { assertNull(parseGramsToMilligrams(it)) }
        assertEquals("15.001", formatMilligrams(15001))
        assertEquals("250", formatMilligrams(250000))
        assertNull(parsePositiveCount("1.5"))
        assertNull(parsePositiveCount("0"))
    }
}
