package moe.shizuku.manager.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)

/**
 * The local-network grant wireless debugging needs is missing, so discovery and the connection
 * could not reach anything. Kept apart from a plain connection failure so the user is told to
 * grant access instead of being sent to look at wireless debugging settings.
 */
class LocalNetworkDeniedException : AdbException()
