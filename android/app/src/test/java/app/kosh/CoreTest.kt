package app.kosh

import app.kosh.core.Calculator
import app.kosh.core.Hlc
import app.kosh.core.HlcClock
import app.kosh.core.Ids
import app.kosh.core.Money
import app.kosh.core.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

class IdsTest {
    @Test fun v5MatchesServer() {
        // Value produced by server/internal/ids.V5(RecurringNamespace, ...) — both must agree
        // so two devices generate the same ID for the same recurring occurrence.
        assertEquals(
            "1e0966bb-cefe-5d1f-ab05-ff19e702f91b",
            Ids.recurringOccurrence("0192f0c4-0000-7000-8000-000000000001", LocalDate.of(2026, 1, 31)),
        )
    }

    @Test fun v7IsValidAndTimeOrdered() {
        val a = Ids.newV7(1_700_000_000_000)
        val b = Ids.newV7(1_700_000_000_001)
        assertTrue(Ids.isValid(a))
        assertEquals('7', a[14])
        assertTrue(a < b)
    }

    @Test fun rejectsBadIds() {
        assertTrue(!Ids.isValid("not-a-uuid"))
        assertTrue(!Ids.isValid("0192F0C4-0000-7000-8000-000000000001")) // must be lowercase
    }
}

class HlcTest {
    @Test fun formatMatchesServer() {
        assertEquals("1729771234567-000a-9f2c4a1b7e3d5c60", Hlc(1729771234567, 10, "9f2c4a1b7e3d5c60").toString())
        assertEquals(Hlc(1729771234567, 10, "9f2c4a1b7e3d5c60"), Hlc.parse("1729771234567-000a-9f2c4a1b7e3d5c60"))
        assertNull(Hlc.parse("1729771234567-000A-9f2c4a1b7e3d5c60"))
        assertNull(Hlc.parse("short"))
    }

    @Test fun clockIsMonotonicEvenIfWallClockGoesBack() {
        var now = 1000L
        val clock = HlcClock("aaaaaaaaaaaaaaaa", null, nowMs = { now })
        val a = clock.now()
        now = 500 // device clock jumps backwards
        val b = clock.now()
        assertTrue(b > a)
        clock.observe("0000000005000-0003-bbbbbbbbbbbbbbbb")
        val c = clock.now()
        assertTrue(c > "0000000005000-0003-bbbbbbbbbbbbbbbb")
        assertTrue(c.endsWith("aaaaaaaaaaaaaaaa"))
    }

    @Test fun resumesFromPersistedValue() {
        val clock = HlcClock("aaaaaaaaaaaaaaaa", "0000000009000-0000-aaaaaaaaaaaaaaaa", nowMs = { 10 })
        assertTrue(clock.now() > "0000000009000-0000-aaaaaaaaaaaaaaaa")
    }
}

class MoneyTest {
    private val us = Locale.US

    @Test fun formatsWithIndianGrouping() {
        assertEquals("₹1,23,456.78", Money.format(12345678, "INR", locale = us))
        assertEquals("₹999.00", Money.format(99900, "INR", locale = us))
        assertEquals("−₹1,250.50", Money.format(-125050, "INR", locale = us))
        assertEquals("+₹10.00", Money.format(1000, "INR", signed = true, locale = us))
    }

    @Test fun formatsOtherCurrencies() {
        assertEquals("$123,456.78", Money.format(12345678, "USD", locale = us))
        assertEquals("¥1,500", Money.format(1500, "JPY", locale = us))
        assertEquals("KD 1.500", Money.format(1500, "KWD", locale = us))
        assertEquals("1.234,56 €", Money.format(123456, "EUR", locale = Locale.GERMANY))
    }

