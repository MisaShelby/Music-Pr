package fr.offlinetune.settings

import kotlinx.serialization.Serializable

/**
 * « Apparence »: how the whole app looks. Every field has a default equal to the original look, so older saved
 * settings read as « Classique ».
 */
@Serializable
data class Look(
    /** Colour of buttons, highlights, sliders: taken from the playing song's cover, or fixed ([accent]). */
    val accentFromCover: Boolean = true,
    val accent: Long = 0xFFE8B04B,
    /** Background and card colours: see [Palettes]. */
    val palette: String = "NUIT",
    /** Behind the screens: COVER (blurred cover), GRADIENT (glow of the accent colour), PLAIN (palette only). */
    val backdrop: String = "COVER",
    /** Frosted-glass cards: SUBTLE, NORMAL, STRONG. */
    val glass: String = "NORMAL",
    /** Text size: 0.85 … 1.3. */
    val textScale: Float = 1f,
    /** Rounded corners of the cards: 0.4 (almost square) … 1.4 (very round). */
    val roundness: Float = 1f,
)

/** « Écran de verrouillage » shown while music plays (see LockScreenActivity). */
@Serializable
data class LockLook(
    /** THIN, BOLD, DIGITAL, STACKED, HIDDEN. */
    val clock: String = "THIN",
    val showDate: Boolean = true,
    /** NONE, BATTERY, NEXT_SONG, PROGRESS. */
    val widget: String = "NONE",
    /** LARGE (big cover), COMPACT (card), MINIMAL (text only), SPECTRUM (spectrum fills the screen). */
    val layout: String = "LARGE",
    val spectrum: Boolean = true,
)

/** Background colour sets. */
data class PaletteColors(val name: String, val label: String, val background: Long, val surface: Long, val surfaceHigh: Long, val text: Long, val muted: Long)

object Palettes {
    val all = listOf(
        PaletteColors("NUIT", "Nuit", 0xFF12141C, 0xFF1C1F2B, 0xFF272B3A, 0xFFECEAE4, 0xFF9A9DAB),
        PaletteColors("AMOLED", "Noir AMOLED", 0xFF000000, 0xFF0E0E10, 0xFF1A1A1E, 0xFFF2F2F2, 0xFF8E8E96),
        PaletteColors("ARDOISE", "Ardoise", 0xFF1B2230, 0xFF253045, 0xFF2F3B54, 0xFFE8EDF5, 0xFF9AA6BA),
        PaletteColors("OCEAN", "Océan", 0xFF0B1A1F, 0xFF13262D, 0xFF1C333B, 0xFFE3F2F1, 0xFF8FB0B0),
        PaletteColors("PRUNE", "Prune", 0xFF1A1222, 0xFF251A30, 0xFF30223E, 0xFFF1E8F7, 0xFFA898B5),
        PaletteColors("CHAUD", "Café", 0xFF1E1712, 0xFF2A2019, 0xFF362A20, 0xFFF3E9DC, 0xFFB3A391),
        PaletteColors("FORET", "Forêt", 0xFF0F1A13, 0xFF18271D, 0xFF213427, 0xFFE6F2E8, 0xFF94AE9A),
    )
    fun of(name: String) = all.firstOrNull { it.name == name } ?: all.first()
}

/** Fixed accent colours offered (when not taken from the cover). */
val ACCENTS = listOf(
    "Laiton" to 0xFFE8B04B, "Corail" to 0xFFFF6A88, "Rouge" to 0xFFFF5252, "Orange" to 0xFFFF9F43,
    "Citron" to 0xFFE6EE7A, "Menthe" to 0xFF3DDC97, "Turquoise" to 0xFF00E5D0, "Ciel" to 0xFF4FC3F7,
    "Bleu" to 0xFF5B8CFF, "Lavande" to 0xFFB39DDB, "Rose" to 0xFFF48FB1, "Blanc" to 0xFFECEAE4,
)

/** Ready-made looks, to start from (the user can then change any detail). */
data class LookPreset(val name: String, val description: String, val look: Look)

val LOOK_PRESETS = listOf(
    LookPreset("Classique", "Pochette floutée, couleur de la chanson", Look()),
    LookPreset("AMOLED", "Noir pur, économise la batterie", Look(palette = "AMOLED", backdrop = "PLAIN", glass = "SUBTLE")),
    LookPreset("Néon", "Noir et cyan lumineux", Look(accentFromCover = false, accent = 0xFF00E5D0, palette = "AMOLED", backdrop = "GRADIENT", glass = "STRONG")),
    LookPreset("Coucher de soleil", "Tons chauds, corail", Look(accentFromCover = false, accent = 0xFFFF6A88, palette = "CHAUD", backdrop = "GRADIENT")),
    LookPreset("Océan", "Bleu-vert profond, menthe", Look(accentFromCover = false, accent = 0xFF3DDC97, palette = "OCEAN")),
    LookPreset("Lavande", "Violet doux, très arrondi", Look(accentFromCover = false, accent = 0xFFB39DDB, palette = "PRUNE", backdrop = "GRADIENT", roundness = 1.4f)),
    LookPreset("Forêt", "Vert sombre, laiton", Look(accentFromCover = false, accent = 0xFFE8B04B, palette = "FORET")),
    LookPreset("Minimal", "Ardoise, coins nets, sans effet verre", Look(palette = "ARDOISE", backdrop = "PLAIN", glass = "SUBTLE", roundness = 0.4f)),
)
