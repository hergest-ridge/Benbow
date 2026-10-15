package app.benbow.client

/** What an archive export wrote. [missing] files were neither on this device nor reachable on the server; they are listed in the README. */
data class ExportReport(val entries: Int, val files: Int, val missing: Int)

/** How far an export is ([done] of [total] steps: files first, then entries). */
data class ExportProgress(val done: Int, val total: Int)
