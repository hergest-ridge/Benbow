package app.benbow.ui.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.benbow.core.text.Atom
import app.benbow.core.text.Links
import app.benbow.ui.resources.Res
import app.benbow.ui.resources.picture_coming
import app.benbow.ui.resources.picture_coming_gif
import app.benbow.ui.theme.BenbowTheme
import org.jetbrains.compose.resources.stringResource

/**
 * A link that is surely a picture, on its way to an attachment (owner: show it when it is surely a picture, not just a
 * link; ADR-045): the picture's own place (its proportions once its first bytes came), a light sweeping across it, a
 * turning ring over a picture's sign, «Загружаю гифку…» or «Загружаю картинку…» and the site.
 */
@Composable
internal fun PictureComingView(card: Atom.LinkCard, coming: PictureLoading, modifier: Modifier, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val sweep by rememberInfiniteTransition().animateFloat(-0.5f, 1.5f, infiniteRepeatable(tween(SWEEP_MILLIS, easing = LinearEasing)))
    val light = colors.onSurface.copy(alpha = 0.07f)
    Box(
        modifier.clip(shape).background(colors.surfaceContainerHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else BenbowTheme.colors.hairline, shape)
            .drawBehind {
                val x = size.width * sweep
                val band = size.width * 0.35f
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, light, Color.Transparent),
                        Offset(x - band, 0f),
                        Offset(x + band, size.height),
                    ),
                )
            }
            .pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    Modifier.size(48.dp),
                    color = colors.primary,
                    strokeWidth = 3.dp,
                    trackColor = colors.primary.copy(alpha = 0.15f),
                )
                Icon(EditorIcons.Picture, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
            }
            Text(
                stringResource(if (coming.gif) Res.string.picture_coming_gif else Res.string.picture_coming),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
            )
            Text(
                Links.host(card.href).ifEmpty { Links.display(card.href) },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val SWEEP_MILLIS = 1400
