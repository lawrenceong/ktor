package io.ktor.util.cio


suspend inline fun <R> Semaphore.use(block: () -> R): R {
    enter()
    try {
        return block()
    } finally {
        leave()
    }
}
