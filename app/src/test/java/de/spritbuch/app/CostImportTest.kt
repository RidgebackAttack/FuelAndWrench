package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CostImportTest {
    @Test
    fun classifiesSpritmonitorCostTypes() {
        assertEquals(false to "Wäsche", CostImport.classify("Wagenpflege"))
        assertEquals(false to "Versicherung", CostImport.classify("Versicherung"))
        assertEquals(false to "Steuer", CostImport.classify("Steuer"))
        assertEquals(false to "Parken", CostImport.classify("Parkgebühren"))
        assertEquals(false to "Maut", CostImport.classify("Vignette"))
        assertEquals(false to "Finanzierung", CostImport.classify("Leasing"))
        assertEquals(false to "Gebühren", CostImport.classify("Bußgeld"))
        assertEquals(true to "Wartung", CostImport.classify("Wartung"))
        assertEquals(true to "Reparatur", CostImport.classify("Motorreparatur"))
        assertEquals(true to "Reifen", CostImport.classify("Reifen"))
        assertEquals(true to "HU/AU", CostImport.classify("TÜV"))
        assertEquals(false to "Sonstiges", CostImport.classify("Sonstiges"))
    }
}
