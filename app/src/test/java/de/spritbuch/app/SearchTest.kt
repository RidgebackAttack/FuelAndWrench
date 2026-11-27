package de.spritbuch.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    @Test fun emptyQueryMatchesAll() = assertTrue(Search.hit("  ", "egal"))
    @Test fun caseInsensitive() = assertTrue(Search.hit("bremsen", "Bremsen vorne"))
    @Test fun allWordsMustMatch() {
        assertTrue(Search.hit("bremse vorn", "Bremsen vorne", "Werkstatt Müller"))
        assertFalse(Search.hit("bremse hinten", "Bremsen vorne"))
    }
    @Test fun wordsAcrossFields() = assertTrue(Search.hit("müller öl", "Ölwechsel", "Werkstatt Müller"))
    @Test fun numbersWithoutSeparators() {
        assertTrue(Search.hit("153931", "153.931 km"))
        assertTrue(Search.hit("1482", "1.482,48 €"))
        assertTrue(Search.hit("153.931", "153.931 km"))
    }
}
