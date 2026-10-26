package app.kosh

import app.kosh.domain.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsParserTest {
    @Test fun upiDebit() {
        val r = SmsParser.parse("Rs.450.00 debited from A/c XX1234 on 24-Oct-26 to VPA swiggy@icici (UPI Ref 1234). Not you? Call 1800")
        assertEquals("450.00", r.amount); assertEquals("expense", r.type); assertEquals("Swiggy", r.payee)
        assertEquals("1234", r.accountHint); assertTrue(r.isTransaction)
    }

    @Test fun salaryCreditSkipsTheBalance() {
        val r = SmsParser.parse("INR 85,000.00 credited to your A/c XX9876 on 01-Oct by NEFT from ACME TECHNOLOGIES. Avl Bal INR 1,20,000")
        assertEquals("85000.00", r.amount); assertEquals("income", r.type); assertEquals("ACME TECHNOLOGIES", r.payee); assertEquals("9876", r.accountHint)
    }

    @Test fun balanceBeforeAmount() {
        val r = SmsParser.parse("Avl Bal Rs.5,000.00 after debit of Rs 200.00 on your A/c X4411 at CHAI POINT.")
        assertEquals("200.00", r.amount); assertEquals("CHAI POINT", r.payee)
    }

    @Test fun cardSpendAndAmountWithoutSymbol() {
        val r = SmsParser.parse("You spent ₹1,299 at DECATHLON on your HDFC Credit Card ending 4521.")
        assertEquals("1299", r.amount); assertEquals("DECATHLON", r.payee); assertEquals("4521", r.accountHint)
        val sbi = SmsParser.parse("Dear UPI user A/C X5512 debited by 250.0 on date 12Oct26 trf to RAHUL SHARMA Refno 4312. If not u? call 1800")
        assertEquals("250.0", sbi.amount); assertEquals("RAHUL SHARMA", sbi.payee); assertTrue(sbi.isTransaction)
    }

    @Test fun paymentAppNotifications() {
        val paid = SmsParser.parse("Paid ₹80 to Chai Point")
        assertEquals("80", paid.amount); assertEquals("expense", paid.type); assertEquals("Chai Point", paid.payee); assertTrue(paid.isTransaction)
        val got = SmsParser.parse("Priya Nair sent you ₹500. You've received ₹500 from Priya Nair")
        assertEquals("500", got.amount)
    }

    @Test fun ignoresOtpsOffersAndReminders() {
        assertFalse(SmsParser.parse("482913 is your OTP for txn of Rs 500.00 at AMAZON. Do not share it with anyone.").isTransaction)
        assertFalse(SmsParser.parse("Get upto Rs 500 cashback on your next recharge! Click here").isTransaction)
        assertFalse(SmsParser.parse("Your credit card bill of Rs 12,400 is due on 15-Nov. Pay now to avoid charges.").isTransaction)
        assertFalse(SmsParser.parse("Hello there").isTransaction)
        assertNull(SmsParser.parse("Hello there").amount)
    }
}
