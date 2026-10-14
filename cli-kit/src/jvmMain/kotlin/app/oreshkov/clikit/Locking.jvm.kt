package app.oreshkov.clikit

internal actual fun <T> withLock(lock: Any, block: () -> T): T = synchronized(lock, block)
