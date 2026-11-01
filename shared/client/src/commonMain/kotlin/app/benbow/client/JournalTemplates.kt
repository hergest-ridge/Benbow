package app.benbow.client

import app.benbow.core.board.Board
import app.benbow.core.content.AttachmentBlock
import app.benbow.core.content.AttachmentRowBlock
import app.benbow.core.content.DocumentCodec
import app.benbow.core.content.DocumentFormatException
import app.benbow.core.content.TemplateSpace
import app.benbow.core.sync.AttachmentState
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow

// One's own templates (ADR-061): entries in the space `template`, made from a note («Сохранить как шаблон»), from
// a standard template («Дублировать») or empty. What is made from one gets its words with the placeholders filled,
// its look, color and tags, and its pictures as links of their own to the same files.

/** One's own template as the choice of templates shows it. */
data class OwnTemplate(val id: String, val name: String, val preview: String)

fun JournalRepository.ownTemplates(): Flow<List<OwnTemplate>> = q.templates(::ownTemplate).asFlow().mapToList(context)

/** One's own templates in the trash, the latest first (the trash screen's «Шаблоны»). */
fun JournalRepository.templateTrash(): Flow<List<OwnTemplate>> = q.templateTrash(::ownTemplate).asFlow().mapToList(context)

@Suppress("UNUSED_PARAMETER")
private fun ownTemplate(
    id: String,
    day: String,
    createdAt: Long,
    title: String,
    preview: String,
    conflictHeads: String,
    mediaCount: Long,
    cover: String?,
    fields: String,
) = OwnTemplate(id, decodeFields(fields)[TemplateSpace.NAME] ?: title, preview)

/** What a template is called: its name, else its title. */
fun JournalRepository.templateName(id: String): String? = q.selectEntry(id).executeAsOneOrNull()?.let { entry ->
    decodeFields(entry.fields)[TemplateSpace.NAME] ?: entry.title
}

/**
 * A new template called [name] from [body] (a note's words as its editor has them, or a standard template's) with
 * the look, color, tags and pictures of entry [from], when it comes from one; made on [day].
 */
fun JournalRepository.saveAsTemplate(name: String, body: String, day: String, from: String? = null): String = db.transactionWithResult {
    val source = from?.let { q.selectEntry(it).executeAsOneOrNull() }
    val copy = source?.let { TemplateCopy.of(decodeAttachments(it.attachments), decodeFields(it.fields), ::newId) } ?: TemplateCopy()
    val id = createEntry(day, TemplateSpace.KIND, copy.body(body))
    setField(id, Board.SPACE, TemplateSpace.TEMPLATE)
    setField(id, TemplateSpace.NAME, name)
    putCopy(id, copy)
    id
}

fun JournalRepository.renameTemplate(id: String, name: String) = setField(id, TemplateSpace.NAME, name)

/** A template's words as they stand: its draft while one is open, else its head. */
fun JournalRepository.templateBody(id: String): String? =
    q.selectDraft(id).executeAsOneOrNull()?.body ?: q.selectEntry(id).executeAsOneOrNull()?.body

/**
 * What an entry made from one's own template [id] starts with: the template's words (its draft, if one is open) with
 * [context]'s placeholders filled; [JournalRepository.fillFromTemplate] adds the rest once the entry exists. Null when
 * [id] is no template here or its text can't be read.
 */
fun JournalRepository.templateStart(id: String, context: TemplateContext): TemplateStart? {
    val entry = q.selectEntry(id).executeAsOneOrNull() ?: return null
    val fields = decodeFields(entry.fields)
    if (!TemplateSpace.isTemplate(fields)) return null
    val copy = TemplateCopy.of(decodeAttachments(entry.attachments), fields, ::newId)
    val body = templateBody(id) ?: entry.body
    val document = try {
        DocumentCodec.decode(copy.body(body))
    } catch (_: DocumentFormatException) {
        return null
    }
    return TemplateStart(DocumentCodec.encode(Templates.fill(document, context)), copy)
}

/** An entry's start from a template: its [body], and what [JournalRepository.fillFromTemplate] puts on it. */
class TemplateStart internal constructor(val body: String, internal val copy: TemplateCopy)

/** The template's look, color, tags and pictures on entry [entryId], just made from [start]. */
fun JournalRepository.fillFromTemplate(entryId: String, start: TemplateStart) = db.transaction { putCopy(entryId, start.copy) }

private fun JournalRepository.putCopy(entryId: String, copy: TemplateCopy) {
    copy.attachments.forEach { copyAttachment(entryId, it, copy.ids.getValue(it.meta.id)) }
    copy.fields.forEach { (field, value) -> setField(entryId, field, value) }
}

/**
 * What passes from an entry to a template made from it, and from a template to an entry: its shown attachments, each
 * under a new id ([ids], old to new), and the fields that make its look ([LookFields], the color), its tags, and those
 * of its attachments (`<prefix>:<attachment id>`, under the new ids). Nothing of where it lives or how it is kept
 * (space, trash, star, pin, Бережно, the day) passes on.
 */
internal class TemplateCopy(
    val attachments: List<AttachmentState> = emptyList(),
    val ids: Map<String, String> = emptyMap(),
    val fields: Map<String, String> = emptyMap(),
) {
    /** [body] with its pictures pointing at the new links; as it is when it can't be read (a later app's). */
    fun body(body: String): String {
        if (ids.isEmpty()) return body
        val document = try {
            DocumentCodec.decode(body)
        } catch (_: DocumentFormatException) {
            return body
        }
        val moved = { ref: String -> ids[ref] ?: ref }
        return DocumentCodec.encode(
            document.copy(
                blocks = document.blocks.map { block ->
                    when (block) {
                        is AttachmentBlock -> block.copy(ref = moved(block.ref))
                        is AttachmentRowBlock -> block.copy(refs = block.refs.map(moved))
                        else -> block
                    }
                },
            ),
        )
    }

    companion object {
        fun of(attachments: Map<String, AttachmentState>, fields: Map<String, String?>, newId: () -> String): TemplateCopy {
            val shown = attachments.values.filter { it.present && !it.purged }.sortedBy { it.position }
            val ids = shown.associate { it.meta.id to newId() }
            val passed = buildMap {
                for ((field, value) in fields) {
                    if (value == null) continue
                    val attachment = field.substringAfter(':', "")
                    when {
                        field.startsWith(
                            LookFields.PREFIX,
                        ) || field == BACKGROUND_FIELD || field.startsWith(TAG_PREFIX) -> put(field, value)

                        attachment in ids -> put(field.substringBefore(':') + ":" + ids.getValue(attachment), value)
                    }
                }
            }
            return TemplateCopy(shown, ids, passed)
        }
    }
}
