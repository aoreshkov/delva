package app.oreshkov.delva

/**
 * Identifier of a stored document. Dense and increasing in insert start order; aborted inserts leave gaps. [raw] is
 * the number itself.
 *
 * A value class, so it costs no allocation and no Java caller can name it: every function that takes or returns one
 * has a mangled JVM name. Delva 0.1.0 is a Kotlin API (project/GUIDE.md, *Using it from Java*).
 */
@JvmInline
public value class DocId(public val raw: Long) {
    override fun toString(): String = "DocId($raw)"
}
