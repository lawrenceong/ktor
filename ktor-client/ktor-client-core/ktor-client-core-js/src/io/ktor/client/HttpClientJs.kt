package io.ktor.client

import io.ktor.client.engine.js.*

actual fun HttpClient(
    useDefaultTransformers: Boolean,
    block: HttpClientConfig.() -> Unit
): HttpClient = HttpClient(JsClient(), useDefaultTransformers, block)
