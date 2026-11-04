package fr.offlinetune.core.dsp

/** Built-in EQ presets (dB for 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz). */
object Presets {
    val builtIn: Map<String, FloatArray> = linkedMapOf(
        "Plat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Rock" to floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f),
        "Pop" to floatArrayOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 0f, 1f),
        "Jazz" to floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
        "Classique" to floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Électro" to floatArrayOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f),
        "Hip-Hop" to floatArrayOf(5f, 4f, 2f, 3f, -1f, -1f, 1f, -1f, 2f, 3f),
        "Bass boost" to floatArrayOf(8f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Voix" to floatArrayOf(-3f, -2f, -1f, 1f, 3f, 4f, 4f, 3f, 1f, 0f),
        "Acoustique" to floatArrayOf(4f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 3f, 2f),
    )
}
