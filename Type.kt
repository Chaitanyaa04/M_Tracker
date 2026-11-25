package com.mtracker.app

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/** Every style carries tabular figures so digits align vertically. */
private fun TextStyle.t() = copy(fontFamily = FontFamily.SansSerif, fontFeatureSettings = "tnum")

val LedgerTypography: Typography = Typography().let {
    Typography(
        displayLarge = it.displayLarge.t(), displayMedium = it.displayMedium.t(), displaySmall = it.displaySmall.t(),
        headlineLarge = it.headlineLarge.t(), headlineMedium = it.headlineMedium.t(), headlineSmall = it.headlineSmall.t(),
        titleLarge = it.titleLarge.t(), titleMedium = it.titleMedium.t(), titleSmall = it.titleSmall.t(),
        bodyLarge = it.bodyLarge.t(), bodyMedium = it.bodyMedium.t(), bodySmall = it.bodySmall.t(),
        labelLarge = it.labelLarge.t(), labelMedium = it.labelMedium.t(), labelSmall = it.labelSmall.t()
    )
}
