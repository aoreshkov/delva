package app.oreshkov.clikit

/**
 * Runs [block] holding [lock]'s monitor, as `synchronized` does on the JVM: the common standard library has no lock,
 * and the progress line is drawn from a command's thread and cleared from the shutdown hook's.
 */
internal expect fun <T> withLock(lock: Any, block: () -> T): T
