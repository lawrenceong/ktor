package io.ktor.client.engine.apache

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import kotlinx.coroutines.experimental.*
import org.apache.http.impl.nio.client.*
import org.apache.http.impl.nio.reactor.*

internal class ApacheEngine(override val config: ApacheEngineConfig) : HttpClientEngine {
    override val dispatcher: CoroutineDispatcher get() = TODO("not implemented")

    private val engine: CloseableHttpAsyncClient = prepareClient().apply { start() }

    override suspend fun execute(call: HttpClientCall, data: HttpRequestData): HttpEngineCall {
        val request = ApacheHttpRequest(call, engine, config, data)
        val response = request.execute()

        return HttpEngineCall(request, response)
    }

    override fun close() {
        try {
            engine.close()
        } catch (cause: Throwable) {
        }
    }

    private fun prepareClient(): CloseableHttpAsyncClient {
        val clientBuilder = HttpAsyncClients.custom()
        with(clientBuilder) {
            setThreadFactory { Thread(it, "Ktor-client-apache").apply { isDaemon = true } }
            disableAuthCaching()
            disableConnectionState()
            disableCookieManagement()
            setDefaultIOReactorConfig(IOReactorConfig.custom().apply {
                setMaxConnPerRoute(config.maxConnectionsPerRoute)
                setMaxConnTotal(config.maxConnectionsCount)
                if (config.maxThreadsCount > 0) setIoThreadCount(config.maxThreadsCount)
            }.build())
        }

        with(config) {
            clientBuilder.customClient()
        }

        config.sslContext?.let { clientBuilder.setSSLContext(it) }
        return clientBuilder.build()!!
    }
}
