package io.ktor.client.engine.apache

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.http.content.*
import io.ktor.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.*
import org.apache.http.*
import org.apache.http.HttpRequest
import org.apache.http.client.config.*
import org.apache.http.client.methods.*
import org.apache.http.client.utils.*
import org.apache.http.entity.*
import org.apache.http.nio.*
import org.apache.http.nio.protocol.*
import org.apache.http.protocol.*
import java.nio.ByteBuffer

internal class ApacheRequestProducer(
    private val requestData: HttpRequestData,
    private val config: ApacheEngineConfig,
    private val body: OutgoingContent,
    private val context: CompletableDeferred<Unit>
) : HttpAsyncRequestProducer {
    private var requestJob: Job? = null
    private val requestChannel = Channel<ByteBuffer>(1)
    private val request: HttpUriRequest = setupRequest()
    private val host = URIUtils.extractHost(request.uri)

    init {
        when (body) {
            is OutgoingContent.ByteArrayContent -> {
                requestChannel.offer(ByteBuffer.wrap(body.bytes()))
                requestChannel.close()
            }
            is OutgoingContent.ProtocolUpgrade -> throw UnsupportedContentTypeException(body)
            is OutgoingContent.NoContent -> requestChannel.close()
            is OutgoingContent.ReadChannelContent -> prepareBody(body.readFrom())
            is OutgoingContent.WriteChannelContent -> writer(Unconfined, autoFlush = true) {
                body.writeTo(channel)
            }.channel.let { prepareBody(it) }
        }
    }

    override fun isRepeatable(): Boolean = true

    override fun getTarget(): HttpHost = host

    override fun generateRequest(): HttpRequest = request

    override fun requestCompleted(context: HttpContext) {
    }

    override fun resetRequest() {}

    override fun failed(cause: Exception) {
        requestChannel.close(cause)
        requestJob?.cancel(cause)
        context.complete(Unit)
    }

    override fun produceContent(encoder: ContentEncoder, ioctrl: IOControl) {
        val buffer = runBlocking { requestChannel.receiveOrNull() }
        if (buffer == null) {
            encoder.complete()
            return
        }

        try {
            while (buffer.hasRemaining()) {
                encoder.write(buffer)
            }
        } finally {
            if (body is OutgoingContent.WriteChannelContent || body is OutgoingContent.ReadChannelContent) {
                HttpClientDefaultPool.recycle(buffer)
            }
        }
    }

    override fun close() {
        requestChannel.close()
        context.complete(Unit)
    }

    private fun setupRequest(): HttpUriRequest = with(requestData) {
        val builder = RequestBuilder.create(method.value)!!
        builder.uri = URIBuilder().apply {
            scheme = url.protocol.name
            host = url.host
            if (url.port != url.protocol.defaultPort) port = url.port
            path = url.encodedPath

            if (url.parameters.isEmpty() && url.trailingQuery) setParameters(listOf())
            url.parameters.flattenForEach { key, value -> addParameter(key, value) }
        }.build()

        val content = this@ApacheRequestProducer.body
        val length = headers[io.ktor.http.HttpHeaders.ContentLength] ?: content.contentLength?.toString()
        val type = headers[io.ktor.http.HttpHeaders.ContentType] ?: content.contentType?.toString()

        mergeHeaders(headers, content) { key, value ->
            if (HttpHeaders.CONTENT_LENGTH == key) return@mergeHeaders
            if (HttpHeaders.CONTENT_TYPE == key) return@mergeHeaders

            builder.addHeader(key, value)
        }

        if (body !is OutgoingContent.NoContent && body !is OutgoingContent.ProtocolUpgrade) {
            builder.entity = BasicHttpEntity().apply {
                if (length == null) isChunked = true else contentLength = length.toLong()
                setContentType(type)
            }
        }

        with(config) {
            builder.config = RequestConfig.custom()
                .setRedirectsEnabled(followRedirects)
                .setSocketTimeout(socketTimeout)
                .setConnectTimeout(connectTimeout)
                .setConnectionRequestTimeout(connectionRequestTimeout)
                .customRequest()
                .build()
        }

        return builder.build()
    }

    private fun prepareBody(bodyChannel: ByteReadChannel) {
        val result = launch(parent = context) {
            while (!bodyChannel.isClosedForRead) {
                val buffer = HttpClientDefaultPool.borrow()
                try {
                    while (bodyChannel.readAvailable(buffer) != -1 && buffer.remaining() > 0) {
                    }
                    buffer.flip()
                    requestChannel.send(buffer)
                } catch (cause: Throwable) {
                    HttpClientDefaultPool.recycle(buffer)
                    throw cause
                }
            }
        }

        result.invokeOnCompletion { cause ->
            requestChannel.close(cause)
            if (cause != null) context.completeExceptionally(cause)
            else context.complete(Unit)
        }
    }
}
