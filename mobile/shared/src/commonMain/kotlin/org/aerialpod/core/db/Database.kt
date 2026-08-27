package org.aerialpod.core.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Where the SQLite handle comes from.
 *
 * An interface rather than an expect/actual class because the platforms need
 * genuinely different constructor arguments — a `Context` on Android, a file
 * path on the JVM — and expect/actual makes that awkward for no gain. The app
 * wires the right one; nothing in the core cares which it got.
 */
interface DriverFactory {
    fun createDriver(): SqlDriver
}

fun openDatabase(factory: DriverFactory): AerialPodDatabase =
    AerialPodDatabase(factory.createDriver())
