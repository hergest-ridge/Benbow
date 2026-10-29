package app.benbow.core.api

import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/fonts` (ADR-052): the fonts a note can take beyond the app's own eight: the pack the server's package
 * carries and the owner's own, added on the admin page. A device fetches a font's files the first time it draws one
 * (`GET /api/v1/fonts/files/{sha256}`), checks them by SHA-256 and keeps them.
 */
@Serializable
data class FontCatalog(val fonts: List<FontInfo>)

/** One font: its token in `look_font`, its name, its group in «Шрифт», how large it is set, and its files. */
@Serializable
data class FontInfo(
    val token: String,
    val name: String,
    /** `diary`, `serif`, `sans`, `accent`, `mono`, or `own` for the owner's. */
    val group: String,
    /** Its size against the app's font: a small or narrow hand is set larger so it reads the same. */
    val scale: Float = 1f,
    val files: List<FontFileInfo>,
    /** False when its letters are Latin only: Russian words fall back to the app's font. */
    val cyrillic: Boolean = true,
)

@Serializable
data class FontFileInfo(
    /** 400 regular, 700 bold. */
    val weight: Int,
    val sha256: String,
    val size: Long,
)
