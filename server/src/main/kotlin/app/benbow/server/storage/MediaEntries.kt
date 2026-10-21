package app.benbow.server.storage

import app.benbow.core.media.MediaEntry
import app.benbow.core.sync.AttachmentState
import app.benbow.core.sync.LwwValue
import app.benbow.core.sync.Purging
import kotlinx.serialization.json.Json
import java.sql.Connection

private val json = Json { ignoreUnknownKeys = true }

/**
 * Every entry as [app.benbow.core.media.MediaRefs] needs it (ADR-041): its links, its trash, its parent, its head and
 * conflict heads. An entry deleted for good holds only its links (all purged): its text is unseen, so it holds no file.
 * Called with the database's lock held ([Database.locked]), so the picture is one moment's.
 */
internal fun readMediaEntries(c: Connection): List<MediaEntry> {
    class Row(val id: String, val day: String, val fields: String, val attachments: String, val conflictHeads: String, val head: String?)
    val rows = c.createStatement().use { st ->
        st.executeQuery(
            "SELECT e.id, e.day, e.fields, e.attachments, e.conflict_heads, r.body FROM entries e " +
                "LEFT JOIN revisions r ON r.id = e.head_rev",
        ).use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                    )
                }
            }
        }
    }
    return rows.map { row ->
        val fields = json.decodeFromString<Map<String, LwwValue>>(row.fields)
        val gone = fields[Purging.FIELD]?.value == "true"
        val conflicts = if (gone) {
            emptyList()
        } else {
            json.decodeFromString<List<String>>(row.conflictHeads).mapNotNull { id ->
                c.prepareStatement("SELECT body FROM revisions WHERE id = ?").use { st ->
                    st.setString(1, id)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }
        }
        MediaEntry(
            id = row.id,
            trashed = fields["deleted"]?.value == "true",
            parent = fields["parent"]?.value,
            attachments = json.decodeFromString<Map<String, AttachmentState>>(row.attachments),
            bodies = if (gone) emptyList() else listOfNotNull(row.head) + conflicts,
            day = row.day,
        )
    }
}
