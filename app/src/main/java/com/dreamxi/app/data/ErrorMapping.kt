package com.dreamxi.app.data

import android.util.Log
import com.dreamxi.app.core.ui.components.DreamXiError
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

private const val TAG = "DreamXI"

/**
 * Turns a thrown exception into something a player can act on.
 *
 * The technical detail is logged, never shown. A user needs to know whether
 * to check their Wi-Fi or just try again; the URL that failed and the DNS
 * error code help neither, and showing them makes an offline phone look like
 * a broken app.
 *
 * `UnknownHostException` is the important case: on Android that is what an
 * offline device produces (and also what a missing INTERNET permission
 * produces), not a "server down" error, so it maps to Offline rather than to
 * a generic failure.
 */
fun Throwable.toUserFacingError(context: String): DreamXiError {
    if (this is CancellationException) throw this
    Log.w(TAG, "$context failed", this)

    // supabase-kt wraps transport failures, so walk the cause chain rather
    // than only inspecting the top-level type.
    var t: Throwable? = this
    while (t != null) {
        when (t) {
            is UnknownHostException -> return DreamXiError.Offline
            is ConnectException -> return DreamXiError.Offline
            is SocketTimeoutException -> return DreamXiError.ServerUnreachable
            else -> Unit
        }
        val message = t.message.orEmpty()
        if (message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("No address associated with hostname", ignoreCase = true)
        ) {
            return DreamXiError.Offline
        }
        if (t is IOException) return DreamXiError.ServerUnreachable
        t = t.cause.takeIf { it !== t }
    }
    return DreamXiError.Unexpected(message.orEmpty())
}
