package com.nuva.assistant.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Layered aurora background used behind every route. */
@Composable
fun NuvaBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        colors.background,
                        colors.primaryContainer.copy(alpha = 0.42f),
                        colors.background,
                        colors.secondaryContainer.copy(alpha = 0.30f),
                    ),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(colors.primary.copy(alpha = 0.18f), Color.Transparent),
                ),
                radius = size.minDimension * 0.52f,
                center = Offset(size.width * 0.88f, size.height * 0.08f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(colors.secondary.copy(alpha = 0.13f), Color.Transparent),
                ),
                radius = size.minDimension * 0.46f,
                center = Offset(size.width * 0.08f, size.height * 0.72f),
            )
        }
        content()
    }
}

/** Raised translucent panel with highlight edge and soft lower shadow. */
@Composable
fun NuvaGlassPanel(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    cornerRadius: Dp = 24.dp,
    contentPadding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val colors = MaterialTheme.colorScheme
    val clickableModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Box(
        modifier = modifier
            .shadow(14.dp, shape = shape, clip = false)
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.surfaceVariant.copy(alpha = 0.95f),
                        colors.surface.copy(alpha = 0.91f),
                    ),
                ),
                shape,
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.28f),
                        accent.copy(alpha = 0.38f),
                        colors.outline.copy(alpha = 0.14f),
                    ),
                ),
                shape = shape,
            )
            .then(clickableModifier)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun NuvaScreenHeader(
    eyebrow: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            eyebrow.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun NuvaStatusChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.14f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.32f), CircleShape)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun NuvaDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

/** Tactile 3D command orb; static layers avoid motion-sickness and battery cost. */
@Composable
fun NuvaVoiceOrb(
    color: Color,
    busy: Boolean,
    accessibilityLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(164.dp)
            .semantics {
                contentDescription = accessibilityLabel
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(158.dp)
                .shadow(30.dp, CircleShape, clip = false)
                .background(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = 0.44f), color.copy(alpha = 0.10f), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )
        Box(
            Modifier
                .size(132.dp)
                .shadow(18.dp, CircleShape, clip = false)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.30f),
                            color,
                            color.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.30f),
                        ),
                        start = Offset.Zero,
                        end = Offset(132f, 132f),
                    ),
                    CircleShape,
                )
                .border(2.dp, Color.White.copy(alpha = 0.30f), CircleShape),
        )
        Box(
            Modifier
                .size(104.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.27f), Color.Transparent),
                        center = Offset(28f, 24f),
                    ),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(46.dp),
                    color = Color.White,
                    strokeWidth = 4.dp,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✦", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.displaySmall)
                    Text(
                        "TAP TO SPEAK",
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Gradient CTA that retains a 48dp+ accessible touch target. */
@Composable
fun NuvaPrimaryAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier = modifier
            .heightIn(min = 50.dp)
            .shadow(if (enabled) 12.dp else 0.dp, shape, clip = false)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                        MaterialTheme.colorScheme.secondary.copy(alpha = alpha),
                    ),
                ),
                shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.24f * alpha), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
