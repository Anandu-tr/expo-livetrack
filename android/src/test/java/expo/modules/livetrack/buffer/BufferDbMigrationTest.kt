package expo.modules.livetrack.buffer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the v1 -> v2 upgrade path.
 *
 * The buffer holds un-uploaded user data, so adding the `attempts` column must not
 * cost a single row. This test builds a real v1 database by hand (Room v1 emitted no
 * `attempts` column), seeds it, then opens it through [BufferDb.MIGRATION_1_2] and
 * asserts the rows survived with the column defaulted.
 *
 * If [BufferDb] were ever bumped without registering a migration, Room would throw
 * "A migration from 1 to 2 was required but not found" here — which is exactly the
 * production failure this guards (that throw happens on the capture path, so it
 * would kill tracking and make the whole buffer unreadable).
 */
@RunWith(RobolectricTestRunner::class)
class BufferDbMigrationTest {

  /** The exact DDL Room generated for schema version 1 (no `attempts`). */
  private val v1Ddl = """
    CREATE TABLE IF NOT EXISTS `points` (
      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
      `userId` TEXT NOT NULL,
      `t` INTEGER NOT NULL,
      `lat` REAL,
      `lng` REAL,
      `speed` REAL NOT NULL,
      `acc` REAL NOT NULL,
      `batt` INTEGER NOT NULL,
      `charging` INTEGER NOT NULL,
      `act` TEXT,
      `mock` INTEGER NOT NULL,
      `eventType` TEXT
    )
  """.trimIndent()

  @Test
  fun migrating1To2_preservesRowsAndDefaultsAttemptsToZero() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val name = "migration_test.db"
    ctx.deleteDatabase(name)

    // --- Build a v1 database by hand and seed it (one location + one event). ---
    val raw = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name), null)
    raw.execSQL(v1Ddl)
    raw.execSQL(
      "INSERT INTO points (userId, t, lat, lng, speed, acc, batt, charging, act, mock, eventType) " +
        "VALUES ('u1', 1, 12.9, 77.5, 1.4, 8.0, 73, 0, 'walking', 0, NULL)",
    )
    raw.execSQL(
      "INSERT INTO points (userId, t, lat, lng, speed, acc, batt, charging, act, mock, eventType) " +
        "VALUES ('u1', 2, NULL, NULL, 0.0, -1.0, 70, 0, 'STILL', 0, 'HEARTBEAT')",
    )
    raw.version = 1
    raw.close()

    // --- Open through the migration. ---
    val db = Room.databaseBuilder(ctx, BufferDb::class.java, name)
      .addMigrations(BufferDb.MIGRATION_1_2)
      .allowMainThreadQueries()
      .build()
    val dao = db.pointDao()

    assertEquals(2, dao.count())
    val rows = dao.unsynced(50)
    assertEquals(2, rows.size)
    assertTrue("attempts must default to 0 for migrated rows", rows.all { it.attempts == 0 })
    // Payload survived intact, not just the row count.
    assertEquals("u1", rows[0].userId)
    assertEquals(12.9, rows[0].lat!!, 1e-9)
    assertEquals("HEARTBEAT", rows[1].eventType)

    // The new column is writable post-migration.
    assertEquals(1, dao.incrementAttempts(listOf(rows[0].id)))
    assertEquals(1, dao.unsynced(50).first { it.id == rows[0].id }.attempts)

    db.close()
    ctx.deleteDatabase(name)
  }
}
