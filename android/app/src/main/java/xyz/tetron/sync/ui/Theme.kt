// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em

/**
 * SYNC-009: matches tetron-mobile's brand palette (green primary,
 * `xyz.tetron.mobile.ui.Theme.kt`'s `active`/background/surface values) so
 * the two apps read as one family on the same device. Fresh code, not
 * shared/copied -- tetron-mobile is proprietary and this repo is GPL-3.0
 * (AGENTS.md: "use them as design reference only"); the hex values
 * themselves are not original expression, so matching them for visual
 * consistency does not implicate that boundary. Unlike tetron-mobile this
 * app has no manual dark-mode override setting (nothing in SYNC-009's
 * scope calls for one), so this always follows [isSystemInDarkTheme].
 *
 * Every core Material slot both apps set (primary, background, surface,
 * onBackground, onSurface, outline, onPrimary) is hex-identical to
 * tetron-mobile in both modes; this app additionally pins the container /
 * secondary slots M3 would otherwise fill with baseline purple. The two
 * shared text styles below ([MonoTextStyle], [EyebrowTextStyle]) carry the
 * same definitions as tetron-mobile so technical strings and section
 * eyebrows render the same in both apps.
 */
// Material3's darkColorScheme()/lightColorScheme() builders default every
// unspecified slot to the library's own baseline purple tonal palette, not
// something derived from primary/surface -- NavigationBar's selected-item
// pill (secondaryContainer) and default background (surfaceContainer) were
// still purple with only primary/background/surface set, so every slot a
// stock component actually reads from needs an explicit green-toned value.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4CAF50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3C8C40),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFA8C9AC),
    onSecondary = Color(0xFF14201C),
    secondaryContainer = Color(0xFF2C3B30),
    onSecondaryContainer = Color(0xFFECF3F0),
    background = Color(0xFF10161A),
    onBackground = Color(0xFFECF3F0),
    surface = Color(0xFF1A2226),
    onSurface = Color(0xFFECF3F0),
    surfaceVariant = Color(0xFF212B2F),
    onSurfaceVariant = Color(0xFFC3CFC9),
    surfaceContainer = Color(0xFF16201F),
    outline = Color(0xFF253230),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA8D5AB),
    onPrimaryContainer = Color(0xFF14201C),
    secondary = Color(0xFF4A6350),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E8D9),
    onSecondaryContainer = Color(0xFF14201C),
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF14201C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14201C),
    surfaceVariant = Color(0xFFEEF2F0),
    onSurfaceVariant = Color(0xFF3F4A45),
    surfaceContainer = Color(0xFFEEF2F0),
    outline = Color(0xFFDCE4E1),
)

/** Technical data (IPs, hostnames, build SHA) -- tabular precision, same
 *  definition as `xyz.tetron.mobile.ui.MonoTextStyle`. */
val MonoTextStyle = TextStyle(fontFamily = FontFamily.Monospace)

/** Small uppercase section labels ("BACKUP TARGET") -- same definition as
 *  `xyz.tetron.mobile.ui.EyebrowTextStyle`. Callers uppercase the text. */
val EyebrowTextStyle =
    TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.09.em,
    )

@Composable
fun TetronSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme,
        typography = Typography(),
        content = content,
    )
}
