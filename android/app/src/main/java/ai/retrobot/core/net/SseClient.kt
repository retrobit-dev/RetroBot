package ai.retrobot.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * A minimal Server-Sent Events reader.
 *
 * Every provider in this app streams its answer as SSE, but each one frames
 * the events differently: OpenAI sends `data: {json}`, Anthropic sends
 * `event: content_block_delta` followed by `data: {json}`, and Gemini sends
 * `data: {json}` without a terminating sentinel. This reader normalises all
 * of them into a stream of raw `data:` payloads so each provider only has to
 * worry about its own JSON.
 *
 * The HTTP call is cancelled when the collecting coroutine is cancelled, so
 * closing a battle really does stop the tokens.
 */
class SseClient(
    private val httpClient: OkHttpClient,
) {

    /**
     * Stream the `data:` payloads of an SSE response.
     *
     * @param onHttpError invoked with the status and body when the server
     *   answers with a non-2xx status. Throwing from it is how a provider
     *   turns an HTTP error into a [ProviderHttpException].
     */
    fun stream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        method: String = "POST",
        onHttpError: (status: Int, body: String) -> Unit = { status, _ ->
            throw ProviderHttpException(status, "HTTP $status")
        },
    ): Flow<String> = flow {
        val request = Request.Builder()
            .url(url)
            .method(
                method,
                body?.toRequestBody(JSON_MEDIA_TYPE) ?: null,
            )
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()

        val response = executeCancellable(request)
        response.use {
            if (!it.isSuccessful) {
                onHttpError(it.code, it.body?.string().orEmpty())
                return@use
            }

            val source = it.body?.source() ?: return@use

            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith(DATA_PREFIX)) continue
                val payload = line.removePrefix(DATA_PREFIX).trim()
                if (payload.isEmpty()) continue
                if (payload == DONE_SENTINEL) break
                emit(payload)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Execute a call without blocking the caller's thread, and abort the
     * request if the coroutine is cancelled.
     */
    private suspend fun executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(response))
                    } else {
                        // The coroutine was cancelled while the response was in
                        // flight; release the connection instead of leaking it.
                        response.close()
                    }
                }
            })
        }

    /**
     * Perform a non-streaming JSON request.
     *
     * @return the parsed body, or the raw text when the response is not JSON.
     */
    suspend fun postJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
    ): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        val response = try {
            executeCancellable(request)
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
            throw ProviderHttpException(0, e.message ?: "Network error")
        }

        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw ProviderHttpException(it.code, text)
            }
            return text
        }
    }

    companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DATA_PREFIX = "data:"
        const val DONE_SENTINEL = "[DONE]"
    }
}

/** A non-2xx response from a provider. */
class ProviderHttpException(
    val statusCode: Int,
    val body: String,
) : Exception("HTTP $statusCode: ${body.take(300)}") {

    /** A hint the UI can show next to the error. */
    val hint: String
        get() = when (statusCode) {
            401, 403 -> "Check the API key for this provider."
            404 -> "That model or endpoint does not exist for this provider."
            429 -> "Rate limited by the provider. Wait a moment."
            in 500..599 -> "The provider had a server error. Try again."
            else -> body.take(200)
        }
}
