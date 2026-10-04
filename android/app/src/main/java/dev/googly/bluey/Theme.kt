package dev.googly.bluey

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Blueberry colors from the design canvas (same as Shared/Palette.swift). */
object Palette {
    val berry1 = hex(0xA9BCFF)  // light periwinkle
    val berry2 = hex(0x6C86F5)
    val berry3 = hex(0x4254D6)
    val berry4 = hex(0x2B2F8F)  // deep indigo
    val nose = hex(0x1C1F66)
    val ink = hex(0x17151F)
    val inkSoft = hex(0xB9B2CC)
    val panel = hex(0x1E1B29)

    val gradient = listOf(berry1, berry2, berry3, berry4)
    val gradientStops = listOf(0f, 0.34f, 0.68f, 1f)

    /** The blueberry gradient at 150°, stretched over whatever it fills (buttons, bubbles). */
    val berryFill: Brush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader = LinearGradientShader(
            from = Offset(0.25f * size.width, 0.067f * size.height),
            to = Offset(0.75f * size.width, 0.933f * size.height),
            colors = gradient,
            colorStops = gradientStops,
        )
    }

    /** The blueberry gradient at 150°, like the design, across [r]. */
    fun berryBrush(r: Rect): Brush = Brush.linearGradient(
        colorStops = gradientStops.zip(gradient).toTypedArray(),
        start = Offset(r.left + 0.25f * r.width, r.top + 0.067f * r.height),
        end = Offset(r.left + 0.75f * r.width, r.top + 0.933f * r.height),
    )
}

fun hex(rgb: Long, alpha: Float = 1f): Color = Color(0xFF000000 or rgb).copy(alpha = alpha)

/** The design's fonts: Fredoka for his voice and titles, IBM Plex for everything else. */
object Fonts {
    // Fredoka and Plex Sans are variable fonts: each weight is picked with the font's own weight axis.
    @OptIn(ExperimentalTextApi::class)
    private fun variable(res: Int, weight: FontWeight) =
        Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

    private val fredokaFamily = FontFamily(variable(R.font.fredoka, FontWeight.Bold))
    private val plexSansFamily = FontFamily(
        variable(R.font.ibm_plex_sans, FontWeight.Normal),
        variable(R.font.ibm_plex_sans, FontWeight.Medium),
        variable(R.font.ibm_plex_sans, FontWeight.SemiBold),
        variable(R.font.ibm_plex_sans, FontWeight.Bold),
    )
    private val plexMonoFamily = FontFamily(
        Font(R.font.ibm_plex_mono, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Bold),
    )

    fun fredoka(size: Int) = TextStyle(fontFamily = fredokaFamily, fontWeight = FontWeight.Bold, fontSize = size.sp)
    fun plexSans(size: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = plexSansFamily, fontWeight = weight, fontSize = size.sp)
    fun plexMono(size: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = plexMonoFamily, fontWeight = weight, fontSize = size.sp)
}
