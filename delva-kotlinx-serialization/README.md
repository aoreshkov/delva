# delva-kotlinx-serialization

Stores `@Serializable` values in a Delva store and reads hits and selected nodes back as them, with the `Json`
configuration you pass (`Json` by default).

```kotlin
dependencies {
    implementation("app.oreshkov.delva:delva-kotlinx-serialization:0.1.0")
}
```

It adds `kotlinx-serialization-json` to your dependencies; the core `delva` artifact doesn't depend on it. Its JPMS
module name is `app.oreshkov.delva.serialization`.

```kotlin
@Serializable
data class Order(val id: String, val customer: String, val total: Int)

val db = store.blocking()
db.insertValue(Order("o-1", "ada", 120), types = mapOf("$" to "Order"))
val orders = db.search(KeyValueQuery("customer", JsonQueryValue.Str("ada"))).use { result ->
    result.iterator().asSequence().map { it.decode<Order>() }.toList()                // [Order(id=o-1, …)]
}
```

`replaceValue`, `upsertValue` and `insertSourceOf` (for `insertAll`) take values the same way, on the suspending store
too. `JsonElement.toQueryValue()` turns a `JsonElement` into a `JsonQueryValue` for a search or a template. A value is
encoded to JSON text in memory before it is stored, and a node is read as JSON text before it is decoded.