    @Test fun parsesExactly() {
        assertEquals(125050L, Money.parse("1,250.50", "INR"))
        assertEquals(1250L, Money.parse("12,50", "EUR"))      // decimal comma
        assertEquals(125000L, Money.parse("1,250", "INR"))    // thousands grouping
        assertEquals(15000000L, Money.parse("1,50,000", "INR"))
        assertEquals(1L, Money.parse("0.01", "USD"))
        assertEquals(1500L, Money.parse("1500", "JPY"))
        assertEquals(30L, Money.parse("0.1", "USD")?.let { it * 3 }) // no float drift
        assertNull(Money.parse("abc", "USD"))
        assertEquals("1250.5", Money.toPlain(125050, "INR"))
    }

    @Test fun compactLabels() {
        assertEquals("₹1.2L", Money.compact(12000000, "INR"))
        assertEquals("₹2.5Cr", Money.compact(2500000000, "INR"))
        assertEquals("$12.5K", Money.compact(1250000, "USD"))
    }
}

class CalculatorTest {
    @Test fun precedence() {
        assertEquals(BigDecimal("2050"), Calculator.evaluate("450+800×2"))
        assertEquals(0, BigDecimal("33.3333").compareTo(Calculator.evaluate("100÷3")!!.setScale(4, java.math.RoundingMode.HALF_UP)))
        assertEquals(BigDecimal("250"), Calculator.evaluate("1000−750"))
        assertEquals(BigDecimal("12"), Calculator.evaluate("12+")) // trailing operator ignored
        assertNull(Calculator.evaluate("5÷0"))
        assertNull(Calculator.evaluate(""))
    }

    @Test fun keyPressesKeepExpressionValid() {
        var e = ""
        for (k in "0012.345") e = Calculator.press(e, k, 2)
        assertEquals("12.34", e)
        e = Calculator.press(e, '+', 2)
        e = Calculator.press(e, '×', 2)
        assertEquals("12.34×", e)
        e = Calculator.press(e, '.', 2)
        assertEquals("12.34×0.", e)
        assertEquals("1", Calculator.press("1", '.', 0)) // JPY: no decimals
        assertEquals("", Calculator.press("", '+', 2))
    }
}

class RecurrenceTest {
    @Test fun monthEndClampsAndReturns() {
        val start = LocalDate.of(2026, 1, 31)
        assertEquals(LocalDate.of(2026, 2, 28), Recurrence.occurrence(start, "monthly", 1, 1))
        assertEquals(LocalDate.of(2026, 3, 31), Recurrence.occurrence(start, "monthly", 1, 2))
        assertEquals(LocalDate.of(2026, 4, 30), Recurrence.occurrence(start, "monthly", 1, 3))
    }

    @Test fun dueRespectsLimits() {
        val start = LocalDate.of(2026, 1, 1)
        val due = Recurrence.due(start, "weekly", 2, null, LocalDate.of(2026, 2, 28))
        assertEquals(listOf(1, 15, 29, 12, 26), due.map { it.date.dayOfMonth })
        assertEquals(3, Recurrence.due(start, "monthly", 1, null, LocalDate.of(2027, 1, 1), maxOccurrences = 3).size)
        assertEquals(2, Recurrence.due(start, "monthly", 1, null, LocalDate.of(2027, 1, 1), end = LocalDate.of(2026, 2, 15)).size)
        // Resume from next_date.
        val resumed = Recurrence.due(start, "monthly", 1, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 30))
        assertEquals(listOf(3, 4), resumed.map { it.date.monthValue })
    }

    @Test fun nextAfter() {
        val start = LocalDate.of(2026, 1, 10)
        assertEquals(LocalDate.of(2026, 2, 10), Recurrence.nextAfter(start, "monthly", 1, LocalDate.of(2026, 1, 10)))
        assertEquals(LocalDate.of(2026, 2, 10), Recurrence.nextAfter(start, "monthly", 1, LocalDate.of(2026, 1, 20)))
        assertNull(Recurrence.nextAfter(start, "monthly", 1, LocalDate.of(2026, 1, 10), maxOccurrences = 1))
        assertEquals(LocalDate.of(2027, 2, 28), Recurrence.nextAfter(LocalDate.of(2024, 2, 29), "yearly", 1, LocalDate.of(2026, 3, 1)))
    }
}
