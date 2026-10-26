package app.kosh.data

import android.content.Context
import android.net.Uri
import app.kosh.domain.StatementImport
import app.kosh.domain.StatementImport.Kind
import java.io.IOException

/**
 * Turns a statement file picked on the phone into either ready rows (OFX, QIF, PDF) or a table whose columns
 * the person confirms (CSV, Excel, HTML). Nothing is uploaded: the file is read here and only the rows the
 * person keeps are saved.
 */
object StatementReader {
    class NeedsPassword : Exception("This PDF is locked")
    class WrongPassword : Exception("That password didn't open the PDF")
    class Unreadable(message: String) : Exception(message)

    sealed class Content {
        abstract val name: String
        data class Table(override val name: String, val kind: Kind, val table: List<List<String>>) : Content()
        data class Rows(override val name: String, val kind: Kind, val parsed: StatementImport.Parsed, val text: String = "") : Content()
    }

    private const val MAX_BYTES = 25L * 1024 * 1024

    fun bytes(context: Context, uri: Uri): Pair<String, ByteArray> {
        val cr = context.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "statement"
        val data = cr.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw Unreadable("That file is over 25 MB. Download a shorter period from your bank.")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("Couldn't open the file")
        return name to data
    }

    /** Reads [data]; for a locked PDF throws [NeedsPassword] (or [WrongPassword] when [password] did not work). */
    fun read(context: Context, name: String, data: ByteArray, currency: String, password: String? = null): Content {
        val preferUS = java.util.Locale.getDefault().country == "US"
        return when (val kind = StatementImport.kind(name, data.copyOf(minOf(data.size, 4096)))) {
            Kind.PDF -> {
                val text = PdfText.extract(context, data, password)
                if (text.isBlank()) throw Unreadable("This PDF has no text in it (it may be a scan). Download the statement as Excel or CSV instead.")
                val parsed = StatementImport.parseStatementText(text, currency, preferUS)
                if (parsed.rows.isEmpty()) throw Unreadable("No transactions were recognised in this PDF. Try the Excel or CSV download from your bank.")
                Content.Rows(name, kind, parsed, text)
            }
            Kind.OFX -> Content.Rows(name, kind, StatementImport.parseOfx(decode(data), currency))
            Kind.QIF -> Content.Rows(name, kind, StatementImport.parseQif(decode(data), currency, preferUS))
            Kind.XLSX -> Content.Table(name, kind, runCatching { Spreadsheets.readXlsx(data) }.getOrElse { throw Unreadable(it.message ?: "Couldn't read this Excel file.") })
            Kind.XLS -> Content.Table(name, kind, runCatching { Spreadsheets.readXls(data) }.getOrElse { throw Unreadable(it.message ?: "Couldn't read this Excel file.") })
            Kind.HTML -> Content.Table(name, kind, StatementImport.parseHtmlTable(decode(data)))
            Kind.CSV -> {
                val text = decode(data)
                if (text.count { it == '\u0000' } > 10) throw Unreadable("This file isn't a statement LekhaMint can read. Use CSV, Excel, PDF, OFX or QIF.")
                Content.Table(name, kind, StatementImport.parseCsv(text, StatementImport.detectDelimiter(text)))
            }
        }
    }

    /** UTF-8, or Windows-1252 for older bank exports that are not valid UTF-8. */
    private fun decode(data: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
        return runCatching { utf8.decode(java.nio.ByteBuffer.wrap(data)).toString() }.getOrElse { String(data, charset("windows-1252")) }
            .removePrefix("\uFEFF")
    }
}

/** PDF text via PdfBox-Android, laid out by position so table columns stay on one line. */
object PdfText {
    @Volatile private var ready = false

    fun extract(context: Context, data: ByteArray, password: String?): String {
        if (!ready) {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
            ready = true
        }
        val doc = try {
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(data, password.orEmpty())
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw if (password.isNullOrEmpty()) StatementReader.NeedsPassword() else StatementReader.WrongPassword()
        }
        return doc.use {
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            stripper.sortByPosition = true
            stripper.getText(it)
        }
    }
}
