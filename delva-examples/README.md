# delva-examples

Runnable programs that use nothing but Delva's public API, each with a test that runs it.

| Example | Shows |
|---|---|
| `TypedSearch` | A search on two conditions at once, such as the objects whose `@type` is `Battlefield` *and* whose `id` is `bf-101`: the objects of the first condition are tagged with a type once, after which each lookup is a single search. |
| `Records` | A record store keyed by `$.id`: save and read the current version, list ids by prefix a page at a time, and select within the records of one kind through a document-filter template. |
| `Versions` | The current version of a record without a document key: `replace` the newest version, which a descending search with a limit of one finds. |
| `Follower` | A secondary store that refreshes until the writer has written what it waits for. |
| `EmbeddingSearch` | The chunks nearest to a query embedding, searched in application code: chunk documents with their embedding left out of the index and written short, and a top k by cosine distance over the candidates a document filter picks. |

`TypedSearch` is the module's main program:

```shell
./gradlew :delva-examples:run --args="catalog.json @type=Battlefield id=bf-101"
```

`EmbeddingSearch` runs with `./gradlew :delva-examples:runEmbeddingSearch`. It needs no model: its embeddings are random
unit vectors from a seeded generator.
