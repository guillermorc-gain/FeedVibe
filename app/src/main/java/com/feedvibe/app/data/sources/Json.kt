package com.feedvibe.app.data.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
fun JsonElement?.str(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonElement?.long(key: String): Long? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
