package app.kosh

import app.kosh.ocr.OcrLine
import app.kosh.ocr.ReceiptParser
import app.kosh.ocr.TextFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ReceiptOcrTest {
    private fun lines(vararg pairs: Pair<String, Float>) = pairs.mapIndexed { i, (t, h) -> OcrLine(t, i * 50f, h) }
    private val today = LocalDate.of(2026, 10, 26)

    @Test fun indianSupermarketReceipt() {
        val r = ReceiptParser.parse(lines(
            "DMART" to 60f,
            "Avenue Supermarts Ltd" to 30f,
            "Plot 12, MG Road, Pune 411001" to 26f,
            "GSTIN: 27AACCA8432H1ZQ" to 26f,
            "TAX INVOICE" to 30f,
            "Bill No: 1234567890 Date: 24/10/2026 18:42" to 26f,
            "Toor Dal 1kg 1 x 165.00 165.00" to 26f,
            "Amul Butter 500g 2 x 275.00 550.00" to 26f,
            "Sub Total 715.00" to 26f,
            "CGST 2.5% 17.88" to 26f,
            "SGST 2.5% 17.88" to 26f,
            "You saved Rs. 120.00" to 26f,
            "Grand Total ₹ 750.76" to 40f,
            "Round off 0.24" to 26f,
            "Net Payable 751.00" to 40f,
            "UPI 751.00" to 26f,
        ), today)
        assertEquals(BigDecimal("751.00"), r.total)
        assertEquals(LocalDate.of(2026, 10, 24), r.date)
        assertEquals("Dmart", r.merchant)
        assertEquals("INR", r.currency)
    }

    @Test fun polishReceiptWithDecimalCommas() {
        // Lines as the on-device models read the Biedronka receipt (one of the test photos).
        val r = ReceiptParser.parse(lines(
            "Biedronka" to 44f, "Codziennie niskie ceny" to 30f, "Sklep 5163 ul. Grabarska 2, 50-079 Wroctaw" to 30f,
            "Jeronimo Martins Polska S.A." to 30f, "NIP 779-10-11-327" to 30f, "2020-01-09 13:18 422351" to 30f, "PARAGON FISKALNY" to 34f,
            "ChlebPszen-Zyt550g C 1 x1,99 1,99c" to 30f, "Rabat -2,00" to 30f, "SPRZEDAZ OPODATKOWANA C 8,27" to 30f,
            "PTU C 5,00 % 0,39" to 30f, "SUMA PTU 0,39" to 30f, "SUMA PLN 8,27" to 70f, "Karta Visa Debit 05 1 8,27 PLN" to 30f,
        ), LocalDate.of(2020, 1, 20))
        assertEquals(BigDecimal("8.27"), r.total)
        assertEquals(LocalDate.of(2020, 1, 9), r.date)
        assertEquals("Biedronka", r.merchant)
        assertEquals("PLN", r.currency)
    }

    @Test fun italianReceiptAndFallbacks() {
        val r = ReceiptParser.parse(lines(
            "DOCUMENTO COMMERCIALE" to 40f, "di vendita o prestazione" to 40f, "DESCRIZIONE IVA Prezzo(€)" to 30f,
            "ACQUA S.ANGELO NATUR 22,00% 1,32" to 30f, "LINDT TAVOLETTA LATT 10,00% 2,85" to 30f,
            "TOTALE COMPLESSIVO 11,85" to 34f, "di cui IVA" to 30f, "Pagamento elettronico 11,85" to 30f, "Importo pagato 11,85" to 30f,
        ), today)
        assertEquals(BigDecimal("11.85"), r.total)
        assertEquals("EUR", r.currency)
        // No total line at all: the amount printed twice wins.
        val plain = ReceiptParser.parse(lines("CHAI POINT" to 50f, "Masala chai 2 40.00" to 30f, "Samosa 1 25.00" to 30f, "65.00" to 30f, "Card 65.00" to 30f), today)
        assertEquals(BigDecimal("65.00"), plain.total)
        assertEquals("Chai Point", plain.merchant)
        assertNull(plain.date)
    }

    @Test fun amountsAndDates() {
        assertEquals(listOf(BigDecimal("123456.00")), ReceiptParser.amounts("Total 1,23,456.00").map { it.value })
        assertEquals(listOf(BigDecimal("8.27")), ReceiptParser.amounts("SUMA PLN 8,27").map { it.value })
        assertTrue(ReceiptParser.amounts("Ph 9876543210").isEmpty())
        assertEquals(LocalDate.of(2026, 10, 12), ReceiptParser.date(listOf("12-Oct-2026 08:15 PM"), today))
        assertEquals(LocalDate.of(2026, 10, 12), ReceiptParser.date(listOf("Oct 12, 2026"), today))
        assertEquals(LocalDate.of(2026, 10, 5), ReceiptParser.date(listOf("10/05/2026"), today, monthFirst = true))
        // A date far in the past is a "best before" or a licence, not the bill date.
        assertNull(ReceiptParser.date(listOf("FSSAI Lic 12/03/2011"), today))
    }

    @Test fun findsRotatedTextBoxes() {
        // A slanted bar of "text" in a 64 x 64 probability map.
        val w = 64; val h = 64
        val prob = FloatArray(w * h)
        for (x in 10 until 50) for (dy in 0 until 4) {
            val y = 20 + x / 5 + dy
            prob[y * w + x] = 0.9f
        }
        val boxes = TextFinder.find(prob, w, h)
        assertEquals(1, boxes.size)
        val b = boxes[0]
        assertTrue("long side is the text direction", b.w > b.h * 3)
        assertTrue("tilted about 11 degrees", Math.toDegrees(b.angle.toDouble()) in 6.0..16.0)
        // A lone square character reads horizontally, not rotated by 90 degrees.
        val sq = FloatArray(w * h)
        for (y in 5 until 13) for (x in 5 until 13) sq[y * w + x] = 0.9f
        assertTrue(kotlin.math.abs(TextFinder.find(sq, w, h).single().angle) < 0.01f)
    }

    @Test fun groupsBoxesIntoLines() {
        val total = app.kosh.ocr.TextBox(100f, 500f, 120f, 30f, 0f, 1f)
        val amount = app.kosh.ocr.TextBox(600f, 504f, 90f, 32f, 0f, 1f)
        val next = app.kosh.ocr.TextBox(110f, 560f, 120f, 30f, 0f, 1f)
        val l = TextFinder.lines(listOf("amount" to amount, "next" to next, "total" to total)) { it.second }
        assertEquals(listOf(listOf("total", "amount"), listOf("next")), l.map { line -> line.map { it.first } })
    }

    @Test fun modelsLoadOnTheBundledNcnn() {
        // The app ships ncnn 20260526. Newer pnnx writes batch-aware Reshape layers ("0n" in a shape expression,
        // batch axes 12/13) that this version cannot load, so the receipt reader would silently stop working.
        for (name in listOf("det.param", "rec.param")) {
            val text = java.io.File("src/main/assets/ocr/$name").readText()
            val reshapes = text.lines().filter { it.startsWith("Reshape") }
            assertTrue("$name has no batch-aware shapes", reshapes.none { Regex("""\b\dn\b""").containsMatchIn(it) || Regex("""(^|\s)1[23]=""").containsMatchIn(it) })
        }
    }

    @Test fun groupsLinesOnATiltedPhoto() {
        // The page is turned about 6 degrees: each line climbs 0.1 px per px to the right, lines are 40 px apart.
        val a = -0.1f
        fun at(x: Float, row: Int, w: Float) = app.kosh.ocr.TextBox(x, 800f + row * 40f + (x - 100f) * a, w, 28f, a, 1f)
        val items = listOf(
            "Rs 1,300.00" to at(620f, 0, 160f), "Paid" to at(110f, 0, 80f),
            "TOTAL" to at(120f, 1, 100f), "1,300.00" to at(620f, 1, 140f),
            "Thank you" to at(300f, 2, 200f),
        )
        val l = TextFinder.lines(items) { it.second }
        assertEquals(listOf(listOf("Paid", "Rs 1,300.00"), listOf("TOTAL", "1,300.00"), listOf("Thank you")), l.map { line -> line.map { it.first } })
    }
}
