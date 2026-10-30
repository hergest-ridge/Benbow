package app.benbow.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SelectableChipElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Material's filter chip, flat under a mouse too (owner, ADR-058): its default lifts it a level when hovered, and an
 * unselected chip, being see-through, then showed its shadow inside itself (a grey box in the light theme). Every
 * screen takes this one (ThemeSourceTest).
 */
@Composable
internal fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = FilterChipDefaults.shape,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    elevation: SelectableChipElevation? = FilterChipDefaults.filterChipElevation(hoveredElevation = 0.dp),
    border: BorderStroke? = FilterChipDefaults.filterChipBorder(enabled, selected),
    interactionSource: MutableInteractionSource? = null,
) = androidx.compose.material3.FilterChip(
    selected = selected,
    onClick = onClick,
    label = label,
    modifier = modifier,
    enabled = enabled,
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    interactionSource = interactionSource,
)
