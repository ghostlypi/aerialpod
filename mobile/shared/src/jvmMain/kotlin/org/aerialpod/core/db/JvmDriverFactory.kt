package org.aerialpod.core.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * JVM driver — the tests' database, and the harness that will drive a real
 * desktop peer. Pass null for an in-memory database.
 */
class JvmDriverFactory(private val path: String? = null) : DriverFactory {
    override fun createDriver(): SqlDriver {
        val driver = JdbcSqliteDriver(
            path?.let { "jdbc:sqlite:$it" } ?: JdbcSqliteDriver.IN_MEMORY
        )
        AerialPodDatabase.Schema.create(driver)
        // The schema declares ON DELETE CASCADE, which SQLite ignores unless
        // this is on — and it is off per-connection by default.
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return driver
    }
}
