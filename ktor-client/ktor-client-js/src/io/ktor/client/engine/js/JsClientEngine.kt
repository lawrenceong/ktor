package io.ktor.client.engine.js

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.http.*
import io.ktor.http.Headers
import io.ktor.http.content.*
import io.ktor.util.date.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.io.core.*
import org.khronos.webgl.*
import org.w3c.fetch.*
import kotlin.browser.*

class JsClientEngine(override val config: HttpClientEngineConfig) : HttpClientEngine {
    override val dispatcher: CoroutineDispatcher = config.dispatcher ?: DefaultDispatcher

    override suspend fun execute(
        call: HttpClientCall, data: HttpRequestData
    ): HttpEngineCall = withContext(dispatcher) {
        val requestTime = GMTDate()
        val request = DefaultHttpRequest(call, data)
        val rawResponse = fetch(request.url, request.toRaw())

        val stream = rawResponse.body as ReadableStream

        val contentStream = Channel<Uint8Array>(Channel.UNLIMITED)
        stream.getReader().read().then { done: Boolean, chunk: Uint8Array ->
            if (done) {
                contentStream.close()
                return@then Unit
            }

            contentStream.offer(chunk)
        }

        val contentWriter = writer(dispatcher) {
            contentStream.consumeEach { array ->
                channel.writeFully(ByteArray(array.length) { array[it] })
            }
        }

        val response = JsHttpResponse(call, requestTime, rawResponse, contentWriter.channel, contentWriter)
        HttpEngineCall(request, response)
    }

    override fun close() {
    }

    private suspend fun HttpRequest.toRaw(): RequestInit {
        val jsHeaders = js("({})")
        headers.forEach { key, values ->
            jsHeaders[key] = values
        }

        val rawRequest = js("({})")
        rawRequest["method"] = method.value
        rawRequest["headers"] = jsHeaders

        val content = content
        val body  = when (content) {
            is OutgoingContent.ByteArrayContent -> content.bytes().toTypedArray()
            is OutgoingContent.ReadChannelContent -> content.readFrom().readRemaining().readBytes().toTypedArray()
            is OutgoingContent.WriteChannelContent -> writer(dispatcher) {
                content.writeTo(channel)
            }.channel.readRemaining().readBytes().toTypedArray()
            else -> null
        }

        body?.let { rawRequest["body"] = Uint8Array(it) }

        return rawRequest.unsafeCast<RequestInit>()
    }
}

class JsHttpResponse(
    override val call: HttpClientCall,
    override val requestTime: GMTDate,
    private val response: Response,
    override val content: ByteReadChannel,
    override val executionContext: Job
) : HttpResponse {

    override val status: HttpStatusCode = TODO()

    override val version: HttpProtocolVersion
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.

    override val responseTime: GMTDate = GMTDate()

    override val headers: Headers = Headers.build {
        response.headers.asDynamic().forEach { key: String, value: String ->
            append(key, value)
        } as Unit
    }

    override fun close() {}
}

private suspend fun fetch(url: Url, request: RequestInit): Response = suspendCancellableCoroutine {
    window.fetch(url.toString(), request).then({ response ->
        it.resume(response)
    }, { cause ->
        it.cancel(cause)
    })
}

/*
private suspend fun Response.receiveBody(): String = suspendCancellableCoroutine { continuation ->
    text().then { continuation.resume(it) }
}
*/
