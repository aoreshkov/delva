package app.oreshkov.delva.serialization

import app.oreshkov.delva.BlockingJsonStore
import app.oreshkov.delva.DocId
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyConflict
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

// Every write encodes the whole value to JSON text in memory first, with Json.encodeToString: a value a serializer
// encodes is in memory already, and the stable string functions keep this module off kotlinx.serialization's
// experimental stream API. The text then goes in as the store's own JSON text would.

/**
 * Inserts [value], encoded by [serializer] with [json], as [JsonStore.insert] inserts JSON text, assigning [types].
 * The encoding fails with kotlinx.serialization's `SerializationException`, before anything is stored; JSON the store
 * doesn't take, such as `NaN` from a [json] that allows special floating-point values, is a
 * [app.oreshkov.delva.DocumentRejectedException] as for any text.
 */
public suspend fun <T> JsonStore.insertValue(
    serializer: SerializationStrategy<T>,
    value: T,
    json: Json = Json,
    types: Map<String, String> = emptyMap(),
): DocId = insert(json.encodeToString(serializer, value), types)

/** [insertValue] with the serializer [json]'s module has for [T]: `store.insertValue(order)`. */
public suspend inline fun <reified T> JsonStore.insertValue(value: T, json: Json = Json, types: Map<String, String> = emptyMap()): DocId =
    insertValue(json.serializersModule.serializer<T>(), value, json, types)

/** Inserts [value], encoded as [insertValue] encodes it, and deletes [docIds] in one atomic change ([JsonStore.replace]). */
@ExperimentalDelvaApi
public suspend fun <T> JsonStore.replaceValue(
    docIds: Collection<DocId>,
    serializer: SerializationStrategy<T>,
    value: T,
    json: Json = Json,
    types: Map<String, String> = emptyMap(),
): DocId = replace(docIds, json.encodeToString(serializer, value), types)

/** [replaceValue] with the serializer [json]'s module has for [T]. */
@ExperimentalDelvaApi
public suspend inline fun <reified T> JsonStore.replaceValue(
    docIds: Collection<DocId>,
    value: T,
    json: Json = Json,
    types: Map<String, String> = emptyMap(),
): DocId = replaceValue(docIds, json.serializersModule.serializer<T>(), value, json, types)

/** Inserts [value], encoded as [insertValue] encodes it, replacing the document with its key ([JsonStore.upsert]). */
@ExperimentalDelvaApi
public suspend fun <T> JsonStore.upsertValue(
    serializer: SerializationStrategy<T>,
    value: T,
    json: Json = Json,
    types: Map<String, String> = emptyMap(),
): DocId = upsert(json.encodeToString(serializer, value), types)

/** [upsertValue] with the serializer [json]'s module has for [T]. */
@ExperimentalDelvaApi
public suspend inline fun <reified T> JsonStore.upsertValue(value: T, json: Json = Json, types: Map<String, String> = emptyMap()): DocId =
    upsertValue(json.serializersModule.serializer<T>(), value, json, types)

/** See [JsonStore.insertValue]. */
public fun <T> BlockingJsonStore.insertValue(
    serializer: SerializationStrategy<T>,
    value: T,
    json: Json = Json,
    types: Map<String, String> = emptyMap(),
): DocId = insert(json.encodeToString(serializer, value), types)

/** See [JsonStore.insertValue]. */
public inline fun <reified T> BlockingJsonStore.insertValue(value: T, json: Json = Json, types: Map<String, String> = emptyMap()): DocId =
    insertValue(json.serializersModule.serializer<T>(), value, json, types)

/** See [JsonStore.replaceValue]. */
@ExperimentalDelvaApi
public fun <T> BlockingJsonStore.replaceValue(
    docIds: Collection<DocId>,
    serializer: SerializationStrategy<T>,
    value: T,
    json: Json = Json,
    types: Map<String, String> = emptyMap(),
): DocId = replace(docIds, json.encodeToString(serializer, value), types)

/** See [JsonStore.replaceValue]. */
@ExperimentalDelvaApi
public inline fun <reified T> BlockingJsonStore.replaceValue(
    docIds: Collection<DocId>,
    value: T,
    json: Json = Json,
    types: Map<String, String> = emptyMap(),
): DocId = replaceValue(docIds, json.serializersModule.serializer<T>(), value, json, types)

/** See [JsonStore.upsertValue]. */
@ExperimentalDelvaApi
public fun <T> BlockingJsonStore.upsertValue(
    serializer: SerializationStrategy<T>,
    value: T,
    json: Json = Json,
    types: Map<String, String> = emptyMap(),
): DocId = upsert(json.encodeToString(serializer, value), types)

/** See [JsonStore.upsertValue]. */
@ExperimentalDelvaApi
public inline fun <reified T> BlockingJsonStore.upsertValue(value: T, json: Json = Json, types: Map<String, String> = emptyMap()): DocId =
    upsertValue(json.serializersModule.serializer<T>(), value, json, types)

/**
 * One document for [JsonStore.insertAll]: [value], encoded by [serializer] with [json] when this is called, so a value
 * that can't be encoded fails here, before the batch starts. [types] as [InsertSource.types].
 */
public fun <T> insertSourceOf(
    serializer: SerializationStrategy<T>,
    value: T,
    json: Json = Json,
    types: Map<String, String> = emptyMap(),
): InsertSource = InsertSource(json.encodeToString(serializer, value).byteInputStream(), types)

/** [insertSourceOf] with the serializer [json]'s module has for [T]. */
public inline fun <reified T> insertSourceOf(value: T, json: Json = Json, types: Map<String, String> = emptyMap()): InsertSource =
    insertSourceOf(json.serializersModule.serializer<T>(), value, json, types)

/** [insertSourceOf], for a document that [replaces] others or, with a document key, [onExistingKey] (see [InsertSource]). */
@ExperimentalDelvaApi
public fun <T> insertSourceOf(
    serializer: SerializationStrategy<T>,
    value: T,
    json: Json = Json,
    types: Map<String, String> = emptyMap(),
    replaces: Collection<DocId>,
    onExistingKey: KeyConflict = KeyConflict.FAIL,
): InsertSource = InsertSource(json.encodeToString(serializer, value).byteInputStream(), types, replaces, onExistingKey)

/** [insertSourceOf] with [replaces] and [onExistingKey], and the serializer [json]'s module has for [T]. */
@ExperimentalDelvaApi
public inline fun <reified T> insertSourceOf(
    value: T,
    json: Json = Json,
    types: Map<String, String> = emptyMap(),
    replaces: Collection<DocId>,
    onExistingKey: KeyConflict = KeyConflict.FAIL,
): InsertSource = insertSourceOf(json.serializersModule.serializer<T>(), value, json, types, replaces, onExistingKey)
