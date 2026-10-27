package app.kosh

import app.kosh.sync.PairLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairLinkTest {
    @Test fun readsTheWebAppsQrLink() {
        val p = PairLink.parse("lekhamint://pair?server=https%3A%2F%2Fmoney.example.com%2F&code=AB12CD34EF")
        assertEquals(PairLink("https://money.example.com", "AB12CD34EF"), p)
        assertEquals("http://192.168.1.20:8080", PairLink.parse("lekhamint://pair?server=http%3A%2F%2F192.168.1.20%3A8080&code=ab12c-d34ef")?.server)
    }

    @Test fun refusesAnythingElse() {
        assertNull(PairLink.parse("https://example.com/?code=AB12CD34EF"))
        assertNull(PairLink.parse("lekhamint://pair?server=ftp%3A%2F%2Fx&code=AB12CD34EF"))
        assertNull(PairLink.parse("lekhamint://pair?server=https%3A%2F%2Fx&code=SHORT"))
        assertNull(PairLink.parse("lekhamint://pair"))
    }

    @Test fun codesAreForgivingLikeTheServer() {
        assertEquals("AB12CD34EF", PairLink.normalizeCode("ab12c-d34ef"))
        assertEquals("0111AB12CD", PairLink.normalizeCode("OIL1 ab12 cd"))
    }
}
