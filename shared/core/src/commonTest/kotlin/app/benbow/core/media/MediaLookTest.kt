package app.benbow.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An attachment's look in its post (owner, 2026-10-30): its own fields, read back as they were written. */
class MediaLookTest {
    @Test
    fun fieldsReadBackAsWritten() {
        val fields = mapOf(
            MediaLook.fitField("a", MediaLook.Fit.Fill),
            MediaLook.focusField("a", MediaLook.Focus(0.25f, 0.8f)),
            MediaLook.playField("g", false),
            MediaLook.fitField("n", MediaLook.Fit.Natural),
            "media_fit:x" to "stretch", // a newer app's: read as the default, kept in its field
            "track:a" to "{}",
        )
        val looks = MediaLook.of(fields)
        assertEquals(MediaLook(MediaLook.Fit.Fill, MediaLook.Focus(0.25f, 0.8f)), looks["a"])
        assertEquals(false, looks["g"]?.play)
        assertNull(fields["media_fit:n"], "its own shape is the default: no value")
        assertNull(looks["x"], "an unknown fit reads as nothing set")
        assertEquals("0.25,0.8", MediaLook.Focus(0.25f, 0.8f).toString())
    }

    @Test
    fun gifsPlayByThemselvesAndVideosDont() {
        assertTrue(MediaLook().plays(gif = true))
        assertEquals(false, MediaLook().plays(gif = false))
        assertEquals(true, MediaLook(play = true).plays(gif = false))
        assertEquals(false, MediaLook(play = false).plays(gif = true))
    }
}
