package com.cryptomesh.frontend.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = MeshGreen,
    onPrimary = Paper,
    primaryContainer = MeshMint,
    onPrimaryContainer = MeshGreenDark,
    secondary = AlertAmber,
    onSecondary = Ink,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = OnAmberContainer,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Panel,
    surfaceContainer = Panel,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = SoftPanel,
    surfaceContainerHigh = Panel,
    surfaceContainerHighest = MeshMint,
    onSurfaceVariant = Slate,
    outline = MeshOutline,
    outlineVariant = MeshOutlineSoft,
    surfaceTint = Color.Transparent
)

private val CryptoMeshShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun CryptoMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = CryptoMeshTypography,
        shapes = CryptoMeshShapes,
        content = content
    )
}
