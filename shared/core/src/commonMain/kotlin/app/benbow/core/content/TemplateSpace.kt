package app.benbow.core.content

import app.benbow.core.board.Board

/**
 * One's own templates (ADR-061): entries with [Board.SPACE] = [TEMPLATE], so a template has what an entry has (the
 * real editor, revisions, looks, tags, pictures, sync and export) and shows nowhere in the journal or on the board,
 * which read only their own spaces. The standard templates stay in the app's code and can't be deleted.
 */
object TemplateSpace {
    const val TEMPLATE = "template"

    /** The kind a template entry is made with. */
    const val KIND = "template"

    /** What the template is called in the choice of templates (its own title becomes the title of what it makes). */
    const val NAME = "template_name"

    fun isTemplate(fields: Map<String, String?>): Boolean = fields[Board.SPACE] == TEMPLATE
}
