package app.benbow.app

import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The page goes through a WebView into the system print dialog, where "Save as PDF" is one of the printers. */
@Composable
internal actual fun rememberPrint(): (PrintPage) -> Unit {
    val context = LocalContext.current
    val alive = remember { arrayOfNulls<WebView>(1) } // the print job reads the page later; keep it from being collected
    return { page ->
        val view = WebView(context)
        view.settings.allowFileAccess = true // the entry's pictures are files in the app's media folder
        view.webViewClient = object : WebViewClient() {
            private var started = false

            override fun onPageFinished(view: WebView, url: String?) {
                if (started) return
                started = true
                context.getSystemService(PrintManager::class.java)
                    .print(page.title, view.createPrintDocumentAdapter(page.title), PrintAttributes.Builder().build())
            }
        }
        alive[0] = view
        view.loadDataWithBaseURL("file:///", page.html, "text/html", "utf-8", null)
    }
}
