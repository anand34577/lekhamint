package app.kosh.core

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Public pages the app links to. The privacy policy URL is the one given to Google Play. */
object Links {
    const val PROJECT = "https://github.com/anand34577/lekhamint"
    const val PRIVACY = "$PROJECT/blob/main/PRIVACY.md"
    const val HELP = "$PROJECT/wiki"
    const val ISSUES = "$PROJECT/issues/new"

    /** A report form for an assistant answer. The answer itself is never attached: it may hold financial data. */
    fun aiReport(host: String, model: String): String =
        "$ISSUES?labels=ai-report&title=" + Uri.encode("Assistant answer report") +
            "&body=" + Uri.encode("Model service: $host\nModel: $model\n\nWhat was wrong with the answer (please leave out personal or financial details):\n")

    fun open(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
