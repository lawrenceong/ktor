package io.ktor.client.engine.android

import io.ktor.util.cio.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.cio.Semaphore
import io.ktor.util.date.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.coroutines.experimental.io.jvm.javaio.*
import kotlinx.coroutines.experimental.scheduling.*
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.*

open class AndroidClientEngine(override val config: AndroidEngineConfig) : HttpClientEngine {
    override val dispatcher: CoroutineDispatcher by lazy { TODO() }

    private val maxParallelConnections = if (config.maxThreadsCount > 0) config.maxThreadsCount else 10
    private val connectionLimiter = Semaphore(maxParallelConnections)

    override suspend fun execute(
        call: HttpClientCall, data: HttpRequestData
    ): HttpEngineCall = withContext(DefaultDispatcher) {
        connectionLimiter.use {
            val request = AndroidHttpRequest(call, data)
            val response = request.execute()
            HttpEngineCall(request, response)
        }
    }

    override fun close() {
    }

    private fun AndroidHttpRequest.execute(): AndroidHttpResponse {
        val requestTime = GMTDate()

        val url = URLBuilder().takeFrom(url).buildString()
        val outgoingContent = this@execute.content
        val contentLength = headers[HttpHeaders.ContentLength]?.toLong() ?: outgoingContent.contentLength
        val context = Job()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = config.connectTimeout
            readTimeout = config.socketTimeout

            requestMethod = method.value
            useCaches = false
            instanceFollowRedirects = false

            mergeHeaders(headers, outgoingContent) { key, value ->
                addRequestProperty(key, value)
            }

            if (outgoingContent !is OutgoingContent.NoContent) {
                if (contentLength != null) {
                    addRequestProperty(HttpHeaders.ContentLength, contentLength.toString())
                } else {
                    addRequestProperty(HttpHeaders.TransferEncoding, "chunked")
                }

                contentLength?.let { setFixedLengthStreamingMode(it.toInt()) } ?: setChunkedStreamingMode(0)
                doOutput = true

                outgoingContent.writeTo(outputStream)
            }
        }

        connection.connect()
        val content = connection.content()
        val headerFields = connection.headerFields

        val responseHeaders = HeadersBuilder().apply {
            headerFields?.forEach { (key, values) -> key?.let { appendAll(it, values) } }
        }.build()

        return AndroidHttpResponse(
            call, content, context,
            responseHeaders, requestTime, GMTDate(),
            HttpStatusCode.fromValue(connection.responseCode), HttpProtocolVersion.HTTP_1_1,
            connection
        )
    }
}

internal fun OutgoingContent.writeTo(stream: OutputStream): Unit = stream.use {
    when (this) {
        is OutgoingContent.ByteArrayContent -> it.write(bytes())
        is OutgoingContent.ReadChannelContent -> readFrom().toInputStream().copyTo(it)
        is OutgoingContent.WriteChannelContent -> {
            writer(Unconfined) { writeTo(channel) }.channel.toInputStream().copyTo(it)
        }
        else -> throw UnsupportedContentTypeException(this)
    }
}

internal fun HttpURLConnection.content(): ByteReadChannel = try {
    inputStream?.buffered()
} catch (_: IOException) {
    errorStream?.buffered()
}?.toByteReadChannel(context = DefaultDispatcher) ?: ByteReadChannel.Empty
