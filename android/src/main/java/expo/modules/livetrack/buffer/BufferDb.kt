package expo.modules.livetrack.buffer

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database backing the offline location/event buffer.
 *
 * Two tables: `points` (un-uploaded rows only) and `archived_points`
 * (server-acknowledged location rows kept for `archiveDays`, see
 * [ArchivedPointEntity]). Process-wide singleton via [getInstance] so the
 * capture service and the module bridge share one connection.
 */
@Database(
  entities = [PointEntity::class, ArchivedPointEntity::class],
  version = 3,
  exportSchema = false,
)
abstract class BufferDb : RoomDatabase() {
  abstract fun pointDao(): PointDao
  abstract fun archiveDao(): ArchiveDao

  /**
   * Atomic ack: copy to the archive (when enabled) and delete from `points` in
   * ONE transaction, so a crash between the two can neither lose a row nor
   * leave it in both tables. Callers chunk `rows` at 500 (`deleteByIds` binds one
   * SQL variable per id).
   */
  fun archiveAndDelete(rows: List<PointEntity>, uploadedAt: Long, archiveOn: Boolean) {
    if (rows.isEmpty()) return
    runInTransaction {
      if (archiveOn) {
        val locations = rows.filter { it.eventType == null }
        if (locations.isNotEmpty()) {
          archiveDao().insertAll(locations.map { ArchivedPointEntity.from(it, uploadedAt) })
        }
      }
      pointDao().deleteByIds(rows.map { it.id })
    }
  }

  companion object {
    private const val DB_NAME = "livetrack_buffer.db"

    /** Hard ceiling on archived rows, independent of `archiveDays`. */
    const val ARCHIVE_ROW_CAP = 400_000

    @Volatile
    private var instance: BufferDb? = null

    /**
     * v1 -> v2: adds `attempts`, the retry counter that bounds re-upload loops.
     *
     * ADDITIVE ONLY — no table rebuild, no data copy, so every un-uploaded row
     * survives the app update. `NOT NULL` is legal in `ALTER TABLE ADD COLUMN`
     * precisely because a DEFAULT is supplied; the matching
     * `@ColumnInfo(defaultValue = "0")` on the entity keeps the fresh-install
     * CREATE TABLE identical, so Room's post-migration validation passes on both
     * install paths.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `points` ADD COLUMN `attempts` INTEGER NOT NULL DEFAULT 0")
      }
    }

    /**
     * v2 -> v3: adds the `archived_points` table + its indices. `points` is not
     * touched. The DDL must match what Room generates for [ArchivedPointEntity]
     * exactly (column affinity, NOT NULL, PK, index names), or Room's
     * post-migration validation throws on the capture path — guarded by
     * `BufferDbMigrationTest`.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `archived_points` (" +
            "`id` INTEGER NOT NULL, `userId` TEXT NOT NULL, `t` INTEGER NOT NULL, " +
            "`lat` REAL, `lng` REAL, `speed` REAL NOT NULL, `acc` REAL NOT NULL, " +
            "`batt` INTEGER NOT NULL, `charging` INTEGER NOT NULL, `act` TEXT, " +
            "`mock` INTEGER NOT NULL, `uploadedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_archived_points_t` ON `archived_points` (`t`)",
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_archived_points_userId_t` " +
            "ON `archived_points` (`userId`, `t`)",
        )
      }
    }

    fun getInstance(context: Context): BufferDb =
      instance ?: synchronized(this) {
        instance ?: build(context).also { instance = it }
      }

    private fun build(context: Context): BufferDb =
      Room.databaseBuilder(
        context.applicationContext,
        BufferDb::class.java,
        DB_NAME,
      )
        // NO destructive fallback: the buffer holds un-uploaded user data that must
        // survive app updates. Every schema bump MUST register an explicit Migration
        // here, so existing data is preserved across updates.
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()
  }
}
