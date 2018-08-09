package io.ktor.util

import kotlin.js.*

actual fun random(bound: Int): Int = (Math.random() * bound).toInt()
