package org.aerialpod.core

actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000

actual fun epochMillis(): Long = System.currentTimeMillis()
