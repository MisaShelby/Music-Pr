package fr.offlinetune.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

val LenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun parseJson(text: String): JsonElement = LenientJson.parseToJsonElement(text)

/** Navigates nested objects: `el.at("a", "b")`. Returns null when any step is missing. */
fun JsonElement?.at(vararg path: String): JsonElement? {
    var cur: JsonElement? = this
    for (p in path) cur = (cur as? JsonObject)?.get(p) ?: return null
    return if (cur is JsonNull) null else cur
}

fun JsonElement?.str(vararg path: String): String? = (at(*path) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
fun JsonElement?.long(vararg path: String): Long? = (at(*path) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
fun JsonElement?.int(vararg path: String): Int? = long(*path)?.toInt()
fun JsonElement?.arr(vararg path: String): List<JsonElement> = (at(*path) as? JsonArray) ?: emptyList()
