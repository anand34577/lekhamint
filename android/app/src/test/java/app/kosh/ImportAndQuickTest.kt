package app.kosh

import app.kosh.data.Spreadsheets
import app.kosh.domain.QuickEntry
import app.kosh.domain.StatementImport
import app.kosh.domain.StatementImport.DateFormat
import app.kosh.domain.StatementImport.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ImportAndQuickTest {
    private val today = LocalDate.of(2026, 10, 26) // a Monday

    // --- One-line entry ---------------------------------------------------------------

    @Test fun quickLineReadsAmountDayTagsAndAccount() {
        val d = QuickEntry.parse("450 swiggy dinner yesterday #work @hdfc", today)
        assertEquals(BigDecimal("450"), d.amount)
        assertEquals("expense", d.type)
        assertEquals(today.minusDays(1), d.date)
        assertEquals(listOf("work"), d.tags)
        assertEquals("hdfc", d.account)
        assertEquals("swiggy dinner", d.text)
        assertTrue(d.datedByText)
    }

    @Test fun quickLineIncomeSumsAndFillers() {
        val d = QuickEntry.parse("+50,000 salary", today)
        assertEquals("income", d.type); assertEquals(BigDecimal("50000"), d.amount); assertEquals("salary", d.text)
        assertEquals(BigDecimal("270"), QuickEntry.parse("spent 230+40 on coffee", today).amount)
        assertEquals("coffee", QuickEntry.parse("spent 230+40 on coffee", today).text)
        assertEquals(BigDecimal("2000"), QuickEntry.parse("2k rent", today).amount)
        assertEquals(BigDecimal("1250.5"), QuickEntry.parse("₹1,250.50 groceries", today).amount)
        assertEquals("expense", QuickEntry.parse("paid rs 99 for chai", today).type)
        assertEquals("chai", QuickEntry.parse("paid rs 99 for chai", today).text)
    }

    @Test fun quickLineDates() {
        assertEquals(LocalDate.of(2026, 10, 12), QuickEntry.parse("250 uber 12 oct", today).date)
        assertEquals(LocalDate.of(2026, 10, 24), QuickEntry.parse("250 uber 24/10", today).date)
        // A date later in the year means last year.
        assertEquals(LocalDate.of(2025, 12, 5), QuickEntry.parse("250 uber dec 5", today).date)
        assertEquals(today.minusDays(3), QuickEntry.parse("300 taxi 3 days ago", today).date)
        // Today is Monday: "friday" is three days back, "monday" is today.
        assertEquals(today.minusDays(3), QuickEntry.parse("80 snacks friday", today).date)
        assertEquals(today, QuickEntry.parse("80 snacks monday", today).date)
        assertNull(QuickEntry.parse("lunch with team", today).amount)
    }

    @Test fun categoryWordsAndMatching() {
        val cats = listOf("Food", "Groceries", "Fuel", "Food delivery")
        assertEquals("Groceries", QuickEntry.categoryFromText(cats, "groceries big bazaar") { it })
        assertEquals("Food delivery", QuickEntry.categoryFromText(cats, "swiggy food delivery") { it })
        assertNull(QuickEntry.categoryFromText(cats, "seafood") { it })
        val defaults = listOf("Dining out", "Taxi & ride-hailing", "Fuel")
        assertEquals("Dining out", QuickEntry.categoryFromText(defaults, "lunch with team") { it })
        assertEquals("Taxi & ride-hailing", QuickEntry.categoryFromText(defaults, "Uber to airport") { it })
        assertNull(QuickEntry.categoryFromText(listOf("Eating out"), "lunch") { it })
        assertEquals("lunch", QuickEntry.wordFor("450 Lunch yesterday", "Dining out"))
        assertEquals("Groceries", QuickEntry.wordFor("groceries big bazaar", "Groceries"))
        assertEquals("big bazaar", QuickEntry.withoutWords("groceries big bazaar", "Groceries"))
        assertEquals("HDFC Bank", QuickEntry.bestMatch(listOf("Cash", "HDFC Bank", "ICICI"), "hdfc") { it })
    }

    // --- Statement files --------------------------------------------------------------

    private val csv = """
        Statement for A/c XXXX4521
        From 01/10/2026 to 31/10/2026

        Txn Date,Narration,Withdrawal Amt,Deposit Amt,Closing Balance
        01/10/2026,"UPI/DR/412345678901/SWIGGY/YESB/swiggy@ybl/Payment",450.00,,"11,550.00"
        02/10/2026,NEFT CR-HDFC0000001-ACME TECHNOLOGIES-SALARY,,"85,000.00","96,550.00"
        02/10/2026,"UPI/DR/412345678901/SWIGGY/YESB/swiggy@ybl/Payment",450.00,,"96,100.00"
        ,Closing balance,,,"96,100.00"
    """.trimIndent()

    @Test fun csvFindsTheTableUnderAPreambleAndReadsDebitCredit() {
        val table = StatementImport.parseCsv(csv, StatementImport.detectDelimiter(csv))
        val header = StatementImport.findHeader(table)
        assertEquals(2, header) // blank lines are dropped while reading
        val roles = StatementImport.guessRoles(table[header])
        assertEquals(listOf(Role.DATE, Role.DESCRIPTION, Role.DEBIT, Role.CREDIT, Role.IGNORE), roles)
        val p = StatementImport.toRows(table, StatementImport.Options(roles, header, DateFormat.DMY, "INR"))
        assertEquals(listOf("expense" to 45000L, "income" to 8500000L, "expense" to 45000L), p.rows.map { it.type to it.amount })
        assertTrue(p.errors.isEmpty())
        // Identical rows get different fingerprints, and the IDs match the web app's.
        val fp = StatementImport.fingerprints(p.rows)
        assertTrue(fp[0] != fp[2])
        assertEquals(
            "36313eb9-fd13-5c96-9207-c33371286e63",
            StatementImport.importId("acc", "2026-10-01|expense|45000|swiggy#0"),
        )
    }

    @Test fun datesAndAmounts() {
        assertEquals(LocalDate.of(2026, 10, 24), StatementImport.parseDate("24 Oct 2026", DateFormat.DMY))
        assertEquals(LocalDate.of(2026, 10, 24), StatementImport.parseDate("Oct 24, 2026", DateFormat.DMY))
        assertEquals(LocalDate.of(2026, 10, 24), StatementImport.parseDate("24-10-26", DateFormat.DMY))
        assertEquals(LocalDate.of(2026, 10, 24), StatementImport.parseDate("10/24/2026", DateFormat.MDY))
        assertEquals(LocalDate.of(2024, 10, 2), StatementImport.parseDate("45567", DateFormat.DMY))
        assertNull(StatementImport.parseDate("31/02/2026", DateFormat.DMY))
        assertEquals(DateFormat.MDY, StatementImport.detectDateFormat(listOf("10/24/2026", "10/02/2026")))
        assertEquals(DateFormat.YMD, StatementImport.detectDateFormat(listOf("2026-10-01", "2026-10-05")))
        assertEquals(java.time.LocalDate.of(2026, 10, 5), StatementImport.parseDate("2026-10-05", DateFormat.YMD))
        assertEquals(-12000L, StatementImport.parseAmount("120.00 DR", "INR"))
        assertEquals(-4500L, StatementImport.parseAmount("(45.00)", "INR"))
        assertEquals(123456L, StatementImport.parseAmount("1.234,56", "EUR", decimalComma = true))
        assertEquals(125000L, StatementImport.parseAmount("₹ 1,250", "INR"))
    }

    @Test fun ofxAndQif() {
        val ofx = """
            OFXHEADER:100
            <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>INR<BANKACCTFROM><ACCTID>1234</BANKACCTFROM>
            <BANKTRANLIST><STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261001<TRNAMT>-450.00<FITID>A1<NAME>SWIGGY &amp; CO<MEMO>Dinner</STMTTRN>
            <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261002120000<TRNAMT>85000.00<FITID>A2<NAME>ACME</STMTTRN></BANKTRANLIST>
        """.trimIndent()
        val p = StatementImport.parseOfx(ofx, "USD")
        assertEquals("INR", p.currency); assertEquals("1234", p.account)
        assertEquals(listOf("expense", "income"), p.rows.map { it.type })
        assertEquals("SWIGGY & CO", p.rows[0].description); assertEquals("Dinner", p.rows[0].note); assertEquals("ofx:1234:A1", p.rows[0].ref)
        val qif = "!Type:Bank\nD10/24/2026\nT-45.50\nPCoffee Shop\nLFood:Coffee\n^\nD10/25/2026\nT1000\nPSalary\n^\n"
        val q = StatementImport.parseQif(qif, "USD", preferUS = true)
        assertEquals(LocalDate.of(2026, 10, 24), q.rows[0].date); assertEquals(4550L, q.rows[0].amount); assertEquals("Food · Coffee", q.rows[0].category)
        assertEquals(StatementImport.Kind.OFX, StatementImport.kind("x.txt", ofx.toByteArray()))
        assertEquals(StatementImport.Kind.QIF, StatementImport.kind("x.txt", qif.toByteArray()))
    }

    @Test fun htmlTablePretendingToBeXls() {
        val html = "<html><body><table><tr><th>Date</th><th>Details</th><th>Amount</th></tr>" +
            "<tr><td>01/10/2026</td><td>AMAZON&nbsp;PAY <b>IN</b></td><td>-1,299.00</td></tr></table></body></html>"
        assertEquals(StatementImport.Kind.HTML, StatementImport.kind("statement.xls", html.toByteArray()))
        val t = StatementImport.parseHtmlTable(html)
        assertEquals(listOf("Date", "Details", "Amount"), t[0])
        assertEquals(listOf("01/10/2026", "AMAZON PAY IN", "-1,299.00"), t[1])
    }

    @Test fun pdfStatementTextUsesTheRunningBalance() {
        val text = """
            STATEMENT OF ACCOUNT  Page 1 of 2
            Date Narration Chq/Ref Value Dt Withdrawal Deposit Balance
            Opening Balance 12,000.00
            01/10/26 UPI-SWIGGY-SWIGGY8.PAYU@AXISBANK- 0000412345 01/10/26 450.00 11,550.00
            UTIB0000100-PAYMENT
            02/10/26 NEFT CR-HDFC0000001-ACME 0000412346 02/10/26 85,000.00 96,550.00
            TECHNOLOGIES-SALARY
            03/10/26 INTEREST PAID 03/10/26 120.50 96,670.50
            04/10/26 ATM WDL MG ROAD 04/10/26 2,000.00 94,670.50
            Closing Balance 94,670.50
        """.trimIndent()
        val p = StatementImport.parseStatementText(text, "INR")
        assertEquals(listOf("expense" to 45000L, "income" to 8500000L, "income" to 12050L, "expense" to 200000L), p.rows.map { it.type to it.amount })
        assertTrue(p.rows[1].description.contains("TECHNOLOGIES-SALARY"))
        assertEquals(LocalDate.of(2026, 10, 1), p.rows[0].date)
        // A card statement: one amount per line, credits marked Cr.
        val card = "12/10/2026 SWIGGY BANGALORE 450.00\n15/10/2026 PAYMENT RECEIVED THANK YOU 5,000.00 Cr\n"
        assertEquals(listOf("expense", "income"), StatementImport.parseStatementText(card, "INR").rows.map { it.type })
    }

    @Test fun payeesFromNarrations() {
        assertEquals("Swiggy", StatementImport.payeeFrom("UPI/DR/412345678901/SWIGGY/YESB/swiggy@ybl/Payment"))
        assertEquals("Swiggy", StatementImport.payeeFrom("UPI-SWIGGY-SWIGGY8.PAYU@AXISBANK-UTIB0000100-412345678901-PAYMENT"))
        assertEquals("Acme Technologies", StatementImport.payeeFrom("NEFT CR-HDFC0000001-ACME TECHNOLOGIES-SALARY"))
        assertEquals("Dmart Avenue", StatementImport.payeeFrom("POS 4591XXXXXXXX1234 DMART AVENUE"))
        assertEquals("ATM withdrawal", StatementImport.payeeFrom("ATM WDL 01234 MG ROAD"))
        assertEquals("Amazon Pay", StatementImport.payeeFrom("AMAZON PAY"))
        assertEquals("Blue Tokai Coffee", StatementImport.payeeFrom("Blue Tokai Coffee"))
    }

    @Test fun duplicatesPairOneToOne() {
        val p = StatementImport.toRows(
            StatementImport.parseCsv(csv), StatementImport.Options(
                listOf(Role.DATE, Role.DESCRIPTION, Role.DEBIT, Role.CREDIT, Role.IGNORE), 2, DateFormat.DMY, "INR",
            ),
        )
        val existing = listOf(StatementImport.Existing("e1", "expense", 45000, LocalDate.of(2026, 10, 2), "acc"))
        val dup = StatementImport.findDuplicates(p.rows, "acc", existing)
        assertEquals(1, dup.size)
        assertEquals("e1", dup[0]) // rows are matched in order, like the web app; each existing entry is used once
    }

    // --- Excel ------------------------------------------------------------------------

    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/import/$name")!!.use { it.readBytes() }

    @Test fun readsOldExcelFiles() {
        val bytes = fixture("bank.xls")
        assertEquals(StatementImport.Kind.XLS, StatementImport.kind("bank.xls", bytes.copyOf(16)))
        val t = Spreadsheets.readXls(bytes)
        val header = StatementImport.findHeader(t)
        assertEquals(listOf("Txn Date", "Narration", "Withdrawal Amt", "Deposit Amt", "Closing Balance"), t[header])
        assertEquals(listOf("2026-10-01", "UPI/DR/412345678901/SWIGGY/YESB/swiggy@ybl/Payment", "450", "", "11550"), t[header + 1])
        val p = StatementImport.toRows(t, StatementImport.Options(StatementImport.guessRoles(t[header]), header, DateFormat.DMY, "INR"))
        assertEquals(4, p.rows.size)
        assertEquals(231050L, p.rows[2].amount)
    }

    @Test fun readsLargeOldExcelFilesWithContinuedStrings() {
        val t = Spreadsheets.readXls(fixture("big.xls"))
        assertEquals(1201, t.size)
        assertEquals("Merchant number 1200 with a long-ish name ₹ café", t[1200][1])
        assertEquals("-1200.25", t[1200][2])
        assertEquals("Merchant number 0001 with a long-ish name ₹ café", t[1][1])
    }

    @Test fun readsNewExcelFiles() {
        val bytes = fixture("bank.xlsx")
        assertEquals(StatementImport.Kind.XLSX, StatementImport.kind("bank.xlsx", bytes.copyOf(16)))
        val t = Spreadsheets.readXlsx(bytes)
        assertEquals(listOf("Date", "Description", "Debit", "Credit", "Balance"), t[0])
        assertEquals("2026-10-02", t[2][0])
        assertEquals("85000", t[2][3])
        assertEquals("", t[2][2])
    }
}
