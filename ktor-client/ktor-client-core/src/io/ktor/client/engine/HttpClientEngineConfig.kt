package io.ktor.client.engine

import kotlinx.coroutines.experimental.*

/**
 * Base configuration for [HttpClientEngine].
 */
open class HttpClientEngineConfig {
    /**
     * The [CoroutineDispatcher] that will be used for the client requests.
     */
    @Deprecated(
        level = DeprecationLevel.ERROR,
        message = "Setting dispatcher by hand is deprecated. You could set max threads count instead."
    )
    var dispatcher: CoroutineDispatcher? = null

    /**
     * Maximum threads count. Default value(-1) is for auto.
     */
    var maxThreadsCount: Int = -1

    /**
     * Enable http pipelining
     */
    var pipelining: Boolean = true
}
