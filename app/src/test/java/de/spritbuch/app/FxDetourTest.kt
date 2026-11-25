package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FxDetourTest {
    @Test fun parsesEcbCsv() {
        val csv = """
            KEY,FREQ,CURRENCY,CURRENCY_DENOM,EXR_TYPE,EXR_SUFFIX,TIME_PERIOD,OBS_VALUE,OBS_STATUS
            EXR.D.DKK.EUR.SP00.A,D,DKK,EUR,SP00,A,2026-07-02,7.4598,A
            EXR.D.DKK.EUR.SP00.A,D,DKK,EUR,SP00,A,2026-07-03,7.4601,A
        """.trimIndent()
        assertEquals(7.4601 to "2026-07-03", Fx.parse(csv))
        assertNull(Fx.parse("nichts"))
    }

    @Test fun currencyFromReceipt() {
        assertEquals("DKK", ReceiptParser.currency("Q8 Kolding\nI ALT DKK 512,40\nBlyfri 95"))
        assertEquals("PLN", ReceiptParser.currency("ORLEN\nSUMA 245,10 zł"))
        assertEquals("GBP", ReceiptParser.currency("TOTAL £64.20"))
        assertEquals("EUR", ReceiptParser.currency("Summe 70,12 €"))
        assertNull(ReceiptParser.currency("Total 512,40 kr"))
        assertEquals("EUR", Fx.code("€"))
        assertEquals(listOf("EUR", "DKK"), Fx.choices(Vehicle()).take(2))
    }

    @Test fun detourPaysOffOnlyWhenCheapEnough() {
        val near = PriceStation("a", "A", "A", "", "", 0.0, 0.0, dist = 0.5, price = 1.899, isOpen = true)
        val far = PriceStation("b", "B", "B", "", "", 0.0, 0.0, dist = 3.5, price = 1.799, isOpen = true)
        val b = Detour.Basis(fill = 45.0, per100 = 8.0)
        // 0,10 € × 45 l = 4,50 € gespart, Umweg 3 km × 2 × 1,3 = 7,8 km × 0,08 l × 1,799 € ≈ 1,12 €
        val s = Detour.saving(near, far, b, Vehicle())!!
        assertEquals(3.38, s, 0.02)
        val tiny = far.copy(price = 1.889)
        assertTrue(Detour.saving(near, tiny, b, Vehicle())!! < Detour.MIN_SAVING)
    }
}
