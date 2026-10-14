package app.oreshkov.delva.serialization

import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.SearchHit
import app.oreshkov.delva.SelectedNode
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * The hit's node decoded by [deserializer] with [json]. The node is read into memory as compact JSON text first and
 * then decoded, so it must fit in a string: a larger node fails with [app.oreshkov.delva.ResultTooLargeException].
 * A node the deserializer can't take fails with kotlinx.serialization's `SerializationException`, or
 * [IllegalArgumentException] for a value that isn't a valid [T]. Readable while the hit's result is open.
 */
public fun <T> SearchHit.decode(deserializer: DeserializationStrategy<T>, json: Json = Json): T =
    json.decodeFromString(deserializer, readJsonAsString(Int.MAX_VALUE))

/** [decode] with the serializer [json]'s module has for [T]: `hit.decode<Order>()`. */
public inline fun <reified T> SearchHit.decode(json: Json = Json): T = decode(json.serializersModule.serializer<T>(), json)

/** The node decoded by [deserializer] with [json], as [SearchHit.decode] decodes a hit. */
public fun <T> SelectedNode.decode(deserializer: DeserializationStrategy<T>, json: Json = Json): T =
    json.decodeFromString(deserializer, readJsonAsString(Int.MAX_VALUE))

/** [decode] with the serializer [json]'s module has for [T]. */
public inline fun <reified T> SelectedNode.decode(json: Json = Json): T = decode(json.serializersModule.serializer<T>(), json)

/**
 * This element as a query value, for a key–value search or a template's placeholder: a string as
 * [JsonQueryValue.Str], a number as [JsonQueryValue.Num], `true` and `false` as [JsonQueryValue.Bool], `null` as
 * [JsonQueryValue.Null], and an object or array as its JSON text, [JsonQueryValue.Json], which a template refuses
 * since it has no literal form. A number whose exponent a [java.math.BigDecimal] can't hold, and an unquoted literal
 * that isn't a number, `true`, `false` or `null`, stay JSON text, which the store checks as it checks any.
 */
public fun JsonElement.toQueryValue(): JsonQueryValue = when (this) {
    JsonNull -> JsonQueryValue.Null
    is JsonPrimitive -> when {
        isString -> JsonQueryValue.Str(content)
        content == "true" -> JsonQueryValue.Bool(true)
        content == "false" -> JsonQueryValue.Bool(false)
        else -> content.toBigDecimalOrNull()?.let { JsonQueryValue.Num(it) } ?: JsonQueryValue.Json(content)
    }
    else -> JsonQueryValue.Json(toString())
}
