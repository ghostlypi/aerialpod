package org.aerialpod.core.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Android driver.
 *
 * `onConfigure` rather than `onOpen` for foreign keys: SQLite refuses to change
 * the pragma inside a transaction, and the framework has already opened one by
 * the time onOpen runs.
 */
class AndroidDriverFactory(
    private val context: Context,
    private val name: String = "aerialpod.db",
) : DriverFactory {
    override fun createDriver(): SqlDriver = AndroidSqliteDriver(
        schema = AerialPodDatabase.Schema,
        context = context,
        name = name,
        callback = object : AndroidSqliteDriver.Callback(AerialPodDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }
        },
    )
}
