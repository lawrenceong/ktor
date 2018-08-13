package io.ktor.client.features.json

import java.util.*

actual fun defaultSerializer(): JsonSerializer =
    ServiceLoader.load(JsonSerializer::class.java).toList().first()
