package org.aerialpod.core.diag

import kotlinx.serialization.json.Json
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.aerialpod.core.db.AerialPodDatabase
import org.aerialpod.core.db.Repo
import org.aerialpod.core.lan.Snapshot
import org.aerialpod.core.lan.SnapshotSync
import org.aerialpod.core.queue.QueueManager
import org.aerialpod.core.sync.Matcher
import java.io.File
import kotlin.test.Test

/**
 * Opt-in: merge a real desktop snapshot into a copy of a real phone database
 * and print what the queue comes out as.
 *
 * Diagnosis, not a test — it asserts nothing, because the interesting output is
 * *which* episodes survive reconcile and why. It exists because the release
 * build on a device is not debuggable, and reading the screen was not enough to
 * tell a merge that did not happen from one that happened and did nothing.
 *
 *   AERIALPOD_DIAG_DB=/path/to/phone.db AERIALPOD_DIAG_SNAPSHOT=/path/to/snap.json \
 *     ./gradlew :shared:jvmTest --tests '*RealMergeIT*' --rerun-tasks
 */
class RealMergeIT {

    @Test
    fun mergeAndReport() {
        val dbPath = System.getenv("AERIALPOD_DIAG_DB") ?: return
        val snapshotPath = System.getenv("AERIALPOD_DIAG_SNAPSHOT") ?: return

        // Straight at the file: the database already exists, so the schema
        // must not be created over the top of it.
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val db = AerialPodDatabase(driver)
        val repo = Repo(db)
        val sync = SnapshotSync(repo, Matcher(repo))
        val queue = QueueManager(repo)

        val json = Json { ignoreUnknownKeys = true }
        val snapshot = json.decodeFromString(Snapshot.serializer(), File(snapshotPath).readText())

        println("--- before ---")
        report(repo, queue)

        val counts = sync.mergeSnapshot(snapshot)
        println("merged: positions=${counts.positions} intents=${counts.intents} settings=${counts.settings}")

        queue.reconcile()
        println("--- after ---")
        report(repo, queue)
    }

    private fun report(repo: Repo, queue: QueueManager) {
        val episodes = queue.episodes()
        println("queue: ${episodes.size}")
        for (e in episodes) {
            val podcast = repo.podcastById(e.podcast_id)?.title ?: "?"
            println(
                "   ${podcast.take(24).padEnd(26)} ${e.state.padEnd(9)} " +
                    "pos=${e.position_secs}/${e.total_secs} stamp=${e.position_updated_at}  " +
                    e.title?.take(34)
            )
        }
    }
}
