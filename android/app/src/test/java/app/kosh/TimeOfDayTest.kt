package app.kosh

import app.kosh.core.Dates
import app.kosh.domain.StatementImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Entries keep the time of day they happened, from forms and from imported statements. */
class TimeOfDayTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun `a picked time is saved and read back`() {
        val at = Dates.at(LocalDate.of(2026, 3, 14), LocalTime.of(21, 5), zone)
        assertEquals(LocalTime.of(21, 5), Dates.timeOf(at, zone))
    }

    @Test fun `an entry saved at exactly midday has no known time`() {
        val at = Dates.at(LocalDate.of(2026, 3, 14), null, zone)
        assertNull(Dates.timeOf(at, zone))
    }

    @Test fun `statement cells keep their time`() {
        assertEquals(LocalTime.of(13, 45), StatementImport.parseTime("2026-10-24 13:45:00"))
        assertEquals(LocalTime.of(14, 5), StatementImport.parseTime("24/10/2026 2:05 PM"))
        assertEquals(LocalTime.of(0, 30), StatementImport.parseTime("12:30 a.m."))
        assertEquals(LocalTime.of(9, 5), StatementImport.parseTime("2026-10-24T09:05"))
        assertNull(StatementImport.parseTime("24/10/2026 00:00:00")) // a bare date padded with midnight
        assertNull(StatementImport.parseTime("24/10/2026"))
    }

    @Test fun `a time column is recognised and OFX times are kept`() {
        assertEquals(StatementImport.Role.TIME, StatementImport.guessRoles(listOf("Date", "Time", "Details", "Amount"))[1])
        val ofx = "<OFX><CURDEF>INR<BANKTRANLIST><STMTTRN><DTPOSTED>20261020193000<TRNAMT>-450.00<FITID>A1<NAME>Cafe</BANKTRANLIST></OFX>"
        assertEquals(LocalTime.of(19, 30), StatementImport.parseOfx(ofx, "INR").rows.single().time)
    }
}
