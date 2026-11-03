package app.oreshkov.clikit

import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The content of the argument file [name], as UTF-8; throws [ArgumentFileException] with the reason it can't be read. */
internal fun readArgumentFile(name: String): String {
    val file = try {
        Path.of(name)
    } catch (e: InvalidPathException) {
        throw ArgumentFileException("not a valid path: ${e.reason}", e)
    }
    if (Files.isDirectory(file)) throw ArgumentFileException("it is a directory")
    val bytes = try {
        Files.newInputStream(file).use { it.readNBytes(MAX_ARGUMENT_FILE_BYTES + 1) }
    } catch (e: NoSuchFileException) {
        throw ArgumentFileException("no such file", e)
    } catch (e: AccessDeniedException) {
        throw ArgumentFileException("permission denied", e)
    } catch (e: IOException) {
        throw ArgumentFileException(e.message ?: e::class.simpleName.orEmpty(), e)
    }
    if (bytes.size > MAX_ARGUMENT_FILE_BYTES) throw ArgumentFileException("larger than 1 MiB")
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        throw ArgumentFileException("not UTF-8 text", e)
    }
}

/**
 * The largest argument file read, so that memory stays bounded whatever a file holds; far more than any operating
 * system lets a command line hold.
 */
private const val MAX_ARGUMENT_FILE_BYTES = 1 shl 20
