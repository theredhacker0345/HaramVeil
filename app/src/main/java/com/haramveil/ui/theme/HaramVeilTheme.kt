/*
 * HaramVeil
 * Copyright (C) 2026 HaramVeil Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.haramveil.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HaramVeilDarkColorScheme = darkColorScheme(
    primary = Color(0xFF1B4332),
    onPrimary = Color(0xFFF6FFF8),
    primaryContainer = Color(0xFF24543F),
    onPrimaryContainer = Color(0xFFE2F6E8),
    secondary = Color(0xFF7BCFA7),
    onSecondary = Color(0xFF082117),
    secondaryContainer = Color(0xFF113727),
    onSecondaryContainer = Color(0xFFCBEEDC),
    tertiary = Color(0xFF4FC3C8),
    onTertiary = Color(0xFF07282B),
    background = Color(0xFF0D1B2A),
    onBackground = Color(0xFFE7F1F7),
    surface = Color(0xFF132537),
    onSurface = Color(0xFFE7F1F7),
    surfaceVariant = Color(0xFF183244),
    onSurfaceVariant = Color(0xFFBDD0DD),
    outline = Color(0xFF406378),
    error = Color(0xFFF4A261),
)

// Light scheme keeps the brand's deep-green identity but flips surfaces so users on a
// light system theme no longer get a dark app in broad daylight.
private val HaramVeilLightColorScheme = lightColorScheme(
    primary = Color(0xFF1B4332),
    onPrimary = Color(0xFFF6FFF8),
    primaryContainer = Color(0xFFBFE8CF),
    onPrimaryContainer = Color(0xFF0A291B),
    secondary = Color(0xFF2E6B4F),
    onSecondary = Color(0xFFF6FFF8),
    secondaryContainer = Color(0xFFD2F1DF),
    onSecondaryContainer = Color(0xFF0A291B),
    tertiary = Color(0xFF1D7C82),
    onTertiary = Color(0xFFF3FEFF),
    tertiaryContainer = Color(0xFFC4F0F3),
    onTertiaryContainer = Color(0xFF062A2D),
    background = Color(0xFFF7FAF6),
    onBackground = Color(0xFF18211C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18211C),
    surfaceVariant = Color(0xFFE3EBE4),
    onSurfaceVariant = Color(0xFF435049),
    outline = Color(0xFF72807A),
    error = Color(0xFFB3550F),
)

@Composable
fun HaramVeilTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) HaramVeilDarkColorScheme else HaramVeilLightColorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
