package app.oreshkov.delva.internal.payload

/** Writes decoded payload values as JSON. */
internal object PayloadJson {
    /**
     * Writes every value [reader] yields until [PayloadReader.END]. A PART's members are written without their
     * container's brackets (object members as `"key":value`, separated by commas). Stubs can't be resolved from one
     * fragment and throw [IllegalStateException]; the reassembler resolves them.
     */
    fun write(reader: PayloadReader, out: JsonEvents) {
        val payload = reader.payload
        while (true) {
            val event = reader.next()
            if (reader.hasKey) out.key(payload, reader.keyOffset, reader.keyLength)
            when (event) {
                PayloadReader.END -> return
                PayloadReader.NULL -> out.nullValue()
                PayloadReader.FALSE -> out.booleanValue(false)
                PayloadReader.TRUE -> out.booleanValue(true)
                PayloadReader.INT -> out.number(reader.intValue)
                PayloadReader.NUM -> out.number(payload, reader.offset, reader.length)
                PayloadReader.STR -> out.string(payload, reader.offset, reader.length)
                PayloadReader.START_OBJECT -> out.beginObject()
                PayloadReader.END_OBJECT -> out.endObject()
                PayloadReader.START_ARRAY -> out.beginArray()
                PayloadReader.END_ARRAY -> out.endArray()
                else -> throw IllegalStateException("stub at pre ${reader.pre} needs other fragments")
            }
        }
    }
}
