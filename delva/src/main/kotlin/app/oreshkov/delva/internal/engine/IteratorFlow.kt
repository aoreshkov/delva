package app.oreshkov.delva.internal.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A cold flow over what [iterator] gives, read on the collector's thread; each collection asks for a new iterator. How
 * the results' blocking iterators become the flows of the API.
 */
internal fun <T> iteratorFlow(iterator: () -> Iterator<T>): Flow<T> = flow {
    for (element in iterator()) emit(element)
}
