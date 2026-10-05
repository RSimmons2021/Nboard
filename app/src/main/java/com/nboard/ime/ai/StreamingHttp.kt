package com.nboard.ime.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/** Cancellation closes the socket, including while a streaming body is being read. */
internal suspend fun <T> Call.readCancellable(parse: (Response) -> T): Result<T> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resume(Result.failure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(parse) }
            if (continuation.isActive) continuation.resume(result)
        }
    })
}
